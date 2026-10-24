package com.badc.sql.control.org;

/**
 * 数据源对研发可见的视图：只有 {@code id/name/dbName/systemId/type}。
 *
 * <p>与 {@link DataSourceEntity} <b>物理隔离</b>，而不是在实体上打 {@code @JsonIgnore}：
 * 实体以后新增字段时，不会因为漏标注解而把 host/port/账号泄漏出去。
 */
public record DataSourceView(Long id, String name, String dbName, Long systemId, String type) {
}
