package com.badc.sql.control.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.support.AbstractMySqlIT;
import com.badc.sql.control.support.JwtTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 系统 / 模块 / 数据源管理的端到端测试：真实 HTTP（随机端口）、真实 Spring Security 过滤器链、
 * 真实 MySQL（每个测试类独立 {@code badc_test_*} schema）与真实 AES-256-GCM 加密。
 *
 * <p>夹具来自 {@code seed/test-fixtures.sql}；账号数据由本测试通过真实接口写入，验证
 * 「入库存密文、出参永不含连接信息」的完整路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSourceApiIT extends AbstractMySqlIT {

    private static final String DATA_SOURCES = "/api/v1/datasources";
    private static final String DEV_TOKEN = JwtTestSupport.devToken(1L);
    private static final String OPS_TOKEN = JwtTestSupport.token(2L, "ops01");

    // 默认 HttpURLConnection 在收到 401/403 时可能抛 HttpRetryException；JDK HttpClient 可以正常读回响应体。
    private static final ClientHttpRequestFactory REQUEST_FACTORY = new JdkClientHttpRequestFactory();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConnAccountService connAccountService;

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
    void datasource_list_returns_only_the_safe_view_fields() throws Exception {
        ResponseEntity<String> response = get(DATA_SOURCES, devToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        JsonNode first = body.get(0);
        assertThat(first.path("id").asLong()).isEqualTo(101L);
        assertThat(first.path("dbName").asText()).isEqualTo("order_db");
        assertThat(first.path("systemId").asLong()).isEqualTo(1L);
        assertThat(first.path("type").asText()).isEqualTo("MYSQL");
        // 视图只有 id/name/dbName/systemId/type。
        assertThat(first.size()).isEqualTo(5);
        assertThat(response.getBody())
                .doesNotContain("127.0.0.1")
                .doesNotContain("3306")
                .doesNotContain("\"host\"")
                .doesNotContain("\"port\"")
                .doesNotContain("\"username\"")
                .doesNotContain("\"password\"")
                .doesNotContain("\"charset\"")
                .doesNotContain("time_zone")
                .doesNotContain("\"timeZone\"");
    }

    @Test
    void anonymous_401_developer_403_primary_ops_200() throws Exception {
        String name = uniqueName("IT Three State");
        String request = json(Map.of("name", name));

        ResponseEntity<String> anonymous = post("/api/v1/systems", null, request);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errorCode(anonymous)).isEqualTo("UNAUTHORIZED");

        ResponseEntity<String> developer = post("/api/v1/systems", devToken(), request);
        assertThat(developer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(developer)).isEqualTo("FORBIDDEN");
        assertThat(systemCount(name)).isZero();

        ResponseEntity<String> ops = post("/api/v1/systems", opsToken(), request);
        assertThat(ops.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(ops.getBody()).path("id").asLong()).isPositive();
        assertThat(systemCount(name)).isEqualTo(1);
    }

    @Test
    void readonly_account_is_encrypted_at_rest_and_decrypted_only_inside_the_service() throws Exception {
        String username = "it_readonly";
        String password = "S3cr3t-1";

        // 只读账号尚未配置时，内部配置读取必须明确失败。
        assertThatThrownBy(() -> connAccountService.readonlyConnConfig(102L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);

        ResponseEntity<String> saved = put("/api/v1/datasources/101/accounts/readonly", opsToken(),
                json(Map.of("username", username, "password", password)));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(saved.getBody()).isNullOrEmpty();

        String cipher = jdbc.queryForObject(
                "select password_cipher from conn_account where datasource_id = 101 and account_type = 'readonly'",
                String.class);
        assertThat(cipher).isNotEqualTo(password).doesNotContain(password);
        assertThat(jdbc.queryForObject(
                "select count(*) from conn_account where datasource_id = 101", Integer.class)).isEqualTo(1);

        ConnConfig config = connAccountService.readonlyConnConfig(101L);
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(3306);
        assertThat(config.dbName()).isEqualTo("order_db");
        assertThat(config.username()).isEqualTo(username);
        assertThat(config.password()).isEqualTo(password);
        assertThat(config.charset()).isEqualTo("utf8mb4");
        assertThat(config.timeZone()).isEqualTo("+00:00");
        assertThat(config.toString()).doesNotContain(password);

        // HTTP 出参永远不含账号或密码。
        ResponseEntity<String> list = get(DATA_SOURCES, devToken());
        assertThat(list.getBody()).doesNotContain(username).doesNotContain(password);

        // 轮换：同一 (datasource, account_type) 只保留一行，密文与明文密码都更新。
        String rotated = "R0tated-2";
        assertThat(put("/api/v1/datasources/101/accounts/readonly", opsToken(),
                json(Map.of("username", username, "password", rotated))).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
                "select count(*) from conn_account where datasource_id = 101", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select password_cipher from conn_account where datasource_id = 101 and account_type = 'readonly'",
                String.class)).isNotEqualTo(cipher);
        assertThat(connAccountService.readonlyConnConfig(101L).password()).isEqualTo(rotated);
    }

    @Test
    void full_lifecycle_keeps_connection_details_out_of_every_response() throws Exception {
        long systemId = createSystem(uniqueName("IT Lifecycle"));
        long moduleId = createModule(systemId, "IT Module");

        ResponseEntity<String> created = post(DATA_SOURCES, opsToken(), json(Map.of(
                "systemId", systemId,
                "name", "IT Datasource",
                "dbName", "it_db",
                "type", "mysql",
                "host", "10.1.2.3",
                "port", 3307,
                "charset", "utf8mb4",
                "timeZone", "+00:00")));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode view = objectMapper.readTree(created.getBody());
        long datasourceId = view.path("id").asLong();
        assertThat(datasourceId).isPositive();
        assertThat(view.path("type").asText()).isEqualTo("MYSQL");
        assertThat(view.size()).isEqualTo(5);
        assertThat(created.getBody()).doesNotContain("10.1.2.3").doesNotContain("3307");

        ResponseEntity<String> connection = put("/api/v1/datasources/" + datasourceId + "/connection", opsToken(),
                json(Map.of("host", "10.9.8.7", "port", 3308, "charset", "utf8mb4", "timeZone", "+00:00")));
        assertThat(connection.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
                "select host from datasource where id = ?", String.class, datasourceId)).isEqualTo("10.9.8.7");

        assertThat(put("/api/v1/datasources/" + datasourceId + "/accounts/readonly", opsToken(),
                json(Map.of("username", "it_user", "password", "it_secret"))).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(post("/api/v1/modules/" + moduleId + "/datasources/" + datasourceId, opsToken(), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
                "select count(*) from module_datasource where module_id = ? and datasource_id = ?",
                Integer.class, moduleId, datasourceId)).isEqualTo(1);
        assertThat(delete("/api/v1/modules/" + moduleId + "/datasources/" + datasourceId, opsToken())
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> list = get(DATA_SOURCES, devToken());
        JsonNode listed = findById(objectMapper.readTree(list.getBody()), datasourceId);
        assertThat(listed.size()).isEqualTo(5);
        assertThat(list.getBody())
                .doesNotContain("10.9.8.7")
                .doesNotContain("10.1.2.3")
                .doesNotContain("3308")
                .doesNotContain("it_user")
                .doesNotContain("it_secret");

        ConnConfig config = connAccountService.readonlyConnConfig(datasourceId);
        assertThat(config.host()).isEqualTo("10.9.8.7");
        assertThat(config.port()).isEqualTo(3308);
        assertThat(config.dbName()).isEqualTo("it_db");
        assertThat(config.password()).isEqualTo("it_secret");

        // 删除数据源要级联账号与模块关联，不能留下孤儿行。
        assertThat(delete("/api/v1/datasources/" + datasourceId, opsToken()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
                "select count(*) from datasource where id = ?", Integer.class, datasourceId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from conn_account where datasource_id = ?", Integer.class, datasourceId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from module_datasource where datasource_id = ?", Integer.class, datasourceId)).isZero();

        assertThat(delete("/api/v1/modules/" + moduleId, opsToken()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete("/api/v1/systems/" + systemId, opsToken()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void module_can_only_mount_datasources_of_its_own_system() throws Exception {
        long systemId = createSystem(uniqueName("IT Mount"));
        long moduleId = createModule(systemId, "IT Mount Module");

        // 999 属于 system 2，不在新建 system 下：必须拒绝，而不是悄悄建立跨系统关联。
        ResponseEntity<String> crossSystem = post(
                "/api/v1/modules/" + moduleId + "/datasources/999", opsToken(), null);
        assertThat(crossSystem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(crossSystem)).isEqualTo("INVALID_ARGUMENT");
        assertThat(jdbc.queryForObject(
                "select count(*) from module_datasource where module_id = ?", Integer.class, moduleId)).isZero();

        long datasourceId = createDatasource(systemId, "IT Mount DS", "it_mount_db");
        assertThat(post("/api/v1/modules/" + moduleId + "/datasources/" + datasourceId, opsToken(), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // 幂等挂载。
        assertThat(post("/api/v1/modules/" + moduleId + "/datasources/" + datasourceId, opsToken(), null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
                "select count(*) from module_datasource where module_id = ? and datasource_id = ?",
                Integer.class, moduleId, datasourceId)).isEqualTo(1);
    }

    @Test
    void duplicate_system_name_returns_409_instead_of_500() throws Exception {
        String name = uniqueName("IT Duplicate");
        assertThat(post("/api/v1/systems", opsToken(), json(Map.of("name", name)))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> duplicate = post("/api/v1/systems", opsToken(), json(Map.of("name", name)));

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorCode(duplicate)).isEqualTo("CONFLICT");
    }

    @Test
    void developer_cannot_mutate_systems_modules_datasources_or_accounts() throws Exception {
        ResponseEntity<String> create = post("/api/v1/systems", devToken(), json(Map.of("name", uniqueName("IT Dev"))));
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> createModule = post("/api/v1/modules", devToken(),
                json(Map.of("systemId", 1, "name", "IT Dev Module")));
        assertThat(createModule.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> mount = post("/api/v1/modules/201/datasources/101", devToken(), null);
        assertThat(mount.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> connection = put("/api/v1/datasources/101/connection", devToken(),
                json(Map.of("host", "10.0.0.9", "port", 3307, "charset", "utf8mb4", "timeZone", "+00:00")));
        assertThat(connection.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> account = put("/api/v1/datasources/101/accounts/readonly", devToken(),
                json(Map.of("username", "dev", "password", "dev")));
        assertThat(account.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> delete = delete("/api/v1/datasources/101", devToken());
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> deleteSystem = delete("/api/v1/systems/1", devToken());
        assertThat(deleteSystem.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(jdbc.queryForObject("select host from datasource where id = 101", String.class))
                .isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForObject(
                "select count(*) from conn_account where datasource_id = 101 and username = 'dev'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from datasource where id = 101", Integer.class)).isEqualTo(1);
    }

    private long createSystem(String name) throws Exception {
        ResponseEntity<String> response = post("/api/v1/systems", opsToken(), json(Map.of("name", name)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("id").asLong();
    }

    private long createModule(long systemId, String name) throws Exception {
        ResponseEntity<String> response = post("/api/v1/modules", opsToken(),
                json(Map.of("systemId", systemId, "name", name)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("id").asLong();
    }

    private long createDatasource(long systemId, String name, String dbName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("systemId", systemId);
        body.put("name", name);
        body.put("dbName", dbName);
        body.put("type", "MYSQL");
        body.put("host", "10.2.3.4");
        body.put("port", 3306);
        body.put("charset", "utf8mb4");
        body.put("timeZone", "+00:00");
        ResponseEntity<String> response = post(DATA_SOURCES, opsToken(), json(body));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("id").asLong();
    }

    private int systemCount(String name) {
        return jdbc.queryForObject("select count(*) from sys_system where name = ?", Integer.class, name);
    }

    private static String uniqueName(String prefix) {
        return prefix + " " + System.nanoTime();
    }

    private static String devToken() {
        return DEV_TOKEN;
    }

    private static String opsToken() {
        return OPS_TOKEN;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode findById(JsonNode array, long id) {
        for (JsonNode node : array) {
            if (node.path("id").asLong() == id) {
                return node;
            }
        }
        throw new AssertionError("datasource " + id + " not found in " + array);
    }

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return exchange(path, HttpMethod.POST, token, body);
    }

    private ResponseEntity<String> put(String path, String token, String body) {
        return exchange(path, HttpMethod.PUT, token, body);
    }

    private ResponseEntity<String> delete(String path, String token) {
        return exchange(path, HttpMethod.DELETE, token, null);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String errorCode(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("code").asText();
    }
}
