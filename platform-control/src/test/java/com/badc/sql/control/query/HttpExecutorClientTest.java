package com.badc.sql.control.query;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 控制面到执行器的内部调用契约。
 *
 * <p>C3 的病根是"接口写好了但没人调"，所以这里要钉住的正是最容易接错的部分：**请求打到哪个路径、
 * 请求体里字段叫什么、执行器的错误体怎么还原**。路径或字段名写错，控制面会在生产上收到 404/400，
 * 表现为"版本校验偶发失效"，而单测里的方言逻辑仍然全绿。
 *
 * <p>执行器用 JDK 自带的 {@link HttpServer} 起一个真实 HTTP 端点，而不是 mock {@code RestClient}：
 * 要验的就是真实的 URI 拼装、JSON 序列化与非 2xx 处理。
 *
 * <p>内部令牌同理：执行器的 {@code /internal/**} 现在要求 {@code X-Badc-Internal-Token}，
 * 客户端漏发这个头，单元测试仍然全绿（桩不校验令牌），生产上每一次内部调用却会被 401 挡下。
 */
class HttpExecutorClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ConnConfig READONLY_CONN = new ConnConfig(
            "10.1.2.3", 3306, "order_db", "readonly_user", "readonly-pass", "utf8mb4", "+00:00");

    private static final ConnConfig ONLINE_DDL_CONN = new ConnConfig(
            "10.1.2.3", 3306, "order_db", "onlineddl_user", "onlineddl-pass", "utf8mb4", "+00:00");

    private HttpServer server;
    private HttpExecutorClient client;

    /** 下一次请求的响应；默认空响应体 + 200。 */
    private final AtomicReference<Reply> reply = new AtomicReference<>(new Reply(200, ""));

    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();

    @BeforeEach
    void startExecutor() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/v1", this::handle);
        server.start();
        client = new HttpExecutorClient("http://127.0.0.1:" + server.getAddress().getPort(), "s3cret");
    }

    @AfterEach
    void stopExecutor() {
        server.stop(0);
    }

    @Test
    void probe_posts_both_connections_and_parses_the_capability() throws Exception {
        reply.set(new Reply(200, """
                {"versionString":"8.0.36","version":"MYSQL_8_0","supportsExplainAnalyze":true,
                 "supportsInstantDdl":true,"binlogEnabled":true,"onlineDdlAvailable":false}
                """));

        Capability capability = client.probe(READONLY_CONN, ONLINE_DDL_CONN);

        assertThat(lastPath.get()).isEqualTo("/internal/v1/probe");
        JsonNode sent = MAPPER.readTree(lastBody.get());
        assertThat(sent.path("conn").path("host").asText()).isEqualTo("10.1.2.3");
        assertThat(sent.path("conn").path("username").asText()).isEqualTo("readonly_user");
        assertThat(sent.path("onlineDdlConn").path("username").asText()).isEqualTo("onlineddl_user");

        assertThat(capability.versionString()).isEqualTo("8.0.36");
        assertThat(capability.version()).isEqualTo(DbVersion.MYSQL_8_0);
        assertThat(capability.supportsExplainAnalyze()).isTrue();
    }

    /** 没配在线 DDL 账号时线上传 {@code null}，执行器据此判定 onlineDdl 不可用。 */
    @Test
    void probe_sends_a_null_online_ddl_connection_when_the_datasource_has_none() throws Exception {
        reply.set(new Reply(200, """
                {"versionString":"5.7.44","version":"MYSQL_5_7","supportsExplainAnalyze":false,
                 "supportsInstantDdl":false,"binlogEnabled":false,"onlineDdlAvailable":false}
                """));

        Capability capability = client.probe(READONLY_CONN, null);

        assertThat(MAPPER.readTree(lastBody.get()).path("onlineDdlConn").isNull()).isTrue();
        assertThat(capability.version()).isEqualTo(DbVersion.MYSQL_5_7);
    }

    /**
     * 非 2xx 必须还原成执行器原始的 {@code {code,message}}，与 {@code query} 走同一条路
     * （{@link ExecutorErrors}）——否则"数据源不可达"会被糊成"执行器返回错误（HTTP 500）"，
     * 用户分不清是自己的库连不上还是平台挂了。
     */
    @Test
    void probe_reuses_the_executor_error_mapping_for_non_2xx() {
        reply.set(new Reply(500, """
                {"code":"INTERNAL_ERROR","message":"数据源不可达（SQLState=08S01），请确认网络连通与白名单配置"}
                """));

        assertThatThrownBy(() -> client.probe(READONLY_CONN, null))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                    assertThat(biz.getMessage()).contains("数据源不可达").contains("08S01");
                });
    }

    /** 执行器进程不在：抛的是"执行器不可用"，由能力缓存吞掉，不阻断查询。 */
    @Test
    void probe_reports_an_unreachable_executor_as_unavailable() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }
        HttpExecutorClient dead = new HttpExecutorClient("http://127.0.0.1:" + deadPort, "s3cret");

        assertThatThrownBy(() -> dead.probe(READONLY_CONN, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("执行器不可用");
    }

    @Test
    void validate_posts_the_sql_with_the_probed_version_and_parses_the_rejection() throws Exception {
        reply.set(new Reply(200, """
                {"ok":false,"errorCode":"DB_VERSION_UNSUPPORTED",
                 "message":"该库为 MySQL 5.7，不支持 WITH 子句（CTE）；请改写 SQL 或改用 8.0 实例",
                 "line":null,"column":null}
                """));

        ValidationResult result = client.validate(
                READONLY_CONN, "with t as (select 1) select * from t", DbVersion.MYSQL_5_7);

        assertThat(lastPath.get()).isEqualTo("/internal/v1/validate");
        JsonNode sent = MAPPER.readTree(lastBody.get());
        assertThat(sent.path("sql").asText()).isEqualTo("with t as (select 1) select * from t");
        assertThat(sent.path("dbVersion").asText()).isEqualTo("MYSQL_5_7");

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DB_VERSION_UNSUPPORTED");
        assertThat(result.message()).contains("5.7").contains("WITH");
    }

    @Test
    void validate_returns_a_success_verdict_instead_of_throwing_when_the_sql_is_supported() throws Exception {
        reply.set(new Reply(200, "{\"ok\":true,\"errorCode\":null,\"message\":null}"));

        ValidationResult result = client.validate(READONLY_CONN, "select 1", DbVersion.MYSQL_8_0);

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).isNull();
    }

    /**
     * 指纹入口的三处接错点：路径、请求体里表名挂在 {@code tables} 数组下、返回的 map 被解析出来。
     *
     * <p>路径写错是 404；字段名写成 {@code tableNames} 之类是 400 或静默的空结果；返回体解析不对则
     * 漂移检测拿到空 map——会被误读成"表不存在"，正是提交与执行判定的分叉点。
     */
    @Test
    void fingerprint_posts_the_tables_array_and_parses_the_returned_map() throws Exception {
        reply.set(new Reply(200, "{\"t_order\":\"fp-1\",\"t_item\":\"fp-2\"}"));

        Map<String, String> fingerprints = client.fingerprint(READONLY_CONN, List.of("t_order", "t_item"));

        assertThat(lastPath.get()).isEqualTo("/internal/v1/fingerprint");
        JsonNode sent = MAPPER.readTree(lastBody.get());
        assertThat(sent.path("conn").path("dbName").asText()).isEqualTo("order_db");
        assertThat(sent.path("tables").get(0).asText()).isEqualTo("t_order");
        assertThat(sent.path("tables").get(1).asText()).isEqualTo("t_item");

        assertThat(fingerprints).containsEntry("t_order", "fp-1").containsEntry("t_item", "fp-2");
    }

    /**
     * 令牌是构造时挂在 {@code RestClient} 上的默认头，validate 带出去即说明其余内部调用也带；
     * 少了这条，桩不校验令牌，单测全绿而生产上每一次内部调用都会被 401 挡下。
     */
    @Test
    void every_internal_call_carries_the_token() throws Exception {
        reply.set(new Reply(200, "{\"ok\":true}"));

        client.validate(READONLY_CONN, "select 1", DbVersion.MYSQL_8_0);

        assertThat(lastToken.get()).isEqualTo("s3cret");
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Badc-Internal-Token"));
        Reply response = reply.get();
        byte[] body = response.body().getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /** 桩响应：状态码 + JSON 响应体。 */
    private record Reply(int status, String body) {
    }
}
