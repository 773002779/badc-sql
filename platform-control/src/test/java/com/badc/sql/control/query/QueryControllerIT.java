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

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ColumnMeta;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
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
        // 102 / 103 也配上只读账号：能力缓存按数据源 ID 分别缓存，用例各自用不同的库，
        // 才能保证"探测失败"与"版本无法识别"两条路径拿到的是冷缓存（互不污染）。
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (2, 102, 'readonly', 'readonly_user', ?)
                """, credentialCipher.encrypt("readonly-pass"));
        jdbc.update("""
                insert into datasource (id, system_id, name, db_name, db_type, host, port, charset, time_zone)
                values (103, 1, '订单库-MariaDB', 'order_db', 'MYSQL', '127.0.0.1', 3306, 'utf8mb4', '+00:00')
                """);
        jdbc.update("insert into module_datasource (id, module_id, datasource_id) values (3, 201, 103)");
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (3, 103, 'readonly', 'readonly_user', ?)
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

    /**
     * C3 的核心用例：5.7 的库上提交 CTE，必须在<b>控制面</b>被拦成 422，而不是把 SQL 发给库去换一个 1064。
     *
     * <p>{@code stub.lastSql == null} 是这条用例的重点：拒绝发生在执行之前，数据库不该被打扰，也就不会
     * 产生一次失败查询的审计噪音。
     */
    @Test
    void a_cte_on_a_mysql_57_datasource_is_rejected_with_422_and_never_reaches_the_executor() throws Exception {
        stub.validateResult = ValidationResult.reject(
                "DB_VERSION_UNSUPPORTED", "该库为 MySQL 5.7，不支持 WITH 子句（CTE）；请改写 SQL 或改用 8.0 实例");

        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"with t as (select 1 as x) select * from t"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("DB_VERSION_UNSUPPORTED");
        // 校验器的消息必须原样透出：版本 + 特性，用户才知道该怎么改。
        assertThat(response.getBody()).contains("5.7").contains("WITH");
        assertThat(stub.lastValidatedVersion).isEqualTo(DbVersion.MYSQL_5_7);
        assertThat(stub.lastSql).as("版本不支持时不应调用执行器查询").isNull();
    }

    /** 对照面：校验通过时必须照常执行，别把正常路径一起堵死。 */
    @Test
    void a_query_that_passes_version_validation_still_reaches_the_executor() throws Exception {
        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"select * from t_order"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.validateCalls).as("每次查询都要按当前版本校验一次").isEqualTo(1);
        // 校验针对的是"真正要执行的 SQL"——含注入后的 LIMIT，而不是用户原始文本。
        assertThat(stub.lastValidatedSql).isEqualTo("select * from t_order limit 1001");
        assertThat(stub.lastSql).isEqualTo("select * from t_order limit 1001");
    }

    /**
     * 探测失败（执行器不可用 / 库不可达 / 权限不足）只代表"能力未知"，不代表"不能查"。
     *
     * <p>用 102 是为了拿到冷缓存：能力按数据源缓存 10 分钟，探测失败的库不该污染其他用例。
     */
    @Test
    void a_failed_probe_leaves_the_version_unknown_and_the_query_still_runs() throws Exception {
        stub.probeThrows = true;

        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":102,"sql":"select 1"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.probeCalls).as("控制面必须先尝试探测一次").isEqualTo(1);
        assertThat(stub.validateCalls).as("能力未知时跳过版本校验").isZero();
        assertThat(stub.lastSql).as("查询仍然要打到执行器").isEqualTo("select 1 limit 1001");
    }

    /**
     * 版本无法识别（如 MariaDB、非标准版本串）时同样跳过校验：宁可让库自己报错，也不要凭猜测拒绝。
     */
    @Test
    void an_unrecognised_version_skips_validation_instead_of_blocking_the_query() throws Exception {
        stub.probedCapability = Capability.fromVersionString("10.11.6-MariaDB", false);

        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":103,"sql":"with t as (select 1 as x) select * from t"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.probeCalls).isEqualTo(1);
        assertThat(stub.validateCalls).as("版本未知时不做版本判定").isZero();
        assertThat(stub.lastSql).isNotNull();
    }

    /**
     * 探测成功但校验调用失败（执行器刚好重启）也不阻断：只有"确定不支持"才拒。
     * 否则平台会把一次执行器抖动变成对用户的 5xx，而这条 SQL 本来完全能跑。
     */
    @Test
    void a_validate_call_that_fails_does_not_block_the_query() throws Exception {
        stub.validateThrows = true;

        ResponseEntity<String> response = post(DEV_TOKEN, """
                {"datasourceId":101,"sql":"select * from t_order"}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.validateCalls).isEqualTo(1);
        assertThat(stub.lastSql).isEqualTo("select * from t_order limit 1001");
    }

    /**
     * FR-01 验收1 的数据来源：前端要按能力决定"有没有 EXPLAIN ANALYZE 入口"，所以详情接口必须给出
     * 版本与能力开关。
     *
     * <p>同时钉住"能力里不含连接信息"：{@code Capability} 只有版本与开关，详情接口不能顺手把
     * host / 账号 / 密码带出来。
     */
    @Test
    void datasource_detail_exposes_capabilities_without_any_connection_details() throws Exception {
        ResponseEntity<String> response = get("/api/v1/datasources/101", DEV_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(101L);
        assertThat(body.path("dbName").asText()).isEqualTo("order_db");
        assertThat(body.path("systemId").asLong()).isEqualTo(1L);
        assertThat(body.path("type").asText()).isEqualTo("MYSQL");

        JsonNode capabilities = body.path("capabilities");
        assertThat(capabilities.path("versionString").asText()).isEqualTo("5.7.44");
        assertThat(capabilities.path("version").asText()).isEqualTo("MYSQL_5_7");
        assertThat(capabilities.path("supportsExplainAnalyze").asBoolean())
                .as("5.7 没有 EXPLAIN ANALYZE，前端不能给这个按钮")
                .isFalse();

        assertThat(response.getBody())
                .doesNotContain("127.0.0.1")
                .doesNotContain("3306")
                .doesNotContain("readonly_user")
                .doesNotContain("readonly-pass")
                .doesNotContain("\"host\"")
                .doesNotContain("\"password\"");
    }

    /** 详情接口同样不能被探测失败拖垮：能力未知就返回 null，别的字段照常给。 */
    @Test
    void datasource_detail_is_still_returned_when_the_capability_probe_fails() throws Exception {
        stub.probeThrows = true;

        ResponseEntity<String> response = get("/api/v1/datasources/102", DEV_TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(102L);
        assertThat(body.path("capabilities").isNull()).as("能力未知时为 null").isTrue();
    }

    /** 详情接口不能成为可见性的旁路：不可见的数据源连"存在但无权"的语义都要与查询链路一致。 */
    @Test
    void datasource_detail_is_guarded_by_visibility() throws Exception {
        ResponseEntity<String> forbidden = get("/api/v1/datasources/999", DEV_TOKEN);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(forbidden)).isEqualTo("FORBIDDEN");

        ResponseEntity<String> missing = get("/api/v1/datasources/12345", DEV_TOKEN);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(missing)).isEqualTo("NOT_FOUND");

        assertThat(get("/api/v1/datasources/101", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private JsonNode getHistory(String token) throws Exception {
        ResponseEntity<String> response = get("/api/v1/history", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

    /**
     * 桩执行器：记录入参并返回固定结果。
     *
     * <p>{@code probe} / {@code validate} 的结果由用例逐个设定：本地只有 MySQL 8.0 实例，
     * "目标是 5.7" 这类语义只能靠桩覆盖（见 {@link MySqlDialectTest} 对真实方言规则的覆盖）。
     */
    static class StubExecutor implements ExecutorClient {

        String lastSql;
        int lastMaxRows;
        ConnConfig lastConn;

        /** 探测次数：用来证明控制面确实走了一次探测，并且失败时不再重复（或按需重试）。 */
        int probeCalls;
        int validateCalls;

        /** 让 probe 抛异常，模拟执行器不可用 / 库不可达 / 权限不足。 */
        boolean probeThrows;

        /** 探测出来的能力；默认 5.7，便于验证"版本不支持"的拦截路径。 */
        Capability probedCapability = Capability.fromVersionString("5.7.44", false);

        /** validate 的判定；默认放行。 */
        ValidationResult validateResult = ValidationResult.success();

        /** 让 validate 抛异常，模拟探测之后执行器抖动/重启。 */
        boolean validateThrows;

        String lastValidatedSql;
        DbVersion lastValidatedVersion;

        void reset() {
            lastSql = null;
            lastMaxRows = 0;
            lastConn = null;
            probeCalls = 0;
            validateCalls = 0;
            probeThrows = false;
            probedCapability = Capability.fromVersionString("5.7.44", false);
            validateResult = ValidationResult.success();
            validateThrows = false;
            lastValidatedSql = null;
            lastValidatedVersion = null;
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

        @Override
        public Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull) {
            probeCalls++;
            if (probeThrows) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器不可用，请稍后重试");
            }
            return probedCapability;
        }

        @Override
        public ValidationResult validate(ConnConfig conn, String sql, DbVersion version) {
            validateCalls++;
            this.lastValidatedSql = sql;
            this.lastValidatedVersion = version;
            if (validateThrows) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器不可用，请稍后重试");
            }
            return validateResult;
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
