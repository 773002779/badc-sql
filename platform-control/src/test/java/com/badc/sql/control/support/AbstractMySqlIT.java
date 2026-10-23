package com.badc.sql.control.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 本地 MySQL 集成测试基类（R10/R11）。
 *
 * <p>每个测试类创建独立随机 schema {@code badc_test_<8位随机>}，执行 Flyway 迁移后提供
 * {@link JdbcTemplate}；测试结束 DROP 该 schema。只允许操作 {@code badc_test_} 前缀的库。
 *
 * <p>连接参数可用环境变量覆盖：{@code BADCSQL_TEST_DB_URL}（服务器地址，不含库名）、
 * {@code BADCSQL_TEST_DB_USER}、{@code BADCSQL_TEST_DB_PASS}。
 */
public abstract class AbstractMySqlIT {

    private static final String DEFAULT_BASE_URL = "jdbc:mysql://127.0.0.1:3306";
    // characterEncoding 只接受 Java 字符集名（UTF-8 在 MySQL 8 上即 utf8mb4）；
    // forceConnectionTimeZoneToSession 把会话时区固定为 UTC，保证 CURRENT_TIMESTAMP 默认值写入 UTC。
    private static final String CONNECTION_PARAMS =
            "useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                    + "&characterEncoding=UTF-8&allowPublicKeyRetrieval=true";
    private static final Pattern SCHEMA_NAME = Pattern.compile("badc_test_[a-z0-9]{8}");

    protected static String schema;
    protected static JdbcTemplate jdbc;

    @BeforeAll
    static void createSchemaAndMigrate() {
        ensureSchema();
    }

    /**
     * 确保随机 schema 已创建并完成迁移，返回该 schema 的 JDBC URL。
     *
     * <p>幂等；供 {@code @DynamicPropertySource} 在 Spring 上下文启动前调用（此时 {@code @BeforeAll}
     * 尚未执行），{@link #createSchemaAndMigrate()} 随后再调用一次不会重复建库。
     */
    protected static synchronized String ensureSchema() {
        if (schema != null) {
            return schemaUrl();
        }

        schema = "badc_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        executeOnServer("create database `" + schema + "` character set utf8mb4 collate utf8mb4_0900_ai_ci");

        Flyway.configure()
                .dataSource(schemaUrl(), dbUser(), dbPass())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(), dbUser(), dbPass()));
        return schemaUrl();
    }

    @AfterAll
    static void dropSchema() {
        if (schema == null) {
            return;
        }
        try {
            executeOnServer("drop database if exists `" + schema + "`");
        } finally {
            jdbc = null;
            schema = null;
        }
    }

    private static void executeOnServer(String sql) {
        if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalStateException("refusing to run DDL against unsafe schema name: " + schema);
        }
        try (Connection connection = DriverManager.getConnection(serverUrl(), dbUser(), dbPass());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to execute on MySQL server: " + sql, e);
        }
    }

    private static String serverUrl() {
        return baseUrl() + "?" + CONNECTION_PARAMS;
    }

    private static String schemaUrl() {
        return baseUrl() + "/" + schema + "?" + CONNECTION_PARAMS;
    }

    private static String baseUrl() {
        String url = System.getenv().getOrDefault("BADCSQL_TEST_DB_URL", DEFAULT_BASE_URL);
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    protected static String dbUser() {
        return System.getenv().getOrDefault("BADCSQL_TEST_DB_USER", "root");
    }

    protected static String dbPass() {
        return System.getenv().getOrDefault("BADCSQL_TEST_DB_PASS", "root");
    }
}
