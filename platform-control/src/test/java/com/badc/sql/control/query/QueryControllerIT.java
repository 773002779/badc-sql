package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.common.dto.ColumnMeta;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.control.crypto.CredentialCipher;
import com.badc.sql.control.support.AbstractMySqlIT;
import com.badc.sql.control.support.JwtTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 控制面查询编排的端到端测试。
 *
 * <p>执行器用桩替换：本类要验证的是控制面的职责——鉴权、可见性（403/404）、只读白名单、LIMIT 注入。
 * 真实 SQL 执行与类型映射由执行器的 {@code QueryRunnerIT} 覆盖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(QueryControllerIT.StubExecutorConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryControllerIT extends AbstractMySqlIT {

    private static final String QUERY = "/api/v1/query";
    private static final String DEV_TOKEN = JwtTestSupport.devToken(1L);
    private static final String OPS_TOKEN = JwtTestSupport.token(2L, "ops01");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CredentialCipher credentialCipher;

    @Autowired
    private StubExecutor stub;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = ensureSchema();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", AbstractMySqlIT::dbUser);
        registry.add("spring.datasource.password", AbstractMySqlIT::dbPass);
    }

    @BeforeAll
    void loadFixtures() {
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("seed/test-fixtures.sql")),
                jdbc.getDataSource());
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (1, 101, 'readonly', 'readonly_user', ?)
                """, credentialCipher.encrypt("readonly-pass"));
    }

    @BeforeEach
    void reset() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        stub.reset();
    }

    @Test
    void anonymous_is_unauthorized() {
        assertThat(post(null, "{\"datasourceId\":101,\"sql\":\"select 1\"}").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void querying_an_existing_datasource_without_permission_is_forbidden() throws Exception {
        ResponseEntity<String> response = post(DEV_TOKEN, "{\"datasourceId\":999,\"sql\":\"select 1\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(response)).isEqualTo("FORBIDDEN");
    }

    @Test
    void querying_a_missing_datasource_is_not_found_so_it_differs_from_forbidden() throws Exception {
        ResponseEntity<String> response = post(DEV_TOKEN, "{\"datasourceId\":12345,\"sql\":\"select 1\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(response)).isEqualTo("NOT_FOUND");
    }

    @Test
    void write_statements_are_rejected_with_a_pointer_to_the_ticket_flow() throws Exception {
        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"update t_order set status = 0"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("变更工单");
        assertThat(stub.lastSql).as("执行器不应被调用").isNull();
    }

    @Test
    void developer_select_gets_the_row_cap_injected_before_reaching_the_executor() throws Exception {
        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"select * from t_order"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.lastSql).isEqualTo("select * from t_order limit 1001");
        assertThat(stub.lastMaxRows).isEqualTo(1001);
    }

    @Test
    void the_connection_config_never_reaches_the_response_body() throws Exception {
        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"select 1"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("readonly-pass").doesNotContain("readonly_user");
    }

    @Test
    void a_successful_query_is_recorded_in_the_callers_history() throws Exception {
        post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"select * from t_order"}
                """);

        JsonNode history = getHistory(DEV_TOKEN);

        assertThat(history.path("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode first = history.path("items").get(0);
        assertThat(first.path("datasource_id").asLong()).isEqualTo(101L);
        assertThat(first.path("sql_text").asText()).contains("limit 1001");
        assertThat(first.path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void history_is_scoped_so_an_operator_cannot_see_a_developers_queries() throws Exception {
        post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"select 1"}
                """);

        JsonNode opsHistory = getHistory(OPS_TOKEN);

        assertThat(opsHistory.path("total").asLong()).isZero();
    }

    @Test
    void history_requires_authentication() {
        assertThat(rest.getForEntity("/api/v1/history", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode getHistory(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/history", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> post(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(QUERY, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String code(ResponseEntity<String> response) throws Exception {
        JsonNode node = objectMapper.readTree(response.getBody());
        return node.path("code").asText();
    }

    /** 桩执行器：记录入参并返回固定结果。 */
    static class StubExecutor implements ExecutorClient {

        String lastSql;
        int lastMaxRows;
        ConnConfig lastConn;

        void reset() {
            lastSql = null;
            lastMaxRows = 0;
            lastConn = null;
        }

        @Override
        public RawResult query(ConnConfig conn, String sql, int maxRows) {
            this.lastConn = conn;
            this.lastSql = sql;
            this.lastMaxRows = maxRows;
            return new RawResult(
                    List.of(new ColumnMeta("id", "INT")),
                    List.of(List.of("1")),
                    false,
                    3L);
        }

    }

    @TestConfiguration
    static class StubExecutorConfig {
        @Bean
        StubExecutor stubExecutor() {
            return new StubExecutor();
        }

        @Bean
        ExecutorClient executorClient(StubExecutor stub) {
            return stub;
        }
    }
}
