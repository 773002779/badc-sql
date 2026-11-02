package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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
 * <p>只放<b>必须连库</b>的用例：验证"语句真的被执行了、真的按条提交了"。URL 拼装、socket 超时数学、
 * 错误消息脱敏这些纯函数用例在 {@link WriteRunnerTest} 里——它们不需要 MySQL，无库 CI 也必须跑。
 *
 * <p>要钉住的是几件容易做错的事：
 * <ul>
 *   <li>语句失败必须<b>回结构化结果</b>而不是抛——抛出去控制面就只剩一个笼统的 FAILED；</li>
 *   <li>前一条成功、后一条失败时，<b>前一条不能跟着消失</b>——这是"部分失败"的来源；</li>
 *   <li>DDL 真的建出了表（不能只看 {@code success}）。</li>
 * </ul>
 */
class WriteRunnerIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String SCHEMA = "badc_test_writerunner";

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

    /**
     * 前一条成功、后一条失败时，前一条<b>不能跟着消失</b>——这正是"部分失败"的来源，工单必须如实记录
     * "哪几条已经生效"。
     *
     * <p><b>这个用例证明的是"已生效的不被抹掉"，不是"事务边界"。</b>每次 {@link WriteRunner#run} 都新开
     * 连接，"前一条跟着回滚"在结构上不可能发生——所以原来的名字 {@code each_statement_is_its_own_transaction}
     * 是恒真的（把实现改成"包事务"它照样绿）。名字已让给本条用例，这里只保留能真正验证的部分：
     * 第一条落库、第二条失败、第一条仍在。
     */
    @Test
    void an_earlier_statement_keeps_its_effect_when_a_later_one_fails() throws Exception {
        execute("create table t_independent (id int)");

        ExecuteResult inserted = runner.run(conn, "insert into t_independent values (1)", ExecAccountType.DML);
        ExecuteResult failed = runner.run(conn, "insert into no_such_table values (1)", ExecAccountType.DML);

        assertThat(inserted.success()).isTrue();
        assertThat(failed.success()).isFalse();
        assertThat(queryLong("select count(*) from t_independent")).isEqualTo(1);
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
}
