package com.badc.sql.control.temp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 临时执行（不建工单直执行）的端到端用例：真实 HTTP + 真实元数据库 + 桩执行器。
 *
 * <p>每一条用例都断言"语句到底发出去了没有"（{@code stub.executedSql} / {@code stub.executeCalls}）与
 * "有没有留痕"（审计 {@code TEMP_EXECUTED} 的行数）。只断言状态码的用例证明不了"408 背后的语句被拦住了"——
 * 一个先执行后报错的实现在那种断言下依然全绿。
 *
 * <p><b>为什么断言 {@code stub.explainedUsernames}</b>：写语句的 EXPLAIN 必须走 dml 账号。只读账号
 * EXPLAIN {@code update} 会被 MySQL 以 1142 拒绝，执行器吞成 {@code -1}，风险引擎把每条 DML 判成 L3——
 * 那时本用例会变成"L3 被 409 拒绝"，而不是"L1 直执行成功"。这条断言把最容易做错的一点钉住。
 */
class TempExecutionIT extends TicketITBase {

    /** DDL 账号的主键：基类用了 1（readonly）与 2（dml），这里用 3。 */
    private static final long DDL_ACCOUNT_ID = 3L;

    private static final String L1_UPDATE = "update t_order set a = 1 where id = 1";

    /**
     * 审计 payload 允许出现的键：**只放元数据**（§5.4）。
     *
     * <p>{@code snapshotRef} 与 {@code tableFingerprints} 只在含 DDL 时出现，所以断言用
     * {@code isSubsetOf} 而不是集合相等——固定成"恰好 6 个"会让 DML 用例与 DDL 用例各需要一份白名单。
     */
    private static final Set<String> AUDIT_PAYLOAD_KEYS = Set.of(
            "riskLevel", "statementCount", "succeeded", "failed", "snapshotRef", "tableFingerprints",
            "selfApproved");

