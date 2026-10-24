package com.badc.sql.control.org;

/**
 * {@code datasource} 的只读投影，仅供服务端内部使用。
 *
 * <p>包含 host/port 等连接信息，<b>绝不可</b>直接作为 HTTP 响应体；对外一律使用
 * {@link DataSourceView}。
 */
public record DataSourceEntity(
        Long id,
        Long systemId,
        String name,
        String dbName,
        String dbType,
        String host,
        int port,
        String charset,
        String timeZone) {
}
