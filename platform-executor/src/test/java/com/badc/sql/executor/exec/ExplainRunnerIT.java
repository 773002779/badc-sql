package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;

/**
 * 真实 MySQL 上的估算权限与故障口径。
 *
 * <p>两条口径必须分开，否则控制面分不清"估不出来"和"探测不了这个库"：
 * <ul>
 *   <li><b>权限不足是故障</b>：只授 SELECT 的账号能 EXPLAIN SELECT，但 EXPLAIN UPDATE 会被 MySQL 以
 *       1142（command denied）拒绝——{@link ExplainRunner} 必须抛 {@code BizException}
 *       （与 {@code ExecutorClient#explain}、{@code ExplainEstimate} 的契约一致）。</li>
 *   <li><b>语句本身不合法不是故障</b>：语法错仍是"估不出来"，返回 {@code -1}，由调用方按最严分档。</li>
 * </ul>
 *
 * <p>历史教训：任务 7a 评审 C1 曾把 1142 吞成 {@code -1}，于是每张走只读账号估算的 DML 工单都被判 L3；
 * 但"吞掉"只是把故障藏起来，并不是它的正确口径——正确解法是<b>用 dml 账号估 DML</b>（提交期如此），
 * 权限真的不足时如实抛错。
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

    /**
     * 只读账号 EXPLAIN 写语句 → 1142 → 必须抛 {@code BizException}（权限不足是故障），
     * 而不是静默给出 {@code -1}。
     */
    @Test
    void a_read_only_account_explaining_a_write_fails_loudly() {
        assertThatThrownBy(() -> runner.estimate(readOnlyConn(),
                "update t_order set status = 1 where id = 1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("1142")
                        .doesNotContain(READONLY_USER));
    }

    /** 语法错不是故障：仍是"估不出来"（{@code -1}），由调用方按最严分档。 */
    @Test
    void a_syntax_error_is_still_an_unknown_estimate() {
        assertThat(runner.estimate(readOnlyConn(), "select * from"))
                .isEqualTo(new ExplainEstimate(-1));
    }

    /** 表单不存在同样是"估不出来"，不能因为 1146 就抛。 */
    @Test
    void a_missing_table_is_still_an_unknown_estimate() {
        assertThat(runner.estimate(readOnlyConn(), "select * from no_such_table"))
                .isEqualTo(new ExplainEstimate(-1));
    }

    /** 库不可达（端口 1 上没有 MySQL）→ 08xxx → 抛"数据源不可达"，不能吞成 {@code -1}。 */
    @Test
    void an_unreachable_database_fails_loudly_instead_of_estimating_zero() {
        ConnConfig unreachable = new ConnConfig(
                HOST, 1, SCHEMA, READONLY_USER, READONLY_PASS, "utf8mb4", "+00:00");

        assertThatThrownBy(() -> runner.estimate(unreachable, "select * from t_order"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("数据源不可达");
    }

    private static ConnConfig readOnlyConn() {
        return new ConnConfig(HOST, PORT, SCHEMA, READONLY_USER, READONLY_PASS, "utf8mb4", "+00:00");
    }

    private static ConnConfig rootConn(String dbName) {
        return new ConnConfig(HOST, PORT, dbName, ROOT_USER, ROOT_PASS, "utf8mb4", "+00:00");
    }
}
