package com.badc.sql.control.grant;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code user_module_grant} 的写入与单行查询（列定义见 {@code V1__baseline.sql}）。
 *
 * <h2>为什么授权粒度不加列</h2>
 * <p>{@code user_module_grant} 存在 = 可查询 + 可提单，{@code can_export} 单独控制导出。切片 1/2 已把
 * "有授权 ⇒ 可见 ⇒ 可查/可提单"写进 {@code VisibilityService} 与 {@code TicketAssignmentService}；
 * 再加 {@code can_query}/{@code can_submit} 会改变已交付语义并要回填历史数据。所以审批通过时只写本表。
 *
 * <h2>upsert 必须是 greatest</h2>
 * <p>同一个人可能已有 {@code can_export = 1}（例如上次审批给了导出权），这次申请只要求"查询"。
 * 若直接覆盖成新值，一次"仅查询"的申请会把已有的导出权<b>悄悄降级</b>——用户丢了权限却没有任何提示。
 * 授权是单调累加的：{@code greatest(旧值, 新值)} 保证只增不减。要收回权限得走专门的撤销流程，
 * 那不是"再来一次申请"能表达的动作。
 *
 * <h2>为什么是 values() 而不是行别名</h2>
 * <p>MySQL 8.0.19+ 支持 {@code insert ... as new} 行别名，8.0.20+ 起 {@code values()} 被标记废弃。
 * 但平台元数据库最低要求是 <b>8.0.16</b>（V4 的 CHECK 生效下限），该版本不认行别名。所以这里用
 * {@code values()}：它在 8.0.16 上可用，在更新版本上仍工作（只是有废弃警告）。
 */
@Repository
public class UserModuleGrantStore {

    private static final String UPSERT = """
            insert into user_module_grant (user_id, module_id, can_export, granted_by)
            values (?, ?, ?, ?)
            on duplicate key update
                can_export = greatest(can_export, values(can_export)),
                granted_by = values(granted_by),
                granted_at = current_timestamp(3)
            """;

    private static final String SELECT_ONE = """
            select can_export from user_module_grant where user_id = ? and module_id = ?
            """;

    private final JdbcTemplate jdbc;

    public UserModuleGrantStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等授予模块权限；已有记录时只升不降（见类注释）。
     *
     * @param userId    被授权人
     * @param moduleId  模块
     * @param canExport 本次申请的导出权
     * @param grantedBy 授权人（审批人）
     */
    public void upsert(long userId, long moduleId, boolean canExport, Long grantedBy) {
        jdbc.update(UPSERT, userId, moduleId, canExport, grantedBy);
    }

    /** 该人该模块的授权行（只看 {@code can_export}）；没有授权时为空。 */
    public Optional<Boolean> findCanExport(long userId, long moduleId) {
        List<Boolean> values = jdbc.queryForList(SELECT_ONE, Boolean.class, userId, moduleId);
        return values.stream().findFirst();
    }
}
