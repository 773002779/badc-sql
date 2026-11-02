package com.badc.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内部端点鉴权的<b>接线</b>测试。
 *
 * <p>{@code InternalAuthFilterTest} 只用 {@code standaloneSetup} 证明了过滤器本身的行为：它不经过
 * Spring 容器，因此<b>无法证明过滤器真的被注册进了容器</b>。这里起真实上下文与真实端口，用真实 HTTP
 * 打 {@code /internal/v1/fingerprint}——即使同名的过滤器忘了注册、或注册在错误的 URL 模式上，也会红。
 *
 * <p>令牌正确的用例刻意用不可达的目标库：请求会走到执行器逻辑、因连不上库返回 500
 * {@code INTERNAL_ERROR}。断言必须是"500 + INTERNAL_ERROR"而不是"不是 401"——后者放得过一个路径写错的 404，
 * 那等于什么都没证明。反过来，若哪天过滤器被误删，前两条会红。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "badcsql.internal.token=it-token")
class InternalEndpointAuthIT {

    private static final String TOKEN_HEADER = "X-Badc-Internal-Token";
    private static final String TOKEN = "it-token";
    private static final String FINGERPRINT_PATH = "/internal/v1/fingerprint";

    /** 端口 1 上不会有 MySQL：放行后请求必然失败，但失败方式不是 401。 */
    private static final String BODY_WITH_UNREACHABLE_DB = """
            {"conn":{"host":"127.0.0.1","port":1,"dbName":"badc_test_auth",
            "username":"root","password":"root","charset":"utf8mb4","timeZone":"+00:00"},
            "tables":["t_order"]}
            """;

    private static final String BODY_WITHOUT_CONN = "{\"conn\":null,\"tables\":[\"t_order\"]}";

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper json = new ObjectMapper();

    // 默认 HttpURLConnection 在收到 401 时会抛 HttpRetryException（流式 POST 无法重试认证）；
    // JDK HttpClient 能正常读回 401 的响应体，断言才做得了。
    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void a_request_without_the_token_is_rejected() throws Exception {
        ResponseEntity<String> response = post(BODY_WITH_UNREACHABLE_DB, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void a_request_with_a_wrong_token_is_rejected() throws Exception {
        ResponseEntity<String> response = post(BODY_WITH_UNREACHABLE_DB, "wrong");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    /** 请求确实穿过了闸门、进到了控制器：连不上目标库 → 执行器的 500 INTERNAL_ERROR，而不是 404/401。 */
    @Test
    void the_right_token_gets_past_the_gate() throws Exception {
        ResponseEntity<String> response = post(BODY_WITH_UNREACHABLE_DB, TOKEN);

        assertThat(response.getStatusCode())
                .as("路径写错会得到 404，令牌被拒会得到 401；只有真进了控制器才是库不可达的 500")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("INTERNAL_ERROR");
    }

    /** 过闸之后请求确实进了控制器：缺少连接配置按既有口径回 400。 */
    @Test
    void the_gate_lets_the_request_reach_the_controller() throws Exception {
        ResponseEntity<String> response = post(BODY_WITHOUT_CONN, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    /**
     * 矩阵参数不能绕过闸门。
     *
     * <p>{@code InternalAuthFilterTest} 用 {@code standaloneSetup} 钉住了过滤器本身；这里在真实容器 + 真实
     * 分发器上再钉一次：Tomcat 保留 {@code ;x=1}，分发器却按剥掉后的 {@code /internal/v1/fingerprint} 路由，
     * 只看原始 URI 的闸门会放行这条<b>无令牌</b>的请求。
     */
    @Test
    void a_matrix_parameter_does_not_bypass_the_gate() throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "/internal;x=1/v1/fingerprint", HttpMethod.POST,
                new HttpEntity<>(BODY_WITH_UNREACHABLE_DB, jsonHeaders(null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    private ResponseEntity<String> post(String body, String token) {
        return rest.exchange(
                FINGERPRINT_PATH, HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(token)), String.class);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(TOKEN_HEADER, token);
        }
        return headers;
    }
}
