package com.badc.sql.control.query;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.common.sql.SqlText;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.grant.VisibilityService;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.user.UserRoleService;

/**
 * 查询编排：鉴权 → 可见性 → 只读白名单 → 行数上限 → 取只读账号 → 调执行器。
 *
 * <p>研发只能执行查询类语句；写语句在这里就被拦下并提示"请提交变更工单"，而不是让执行器报 1044。
 */
@Service
public class QueryService {

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
    private final ResultCacheService resultCacheService;
    private final QueryHistoryService queryHistoryService;
    private final AuditService auditService;

    public QueryService(
            VisibilityService visibilityService,
            UserRoleService userRoleService,
            ConnAccountService connAccountService,
            SqlLimitInjector limitInjector,
            ExecutorClient executorClient,
            ResultCacheService resultCacheService,
            QueryHistoryService queryHistoryService,
            AuditService auditService) {
        this.visibilityService = visibilityService;
        this.userRoleService = userRoleService;
        this.connAccountService = connAccountService;
        this.limitInjector = limitInjector;
        this.executorClient = executorClient;
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

        // 4) 取只读账号（已解密，仅内存传递），执行。
        ConnConfig conn = connAccountService.readonlyConnConfig(datasourceId);
        RawResult raw = executorClient.query(conn, boundedSql, maxRows);

        String resultId = UUID.randomUUID().toString();
        // 5) 结果短期缓存（30 分钟 / 20 条），供后续导出引用。
        resultCacheService.put(resultId, raw);
        // 6) 历史：写失败不影响本次查询返回。
        queryHistoryService.record(userId, datasourceId, boundedSql, raw.rows().size(), raw.elapsedMs(), "SUCCESS");
        // 7) 审计：查询本身也入链（谁、查了哪个库、多少行）。
        auditService.record(new AuditCommand("QUERY", userId, null, datasourceId, "query", resultId,
                "{\"rows\":" + raw.rows().size() + ",\"truncated\":" + raw.truncated() + "}"));

        return new QueryResult(
                resultId,
                raw.columns(),
                raw.rows(),
                raw.truncated(),
                raw.elapsedMs());
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
            return "select".equals(verb) || "with".equals(verb);
        }
        if ("with".equals(first)) {
            return "select".equals(lastTopLevelVerb(code));
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

    /** WITH 子句之后的真正动词：取括号深度 0 上最后一个增删改查关键字。 */
    private static String lastTopLevelVerb(String code) {
        String[] verbs = {"select", "update", "delete", "insert", "replace"};
        int depth = 0;
        int lastIndex = -1;
        String lastVerb = null;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0 || !Character.isLetter(c)) {
                continue;
            }
            int end = i;
            while (end < code.length() && Character.isLetter(code.charAt(end))) {
                end++;
            }
            String word = code.substring(i, end);
            for (String verb : verbs) {
                if (verb.equals(word) && i > lastIndex) {
                    lastIndex = i;
                    lastVerb = verb;
                }
            }
            i = end - 1;
        }
        return lastVerb;
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
