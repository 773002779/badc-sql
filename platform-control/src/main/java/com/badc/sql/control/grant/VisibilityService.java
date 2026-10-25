package com.badc.sql.control.grant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 数据源可见性判定。
 *
 * <p>规则（本切片）：
 * <ul>
 *   <li>{@code DEVELOPER}：经 {@code user_module_grant → module_datasource → datasource} 可见；</li>
 *   <li>{@code PRIMARY_OPS} / {@code BACKUP_OPS} / {@code OPS_LEAD}：经 {@code sys_system_operator}
 *       关联的系统下全部数据源可见；</li>
 *   <li>{@code ADMIN}：全部可见；</li>
 *   <li>其余（含用户不存在）一律不可见。</li>
 * </ul>
 *
 * <p>过滤在 SQL 里完成，不把全量拉进内存再筛——授权链会随系统数量增长。
 *
 * <p>403 与 404 严格区分：数据源存在但无权 → {@link ErrorCode#FORBIDDEN}；不存在 →
 * {@link ErrorCode#NOT_FOUND}。把无权伪装成 404 会让用户以为是库名写错。
 */
@Service
public class VisibilityService {

    private static final String ROLE_SQL = "select role from sys_user where id = ?";

    private static final String DEV_DATASOURCES_SQL = """
            select ds.id
              from user_module_grant g
              join module_datasource md on md.module_id = g.module_id
              join datasource ds on ds.id = md.datasource_id
             where g.user_id = ?
            """;

    private static final String OPS_DATASOURCES_SQL = """
            select ds.id
              from sys_system_operator o
              join datasource ds on ds.system_id = o.system_id
             where o.user_id = ?
            """;

    private static final String ALL_DATASOURCES_SQL = "select id from datasource";

    private static final String DEV_MODULES_SQL = "select module_id from user_module_grant where user_id = ?";

    private static final String OPS_MODULES_SQL = """
            select m.id
              from sys_system_operator o
              join sys_module m on m.system_id = o.system_id
             where o.user_id = ?
            """;

    private static final String ALL_MODULES_SQL = "select id from sys_module";

    private static final String DATASOURCE_EXISTS_SQL = "select count(*) from datasource where id = ?";

    private final JdbcTemplate jdbc;

    public VisibilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 该用户可见的数据源 ID 集合（可能为空，不为 null）。 */
    public Set<Long> visibleDatasourceIds(Long userId) {
        String role = roleOf(userId);
        if (role == null) {
            return Set.of();
        }
        return switch (role) {
            case "DEVELOPER" -> ids(DEV_DATASOURCES_SQL, userId);
            case "PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD" -> ids(OPS_DATASOURCES_SQL, userId);
            case "ADMIN" -> ids(ALL_DATASOURCES_SQL);
            default -> Set.of();
        };
    }

    /** 该用户可见的功能模块 ID 集合（可能为空，不为 null）。 */
    public Set<Long> visibleModuleIds(Long userId) {
        String role = roleOf(userId);
        if (role == null) {
            return Set.of();
        }
        return switch (role) {
            case "DEVELOPER" -> ids(DEV_MODULES_SQL, userId);
            case "PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD" -> ids(OPS_MODULES_SQL, userId);
            case "ADMIN" -> ids(ALL_MODULES_SQL);
            default -> Set.of();
        };
    }

    /**
     * 断言用户对该数据源可见。
     *
     * @throws BizException 数据源不存在 → {@link ErrorCode#NOT_FOUND}；存在但无权 → {@link ErrorCode#FORBIDDEN}
     */
    public void assertVisible(Long userId, Long datasourceId) {
        if (datasourceId == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "数据源 ID 不能为空");
        }
        if (visibleDatasourceIds(userId).contains(datasourceId)) {
            return;
        }
        Integer exists = jdbc.queryForObject(DATASOURCE_EXISTS_SQL, Integer.class, datasourceId);
        if (exists == null || exists == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在：" + datasourceId);
        }
        throw new BizException(ErrorCode.FORBIDDEN, "无权访问该数据源：" + datasourceId);
    }

    private String roleOf(Long userId) {
        if (userId == null) {
            return null;
        }
        List<String> roles = jdbc.queryForList(ROLE_SQL, String.class, userId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    private Set<Long> ids(String sql, Object... args) {
        return new LinkedHashSet<>(jdbc.queryForList(sql, Long.class, args));
    }
}
