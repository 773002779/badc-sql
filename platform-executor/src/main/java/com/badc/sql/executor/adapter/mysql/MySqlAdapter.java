package com.badc.sql.executor.adapter.mysql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.adapter.DataSourceAdapter;

/**
 * MySQL 适配器（云托管 5.7 / 8.0 共用）。
 *
 * <p>连接参数固定带 {@code connectTimeout=3000&socketTimeout=30000}：库不可达时必须 3 秒内失败，
 * 不能把请求线程挂死。同时强制会话时区为 UTC，与平台"时间一律 UTC 存储"的约束一致。
 *
 * <p>错误消息不含 host / 账号 / 密码——执行器的异常会回给控制面，进而可能出现在前端。
 */
@Component
public class MySqlAdapter implements DataSourceAdapter {

    private final MySqlDialect dialect;

    public MySqlAdapter(MySqlDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public Capability probe(ConnConfig conn, ConnConfig onlineDdlConfig) {
        String versionString;
        boolean binlogEnabled;
        try (Connection connection = open(conn)) {
            versionString = scalar(connection, "select @@version");
            binlogEnabled = "1".equals(scalar(connection, "select @@log_bin"));
        } catch (SQLException e) {
            throw unreachable(e);
        }

        Capability base = Capability.fromVersionString(versionString, binlogEnabled);
        return base.withOnlineDdlAvailable(onlineDdlUsable(onlineDdlConfig));
    }

    @Override
    public ValidationResult validate(String sql, DbVersion version) {
        return dialect.validate(sql, version);
    }

    /**
     * 在线 DDL 可用性：配了专用账号、能连上、且具备 gh-ost 所需权限之一。
     *
     * <p>权限判定是"够用即可"的近似：只要 GRANTS 里出现所需关键字就认为可用，不做完整的权限推导。
     * 若账号存在但权限不足，会在真正执行 gh-ost 时失败并给出该账号的授权提示。
     */
    private boolean onlineDdlUsable(ConnConfig onlineDdlConfig) {
        if (onlineDdlConfig == null) {
            return false;
        }
        try (Connection connection = open(onlineDdlConfig)) {
            List<String> grants = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("show grants for current_user()")) {
                while (rs.next()) {
                    grants.add(rs.getString(1).toUpperCase(Locale.ROOT));
                }
            }
            String joined = String.join(" ", grants);
            return joined.contains("ALL PRIVILEGES")
                    || joined.contains("SUPER")
                    || joined.contains("SYSTEM_VARIABLES_ADMIN")
                    || joined.contains("REPLICATION CLIENT");
        } catch (SQLException e) {
            // 配了但连不上 / 没权限：视为不可用，而不是让整个探测失败。
            return false;
        }
    }

    private Connection open(ConnConfig conn) throws SQLException {
        if (conn == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return DriverManager.getConnection(url(conn), conn.username(), conn.password());
    }

    public static String url(ConnConfig conn) {
        StringBuilder url = new StringBuilder("jdbc:mysql://")
                .append(conn.host()).append(':').append(conn.port()).append('/');
        if (conn.dbName() != null && !conn.dbName().isBlank()) {
            url.append(conn.dbName());
        }
        url.append("?useSSL=false")
                .append("&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")
                .append("&connectTimeout=3000&socketTimeout=30000")
                .append("&allowPublicKeyRetrieval=true");
        if (conn.charset() != null && !conn.charset().isBlank()) {
            // Connector/J 只接受 Java 字符集名；UTF-8 在 MySQL 8 上即 utf8mb4。
            url.append("&characterEncoding=UTF-8");
        }
        return url.toString();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static BizException unreachable(SQLException e) {
        // 只透传 SQLState，不回显连接串与账号。
        return new BizException(
                ErrorCode.INTERNAL_ERROR,
                "数据源不可达（SQLState=" + e.getSQLState() + "），请确认网络连通与白名单配置",
                e);
    }
}
