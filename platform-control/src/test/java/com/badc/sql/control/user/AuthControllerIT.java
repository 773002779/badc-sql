package com.badc.sql.control.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.control.security.JwtService;
import com.badc.sql.control.support.AbstractMySqlIT;
import com.badc.sql.control.support.JwtTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 登录与身份端到端测试（真实 HTTP 请求，穿过完整的 Spring Security 过滤器链）。
 *
 * <p>数据库是本机 MySQL 的随机 schema（{@link AbstractMySqlIT}），用户来自共享夹具
 * {@code seed/test-fixtures.sql}（dev01 / ops01，密码 {@code Passw0rd!}，BCrypt 哈希）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIT extends AbstractMySqlIT {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    /** 真实的受保护业务端点，用来验证过滤器链与鉴权行为（替代 T2 的临时探针）。 */
    private static final String PROTECTED_PATH = "/api/v1/datasources";

    // 默认的 HttpURLConnection 在流式发送请求体后收到 401 会抛 HttpRetryException
    //（它想带着认证信息重试）；JDK HttpClient 没有这个行为，401 响应可以正常读回。
    private static final ClientHttpRequestFactory REQUEST_FACTORY = new JdkClientHttpRequestFactory();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ApplicationContext applicationContext;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = ensureSchema();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", AbstractMySqlIT::dbUser);
        registry.add("spring.datasource.password", AbstractMySqlIT::dbPass);
    }

    @BeforeAll
    static void loadFixtures() {
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("seed/test-fixtures.sql")),
                jdbc.getDataSource());
    }

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(REQUEST_FACTORY);
    }

    @Test
    void login_with_valid_password_returns_token_for_dev01() throws Exception {
        ResponseEntity<String> response = login("dev01", "Passw0rd!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("token").asText()).isNotBlank();
        assertThat(body.path("userId").asLong()).isEqualTo(1L);
        assertThat(body.path("loginName").asText()).isEqualTo("dev01");
        // 返回的 token 就是该用户的身份凭证。
        assertThat(jwtService.parse(body.path("token").asText())).isEqualTo(1L);
    }

    @Test
    void login_with_valid_password_returns_token_for_ops01() throws Exception {
        ResponseEntity<String> response = login("ops01", "Passw0rd!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("userId").asLong()).isEqualTo(2L);
        assertThat(body.path("loginName").asText()).isEqualTo("ops01");
    }

    @Test
    void login_response_never_exposes_password_or_hash() throws Exception {
        ResponseEntity<String> response = login("dev01", "Passw0rd!");

        assertThat(response.getBody())
                .doesNotContainIgnoringCase("password")
                .doesNotContain("$2a$")
                .doesNotContain("$2b$")
                .doesNotContain("Passw0rd!");
    }

    @Test
    void login_with_wrong_password_returns_401() throws Exception {
        ResponseEntity<String> response = login("dev01", "wrong");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void login_with_unknown_user_returns_the_same_401_as_a_wrong_password() throws Exception {
        ResponseEntity<String> unknownUser = login("no-such-user", "Passw0rd!");
        ResponseEntity<String> wrongPassword = login("dev01", "wrong");

        // 用户名枚举防护：两种情况的状态码与响应体必须完全一致。
        assertThat(unknownUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(unknownUser)).isEqualTo("UNAUTHORIZED");
        assertThat(unknownUser.getBody()).isEqualTo(wrongPassword.getBody());
    }

    @Test
    void api_requests_without_a_token_are_rejected_with_401_not_403() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(PROTECTED_PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void api_requests_with_a_malformed_token_are_rejected_with_401() throws Exception {
        ResponseEntity<String> response = getWithToken(PROTECTED_PATH, "not-a-real-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(response)).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void api_requests_with_a_token_signed_by_another_secret_are_rejected_with_401() throws Exception {
        String forged = new JwtService("forged-secret-forged-secret-forged-secret-0123")
                .issue(1L, "dev01");

        assertThat(getWithToken(PROTECTED_PATH, forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void api_requests_with_a_valid_token_reach_the_handler() throws Exception {
        ResponseEntity<String> response = getWithToken(PROTECTED_PATH, JwtTestSupport.devToken(1L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody())).isNotEmpty();
    }

    @Test
    void no_default_in_memory_user_is_configured() {
        // 身份只来自 JWT：Spring Boot 的默认内存用户（随机密码）必须被排除，否则等于凭空多一个账号。
        assertThat(applicationContext.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void login_with_a_malformed_json_body_returns_400_with_the_common_error_shape() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                LOGIN_PATH, new HttpEntity<>("{\"loginName\":", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo("INVALID_ARGUMENT");
    }

    private ResponseEntity<String> login(String loginName, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"loginName\":\"%s\",\"password\":\"%s\"}".formatted(loginName, password);
        return rest.postForEntity(LOGIN_PATH, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String errorCode(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("code").asText();
    }
}
