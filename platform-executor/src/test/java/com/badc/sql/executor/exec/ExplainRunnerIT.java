package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;

/**
 * 真实 MySQL 上的估算权限测试：<b>只授 SELECT 的账号能 EXPLAIN SELECT，但 EXPLAIN UPDATE 会被
 * MySQL 以 1142（command denied）拒绝</b>，{@link ExplainRunner} 必须把它转成 {@code -1}。
 *
 * <p>这是任务 7a 评审 C1 的根因用例：控制面若用只读账号估算 DML，1142 会被吞成 {@code -1}，
 * 风险引擎再把负估算判 L3，于是每张 DML 工单都变成 L3——L1/L2 分档在真实环境里从未生效。
 * 本用例把"只读账号估不了写语句"钉在真实库上，防止将来有人把异常改成 0 或抛出去。
 *
 * <p>需要 root 建临时用户；用完删掉，不污染本机 MySQL。
 */
class ExplainRunnerIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String ROOT_USER = "root";
    private static final String ROOT_PASS = "root";
    private static final String SCHEMA = "badc_test_explainrunner";
    private static final String READONLY_USER = "badc_explain_ro";
    private static final String READONLY_PASS = "BadcExplain#2026";

    private final ExplainRunner runner = new ExplainRunner();

    @BeforeAll
    static void createSchemaAndReadOnlyUser() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MySqlAdapter.url(rootConn("")), ROOT_USER, ROOT_PASS);
                Statement statement = connection.createStatement()) {
            statement.execute("drop database if exists " + SCHEMA);
            statement.execute("create database " + SCHEMA + " default character set utf8mb4");
            statement.execute("create table " + SCHEMA + ".t_order (id int primary key, status int not null)");
            statement.execute("insert into " + SCHEMA + ".t_order values (1, 0)");
            statement.execute("drop user if exists '" + READONLY_USER + "'@'%'");
            statement.execute("create user '" + READONLY_USER + "'@'%' identified by '" + READONLY_PASS + "'");
            statement.execute("grant select on `" + SCHEMA + "`.* to '" + READONLY_USER + "'@'%'");
            statement.execute("flush privileges");
        }
    }

    @AfterAll
    static void dropSchemaAndUser() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MySqlAdapter.url(rootConn("")), ROOT_USER, ROOT_PASS);
                Statement statement = connection.createStatement()) {
            statement.execute("drop user if exists '" + READONLY_USER + "'@'%'");
            statement.execute("drop database if exists " + SCHEMA);
        }
    }

    @Test
    void a_read_only_account_can_explain_a_select() {
        ExplainEstimate estimate = runner.estimate(readOnlyConn(),
                "select * from t_order where id = 1");

        assertThat(estimate.estimatedRows()).isGreaterThanOrEqualTo(0);
    }

    /** 只读账号 EXPLAIN 写语句 → 1142 → 必须返回 -1（"估不出来"），而不是 0 或异常。 */
    @Test
    void a_read_only_account_cannot_explain_a_write_and_gets_minus_one() {
        ExplainEstimate estimate = runner.estimate(readOnlyConn(),
                "update t_order set status = 1 where id = 1");

        assertThat(estimate).isEqualTo(new ExplainEstimate(-1));
    }

    private static ConnConfig readOnlyConn() {
        return new ConnConfig(HOST, PORT, SCHEMA, READONLY_USER, READONLY_PASS, "utf8mb4", "+00:00");
    }

    private static ConnConfig rootConn(String dbName) {
        return new ConnConfig(HOST, PORT, dbName, ROOT_USER, ROOT_PASS, "utf8mb4", "+00:00");
    }
}
