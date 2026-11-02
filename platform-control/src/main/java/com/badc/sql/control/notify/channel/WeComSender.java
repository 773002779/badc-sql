package com.badc.sql.control.notify.channel;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationProperties;
import com.badc.sql.control.notify.NotificationTarget;

/**
 * 企业微信应用消息渠道：先取 token，再发文本消息。
 *
 * <p>流程与失败口径：
 * <ol>
 *   <li>{@code GET /cgi-bin/gettoken?corpid=&corpsecret=} 取 {@code access_token}，
 *       缓存到 {@code expires_in - 120s}（提前 2 分钟过期，避免网络往返途中正好过期）；</li>
 *   <li>{@code POST /cgi-bin/message/send?access_token=} 发
 *       {@code {"touser":..., "msgtype":"text", "agentid":..., "text":{"content":"标题\n正文"}}}；</li>
 *   <li>两次调用只要 {@code errcode != 0} 就抛 {@link IllegalStateException}，消息里带
 *       {@code errcode} 与企微返回的 {@code errmsg}——运维要能从 {@code last_error} 看出
 *       "是凭证错了还是收件人不存在"。</li>
 * </ol>
 *
 * <p>base url 走 {@code badcsql.notify.wecom.base-url}，默认企微官方地址；单测把它指向本机
 * {@code HttpServer} 桩，生产/测试走同一段真实 HTTP 代码，不需要为打桩改造实现。
 *
 * <p>用 JDK 自带的 {@link HttpClient}，不引额外 HTTP 客户端依赖。
 *
 * <p>token 缓存用 {@link Clock} 判过期（注入的 UTC 时钟），不读 {@code System.currentTimeMillis}：
 * 时间可被测试控制，且与平台其它时间口径一致。
 *
 * <p>收件人没有企微 userid 时<b>直接跳过，连 token 都不取</b>——没配企微的人不该让整批投递多打一次
 * 外部请求，更不该拿 {@code null} 去请求企微。
 */
@Component
public class WeComSender implements ChannelSender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** 提前 2 分钟认为 token 过期。 */
    private static final long TOKEN_EXPIRY_SAFETY_SECONDS = 120;
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 7200;

    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;

    private String cachedToken;
    private Instant cachedTokenExpiresAt = Instant.EPOCH;

    public WeComSender(NotificationProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WECOM;
    }

    @Override
    public boolean isConfigured() {
        return properties.wecom().enabled();
    }

    @Override
    public void send(NotificationTarget target, String title, String body) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("企微渠道未启用（badcsql.notify.wecom.enabled=false）");
        }
        String wecomUserId = target.wecomUserId();
        if (wecomUserId == null || wecomUserId.isBlank()) {
            return;
        }

        String accessToken = accessToken();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("touser", wecomUserId);
        payload.put("msgtype", "text");
        payload.put("agentid", properties.wecom().agentId());
        payload.putObject("text").put("content", title + "\n" + body);
        payload.put("safe", 0);
        String requestBody = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder(
                        uri("/cgi-bin/message/send?access_token=" + encode(accessToken)))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireHttpOk("message/send", response);
        JsonNode result = readJson(response);
        int errcode = result.path("errcode").asInt(-1);
        if (errcode != 0) {
            throw new IllegalStateException("企微消息发送失败：errcode=" + errcode
                    + "，errmsg=" + errmsg(result));
        }
    }

    /**
     * 取 access_token（带缓存）。
     *
     * <p>{@code synchronized}：投递器可能并发投递，多个线程同时发现过期时只应有一个去取 token，
     * 其余等待后直接用缓存，避免把企微的取 token 配额打满。
     */
    private synchronized String accessToken() throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(cachedTokenExpiresAt)) {
            return cachedToken;
        }

        NotificationProperties.Wecom wecom = properties.wecom();
        HttpRequest request = HttpRequest.newBuilder(uri("/cgi-bin/gettoken?corpid=" + encode(wecom.corpId())
                        + "&corpsecret=" + encode(wecom.secret())))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireHttpOk("gettoken", response);

        JsonNode json = readJson(response);
        int errcode = json.path("errcode").asInt(-1);
        if (errcode != 0) {
            throw new IllegalStateException("企微 gettoken 失败：errcode=" + errcode
                    + "，errmsg=" + errmsg(json));
        }
        String token = json.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("企微 gettoken 未返回 access_token");
        }
        long expiresIn = json.path("expires_in").asLong(DEFAULT_EXPIRES_IN_SECONDS);
        cachedToken = token;
        cachedTokenExpiresAt = now.plusSeconds(Math.max(0, expiresIn - TOKEN_EXPIRY_SAFETY_SECONDS));
        return token;
    }

    private URI uri(String pathAndQuery) {
        String base = properties.wecom().baseUrl();
        if (base == null || base.isBlank()) {
            base = NotificationProperties.DEFAULT_WECOM_BASE_URL;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + pathAndQuery);
    }

    private static void requireHttpOk(String operation, HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("企微 " + operation + " 失败：HTTP " + response.statusCode());
        }
    }

    private JsonNode readJson(HttpResponse<String> response) throws IOException {
        return objectMapper.readTree(response.body());
    }

    private static String errmsg(JsonNode json) {
        return json.path("errmsg").asText("");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
