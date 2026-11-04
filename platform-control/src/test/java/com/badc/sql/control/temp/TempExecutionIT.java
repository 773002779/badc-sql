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
import com.badc.sql.control.support.JwtTestSupport;
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

    /**
     * 研发的请求必须 403，而且**角色闸在取锁之前**。
     *
     * <p>怎么把"取锁后又释放"与"从未取锁"分开：先给数据源 101 手工插一行别人持有的锁。若实现把角色闸放在
     * {@code acquire} 之后，这一行会让请求撞主键、返回 409「该数据源有变更正在执行」；只有角色闸真的先跑，
     * 才会看到 403。原先的 {@code lockRows(...) == 0} 断言分不开这两种情形——{@code release} 在
     * {@code finally} 里，异常路径会把锁放掉，于是"取过又放掉"与"从未取过"都是 0。
     */
    @Test
    void a_developer_cannot_execute_anything_directly() {
        jdbc.update("""
                insert into datasource_exec_lock (datasource_id, ticket_id, acquired_at)
                values (?, ?, utc_timestamp(3))
                """, ORDER_DATASOURCE_ID, 900L);
        try {
            ResponseEntity<String> response = postExecution(DEV_TOKEN, body(ORDER_DATASOURCE_ID, true, L1_UPDATE));

            assertThat(response.getStatusCode())
                    .as("角色闸必须在取锁之前：真去取锁会撞上那行锁并返回 409")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).contains("研发");
            assertThat(stub.executedSql).as("研发一条语句都不许发出去").isEmpty();
            assertThat(tempExecutedAuditCount()).as("被拒绝的请求不得留下执行审计").isZero();
            assertThat(lockRows(ORDER_DATASOURCE_ID)).as("别人的锁不能被删掉").isEqualTo(1);
            assertThat(stub.explainCalls).as("角色闸必须先于分级：被拒的请求不该为它跑一次 EXPLAIN").isZero();
        } finally {
            jdbc.update("delete from datasource_exec_lock where datasource_id = ? and ticket_id = ?",
                    ORDER_DATASOURCE_ID, 900L);
        }
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

    // ------------------------------------------------------------------ L3 转工单

    /**
     * L3 且提交人不是组长：不再 409，而是**转成一张 {@code TEMP_EXEC} 工单**等组长审批，一条语句都不下发。
     *
     * <p>断言里既有"响应形状"（202 + executed:false + ticketId + REVIEWING_L1），也有"库里的那张单长什么样"
     * （类型、等级、状态、提交人）与"到底有没有执行"（{@code stub.executeCalls == 0}）。只断言状态码的用例
     * 证明不了"转单没有顺手把语句跑掉"。
     */
    @Test
    void an_l3_change_by_a_non_lead_becomes_a_ticket_instead_of_running() {
        ResponseEntity<String> response =
                postExecution(OPS02_TOKEN, body(ORDER_DATASOURCE_ID, true, "truncate table t_order"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode json = tree(response);
        assertThat(json.path("executed").asBoolean()).as("转单不是执行").isFalse();
        assertThat(json.path("riskLevel").asText()).isEqualTo("L3");
        assertThat(json.path("status").asText()).isEqualTo("REVIEWING_L1");
        long ticketId = json.path("ticketId").asLong();
        assertThat(ticketId).as("响应里必须带新工单 ID，前端据此跳转").isPositive();

        Map<String, Object> ticket = ticketRow(ticketId);
        assertThat(ticket.get("ticket_type")).isEqualTo("TEMP_EXEC");
        assertThat(ticket.get("risk_level")).isEqualTo("L3");
        assertThat(ticket.get("status")).isEqualTo("REVIEWING_L1");
        assertThat(ticket.get("datasource_id")).isEqualTo(ORDER_DATASOURCE_ID);
        assertThat(((Number) ticket.get("submitter_id")).longValue())
                .as("提交人是发起临时执行的运维本人（审批时据此判「不能审自己的单」）").isEqualTo(4L);

        assertThat(stub.executedSql).as("一条语句都不许发出去").isEmpty();
        assertThat(stub.executeCalls).isZero();
        assertThat(tempExecutedAuditCount()).as("转单没有执行，不该写 TEMP_EXECUTED").isZero();
        assertThat(lockRows(ORDER_DATASOURCE_ID)).as("转单不取执行锁：没有任何语句要跑").isZero();
    }

    /**
     * 普通运维（主运维）不能批 {@code TEMP_EXEC} 单：这张单只有组长能批。
     *
     * <p><b>为什么由备运维提单</b>：若让 ops02 自己提、自己批，拦下他的是"提交人不能审核自己的工单"
     * （同样 403），这条用例就分不开"角色闸生效"与"自审闸生效"——把 Step 4 的类型判定整段删掉它依然全绿。
     * 换一个非组长的运维提单，403 就只能来自 LEAD_ONLY 这道闸。夹具里系统 1 没有备运维，这里临时建一个
     * （{@code finally} 清理，与 {@code TicketReviewIT} 同形）。
     */
    @Test
    void a_regular_operator_cannot_approve_a_temp_execution_ticket() {
        addBackupOperator();
        try {
            long ticketId = postExecutionForTicketId(BACKUP_OPS_TOKEN,
                    body(ORDER_DATASOURCE_ID, true, "truncate table t_order"));

            ResponseEntity<String> response = approve(OPS02_TOKEN, ticketId, "主运维想批临时执行单");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).contains("运维临时执行的 L3 变更只能由运维组长审批");
            assertThat(status(ticketId)).isEqualTo("REVIEWING_L1");
            assertThat(reviewRows(ticketId)).as("被拒的审批不得留下审核行").isZero();
        } finally {
            removeBackupOperator();
        }
    }

    /**
     * 组长批准 {@code TEMP_EXEC} 单：**一级即终态**，不得出现 {@code REVIEWING_L2}。
     *
     * <p>"不得进二级"必须用状态与审核行一起断言：只看最终 {@code APPROVED} 的话，一个"先进二级、再由同一个
     * 组长终审"的实现也会绿——而那正是这张单不该有的行为（运维临时执行不走四眼原则）。
     */
    @Test
    void the_lead_approves_a_temp_execution_ticket_in_one_level() {
        long ticketId = postExecutionForTicketId(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, "truncate table t_order"));

        ResponseEntity<String> response = approve(LEAD01_TOKEN, ticketId, "组长批准");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = tree(response);
        assertThat(json.path("status").asText()).isEqualTo("APPROVED");
        assertThat(status(ticketId)).isEqualTo("APPROVED");
        assertThat(reviewRows(ticketId)).as("只有一级审核记录，没有第二条").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select review_level from ticket_review where ticket_id = ?", Integer.class, ticketId))
                .as("批准落在 review_level=1").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from ticket_event where ticket_id = ? and event_type = 'APPROVED_L2'",
                Integer.class, ticketId))
                .as("临时执行单不进二级：不该有 APPROVED_L2 事件").isZero();
        assertThat(jdbc.queryForObject(
                "select approved_at from ticket where id = ?", java.sql.Timestamp.class, ticketId))
                .as("单级批准必须置 approved_at").isNotNull();
    }

    /**
     * 转工单不要求回滚 SQL / 回滚方案：请求里没有任何回滚信息，建单依然成功，且两列都是空。
     *
     * <p>这条性质如果不成立，运维会被迫为一次临时执行编一段"回滚 SQL"——而临时执行的安全网是执行前结构快照
     * 与审计（§4.3-3），假 SQL 只会让审批人误以为有一份可用的回滚方案。
     */
    @Test
    void a_temp_execution_ticket_needs_no_rollback_sql() {
        long ticketId = postExecutionForTicketId(OPS02_TOKEN,
                body(ORDER_DATASOURCE_ID, true, "truncate table t_order"));

        Map<String, Object> ticket = ticketRow(ticketId);
        assertThat(ticket.get("rollback_sql")).as("临时执行单没有回滚 SQL").isNull();
        assertThat(ticket.get("rollback_plan")).as("临时执行单没有回滚方案").isNull();
        assertThat((String) ticket.get("title")).startsWith("运维临时执行：").contains("TRUNCATE").contains("t_order");
        assertThat((String) ticket.get("change_reason")).isEqualTo("运维临时执行（L3），需组长一级审批");
        // 语句仍然完整落库（审批人要看的是"到底要执行什么"，而不是回滚怎么做）。
        assertThat(jdbc.queryForObject(
                "select count(*) from ticket_statement where ticket_id = ?", Integer.class, ticketId)).isEqualTo(1);
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

    /** 系统 1 的备运维（夹具里没有；只在"普通运维不能批临时执行单"那条用例里临时存在）。 */
    private static final long BACKUP_OPERATOR_ID = 6L;

    private static final String BACKUP_OPS_TOKEN = JwtTestSupport.token(BACKUP_OPERATOR_ID, "backup01");

    private void addBackupOperator() {
        removeBackupOperator();
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (?, 'backup01', ?, 'BACKUP_OPS', '备运维')
                """, BACKUP_OPERATOR_ID,
                "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW");
        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (1, ?, 'BACKUP')
                """, BACKUP_OPERATOR_ID);
    }

    private void removeBackupOperator() {
        jdbc.update("delete from sys_system_operator where system_id = 1 and user_id = ?", BACKUP_OPERATOR_ID);
        jdbc.update("delete from sys_user where id = ?", BACKUP_OPERATOR_ID);
    }

    /**
     * 发一次临时执行请求并要求它转成了工单：返回新工单 ID。
     *
     * <p>状态码与 {@code executed:false} 也在这里断言一次，避免三个审批用例各自漏掉"到底转没转单"。
     */
    private long postExecutionForTicketId(String token, String body) {
        ResponseEntity<String> response = postExecution(token, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode json = tree(response);
        assertThat(json.path("executed").asBoolean()).isFalse();
        long ticketId = json.path("ticketId").asLong();
        assertThat(ticketId).isPositive();
        return ticketId;
    }

    private ResponseEntity<String> approve(String token, long ticketId, String comment) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment);
        try {
            return rest.exchange("/api/v1/tickets/" + ticketId + "/approve", HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化审批请求体", e);
        }
    }

    /** {@code ticket} 表的一行（用例自己取需要的列断言）。 */
    private Map<String, Object> ticketRow(long ticketId) {
        return jdbc.queryForMap("""
                select ticket_type, risk_level, status, datasource_id, submitter_id,
                       title, change_reason, rollback_plan, rollback_sql
                  from ticket where id = ?
                """, ticketId);
    }

    private int reviewRows(long ticketId) {
        return jdbc.queryForObject(
                "select count(*) from ticket_review where ticket_id = ?", Integer.class, ticketId);
    }

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
