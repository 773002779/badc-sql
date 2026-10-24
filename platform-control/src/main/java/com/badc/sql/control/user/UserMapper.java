package com.badc.sql.control.user;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code sys_user} 查询。
 */
@Repository
public class UserMapper {

    private static final String SELECT_BY_LOGIN_NAME = """
            select id, login_name, password_hash, role, display_name
              from sys_user
             where login_name = ?
            """;

    private static final String SELECT_ROLE_BY_ID = "select role from sys_user where id = ?";

    private static final RowMapper<SysUser> ROW_MAPPER = (rs, rowNum) -> new SysUser(
            rs.getLong("id"),
            rs.getString("login_name"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("display_name"));

    private final JdbcTemplate jdbcTemplate;

    public UserMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SysUser> findByLoginName(String loginName) {
        return jdbcTemplate.query(SELECT_BY_LOGIN_NAME, ROW_MAPPER, loginName).stream().findFirst();
    }

    /** 当前用户的角色，供写接口的角色判定使用（本任务先按 {@code sys_user.role}）。 */
    public Optional<String> findRoleById(long userId) {
        return jdbcTemplate.query(SELECT_ROLE_BY_ID,
                (rs, rowNum) -> rs.getString("role"), userId).stream().findFirst();
    }
}
