package com.badc.sql.control.user;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 查询用户的平台角色。
 *
 * <p>单独抽出来是因为可见性判定与查询编排都要用；同一段 SQL 抄两遍迟早会分叉。
 */
@Service
public class UserRoleService {

    private static final String ROLE_SQL = "select role from sys_user where id = ?";

    private final JdbcTemplate jdbc;

    public UserRoleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 用户角色；用户不存在返回 {@code null}。 */
    public String roleOf(Long userId) {
        if (userId == null) {
            return null;
        }
        List<String> roles = jdbc.queryForList(ROLE_SQL, String.class, userId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    /** 是否为研发（只有研发受"行数上限 + 只读白名单"的强约束）。 */
    public boolean isDeveloper(Long userId) {
        return "DEVELOPER".equals(roleOf(userId));
    }
}
