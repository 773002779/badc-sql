package com.badc.sql.control.ticket;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.grant.VisibilityService;
import com.badc.sql.control.user.UserRoleService;

/**
 * 工单归属推导：提交人 → 系统 / 模块。
 *
 * <p>工单必填"系统、模块"，但请求体只给数据源——让前端传归属，迟早会传出"数据源属于 A 系统、工单挂到
 * B 系统"的错单。这里从两条真实授权链反推：
 * <ul>
 *   <li>{@code system_id}：{@code datasource.system_id}（一个库只属一个系统）；</li>
 *   <li>{@code module_id}：该提交人<b>经由哪个模块</b>拿到这个数据源——
 *       {@code user_module_grant} ⨝ {@code module_datasource}。查不到说明可见性来自别处（例如管理员），
 *       不该在这里提单 → 403。</li>
 * </ul>
 *
 * <p><b>多行取最小 module_id</b>，并把候选列表一起返回，由落库段记一条 {@code MODULE_AMBIGUOUS} 事件。
 * 取最小只是为了确定性（否则同一份输入两次提交可能挂到不同模块），不是因为"最小"更有业务含义——
 * 事件明细里写清"共几个、取了哪个"，审核时能看出这里做过一次选择。
 *
 * <p>角色与可见性的顺序不可交换：先判"是不是研发"（403），再判"能不能看这个库"（403/404）。运维提交研发
 * 工单与库存不存在无关，不该因为传了一个不存在的数据源 ID 就得到 404。
 */
@Service
public class TicketAssignmentService {

    private static final String ROLE_DEVELOPER = "DEVELOPER";

    /** 能通过临时执行通道转出工单的角色：与该通道的直执行角色集合一致（研发与管理员不在其中）。 */
    private static final Set<String> TEMP_EXEC_ROLES = Set.of("PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD");

    private static final String SYSTEM_OF_DATASOURCE_SQL =
            "select system_id from datasource where id = ?";

    /**
     * 提交人经由哪些模块拿到这个数据源，按 module_id 升序。
     *
     * <p>不加 {@code limit 1}：落库段要据此判断"是否多行"，只取一行就永远发现不了归属歧义。
     */
    private static final String MODULE_OF_GRANT_SQL = """
            select g.module_id
              from user_module_grant g
              join module_datasource md on md.module_id = g.module_id
             where g.user_id = ? and md.datasource_id = ?
             order by g.module_id
            """;

    /** 数据源挂在哪些模块下（TEMP_EXEC：运维没有 {@code user_module_grant}，只能按数据源反查模块）。 */
    private static final String MODULES_OF_DATASOURCE_SQL =
            "select module_id from module_datasource where datasource_id = ? order by module_id";

    /** 兜底：该系统的模块（数据源没被任何模块挂载时用）。 */
    private static final String MODULES_OF_SYSTEM_SQL =
            "select id from sys_module where system_id = ? order by id";

    private final JdbcTemplate jdbc;
    private final UserRoleService userRoleService;
    private final VisibilityService visibilityService;

    public TicketAssignmentService(
            JdbcTemplate jdbc, UserRoleService userRoleService, VisibilityService visibilityService) {
        this.jdbc = jdbc;
        this.userRoleService = userRoleService;
        this.visibilityService = visibilityService;
    }

