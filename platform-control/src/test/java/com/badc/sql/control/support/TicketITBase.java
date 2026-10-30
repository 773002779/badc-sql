package com.badc.sql.control.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.badc.sql.common.sql.SqlText;
import com.badc.sql.control.crypto.CredentialCipher;
import com.badc.sql.control.risk.RiskEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工单类集成测试基类：真实 HTTP + 真实元数据库 + 桩执行器。
 *
 * <p>夹具分两个文件加载：共享的 {@code seed/test-fixtures.sql}（不能动，见任务 7 简报）与工单专用的
 * {@code seed/ticket-fixtures.sql}（ops02 / lead01）。只读账号在这里补，因为凭证要经
 * {@link CredentialCipher} 加密后才能落库，SQL 夹具里没有密钥。
 *
 * <p><b>窗口固定为全天</b>：默认业务窗口是 20:00–06:00，测试若在窗口外跑，一条估算 1 行的 update 会被
 * 升到 L2，等级断言就会随执行时刻变化。工单测试要验证的是提交流程，不是窗口判定（那由
 * {@code RiskEngineTest} 覆盖），所以这里用 {@link TestPropertySource} 把窗口铺满。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TicketStubExecutorConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "badcsql.risk.window-start=00:00",
        "badcsql.risk.window-end=23:59:59.999999999"
})
public abstract class TicketITBase extends AbstractMySqlIT {

    /** dev01：研发，模块 201 授权可见数据源 101/102。 */
    protected static final String DEV_TOKEN = JwtTestSupport.token(1L, "dev01");
    /** ops01：系统 2 的主运维，不是研发。 */
    protected static final String OPS_TOKEN = JwtTestSupport.token(2L, "ops01");
    /** admin01：管理员，研发工单同样不能由他提交。 */
    protected static final String ADMIN_TOKEN = JwtTestSupport.token(3L, "admin01");
    /** ops02：系统 1 的主运维（ticket-fixtures.sql）。 */
    protected static final String OPS02_TOKEN = JwtTestSupport.token(4L, "ops02");
    /** lead01：系统 1 的运维组长（ticket-fixtures.sql）。 */
    protected static final String LEAD01_TOKEN = JwtTestSupport.token(5L, "lead01");

    protected static final long ORDER_DATASOURCE_ID = 101L;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TicketStubExecutor stub;

    /** 与生产同一个引擎 bean（窗口由 {@link TestPropertySource} 铺满全天）。 */
    @Autowired
    protected RiskEngine riskEngine;

    @Autowired
    protected CredentialCipher credentialCipher;

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
                new ResourceDatabasePopulator(
                        new ClassPathResource("seed/test-fixtures.sql"),
                        new ClassPathResource("seed/ticket-fixtures.sql")),
                jdbc.getDataSource());
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (1, 101, 'readonly', 'readonly_user', ?)
                """, credentialCipher.encrypt("readonly-pass"));
        // 写语句的 EXPLAIN 必须用 dml 账号：只读账号会被 MySQL 以 1142 拒绝，估算变 -1、整单误判 L3。
        addDmlAccount();
    }

    /**
     * 数据源 101 的 dml 账号。测试里要造"没有 dml 账号"的用例会先删掉它，再在 {@code finally} 里调本方法恢复。
     */
    protected void addDmlAccount() {
        jdbc.update("delete from conn_account where datasource_id = ? and account_type = 'dml'",
                ORDER_DATASOURCE_ID);
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (2, 101, 'dml', 'dml_user', ?)
                """, credentialCipher.encrypt("dml-pass"));
    }

    /**
     * 断言提交失败后六张工单表全为空。
     *
     * <p>只查 {@code ticket} 不够：这些表之间没有外键，子表（语句/事件/快照/确认/执行锁）的孤儿行不会让
     * {@code ticket} 的计数变化。BLOCK 与执行器故障两条用例必须都没留下任何一行。
     */
    protected void assertNothingPersisted() {
        for (String table : List.of("ticket", "ticket_statement", "ticket_event",
                "schema_snapshot", "risk_acknowledgement", "datasource_exec_lock")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class))
                    .as("%s 不应有任何行", table)
                    .isZero();
        }
    }

    @BeforeEach
    void resetStubAndHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        stub.reset();
    }

    /** 提交一张工单，返回原始 HTTP 响应（用例自己断言状态码与正文）。 */
    protected ResponseEntity<String> postTicket(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange("/api/v1/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    /** 工单当前状态；不存在时返回 {@code null}。 */
    protected String status(long ticketId) {
        List<String> statuses = jdbc.queryForList(
                "select status from ticket where id = ?", String.class, ticketId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** 工单语句（按执行顺序）。 */
    protected List<StatementRow> statementsOf(long ticketId) {
        return jdbc.query("""
                        select seq_no, sql_text, statement_type, risk_level, status
                          from ticket_statement
                         where ticket_id = ?
                         order by seq_no
                        """,
                (rs, rowNum) -> new StatementRow(
                        rs.getInt("seq_no"),
                        rs.getString("sql_text"),
                        rs.getString("statement_type"),
                        rs.getString("risk_level"),
                        rs.getString("status")),
                ticketId);
    }

    /** 响应体里的工单 ID。 */
    protected long idOf(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody()).path("id").asLong();
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON: " + response.getBody(), e);
        }
    }

    /**
     * 请求体：{@code body(sql, rollbackSql)}。
     *
     * <p>SQL 按顶层分号切分成 {@code statements} 数组：多语句用例传
     * {@code "update ...; truncate ..."} 时得到的是一张两条语句的工单，而不是一条被 BLOCK 的多语句。
     */
    protected String body(String sql, String rollbackSql) {
        return bodyFor(ORDER_DATASOURCE_ID, sql, null, rollbackSql);
    }

    /** 请求体：{@code body(sql, rollbackPlan, rollbackSql)}。 */
    protected String body(String sql, String rollbackPlan, String rollbackSql) {
        return bodyFor(ORDER_DATASOURCE_ID, sql, rollbackPlan, rollbackSql);
    }

    /** 请求体：{@code bodyFor(datasourceId, sql, rollbackSql)}。 */
    protected String bodyFor(long datasourceId, String sql, String rollbackSql) {
        return bodyFor(datasourceId, sql, null, rollbackSql);
    }

    protected String bodyFor(long datasourceId, String sql, String rollbackPlan, String rollbackSql) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasourceId", datasourceId);
        body.put("title", "订单数据变更");
        body.put("changeReason", "修复线上数据");
        body.put("rollbackPlan", rollbackPlan);
        body.put("rollbackSql", rollbackSql);
        body.put("statements", SqlText.splitStatements(sql));
        body.put("expectedExecuteAt", Instant.now().plus(1, ChronoUnit.HOURS).toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    /** 一条工单语句的测试视图。 */
    public record StatementRow(int seqNo, String sqlText, String statementType, String riskLevel, String status) {
    }

    /** 断言响应是 400 且正文含指定片段。 */
    protected void assertBadRequest(ResponseEntity<String> response, String fragment) {
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(fragment);
    }
}
