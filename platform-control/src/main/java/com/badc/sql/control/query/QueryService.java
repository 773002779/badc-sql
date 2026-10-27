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

    public QueryService(
            VisibilityService visibilityService,
            UserRoleService userRoleService,
            ConnAccountService connAccountService,
            SqlLimitInjector limitInjector,
            ExecutorClient executorClient,
            ResultCacheService resultCacheService,
            QueryHistoryService queryHistoryService) {
        this.visibilityService = visibilityService;
        this.userRoleService = userRoleService;
        this.connAccountService = connAccountService;
        this.limitInjector = limitInjector;
        this.executorClient = executorClient;
        this.resultCacheService = resultCacheService;
        this.queryHistoryService = queryHistoryService;
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
            throw new BizException(ErrorCode.FORBIDDEN, "查询接口只接受查询类语句，需要变更请提交变更工单");
        }
        // 2) 可见性：存在但无权 403，不存在 404。
        visibilityService.assertVisible(userId, datasourceId);

        // 3) 行数上限：研发强约束，其他角色用宽松上限。
        boolean developer = userRoleService.isDeveloper(userId);
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

        return new QueryResult(
                resultId,
                raw.columns(),
                raw.rows(),
                raw.truncated(),
                raw.elapsedMs());
    }

    /** 只看首个关键字，且必须先剥掉注释与字面量（{@code -- select} 不算查询语句）。 */
    static boolean isReadonlyStatement(String sql) {
        String stripped = SqlText.stripLiteralsAndComments(sql);
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                continue;
            }
            int end = i;
            while (end < stripped.length() && Character.isLetter(stripped.charAt(end))) {
                end++;
            }
            String keyword = stripped.substring(i, end).toLowerCase(Locale.ROOT);
            return READONLY_KEYWORDS.contains(keyword);
        }
        return false;
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
