package com.badc.sql.control.org;

/**
 * {@code sys_system} 的只读投影。系统本身不含连接信息，可直接作为响应体。
 */
public record SystemEntity(Long id, String name) {
}
