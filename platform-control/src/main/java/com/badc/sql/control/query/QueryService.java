package com.badc.sql.control.query;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.common.sql.SqlText;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.grant.VisibilityService;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.user.UserRoleService;

/**
 * 查询编排：鉴权 → 可见性 → 只读白名单 → 行数上限 → 取只读账号 → 版本校验 → 调执行器。
 *
 * <p>研发只能执行查询类语句；写语句在这里就被拦下并提示"请提交变更工单"，而不是让执行器报 1044。
 *
 * <p>版本校验（C3）在这里的原因：5.7 的库上跑 CTE 会被数据库报 1064，用户看到的是"SQL 语法错误"，
 * 而真实原因是版本。控制面在手上有能力信息时先行拒绝，并给出"该库为 MySQL 5.7，不支持 WITH 子句"。
 * <b>能力未知（探测失败、版本无法识别、校验调用失败）一律放行</b>——版本校验只是前置提示，
 * 不能因为它自己不好用就把查询拦死。
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    /** 本切片允许经查询接口执行的语句类型。 */
    private static final Set<String> READONLY_KEYWORDS =
            Set.of("select", "with", "show", "desc", "describe", "explain");

    /** 研发的单次查询行数上限（多取 1 行用于判定"被截断"）。 */
    private static final int DEVELOPER_ROW_CAP = 1001;

    /** 其他角色的行数上限（对应"单次结果集 10 万行"）。 */
    private static final int DEFAULT_ROW_CAP = 100_000;

    private final VisibilityService visibilityService;
    private final UserRoleService userRoleService;
    private final ConnAccountService connAccountService;
    private final SqlLimitInjector limitInjector;
    private final ExecutorClient executorClient;
    private final DatasourceCapabilityService capabilityService;
    private final ResultCacheService resultCacheService;
    private final QueryHistoryService queryHistoryService;
    private final AuditService auditService;

    public QueryService(
            VisibilityService visibilityService,
            UserRoleService userRoleService,
            ConnAccountService connAccountService,
            SqlLimitInjector limitInjector,
            ExecutorClient executorClient,
            DatasourceCapabilityService capabilityService,
            ResultCacheService resultCacheService,
            QueryHistoryService queryHistoryService,
            AuditService auditService) {
        this.visibilityService = visibilityService;
        this.userRoleService = userRoleService;
        this.connAccountService = connAccountService;
        this.limitInjector = limitInjector;
        this.executorClient = executorClient;
        this.capabilityService = capabilityService;
        this.resultCacheService = resultCacheService;
        this.queryHistoryService = queryHistoryService;
        this.auditService = auditService;
    }

    /**
     * 执行一次只读查询。
     *
     * @param datasourceId 目标数据源
     * @param sql          用户提交的 SQL
     */
    public QueryResult query(Long userId, long datasourceId, String sql) {
        if (sql == null || sql.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "SQL 不能为空");
        }
        // 1) 只读白名单：写语句走工单，不走查询接口。
        if (!isReadonlyStatement(sql)) {
            // 被拒的写操作也要留痕：这是"有人试图经查询接口改数据"的证据。
            auditService.record(new AuditCommand(
                    "QUERY_REJECTED", userId, null, datasourceId, "non_readonly_statement", null,
                    "{\"sql\":\"" + sql.replace("\"", "'") + "\"}"));
            throw new BizException(ErrorCode.FORBIDDEN, "查询接口只接受查询类语句，需要变更请提交变更工单");
        }
        // 2) 可见性：存在但无权 403，不存在 404。
        visibilityService.assertVisible(userId, datasourceId);

        // 3) 管理员不可执行生产语句（§4.3-5 的硬隔离）：只能配置与授权。
        String role = userRoleService.roleOf(userId);
        if ("ADMIN".equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不可执行生产语句，只能进行配置与授权");
        }
        // 4) 行数上限：研发强约束，其他角色用宽松上限。
        boolean developer = "DEVELOPER".equals(role);
        int maxRows = developer ? DEVELOPER_ROW_CAP : DEFAULT_ROW_CAP;
        String boundedSql = developer ? limitInjector.apply(sql) : sql;

        // 4) 取只读账号（已解密，仅内存传递）。
        ConnConfig conn = connAccountService.readonlyConnConfig(datasourceId);
        // 5) 版本校验：能力未知时跳过，只在"确定不支持"时拒绝。
        assertVersionSupported(datasourceId, conn, boundedSql);
        RawResult raw = executorClient.query(conn, boundedSql, maxRows);

        String resultId = UUID.randomUUID().toString();
        // 6) 结果短期缓存（30 分钟 / 20 条），供后续导出引用。
        resultCacheService.put(resultId, raw);
        // 7) 历史：写失败不影响本次查询返回。
        queryHistoryService.record(userId, datasourceId, boundedSql, raw.rows().size(), raw.elapsedMs(), "SUCCESS");
        // 8) 审计：查询本身也入链（谁、查了哪个库、多少行）。
        auditService.record(new AuditCommand("QUERY", userId, null, datasourceId, "query", resultId,
                "{\"rows\":" + raw.rows().size() + ",\"truncated\":" + raw.truncated() + "}"));

        return new QueryResult(
                resultId,
                raw.columns(),
                raw.rows(),
                raw.truncated(),
                raw.elapsedMs());
    }

    /**
     * 按探测到的目标库版本校验将要执行的 SQL。
     *
     * <p>三种"不确定"都放行，且<b>只记日志</b>：<ul>
     *   <li>能力为 null（探测失败）；</li>
     *   <li>版本为 null（MariaDB / 非标准版本串）；</li>
     *   <li>校验调用本身失败（执行器抖动）。</li>
     * </ul>
     * 只有执行器明确返回 {@code ok=false} 才拒绝：那是"这个版本确实不支持这条 SQL"的确定结论。
     */
    private void assertVersionSupported(long datasourceId, ConnConfig conn, String sql) {
        Capability capability = capabilityService.capabilityOf(datasourceId);
        if (capability == null || capability.version() == null) {
            return;
        }
        ValidationResult verdict;
        try {
            verdict = executorClient.validate(conn, sql, capability.version());
        } catch (RuntimeException e) {
            log.warn("数据源 {} 的版本校验调用失败，本次跳过校验：{}", datasourceId, e.toString());
            return;
        }
        if (verdict != null && !verdict.ok()) {
            String message = verdict.message() == null || verdict.message().isBlank()
                    ? ErrorCode.DB_VERSION_UNSUPPORTED.defaultMessage()
                    : verdict.message();
            throw new BizException(ErrorCode.DB_VERSION_UNSUPPORTED, message);
        }
    }

    /** 只看首个关键字，且必须先剥掉注释与字面量（{@code -- select} 不算查询语句）。 */
    static boolean isReadonlyStatement(String sql) {
        String code = SqlText.stripLiteralsAndComments(sql).toLowerCase(Locale.ROOT);
        String first = firstKeyword(code);
        if (first == null) {
            return false;
        }
        if ("explain".equals(first)) {
            String rest = code.substring(Math.min(7, code.length())).stripLeading();
            if (rest.startsWith("analyze")) {
                rest = rest.substring(7).stripLeading();
            }
            String verb = firstKeyword(rest);
            if ("with".equals(verb)) {
                // EXPLAIN [ANALYZE] WITH ...：CTE 的性质看它之后的顶层动词，与下面的 with 分支同一口径。
                // 只信任 "with" 会让 `explain analyze with cte as (...) delete from t` 通过只读白名单，
                // 而 EXPLAIN ANALYZE 在 MySQL 里是会真的执行那条 DML 的。
                return "select".equals(SqlText.firstTopLevelVerb(code));
            }
            return "select".equals(verb);
        }
        if ("with".equals(first)) {
            // 取 CTE 之后的第一个顶层动词，而不是最后一个：`with ... insert into t2 select ...` 尾部的
            // select 属于 insert，按最后一个会把它放行成只读。拿不到动词时 fail-closed（非只读）。
            return "select".equals(SqlText.firstTopLevelVerb(code));
        }
        return READONLY_KEYWORDS.contains(first);
    }

    private static String firstKeyword(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                continue;
            }
            int end = i;
            while (end < text.length() && Character.isLetter(text.charAt(end))) {
                end++;
            }
            return end == i ? null : text.substring(i, end);
        }
        return null;
    }

    /** 查询结果：{@code resultId} 供后续导出 / 结果缓存引用。 */
    public record QueryResult(
            String resultId,
            List<com.badc.sql.common.dto.ColumnMeta> columns,
            List<List<Object>> rows,
            boolean truncated,
            long elapsedMs) {
    }
}
