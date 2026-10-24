package com.badc.sql.control.org;

/**
 * {@code sys_module} 的只读投影。模块是研发授权的最小单位。
 */
public record ModuleEntity(Long id, Long systemId, String name) {
}
