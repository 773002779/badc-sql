package com.badc.sql.control.user;

/**
 * 平台用户（{@code sys_user}）的只读视图，仅供登录校验使用。
 *
 * <p>{@code passwordHash} 是 BCrypt 哈希，绝不进入任何 HTTP 响应。
 */
public record SysUser(Long id, String loginName, String passwordHash, String role, String displayName) {
}
