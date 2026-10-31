package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;

/**
 * 真实 MySQL 上的写执行：沿用执行器既有风格（见 {@code QueryRunnerIT}），纯 JUnit、不起 Spring 上下文、
 * 固定 {@code badc_test_writerunner} schema、裸 JDBC。
 *
 * <p>要钉住的是几件容易做错的事：
 * <ul>
 *   <li>语句失败必须<b>回结构化结果</b>而不是抛——抛出去控制面就只剩一个笼统的 FAILED；</li>
 *   <li>每条语句<b>独立提交</b>：前一条成功、后一条失败，前一条不能跟着回滚；</li>
 *   <li>错误消息<b>脱敏</b>：{@code '账号'@'主机'} 不能流到调用方；</li>
 *   <li>socket 读超时<b>不短于</b>语句超时（查询路径的 30 秒用在写路径上会误报"数据源不可达"）。</li>
 * </ul>
 */
class WriteRunnerIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String SCHEMA = "badc_test_writerunner";

    private static final Pattern SOCKET_TIMEOUT = Pattern.compile("[?&]socketTimeout=(\\d+)");

    private final WriteRunner runner = new WriteRunner();

    private static ConnConfig conn;

    private static ConnConfig conn(String dbName) {
        return new ConnConfig(HOST, PORT, dbName, USER, PASS, "utf8mb4", "+00:00");
    }

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
            s.execute("create database " + SCHEMA + " default character set utf8mb4");
        }
        conn = conn(SCHEMA);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
        }
    }

    @Test
    void an_update_reports_the_affected_row_count() throws Exception {
        execute("create table t_update (id bigint primary key, a int not null)");
        execute("insert into t_update values (1, 0), (2, 0)");

        ExecuteResult r = runner.run(conn, "update t_update set a = 1", ExecAccountType.DML);

        assertThat(r.success()).isTrue();
        assertThat(r.affectedRows()).isEqualTo(2);
        assertThat(r.errorCode()).isNull();
        assertThat(r.errorMessage()).isNull();
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(0);
        // 行数来自 executeUpdate 的返回值；再回读一次确认它没有虚报。
        assertThat(queryLong("select count(*) from t_update where a = 1")).isEqualTo(2);
    }

    /** 语句本身有错不是"执行器挂了"：要回结构化错误，让工单能逐条记录失败原因。 */
    @Test
    void a_failing_statement_returns_a_structured_failure_instead_of_throwing() {
        ExecuteResult r = runner.run(conn, "update no_such_table set a = 1", ExecAccountType.DML);

        assertThat(r.success()).isFalse();
        assertThat(r.affectedRows()).isZero();
        assertThat(r.errorMessage()).contains("no_such_table");
        assertThat(r.errorCode()).isNotBlank();
    }

    @Test
    void a_ddl_statement_succeeds() throws Exception {
        ExecuteResult r = runner.run(conn, "create table t_ddl (id int)", ExecAccountType.DDL);

        assertThat(r.success()).isTrue();
        assertThat(r.errorCode()).isNull();
        // 不能只看 success：表要真的建出来了（空表 count 为 0）。
        assertThat(queryLong("select count(*) from t_ddl")).isZero();
    }

    /** 每条语句独立提交：前一条成功、后一条失败，前一条不能跟着回滚——这正是"部分失败"的来源。 */
    @Test
    void each_statement_is_its_own_transaction() throws Exception {
        execute("create table t_independent (id int)");

        ExecuteResult inserted = runner.run(conn, "insert into t_independent values (1)", ExecAccountType.DML);
        ExecuteResult failed = runner.run(conn, "insert into no_such_table values (1)", ExecAccountType.DML);

        assertThat(inserted.success()).isTrue();
        assertThat(failed.success()).isFalse();
        assertThat(queryLong("select count(*) from t_independent")).isEqualTo(1);
    }

    /**
     * 28000 的 access denied 原文带着 {@code '账号'@'主机'}，不能进响应体。
     *
     * <p>纯函数用例：这里不验"库怎么拒绝"，只验"拒绝消息怎么进 {@link ExecuteResult}"。
     */
    @Test
    void an_access_denied_message_never_carries_the_account_or_host() {
        ExecuteResult r = WriteRunner.failure(
                new SQLException("Access denied for user 'root'@'127.0.0.1' (using password: YES)", "28000", 1045),
                7);

        assertThat(r.success()).isFalse();
        assertThat(r.affectedRows()).isZero();
        assertThat(r.elapsedMs()).isEqualTo(7);
        assertThat(r.errorCode()).isEqualTo("28000");
        assertThat(r.errorMessage())
                .contains("'***'@'***'")
                .doesNotContain("root")
                .doesNotContain("127.0.0.1");
    }

    /**
     * socket 读超时必须 ≥ 语句超时。
     *
     * <p>不真跑一条 5 分钟的语句来验证（那要多花 5 分钟且依赖时序），直接断言写路径拼出来的 URL。
     * 查询路径的 {@code socketTimeout=30000} 照搬到写路径时，本用例会红。
     */
    @Test
    void the_write_url_never_lets_the_socket_time_out_before_the_statement() {
        String dmlUrl = WriteRunner.url(conn, ExecAccountType.DML);
        String ddlUrl = WriteRunner.url(conn, ExecAccountType.DDL);

        assertThat(socketTimeoutOf(dmlUrl))
                .as("DML 语句超时 300s，socket 读超时不能短于它：%s", dmlUrl)
                .isGreaterThanOrEqualTo(300 * 1000);
        assertThat(socketTimeoutOf(ddlUrl))
                .as("DDL 语句超时 3600s，socket 读超时不能短于它：%s", ddlUrl)
                .isGreaterThanOrEqualTo(3600 * 1000);
        assertThat(ddlUrl).doesNotContain("socketTimeout=30000");
    }

    /** 只覆盖 socketTimeout，其余连接参数必须与查询路径一致（尤其是会话时区 UTC）。 */
    @Test
    void the_write_url_keeps_the_query_path_connection_parameters() {
        String url = WriteRunner.url(conn, ExecAccountType.DDL);

        assertThat(url)
                .startsWith("jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA)
                .contains("connectionTimeZone=UTC")
                .contains("forceConnectionTimeZoneToSession=true")
                .contains("connectTimeout=3000")
                .contains("useSSL=false")
                .contains("allowPublicKeyRetrieval=true");
    }

    /** 语句超时按账号类型选：写 300 秒、DDL 3600 秒（全局约束）。 */
    @Test
    void the_statement_timeout_follows_the_account_type() {
        assertThat(WriteRunner.timeoutSeconds(ExecAccountType.DML)).isEqualTo(300);
        assertThat(WriteRunner.timeoutSeconds(ExecAccountType.DDL)).isEqualTo(3600);
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long queryLong(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as("标量查询必须有结果行：%s", sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static int socketTimeoutOf(String url) {
        Matcher matcher = SOCKET_TIMEOUT.matcher(url);
        assertThat(matcher.find()).as("URL 里必须带 socketTimeout：%s", url).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
