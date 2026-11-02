package com.badc.sql.control.notify.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationProperties;
import com.badc.sql.control.notify.NotificationSkippedException;
import com.badc.sql.control.notify.NotificationTarget;

/**
 * 企微渠道：用 JDK 自带的 {@link HttpServer} 起桩（不引入 WireMock，也不打真实企微）。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li>{@code enabled=false} 时 {@code isConfigured()} 为 false —— Task 5 据此把该渠道记成 SKIPPED；</li>
 *   <li>正常路径：先 {@code /cgi-bin/gettoken} 取 token（并缓存，两次投递只取一次），
 *       再 {@code /cgi-bin/message/send} 发文本消息，请求体带 {@code touser}；</li>
 *   <li>{@code errcode != 0} 必须抛异常，且消息里带企微给的 {@code errmsg}——
 *       否则运维只能看到"发送失败"，看不到"为什么"；</li>
 *   <li>收件人没有企微 userid 时<b>抛 {@link NotificationSkippedException}</b>（R18）：一个没配企微的运维
 *       不能让别人也发不出去，更不能拿 {@code null} 去请求企微；但"跳过"必须显式报给投递器，
 *       否则 {@code void send} 的静默返回会被记成 {@code SENT}。</li>
 * </ol>
 *
 * <p>base url 通过 {@code badcsql.notify.wecom.base-url} 指向本机桩，生产默认是企微官方地址。
 */
class WeComSenderTest {

    private static final String WECOM_USER = "ops01-wecom";
    private static final String TITLE = "【工单已提交】支付中心";
    private static final String BODY = "工单号：T-1";

    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger messageRequests = new AtomicInteger();
    private final AtomicReference<String> tokenQuery = new AtomicReference<>();
    private final AtomicReference<String> messageQuery = new AtomicReference<>();
    private final AtomicReference<String> messageBody = new AtomicReference<>();

    private volatile String tokenJson =
            "{\"errcode\":0,\"errmsg\":\"ok\",\"access_token\":\"TOKEN-1\",\"expires_in\":7200}";
    private volatile String sendJson = "{\"errcode\":0,\"errmsg\":\"ok\"}";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/gettoken", exchange -> {
            tokenRequests.incrementAndGet();
            tokenQuery.set(exchange.getRequestURI().getQuery());
            respondJson(exchange, tokenJson);
        });
        server.createContext("/cgi-bin/message/send", exchange -> {
            messageRequests.incrementAndGet();
            messageQuery.set(exchange.getRequestURI().getQuery());
            messageBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, sendJson);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void a_disabled_wecom_channel_is_not_configured() {
        WeComSender sender = sender(false);

        assertThat(sender.channel()).isEqualTo(NotificationChannel.WECOM);
        assertThat(sender.isConfigured()).as("enabled=false 的渠道必须被 Task 5 记成 SKIPPED").isFalse();
    }

    @Test
    void a_text_message_is_posted_to_the_target_wecom_user_with_a_cached_token() throws Exception {
        WeComSender sender = sender(true);

        sender.send(target(WECOM_USER), TITLE, BODY);
        sender.send(target(WECOM_USER), TITLE, BODY);

        assertThat(tokenRequests.get()).as("token 必须缓存：两次投递只取一次").isEqualTo(1);
        assertThat(tokenQuery.get())
                .as("取 token 的请求必须带 corpid 与 corpsecret")
                .contains("corpid=corp-1")
                .contains("corpsecret=secret-1");
        assertThat(messageRequests.get()).isEqualTo(2);
        assertThat(messageQuery.get()).as("发消息必须带上 access_token").contains("access_token=TOKEN-1");
        assertThat(messageBody.get())
                .as("请求体必须指定收件人的企微 userid、文本消息与 agentid")
                .contains("\"touser\":\"" + WECOM_USER + "\"")
                .contains("\"msgtype\":\"text\"")
                .contains("\"agentid\":1001")
                .contains("工单号：T-1");
    }

    @Test
    void a_non_zero_errcode_is_rethrown_with_the_errmsg() {
        sendJson = "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}";
        WeComSender sender = sender(true);

        assertThatThrownBy(() -> sender.send(target(WECOM_USER), TITLE, BODY))
                .as("errcode != 0 必须抛异常，并把企微的 errmsg 带出来")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("40001")
                .hasMessageContaining("invalid credential");
    }

    @Test
    void a_target_without_a_wecom_userid_is_skipped_and_reported_to_the_dispatcher() {
        WeComSender sender = sender(true);

        assertThatThrownBy(() -> sender.send(target(null), TITLE, BODY))
                .as("R18：静默 return 会被投递器记成 SENT；必须抛 NotificationSkippedException")
                .isInstanceOf(NotificationSkippedException.class)
                .hasMessageContaining("企微");

        assertThat(tokenRequests.get()).as("没有企微 userid 时连 token 都不该取").isZero();
        assertThat(messageRequests.get()).isZero();
    }

    private WeComSender sender(boolean enabled) {
        return new WeComSender(properties(enabled), new ObjectMapper(), Clock.systemUTC());
    }

    private NotificationProperties properties(boolean enabled) {
        return new NotificationProperties(
                new NotificationProperties.Wecom(enabled, "corp-1", 1001L, "secret-1", baseUrl),
                new NotificationProperties.Mail(false, ""),
                new NotificationProperties.Retry(3, 500));
    }

    private static NotificationTarget target(String wecomUserId) {
        return new NotificationTarget(2L, "ops01", "ops01@badc.test", wecomUserId);
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
