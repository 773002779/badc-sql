package com.badc.sql.control.org;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;
import com.badc.sql.control.user.UserMapper;

/**
 * 系统 / 模块 / 数据源的写权限：仅 {@code PRIMARY_OPS}、{@code OPS_LEAD}、{@code ADMIN}。
 *
 * <p>本任务先按 {@code sys_user.role} 判断；完整的模块级可见性在 T4 实现。{@code DEVELOPER} 与
 * {@code BACKUP_OPS} 一律 403。角色从数据库读取而不是信任 JWT，避免旧 token 带着过期角色。
 */
@Component
public class OperatorGuard {

    private static final Set<String> WRITE_ROLES = Set.of("PRIMARY_OPS", "OPS_LEAD", "ADMIN");

    private final UserMapper userMapper;

    public OperatorGuard(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void requireOperator() {
        Long userId = CurrentUser.id();
        String role = userMapper.findRoleById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "当前用户不存在"));
        if (!WRITE_ROLES.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅运维或管理员可修改系统、模块与数据源");
        }
    }
}
