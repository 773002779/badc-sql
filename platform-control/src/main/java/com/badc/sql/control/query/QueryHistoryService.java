package com.badc.sql.control.query;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.badc.sql.control.user.RoleScope;

/**
 * 查询历史。
 *
 * <p>写历史<b>不允许影响查询本身</b>：写失败只记日志，不向上抛——用户拿到了结果，不该因为历史表写不进去
 * 而看到 500。
 *
 * <p>读历史按角色收敛：研发只看自己的；运维/组长看所辖系统的；管理员看全部。过滤在 SQL 里做。
 * 角色到范围的映射统一走 {@link RoleScope}，与工单读路径、授权自查共用同一份判定。
 */
@Service
public class QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryService.class);

    private static final String INSERT_SQL = """
            insert into query_history (user_id, datasource_id, sql_text, row_count, elapsed_ms, status, created_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String DEV_FILTER = " where h.user_id = ? ";

    private static final String OPS_FILTER = """
             where exists (
                 select 1 from sys_system_operator o
                  join datasource ds on ds.system_id = o.system_id
                 where o.user_id = ? and ds.id = h.datasource_id)
            """;

    private static final String COUNT_SELECT = "select count(*) from query_history h ";

    private static final String PAGE_SELECT = """
            select h.id, h.datasource_id, h.sql_text, h.row_count, h.elapsed_ms, h.status, h.created_at
              from query_history h
            """;

    private static final String ORDER_AND_PAGE = " order by h.id desc limit ? offset ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public QueryHistoryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 记录一次查询。失败不抛异常。 */
    public void record(Long userId, long datasourceId, String sql, int rowCount, long elapsedMs, String status) {
        try {
            jdbc.update(INSERT_SQL,
                    userId,
                    datasourceId,
                    truncate(sql, 4000),
                    rowCount,
                    elapsedMs,
                    status,
                    Timestamp.from(clock.instant()));
        } catch (RuntimeException e) {
            log.warn("写查询历史失败（已忽略）：userId={}, datasourceId={}", userId, datasourceId, e);
        }
    }

    /**
     * 分页查询历史。
     *
     * @param role 调用者角色，决定可见范围；映射统一走 {@link RoleScope}，本类只把范围翻译成这条表上的谓词
     */
    public Map<String, Object> page(Long userId, String role, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        RoleScope scope = RoleScope.of(role);
        String filter = filterFor(scope);
        // 参数个数由 scope 决定，不看谓词字符串：从 SQL 文本反推"要不要绑 userId"迟早错位。
        Object[] scopeArgs = scope.needsUserId() ? new Object[] {userId} : new Object[0];

        Long total = jdbc.queryForObject(COUNT_SELECT + filter, Long.class, scopeArgs);
        List<Map<String, Object>> items = jdbc.queryForList(
                PAGE_SELECT + filter + ORDER_AND_PAGE,
                concat(scopeArgs, safeSize, (long) (safePage - 1) * safeSize));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total == null ? 0 : total);
        body.put("page", safePage);
        body.put("size", safeSize);
        return body;
    }

    /**
     * 范围 → 谓词。包级可见是为了让单元测试能核对"谓词里的 {@code ?} 个数 == 绑定的参数个数"。
     *
     * <p>这条约束值得单独测：本类没有端到端覆盖（历史接口只有研发与运维两条用例），
     * 谓词与参数个数一旦错位，编译通过、单测通过，只在运行时抛
     * {@code SQLException: 参数个数不对}——而它出现在"用户已经拿到查询结果、只是历史查不出来"的场景里，
     * 很容易被当成可忽略的下游噪音。
     */
    static String filterFor(RoleScope scope) {
        return switch (scope) {
            case SELF -> DEV_FILTER;
            case OPERATED_SYSTEMS -> OPS_FILTER;
            case ALL -> "";
            case NONE -> " where 1 = 0 ";
        };
    }

    private static Object[] concat(Object[] head, Object... tail) {
        Object[] all = new Object[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
