package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;

/**
 * {@link WriteRunner} 的<b>纯函数</b>部分：URL 拼装、超时选择、失败结果构造。
 *
 * <p>为什么单独成类（不放在 {@code WriteRunnerIT}）：这些用例不碰数据库，却钉着两条安全/正确性约束——
 * "socket 读超时必须严格长于语句超时"和"错误消息不能带出账号与主机"。放在 IT 里，无库的 CI 就一条都不跑，
 * 而无库 CI 恰恰是最常跑的那条。IT 只保留真正需要 MySQL 的用例。
 */
class WriteRunnerTest {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String SCHEMA = "badc_test_writerunner";

    private static final Pattern SOCKET_TIMEOUT = Pattern.compile("[?&]socketTimeout=(\\d+)");

    private static final ConnConfig CONN =
            new ConnConfig(HOST, PORT, SCHEMA, USER, PASS, "utf8mb4", "+00:00");

    /**
     * socket 读超时必须<b>严格大于</b>语句超时（不能只是"不小于"）。
     *
     * <p>语句超时到点时 MySQL 要 KILL QUERY 并回一条错误，socket 得还开着才读得到；两者相等时客户端
     * 先等到 socket 超时，用户看到的是 {@code 08S01}/"数据源不可达"，而不是"语句超时"。
     * 用严格大于的断言，把"恰好相等"这种退化解挡在门外。
     */
    @Test
    void the_write_url_never_lets_the_socket_time_out_before_the_statement() {
        assertThat(WriteRunner.socketTimeoutMillis(ExecAccountType.DML))
                .as("DML 语句超时 300s：socket 读超时必须严格更长")
                .isGreaterThan(WriteRunner.timeoutSeconds(ExecAccountType.DML) * 1000);
        assertThat(WriteRunner.socketTimeoutMillis(ExecAccountType.DDL))
                .as("DDL 语句超时 3600s：socket 读超时必须严格更长")
                .isGreaterThan(WriteRunner.timeoutSeconds(ExecAccountType.DDL) * 1000);

        String dmlUrl = WriteRunner.url(CONN, ExecAccountType.DML);
        String ddlUrl = WriteRunner.url(CONN, ExecAccountType.DDL);

        assertThat(socketTimeoutOf(dmlUrl))
                .as("URL 里落地的值也要严格大于语句超时：%s", dmlUrl)
                .isGreaterThan(WriteRunner.timeoutSeconds(ExecAccountType.DML) * 1000);
        assertThat(socketTimeoutOf(ddlUrl))
                .as("URL 里落地的值也要严格大于语句超时：%s", ddlUrl)
                .isGreaterThan(WriteRunner.timeoutSeconds(ExecAccountType.DDL) * 1000);
        assertThat(ddlUrl).doesNotContain("socketTimeout=30000");
    }

    /** 只覆盖 socketTimeout，其余连接参数必须与查询路径一致（尤其是会话时区 UTC）。 */
    @Test
    void the_write_url_keeps_the_query_path_connection_parameters() {
        String url = WriteRunner.url(CONN, ExecAccountType.DDL);

        assertThat(url)
                .startsWith("jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA)
                .contains("connectionTimeZone=UTC")
                .contains("forceConnectionTimeZoneToSession=true")
                .contains("connectTimeout=3000")
                .contains("useSSL=false")
                .contains("allowPublicKeyRetrieval=true");
    }

    /** socketTimeout 按账号类型选，精确到值：DML 360s、DDL 3660s（各比语句超时多 60s）。 */
    @Test
    void the_socket_timeout_leaves_a_margin_over_the_statement_timeout() {
        assertThat(WriteRunner.socketTimeoutMillis(ExecAccountType.DML)).isEqualTo(360_000);
        assertThat(WriteRunner.socketTimeoutMillis(ExecAccountType.DDL)).isEqualTo(3_660_000);
        assertThat(WriteRunner.socketTimeoutMillis(ExecAccountType.DML) - 300_000).isEqualTo(60_000);
        assertThat(WriteRunner.socketTimeoutMillis(ExecAccountType.DDL) - 3_600_000).isEqualTo(60_000);
    }

    /** 语句超时按账号类型选：写 300 秒、DDL 3600 秒（全局约束）。 */
    @Test
    void the_statement_timeout_follows_the_account_type() {
        assertThat(WriteRunner.timeoutSeconds(ExecAccountType.DML)).isEqualTo(300);
        assertThat(WriteRunner.timeoutSeconds(ExecAccountType.DDL)).isEqualTo(3600);
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
     * 1130 与驱动层的连接串也是同样的暴露面（{@code Host 'x'}、{@code jdbc:mysql://x:3306/db}），
     * 写路径同样不能把它们回显给前端。
     */
    @Test
    void host_and_connection_string_messages_are_masked_too() {
        ExecuteResult host = WriteRunner.failure(new SQLException(
                "Host '10.1.2.3' is not allowed to connect to this MySQL server", "HY000", 1130), 1);
        ExecuteResult noDriver = WriteRunner.failure(new SQLException(
                "No suitable driver found for jdbc:mysql://10.1.2.3:3306/order_db?user=root", "08001", 0), 1);

        assertThat(host.errorMessage()).doesNotContain("10.1.2.3").contains("Host '***'");
        assertThat(noDriver.errorMessage()).doesNotContain("10.1.2.3").doesNotContain("order_db");
    }

    /**
     * 连接类（08xxx）<b>不回显驱动原文</b>，与 {@code QueryRunner#translate}、{@code SchemaSnapshotter#failure}
     * 一致：驱动原文可能带 host:port 甚至整条连接串，而这条结果会经控制面流向前端。
     *
     * <p>08xxx 同时说明"这条语句<b>结果未知</b>"：连接断了不代表语句没生效。
     */
    @Test
    void a_connection_failure_never_echoes_the_driver_message() {
        ExecuteResult r = WriteRunner.failure(
                new SQLException("Communications link failure to 10.1.2.3:3306", "08S01", 0), 12);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo("08S01");
        assertThat(r.errorMessage())
                .contains("数据源不可达")
                .contains("08S01")
                .doesNotContain("10.1.2.3")
                .doesNotContain("Communications link failure");
    }

    /** SQLState 缺失时不能拼出字面量 "null"，也不能因为没 SQLState 就吞掉消息。 */
    @Test
    void a_failure_without_sql_state_still_has_a_message() {
        ExecuteResult r = WriteRunner.failure(new SQLException(null, null, 0), 3);

        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isNull();
        assertThat(r.errorMessage()).isEmpty();
    }

    private static int socketTimeoutOf(String url) {
        Matcher matcher = SOCKET_TIMEOUT.matcher(url);
        assertThat(matcher.find()).as("URL 里必须带 socketTimeout：%s", url).isTrue();
        return Integer.parseInt(matcher.group(1));
    }
}