    /**
     * 数据源 101 的 ddl 账号。
     *
     * <p>{@link TicketITBase} 只给了 readonly 与 dml，**没有 ddl**；含 DDL 的用例会在解析账号时抛
     * {@code BizException}（{@code resolve} 按语句类型惰性取账号）。补法与 {@code addDmlAccount()} 同形。
     */
    @BeforeAll
    void addDdlAccount() {
        jdbc.update("delete from conn_account where datasource_id = ? and account_type = 'ddl'",
                ORDER_DATASOURCE_ID);
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (?, 101, 'ddl', 'ddl_user', ?)
                """, DDL_ACCOUNT_ID, credentialCipher.encrypt("ddl-pass"));
    }

    /**
     * 每个用例从"没有锁、没有审计、没有工单"开始。
     *
     * <p>审计必须清空：用例要断言"恰好一条 {@code TEMP_EXECUTED}"，留着上一条用例的行会让它恒真。
     * 工单表也要清空：组长自提 L3 的用例断言"不建单"，共享工单会让那条断言失效。
     */
    @BeforeEach
    void clearState() {
        jdbc.update("delete from datasource_exec_lock");
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket_review");
        jdbc.update("delete from ticket");
        jdbc.update("delete from audit_event");
    }

    // ------------------------------------------------------------------ 角色闸（接口级，不是前端藏按钮）

    @Test
    void a_developer_cannot_execute_anything_directly() {
        ResponseEntity<String> response = postExecution(DEV_TOKEN, body(ORDER_DATASOURCE_ID, true, L1_UPDATE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("研发");
        assertThat(stub.executedSql).as("研发一条语句都不许发出去").isEmpty();
        assertThat(tempExecutedAuditCount()).as("被拒绝的请求不得留下执行审计").isZero();
        assertThat(lockRows(ORDER_DATASOURCE_ID)).as("角色闸必须先于取锁").isZero();
        assertThat(stub.explainCalls).as("角色闸必须先于分级：被拒的请求不该为它跑一次 EXPLAIN").isZero();
    }

    @Test
    void an_admin_cannot_execute_production_statements() {
        ResponseEntity<String> response = postExecution(ADMIN_TOKEN, body(ORDER_DATASOURCE_ID, true, L1_UPDATE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("管理员");
        assertThat(stub.executedSql).isEmpty();
        assertThat(tempExecutedAuditCount()).isZero();
        assertThat(lockRows(ORDER_DATASOURCE_ID)).as("角色闸必须先于取锁").isZero();
        assertThat(stub.explainCalls).as("角色闸必须先于分级").isZero();
    }

    /**
     * 同角色、但不是这台库所属系统的运维：横向越权必须 403。
     *
     * <p>{@code OPS_TOKEN} 是 ops01（系统 2 的主运维），数据源 101 属于系统 1。
     */
    @Test
    void an_operator_of_another_system_cannot_execute_here() {
        ResponseEntity<String> response = postExecution(OPS_TOKEN, body(ORDER_DATASOURCE_ID, true, L1_UPDATE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("只能在自己负责的系统");
        assertThat(stub.executedSql).isEmpty();
        assertThat(tempExecutedAuditCount()).isZero();
        assertThat(lockRows(ORDER_DATASOURCE_ID)).as("归属闸必须先于取锁").isZero();
        assertThat(stub.explainCalls).as("归属闸必须先于分级").isZero();
    }

    // ------------------------------------------------------------------ 自确认

    /**
     * {@code confirmed=false} 必须 400 且零执行——"弹框自确认"不能退化成"服务端默认同意"。
     *
     * <p>分级（EXPLAIN）发生在自确认检查之前：前端要把"你正在执行一条 L1 变更"显示在弹框里，
     * 所以未确认的请求仍会拿到风险等级（{@code stub.explainCalls == 1} 就是这条顺序的证据）。
     */
    @Test
    void an_unconfirmed_execution_is_refused() {
        ResponseEntity<String> response = postExecution(OPS02_TOKEN, body(ORDER_DATASOURCE_ID, false, L1_UPDATE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("确认风险");
        assertThat(stub.explainCalls).as("未确认的请求仍要先算出等级给弹框看").isEqualTo(1);
        assertThat(stub.executedSql).isEmpty();
        assertThat(tempExecutedAuditCount()).isZero();
    }

    // ------------------------------------------------------------------ 直执行

    /**
     * L1 的一条 update：直接执行、按条回显影响行数、审计恰好一条且 {@code selfApproved=false}。
     *
     * <p>桩的 {@code execute} 默认返回 {@code affectedRows=0}（与真实 DDL 一致），所以这条用例必须自己铺
     * "改了一行"的结果，否则"影响行数"这条断言没有任何判别力。
     */
    @Test
    void a_confirmed_l1_update_executes_and_is_audited() {
        stub.executeResults = List.of(new ExecuteResult(true, 1L, 5L, null, null));

        ResponseEntity<String> response = postExecution(OPS02_TOKEN, body(ORDER_DATASOURCE_ID, true, L1_UPDATE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = tree(response);
        assertThat(json.path("executed").asBoolean()).isTrue();
        assertThat(json.path("riskLevel").asText()).isEqualTo("L1");
        assertThat(json.path("selfApproved").asBoolean()).isFalse();
        assertThat(json.path("results").size()).isEqualTo(1);
        assertThat(json.path("results").get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(json.path("results").get(0).path("success").asBoolean()).isTrue();
        assertThat(json.path("results").get(0).path("affectedRows").asLong()).isEqualTo(1L);

        assertThat(stub.explainedUsernames)
                .as("写语句的 EXPLAIN 必须走 dml 账号，只读账号会拿到 1142 → -1 → 整单误判 L3")
                .containsExactly("dml_user");
        assertThat(stub.executedSql).containsExactly(L1_UPDATE);
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DML);
        assertThat(stub.snapshotRefs).as("纯 DML 不抓结构快照：没有结构可变更，抓了就是白付一次执行器往返").isEmpty();

        assertThat(tempExecutedAuditCount()).as("直执行必须恰好留一条审计").isEqualTo(1);
        Map<String, Object> audit = tempExecutedAudit();
        assertThat(((Number) audit.get("operator_id")).longValue()).as("operator_id 是操作人").isEqualTo(4L);
        assertThat(audit.get("exec_identity")).as("exec_identity 是实际使用的库账号").isEqualTo("dml_user");
        assertThat(audit.get("action")).isEqualTo("execute");
        assertThat(audit.get("object_id")).isEqualTo(String.valueOf(ORDER_DATASOURCE_ID));
        assertThat(((Number) audit.get("datasource_id")).longValue()).isEqualTo(ORDER_DATASOURCE_ID);

        String payload = (String) audit.get("payload_json");
        assertThat(payload)
                .contains("\"riskLevel\":\"L1\"")
                .contains("\"statementCount\":1")
                .contains("\"succeeded\":1")
                .contains("\"failed\":0")
                .contains("\"selfApproved\":false")
                .as("审计里不放 SQL 原文，也不放密码").doesNotContain("update t_order")
                .doesNotContain("dml-pass");
    }

    /**
     * 组长自提的 L3：自己就是该系统 L3 的最终把关人，必须直接执行，且审计里能一眼看出"这次没有第二个人"。
     *
     * <p>{@code ticket} 表行数不变是关键断言：一旦组长自提 L3 转了工单，单组长的系统永远执行不了 L3
     * （那张单只有他自己能批，而"提交人不能审核自己的工单"又把他挡住）。
     */
    @Test
    void a_lead_running_an_l3_change_self_approves_instead_of_waiting_for_themselves() {
        ResponseEntity<String> response =
                postExecution(LEAD01_TOKEN, body(ORDER_DATASOURCE_ID, true, "truncate table t_order"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = tree(response);
        assertThat(json.path("executed").asBoolean()).isTrue();
        assertThat(json.path("riskLevel").asText()).isEqualTo("L3");
        assertThat(json.path("selfApproved").asBoolean()).isTrue();
        assertThat(stub.executedSql).containsExactly("truncate table t_order");
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DDL);
        assertThat(jdbc.queryForObject("select count(*) from ticket", Integer.class))
                .as("不能建出一张等自己批的单").isZero();

        String payload = (String) tempExecutedAudit().get("payload_json");
        assertThat(payload).contains("\"riskLevel\":\"L3\"").contains("\"selfApproved\":true");
    }

    /**
     * L3 且提交人不是组长：本任务先返回 409（Task 3 会换成 202 + 转工单），
     * 但"一条语句都不许发出去"这条现在就必须成立。
     */
    @Test
    void an_l3_change_by_a_non_lead_operator_needs_the_lead() {
        ResponseEntity<String> response =
                postExecution(OPS02_TOKEN, body(ORDER_DATASOURCE_ID, true, "truncate table t_order"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("TEMP_EXEC_NEEDS_APPROVAL").contains("组长");
        assertThat(stub.executeCalls).isZero();
        assertThat(tempExecutedAuditCount()).isZero();
    }

    // ------------------------------------------------------------------ DDL：先快照，再执行

    /**
     * DDL 直执行必须先抓结构快照，并把快照引用写进审计 payload。
     *
     * <p>临时执行不建工单，所以没有 {@code schema_snapshot} 行可写——审计 payload 是"执行前结构是什么样"
     * 的唯一凭据。引用形状 {@code {systemId}/{datasourceId}/temp/{uuid}} 与工单的
     * {@code {systemId}/{datasourceId}/{ticketId}/{changeSeq}} 刻意分开：临时执行没有工单 ID。
     */
    @Test
    void a_ddl_execution_captures_a_snapshot_and_records_its_ref() {
        ResponseEntity<String> response = postExecution(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, "create table t_new (id int)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.snapshotRefs).hasSize(1);
        assertThat(stub.snapshotRefs.get(0))
                .as("临时执行的快照引用形如 {systemId}/{datasourceId}/temp/{uuid}")
                .matches("1/101/temp/[0-9a-fA-F-]{36}");
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DDL);
        assertThat(stub.explainCalls).as("DDL 不做 EXPLAIN：影响面不由行数决定，估了也不参与分级").isZero();

        String payload = (String) tempExecutedAudit().get("payload_json");
        assertThat(payload)
                .contains("\"snapshotRef\":\"" + stub.snapshotRefs.get(0) + "\"")
                .as("没有 schema_snapshot 行，执行前的结构指纹只能留在审计里")
                .contains("tableFingerprints");
    }

    /** 快照抓取失败 → 500，且一条语句都不执行（全局约束）。 */
    @Test
    void a_failed_snapshot_prevents_every_statement() {
        stub.snapshotThrows = true;

        ResponseEntity<String> response = postExecution(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, "create table t_new (id int)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(stub.executeCalls).isZero();
        assertThat(tempExecutedAuditCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from datasource_exec_lock", Integer.class))
                .as("失败也必须把锁还回去").isZero();
    }

    // ------------------------------------------------------------------ 执行语义

    /** 失败即停：后面那条不再发出去，但失败本身是结果（200 + success=false），不是 500。 */
    @Test
    void a_failed_statement_stops_the_rest() {
        stub.failOnStatementIndex = Set.of(0);

        ResponseEntity<String> response = postExecution(OPS02_TOKEN, body(ORDER_DATASOURCE_ID, true,
                L1_UPDATE, "update t_order set a = 2 where id = 2"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = tree(response);
        assertThat(json.path("executed").asBoolean()).isTrue();
        assertThat(json.path("results").size()).as("只回显真正尝试过的语句").isEqualTo(1);
        assertThat(json.path("results").get(0).path("success").asBoolean()).isFalse();
        assertThat(json.path("results").get(0).path("errorMessage").asText()).isNotBlank();
        assertThat(stub.executeCalls).as("第一条失败后不再下发").isEqualTo(1);

        String payloadJson = (String) tempExecutedAudit().get("payload_json");
        assertThat(payloadJson).contains("\"succeeded\":0").contains("\"failed\":1");

        // 审计 payload 只放元数据：键集合必须落在白名单内，且那五个必有键一个不少。
        // 用"键集合"而不是"不含某个字符串"来锁这件事，是因为后者只挡得住把原文塞进 payload 根部的写法——
        // 而这条安全性质（§5.4：不放 SQL 原文、不放数据库错误文本）真正要挡的是"任何多余的东西"。
        // {@code snapshotRef}/{@code tableFingerprints} 只在含 DDL 时出现，所以这里用 isSubsetOf 而不是等号。
        Set<String> payloadKeys = new LinkedHashSet<>();
        parse(payloadJson).fieldNames().forEachRemaining(payloadKeys::add);
        assertThat(payloadKeys)
                .as("payload 只允许放这 6 个键（多一个键就是有东西被写进了审计）")
                .isSubsetOf(AUDIT_PAYLOAD_KEYS)
                .contains("riskLevel", "statementCount", "succeeded", "failed", "selfApproved");

        // 键集合挡不住"把错误文本塞进已有键"的写法，所以再补一条内容负向断言。
        // 用失败语句的完整 SQL 与桩的错误文本（而不是裸表名 t_order）：tableFingerprints 里的键就是表名，
        // 拿裸表名做负向断言会在含 DDL 的用例里误伤。
        assertThat(payloadJson)
                .as("数据库错误文本与 SQL 原文都不进审计（错误里可能带 SQL 片段与账号）")
                .doesNotContain("模拟的语句执行失败")
                .doesNotContain(L1_UPDATE);
    }

    /** 只读语句用只读账号：EXPLAIN 与执行都不该借 dml 账号。 */
    @Test
    void a_read_only_statement_uses_the_readonly_account() {
        ResponseEntity<String> response = postExecution(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, "select id from t_order where id = 1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.explainedUsernames).containsExactly("readonly_user");
        assertThat(stub.executedSql).containsExactly("select id from t_order where id = 1");
    }

    /**
     * 混合请求（先 DML 后 DDL）：两条语句用两个账号，快照只抓一次。
     *
     * <p>审计的 {@code exec_identity} 必须按使用顺序用逗号连起来——把两个账号合并成一个会让
     * "哪条语句用的是哪个账号"在审计里消失，而这正是事后追查 DDL 用了什么权限的唯一线索。
     */
    @Test
    void a_mixed_request_uses_both_accounts_and_records_both_identities() {
        ResponseEntity<String> response = postExecution(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, L1_UPDATE, "create table t_new (id int)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DML, ExecAccountType.DDL);
        assertThat(stub.executedSql).containsExactly(L1_UPDATE, "create table t_new (id int)");
        assertThat(stub.snapshotRefs).as("含 DDL 才抓快照，且整次只抓一次").hasSize(1);
        assertThat(tempExecutedAudit().get("exec_identity")).isEqualTo("dml_user,ddl_user");
    }

    // ------------------------------------------------------------------ 输入与并发

    /** 语句列表为空/全空白 → 400，且不碰执行器（分级、快照、执行一次都不发生）。 */
    @Test
    void a_request_without_statements_is_refused() {
        ResponseEntity<String> blankOnly =
                postExecution(OPS02_TOKEN, bodyWithStatements(ORDER_DATASOURCE_ID, true, List.of("", "   ")));

        assertThat(blankOnly.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blankOnly.getBody()).contains("没有要执行的语句");
        assertThat(stub.explainCalls).isZero();
        assertThat(stub.executeCalls).isZero();
        assertThat(tempExecutedAuditCount()).isZero();
    }

    /** 一次最多 50 条：超限是 400，而且不应先去做 51 次 EXPLAIN。 */
    @Test
    void more_than_fifty_statements_are_refused() {
        List<String> statements = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            statements.add("update t_order set a = " + i + " where id = " + i);
        }

        ResponseEntity<String> response =
                postExecution(OPS02_TOKEN, bodyWithStatements(ORDER_DATASOURCE_ID, true, statements));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("一次最多 50 条语句");
        assertThat(stub.explainCalls).as("条数闸先于分级").isZero();
        assertThat(stub.executeCalls).isZero();
        assertThat(tempExecutedAuditCount()).isZero();
    }

    /** 硬规则禁止的语句（{@code drop database}）：400 且消息里带规则码，一条都不执行。 */
    @Test
    void a_blocked_statement_is_refused_with_its_rule_code() {
        ResponseEntity<String> response = postExecution(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, "drop database other_db"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("DROP_DATABASE");
        assertThat(stub.executeCalls).isZero();
        assertThat(tempExecutedAuditCount()).isZero();
    }

    @Test
    void an_unknown_datasource_is_a_404() {
        ResponseEntity<String> response = postExecution(OPS02_TOKEN, body(9999L, true, L1_UPDATE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("数据源不存在");
        assertThat(stub.executedSql).isEmpty();
    }

    /**
     * 同一数据源上已有执行在跑（锁表里已有一行）→ 409 且零副作用。
     *
     * <p>锁行由用例手工插入，{@code ticket_id=999} 是一个"别人的工单"；临时执行不得删掉它。
     */
    @Test
    void an_execution_that_is_already_running_is_a_conflict() {
        jdbc.update("""
                insert into datasource_exec_lock (datasource_id, ticket_id, acquired_at)
                values (?, ?, now(3))
                """, ORDER_DATASOURCE_ID, 999L);
        try {
            ResponseEntity<String> response =
                    postExecution(OPS02_TOKEN, body(ORDER_DATASOURCE_ID, true, L1_UPDATE));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("正在执行");
            assertThat(stub.executedSql).isEmpty();
            assertThat(tempExecutedAuditCount()).isZero();
            assertThat(jdbc.queryForObject(
                    "select ticket_id from datasource_exec_lock where datasource_id = ?",
                    Long.class, ORDER_DATASOURCE_ID))
                    .as("失败方不得替别人解锁").isEqualTo(999L);
        } finally {
            jdbc.update("delete from datasource_exec_lock where datasource_id = ?", ORDER_DATASOURCE_ID);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 请求体：{@code {datasourceId, statements, confirmed}}；身份只来自 token。 */
    private String body(long datasourceId, boolean confirmed, String... statements) {
        return bodyWithStatements(datasourceId, confirmed, List.of(statements));
    }

    private String bodyWithStatements(long datasourceId, boolean confirmed, List<String> statements) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("datasourceId", datasourceId);
        body.put("statements", statements);
        body.put("confirmed", confirmed);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private ResponseEntity<String> postExecution(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange("/api/v1/temp-executions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode tree(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON: " + response.getBody(), e);
        }
    }

    /** 解析一段 JSON 文本（审计 payload）；解析不出来直接失败，不吞成空对象。 */
    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("不是合法 JSON: " + json, e);
        }
    }

    private int tempExecutedAuditCount() {
        return jdbc.queryForObject(
                "select count(*) from audit_event where event_type = 'TEMP_EXECUTED'", Integer.class);
    }

    /** 该数据源当前的执行锁行数：被拒的请求必须一行都没有（失败方零副作用）。 */
    private int lockRows(long datasourceId) {
        return jdbc.queryForObject(
                "select count(*) from datasource_exec_lock where datasource_id = ?", Integer.class, datasourceId);
    }

    /** 唯一一条 {@code TEMP_EXECUTED} 审计行（多于一条时测试直接失败，说明重复写审计）。 */
    private Map<String, Object> tempExecutedAudit() {
        return jdbc.queryForMap("""
                select operator_id, exec_identity, datasource_id, action, object_id, payload_json
                  from audit_event where event_type = 'TEMP_EXECUTED'
                """);
    }
}
