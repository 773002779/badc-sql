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
 * <p>第三个用例刻意用不可达的目标库：令牌正确时请求会走到执行器逻辑并因为连不上库失败，<b>只要不是 401
 * 就说明闸门放行</b>；反过来，若哪天过滤器被误删，这条会以 200/500 通过而前两条会红。
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

    @Test
    void the_right_token_gets_past_the_gate() {
        ResponseEntity<String> response = post(BODY_WITH_UNREACHABLE_DB, TOKEN);

        assertThat(response.getStatusCode())
                .as("放行后允许因目标库不可达而失败，但不能是 401")
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** 过闸之后请求确实进了控制器：缺少连接配置按既有口径回 400。 */
    @Test
    void the_gate_lets_the_request_reach_the_controller() throws Exception {
        ResponseEntity<String> response = post(BODY_WITHOUT_CONN, TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    private ResponseEntity<String> post(String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(TOKEN_HEADER, token);
        }
        return rest.exchange(
                FINGERPRINT_PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
