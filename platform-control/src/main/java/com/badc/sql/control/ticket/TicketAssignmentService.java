package com.badc.sql.control.ticket;

import java.util.List;

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
