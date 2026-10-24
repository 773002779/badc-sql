package com.badc.sql.common.dto;

/**
 * 执行期连接配置（内存态），由控制面产出、执行器消费。
 *
 * <p>放在 {@code platform-common} 而不是执行器模块，避免控制面反向依赖执行器。
 * {@link #password()} 是<b>已解密</b>的数据库密码：只允许在服务端内部传递，
 * 绝不可作为任何 {@code /api/**} 的响应体，也不得写入日志。
 *
 * <p>{@link #toString()} 对密码脱敏，避免对象被顺手打进日志时泄漏凭证。
 */
public record ConnConfig(
        String host,
        int port,
        String dbName,
        String username,
        String password,
        String charset,
        String timeZone) {

    @Override
    public String toString() {
        return "ConnConfig[host=" + host
                + ", port=" + port
                + ", dbName=" + dbName
                + ", username=" + username
                + ", password=***"
                + ", charset=" + charset
                + ", timeZone=" + timeZone
                + "]";
    }
}
