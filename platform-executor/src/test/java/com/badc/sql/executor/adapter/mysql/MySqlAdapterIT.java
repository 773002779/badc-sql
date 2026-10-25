package com.badc.sql.executor.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.error.BizException;

/**
 * 连接真实 MySQL（本地 8.0.16）的适配器测试。
 *
 * <p>5.7 的版本差异由 {@link MySqlDialectTest} 与 {@code CapabilityTest} 用纯逻辑覆盖——本地没有
 * 5.7 实例，不造假容器（R13）。
 */
class MySqlAdapterIT {

    private static final String HOST = System.getProperty("badcsql.test.db.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("badcsql.test.db.port", 3306);
    private static final String USER = System.getProperty("badcsql.test.db.user", "root");
    private static final String PASS = System.getProperty("badcsql.test.db.pass", "root");

    private final MySqlAdapter adapter = new MySqlAdapter(new MySqlDialect());

    private static ConnConfig conn(String dbName) {
        return new ConnConfig(HOST, PORT, dbName, USER, PASS, "utf8mb4", "+00:00");
    }

    @Test
    void probe_reports_the_real_server_version_and_instant_ddl_support() {
        Capability capability = adapter.probe(conn(""), null);

        assertThat(capability.versionString()).isNotBlank();
        assertThat(capability.version()).isEqualTo(DbVersion.MYSQL_8_0);
        // 本地实例是 8.0.16，已支持 INSTANT DDL（>= 8.0.12）。
        assertThat(capability.supportsInstantDdl()).isTrue();
    }

    @Test
    void explain_analyze_boundary_matches_the_actual_patch_version() {
        Capability capability = adapter.probe(conn(""), null);

        int patch = Integer.parseInt(capability.versionString().split("\\.")[2].replaceAll("[^0-9].*$", ""));
        assertThat(capability.supportsExplainAnalyze())
                .as("EXPLAIN ANALYZE 需要 8.0.18+，实际 %s", capability.versionString())
                .isEqualTo(patch >= 18);
    }

    @Test
    void binlog_flag_comes_from_the_server_not_from_the_version_string() throws Exception {
        Capability capability = adapter.probe(conn(""), null);

        try (Connection connection = DriverManager.getConnection(
                MySqlAdapter.url(conn("")), USER, PASS);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select @@log_bin")) {
            assertThat(rs.next()).isTrue();
            assertThat(capability.binlogEnabled()).isEqualTo("1".equals(rs.getString(1)));
        }
    }

    @Test
    void without_an_online_ddl_account_online_ddl_is_not_available() {
        assertThat(adapter.probe(conn(""), null).onlineDdlAvailable()).isFalse();
    }

    @Test
    void unreachable_server_fails_fast_without_leaking_credentials() {
        ConnConfig unreachable = new ConnConfig(HOST, 1, "", USER, "s3cr3t-passw0rd", "utf8mb4", "+00:00");

        assertThatThrownBy(() -> adapter.probe(unreachable, null))
                .isInstanceOf(BizException.class)
                .hasMessageNotContaining("s3cr3t-passw0rd")
                .hasMessageNotContaining("root")
                .hasMessageContaining("不可达");
    }

    @Test
    void validate_delegates_to_the_version_rules() {
        assertThat(adapter.validate("with t as (select 1) select * from t", DbVersion.MYSQL_5_7).ok()).isFalse();
        assertThat(adapter.validate("with t as (select 1) select * from t", DbVersion.MYSQL_8_0).ok()).isTrue();
    }
}