    /**
     * 推导提交人与数据源的归属。
     *
     * @throws BizException 角色不是 {@code DEVELOPER} → 403；数据源不可见 → 403 / 不存在 → 404；
     *                      可见性不来自任何模块 → 403
     */
    public Assignment assign(long submitterId, long datasourceId) {
        String role = userRoleService.roleOf(submitterId);
        if (!ROLE_DEVELOPER.equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅研发可提交变更工单（运维与管理员不提交研发工单）");
        }
        visibilityService.assertVisible(submitterId, datasourceId);

        List<Long> systemIds = jdbc.queryForList(SYSTEM_OF_DATASOURCE_SQL, Long.class, datasourceId);
        if (systemIds.isEmpty()) {
            // 可见性刚判过存在，这里为空只可能是并发删除：按不存在处理，不要 NPE。
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在：" + datasourceId);
        }
        List<Long> moduleIds = jdbc.queryForList(MODULE_OF_GRANT_SQL, Long.class, submitterId, datasourceId);
        if (moduleIds.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "无法确定发起模块：数据源 " + datasourceId + " 不是经由任何已授权模块可见的");
        }
        return new Assignment(systemIds.get(0), moduleIds.get(0), List.copyOf(moduleIds));
    }

    /**
     * 推导"运维临时执行转工单"的归属（{@code TEMP_EXEC}）。
     *
     * <p>与 {@link #assign} 的差别只有两处，都源于"提交人不是研发"：
     * <ul>
     *   <li><b>角色闸</b>从"必须是研发"换成"必须是主运维/备运维/组长"——临时执行通道本来就不给研发与管理员走，
     *       而 {@link #assign} 的 403 文案（"仅研发可提交变更工单"）会把这条路径挡死；</li>
     *   <li><b>模块</b>不是"提交人经由哪个模块拿到这个库"（运维没有 {@code user_module_grant}），而是
     *       <b>该数据源所属的模块</b>，取最小 module_id。数据源没被任何模块挂载时退回该系统的模块；
     *       两者都没有才报 400——{@code ticket.module_id} 是 NOT NULL，编一个不存在的模块只会让工单
     *       从所有按模块过滤的列表里消失。</li>
     * </ul>
     *
     * <p>系统归属仍取 {@code datasource.system_id}，可见性仍走 {@link VisibilityService}（运维经
     * {@code sys_system_operator} 可见），于是"只能在自己负责的系统上转工单"与直执行是同一判据。
     *
     * @throws BizException 角色不是运维/组长 → 403；数据源不可见 → 403 / 不存在 → 404；
     *                      数据源与系统都没有模块 → 400
     */
    public Assignment assignTempExecution(long submitterId, long datasourceId) {
        String role = userRoleService.roleOf(submitterId);
        if (role == null || !TEMP_EXEC_ROLES.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有运维可以通过临时执行通道提交工单");
        }
        visibilityService.assertVisible(submitterId, datasourceId);

        List<Long> systemIds = jdbc.queryForList(SYSTEM_OF_DATASOURCE_SQL, Long.class, datasourceId);
        if (systemIds.isEmpty()) {
            // 可见性刚判过存在，这里为空只可能是并发删除：按不存在处理，不要 NPE。
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在：" + datasourceId);
        }
        long systemId = systemIds.get(0);

        List<Long> moduleIds = jdbc.queryForList(MODULES_OF_DATASOURCE_SQL, Long.class, datasourceId);
        if (moduleIds.isEmpty()) {
            moduleIds = jdbc.queryForList(MODULES_OF_SYSTEM_SQL, Long.class, systemId);
        }
        if (moduleIds.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "无法确定发起模块：数据源 " + datasourceId + " 不属于任何模块，其所属系统也没有模块");
        }
        return new Assignment(systemId, moduleIds.get(0), List.copyOf(moduleIds));
    }

    /**
     * 归属推导结果。
     *
     * @param systemId           数据源所属系统
     * @param moduleId           选定的发起模块（候选里最小的）
     * @param candidateModuleIds 全部候选模块，按升序；长度 &gt; 1 表示归属歧义
     */
    public record Assignment(long systemId, long moduleId, List<Long> candidateModuleIds) {

        public Assignment {
            candidateModuleIds = List.copyOf(candidateModuleIds);
        }

        /** 可见性是否来自多个模块（需要留 {@code MODULE_AMBIGUOUS} 事件）。 */
        public boolean ambiguous() {
            return candidateModuleIds.size() > 1;
        }
    }
}
