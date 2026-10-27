package com.badc.sql.control.org;

import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;
import com.badc.sql.control.user.UserMapper;
import com.badc.sql.control.user.UserRoleService;

/**
 * 系统 / 模块 / 数据源的写权限。
 *
 * <p>两道闸：
 * <ol>
 *   <li><b>角色</b>：只有运维与管理员能写（研发一律 403）；</li>
 *   <li><b>归属</b>：运维只能改<b>自己负责的系统</b>下的东西。</li>
 * </ol>
 *
 * <p>第二道闸不是可选项。没有它，A 系统的运维可以把 B 系统数据源的连接串改成自己的主机——下一次
 * 有人查询时，平台会解密 B 的只读密码并发往那台主机。一个元数据写入越权，直接变成生产凭证泄露。
 *
 * <p>角色一律从数据库读，不信任 JWT，避免旧 token 带着过期角色。
 */
@Component
public class OperatorGuard {

    /** 能配置连接与凭证：主运维、备运维、组长（§4.2「备运维与主运维同权」）。 */
    private static final Set<String> CONNECTION_ROLES =
            Set.of("PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD", "ADMIN");

    /** 能维护系统与模块归属：不含备运维（§4.2 中备运维对模块维护为 ✗）。 */
    private static final Set<String> STRUCTURE_ROLES = Set.of("PRIMARY_OPS", "OPS_LEAD", "ADMIN");

    private static final String SYSTEM_OF_DATASOURCE = "select system_id from datasource where id = ?";

    private static final String SYSTEM_OF_MODULE = "select system_id from sys_module where id = ?";

    private static final String OPERATES_SYSTEM =
            "select count(*) from sys_system_operator where system_id = ? and user_id = ?";

    private final UserMapper userMapper;
    private final UserRoleService userRoleService;
    private final JdbcTemplate jdbc;

    public OperatorGuard(UserMapper userMapper, UserRoleService userRoleService, JdbcTemplate jdbc) {
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
        this.jdbc = jdbc;
    }

    /** 角色级守卫（保留）：产品代码已全部改用下面的归属感知守卫，这里只为兼容旧调用点/测试。 */
    public void requireOperator() {
        requireRole(CONNECTION_ROLES);
    }

    /**
     * 连接 / 凭证类写入：需要角色 + 系统归属。
     *
     * <p>参数用 {@code Long}：新建数据源时 systemId 来自请求体，缺失是常见错误输入，不该在控制器里拆箱成 NPE。
     */
    public void requireConnectionOperatorForSystem(Long systemId) {
        requireOwnership(systemId, CONNECTION_ROLES);
    }

    /** 连接 / 凭证类写入：由数据源反查归属系统。 */
    public void requireConnectionOperatorForDatasource(long datasourceId) {
        // 先判角色，再查归属：无权限者不该通过 404/403 的差异反推出"这个数据源存不存在"。
        String role = requireRole(CONNECTION_ROLES);
        requireMembership(systemOfDatasource(datasourceId), role);
    }

    /** 系统 / 模块结构类写入：需要角色 + 系统归属。 */
    public void requireStructureOperatorForSystem(Long systemId) {
        requireOwnership(systemId, STRUCTURE_ROLES);
    }

    /** 模块结构类写入：由模块反查归属系统。 */
    public void requireStructureOperatorForModule(long moduleId) {
        String role = requireRole(STRUCTURE_ROLES);
        requireMembership(systemOfModule(moduleId), role);
    }

    /** 建档（新建系统）仅管理员（§4.4）；改名 / 删除走 {@link #requireStructureOperatorForSystem}。 */
    public void requireAdmin() {
        Long userId = CurrentUser.id();
        if (!"ADMIN".equals(roleOf(userId))) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可新建系统");
        }
    }

    private void requireOwnership(Long systemId, Set<String> allowedRoles) {
        String role = requireRole(allowedRoles);
        // 先判角色再判参数：无权限者不该从 400/403 的差异里反推出"这个系统存不存在"。
        if (systemId == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "所属系统不能为空");
        }
        requireMembership(systemId, role);
    }

    private String requireRole(Set<String> allowedRoles) {
        Long userId = CurrentUser.id();
        String role = roleOf(userId);
        // 注意：Set.of(...).contains(null) 会抛 NPE，必须先判空。
        if (role == null || !allowedRoles.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权修改该系统的配置");
        }
        return role;
    }

    private void requireMembership(long systemId, String role) {
        if ("ADMIN".equals(role)) {
            return;
        }
        Long userId = CurrentUser.id();
        Integer count = jdbc.queryForObject(OPERATES_SYSTEM, Integer.class, systemId, userId);
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能修改自己负责的系统（systemId=" + systemId + "）");
        }
    }

    private long systemOfDatasource(long datasourceId) {
        var ids = jdbc.queryForList(SYSTEM_OF_DATASOURCE, Long.class, datasourceId);
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在：" + datasourceId);
        }
        return ids.get(0);
    }

    private long systemOfModule(long moduleId) {
        var ids = jdbc.queryForList(SYSTEM_OF_MODULE, Long.class, moduleId);
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "模块不存在：" + moduleId);
        }
        return ids.get(0);
    }

    /** 角色未知一律按无权处理（默认拒绝），不要把"查不到"当成"放行"。 */
    private String roleOf(Long userId) {
        return userRoleService.roleOf(userId);
    }
}
