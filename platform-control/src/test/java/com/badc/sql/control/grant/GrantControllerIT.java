package com.badc.sql.control.grant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.control.support.AbstractMySqlIT;
import com.badc.sql.control.support.JwtTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code GET /api/v1/grants/me}：真实 HTTP + 真实过滤器链 + 真实 MySQL。
 *
 * <p>覆盖三态：匿名 401、研发只看到被授权的库、运维只看到自己系统的库。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrantControllerIT extends AbstractMySqlIT {

    private static final String ME = "/api/v1/grants/me";
    private static final String DEV_TOKEN = JwtTestSupport.devToken(1L);
    private static final String OPS_TOKEN = JwtTestSupport.token(2L, "ops01");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

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

    // 默认 HttpURLConnection 在收到 401 时可能抛 HttpRetryException；JDK HttpClient 能正常读回响应体。
    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void anonymous_is_unauthorized() {
        assertThat(rest.getForEntity(ME, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void developer_sees_exactly_the_granted_datasources() throws Exception {
        JsonNode body = get(DEV_TOKEN);

        assertThat(body.path("modules").size()).isEqualTo(1);
        assertThat(body.path("modules").get(0).asLong()).isEqualTo(201L);
        assertThat(body.path("datasources").size()).isEqualTo(2);
        assertThat(body.path("datasources").get(0).asLong()).isEqualTo(101L);
        assertThat(body.path("datasources").get(1).asLong()).isEqualTo(102L);
    }

    @Test
    void operator_sees_only_the_datasources_of_their_own_system() throws Exception {
        JsonNode body = get(OPS_TOKEN);

        assertThat(body.path("datasources").size()).isEqualTo(1);
        assertThat(body.path("datasources").get(0).asLong()).isEqualTo(999L);
    }

    private JsonNode get(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response =
                rest.exchange(ME, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }
}
