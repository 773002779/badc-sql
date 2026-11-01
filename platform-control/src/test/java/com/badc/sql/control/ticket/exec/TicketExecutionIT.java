package com.badc.sql.control.ticket.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.StatementClassifier;
import com.badc.sql.control.support.TicketITBase;

/**
 * 工单执行编排的端到端用例（真实 HTTP + 真实元数据库 + 桩执行器）。
 *
 * <p>覆盖四组事：<b>正常执行与部分失败</b>（逐条状态、影响行数、跳过、汇总）、<b>入口闸</b>（状态、归属、
 * 管理员、并发锁）、<b>执行前的五道中止</b>（改语句、结构漂移、快照失败、风险升档、风险确认缺失/过期）与
 * <b>审计口径</b>（{@code operator_id} 与 {@code exec_identity} 是两个人）。
 *
 * <p><b>为什么一律走真实提交/审批/确认接口</b>：手写 {@code risk_snapshot_hash}、{@code statement_fingerprint}
 * 会让用例验证的是夹具而不是契约——尤其是"执行前重算的哈希必须与确认时一致"，只有真实提交链路才能证明
 * 两边用的是同一套口径。
 *
 * <p><b>为什么 awaitTerminal 还等锁释放</b>：执行是异步的，只等工单状态会在"状态已写、事件/审计还没写、
 * 锁还没释放"的几毫秒里返回，后续断言（{@code executeCalls}、审计行）就会随机失败。锁在 {@code finally}
 * 里释放，它消失即代表任务真正跑完。
 */
class TicketExecutionIT extends TicketITBase {

    /** 与提交期同一个分类器（纯函数），用来判断一张工单是 DML 还是 DDL 单。 */
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();

    private static final String DML_SQL = "update t_order set a = 1 where id = 1";

    private static final String DML_ROLLBACK_SQL = "update t_order set a = 0 where id = 1";

    /** {@code drop table} 的回滚方案必须包含的固定确认语（{@code TicketSubmitValidator}）。 */
    private static final String DDL_ROLLBACK_PLAN = "将从快照重建表结构，请人工核对已执行的语句";

    /** DDL 账号的主键：基类用了 1（readonly）与 2（dml），这里用 3。 */
    private static final long DDL_ACCOUNT_ID = 3L;

    @BeforeAll
    void addDdlAccount() {
        // 结构变更语句必须走 ddl 账号（dml 账号通常只有 DML 权限）。
        jdbc.update("delete from conn_account where datasource_id = ? and account_type = 'ddl'",
                ORDER_DATASOURCE_ID);
        jdbc.update("""
                insert into conn_account (id, datasource_id, account_type, username, password_cipher)
                values (?, 101, 'ddl', 'ddl_user', ?)
                """, DDL_ACCOUNT_ID, credentialCipher.encrypt("ddl-pass"));
    }

    /**
     * 每个用例从空工单表开始：并发锁用例断言"失败方零副作用"，共享工单会让它退化成弱断言。
     */
    @BeforeEach
    void clearTickets() {
        jdbc.update("delete from datasource_exec_lock");
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket_review");
        jdbc.update("delete from ticket");
        jdbc.update("delete from audit_event");
    }

    // ------------------------------------------------------------------ 正常执行与汇总

    @Test
    void an_approved_dml_ticket_executes_and_records_rows_per_statement() throws Exception {
        stub.executeResults = List.of(new ExecuteResult(true, 2L, 7L, null, null));
        long id = approvedTicket(DML_SQL);

        ResponseEntity<String> response = requestExecution(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(objectMapper.readTree(response.getBody()).path("id").asLong()).isEqualTo(id);
        assertThat(objectMapper.readTree(response.getBody()).path("status").asText()).isEqualTo("EXECUTING");

        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        assertThat(statementStatuses(id)).containsExactly("SUCCEEDED");
        assertThat(affectedRows(id, 1)).as("影响行数按条落库").isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "select elapsed_ms from ticket_statement where ticket_id = ? and seq_no = 1", Long.class, id))
                .isEqualTo(7L);
        assertThat(stub.executedSql).containsExactly(DML_SQL);
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DML);

        // change_seq=0 是提交时写的结构指纹，change_seq=1 是执行前抓的快照。
        assertThat(jdbc.queryForList(
                "select change_seq from schema_snapshot where ticket_id = ? order by change_seq", Integer.class, id))
                .containsExactly(0, 1);
        assertThat(jdbc.queryForObject(
                "select snapshot_ref from schema_snapshot where ticket_id = ? and change_seq = 1", String.class, id))
                .isEqualTo("1/101/" + id + "/1");
        assertThat(stub.snapshotRefs).containsExactly("1/101/" + id + "/1");

        // 事件流：提交 → 审批 → 执行风险确认 → 执行结束。
        assertThat(eventTypes(id)).contains("SUBMITTED", "APPROVED_L1", "FINISHED");
        assertThat(abortReason(id)).as("正常执行不该有中止事件").isNull();
        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select finished_at from ticket where id = ?", java.sql.Timestamp.class, id))
                .as("终态必须写 finished_at")
                .isNotNull();
    }

    @Test
    void a_failing_second_statement_leaves_the_first_committed_and_marks_partial() {
        stub.executeResults = List.of(new ExecuteResult(true, 2L, 5L, null, null));
        stub.failOnStatementIndex = Set.of(1);
        long id = approvedTicket(DML_SQL, "update no_such_table set a = 1");

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("PARTIAL_FAILED");
        assertThat(statementStatuses(id)).containsExactly("SUCCEEDED", "FAILED");
        assertThat(affectedRows(id, 1)).isEqualTo(2L);
        assertThat(stub.executeCalls).as("第二条失败后不再继续").isEqualTo(2);
        assertThat(finishedEventDetail(id))
                .contains("\"failed\":1")
                .contains("\"succeeded\":1");
    }

    @Test
    void the_remaining_statements_are_skipped_after_a_failure() {
        stub.failOnStatementIndex = Set.of(0);
        long id = approvedTicket("update no_such_table set a = 1", DML_SQL);

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(statementStatuses(id)).containsExactly("FAILED", "SKIPPED");
        assertThat(stub.executeCalls).as("失败后一条都不再下发").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 入口闸

    @Test
    void a_ticket_that_is_not_approved_cannot_be_executed() {
        long id = reviewingTicket();

        ResponseEntity<String> response = requestExecution(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(id)).as("被拒之后不能进入 EXECUTING").isEqualTo("REVIEWING_L1");
        assertThat(statementStatuses(id)).containsOnly("PENDING");
        assertThat(stub.executeCalls).isZero();
    }

    @Test
    void another_developers_ticket_cannot_be_executed() {
        long id = approvedTicket(DML_SQL);
        // 换一个提交人：dev01 既不是提交人，也不在系统 1 的运维名单里。
        jdbc.update("update ticket set submitter_id = 2 where id = ?", id);

        ResponseEntity<String> response = requestExecution(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("提交人");
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(statementStatuses(id)).containsOnly("PENDING");
    }

    /**
     * §4.2：管理员只配置与授权，不可执行生产语句。
     *
     * <p>两段式：admin01 本来就没有任何系统的归属，只测这一点的话 403 无法区分是 ADMIN 闸还是归属闸挡的。
     * 第二段临时给他插一条系统 1 的归属行（{@code finally} 删除），让归属判定放行，此时仍必须 403，
     * 且理由必须是"管理员"——这才是"ADMIN 先于归属"那个顺序的证据。
     */
    @Test
    void an_admin_cannot_execute() {
        long id = approvedTicket(DML_SQL);

        ResponseEntity<String> byMembership = requestExecution(ADMIN_TOKEN, id);
        assertThat(byMembership.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byMembership.getBody()).contains("管理员");

        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (1, 3, 'BACKUP')
                """);
        try {
            ResponseEntity<String> byAdminGate = requestExecution(ADMIN_TOKEN, id);

            assertThat(byAdminGate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(byAdminGate.getBody())
                    .as("归属判定已放行，剩下的必须是 ADMIN 闸")
                    .contains("管理员")
                    .doesNotContain("提交人");
            assertThat(status(id)).isEqualTo("APPROVED");
            assertThat(stub.executeCalls).isZero();
        } finally {
            jdbc.update("delete from sys_system_operator where system_id = 1 and user_id = 3");
        }
    }

    /**
     * 拿不到锁：409 且<b>零副作用</b>——工单仍是 APPROVED、语句仍是 PENDING、语句一条都没下发。
     */
    @Test
    void a_second_ticket_on_the_same_datasource_is_refused_without_side_effects() {
        long second = approvedTicket("update t_order set a = 2 where id = 2");
        DatasourceExecLock holder = new DatasourceExecLock(jdbc);
        assertThat(holder.acquire(ORDER_DATASOURCE_ID, -1L)).as("先占住该库").isTrue();
        try {
            ResponseEntity<String> response = requestExecution(DEV_TOKEN, second);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("正在执行");
            assertThat(status(second)).isEqualTo("APPROVED");
            assertThat(statementStatuses(second)).as("失败方不得留下任何执行痕迹").containsOnly("PENDING");
            assertThat(stub.executeCalls).isZero();
            // 持有者的锁不被失败方释放。
            assertThat(jdbc.queryForObject(
                    "select count(*) from datasource_exec_lock where datasource_id = 101", Integer.class))
                    .isEqualTo(1);
        } finally {
            holder.release(ORDER_DATASOURCE_ID, -1L);
        }
    }

    // ------------------------------------------------------------------ 执行前的五道中止

    /** 审批后被改的语句：一条都不许发出去（指纹闸先于确认闸，理由必须是"语句被改"）。 */
    @Test
    void an_approved_ticket_whose_statements_were_edited_is_refused() {
        long id = approvedTicket(DML_SQL);
        jdbc.update("update ticket_statement set sql_text = 'update t_order set a = 2 where id = 1' where ticket_id = ?",
                id);

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("STATEMENT_TAMPERED");
        assertThat(stub.executeCalls).isZero();
    }

    /** 审批后被加列：漂移必须命中，且 detail 要说清是哪张表。 */
    @Test
    void a_schema_drift_after_approval_aborts_before_the_first_statement() {
        long id = approvedTicket(DML_SQL);
        stub.fingerprints = Map.of("t_order", "changed-after-approval");

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("SCHEMA_DRIFT").contains("t_order");
        assertThat(stub.executeCalls).isZero();
        assertThat(stub.executedSql).isEmpty();
    }

    /** 快照抓取失败 → 一条语句都不执行（全局约束）。 */
    @Test
    void a_failed_snapshot_prevents_every_statement() {
        long id = approvedTicket(DML_SQL);
        stub.snapshotThrows = true;

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("SNAPSHOT_FAILED");
        assertThat(stub.executeCalls).isZero();
        assertThat(jdbc.queryForList(
                "select 1 from schema_snapshot where ticket_id = ? and change_seq = 1", Integer.class, id))
                .as("抓取失败不得留下 change_seq=1 的快照行")
                .isEmpty();
    }

    /** 审批后语句的风险等级变高（这里把 update 换成 drop table）→ 中止并让用户重新提单。 */
    @Test
    void a_risk_escalation_after_approval_aborts_execution() {
        long id = approvedTicket(DML_SQL);
        jdbc.update("update ticket_statement set sql_text = 'drop table t_order' where ticket_id = ?", id);

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("RISK_ESCALATED").contains("\"from\":\"L1\"").contains("\"to\":\"L3\"");
        assertThat(stub.executeCalls).isZero();
    }

    /** 从未确认过执行风险 → 拒绝执行。 */
    @Test
    void execution_requires_a_current_risk_acknowledgement() {
        long id = approvedTicketWithoutExecuteAck(DML_SQL);

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("RISK_ACK_MISSING");
        assertThat(stub.executeCalls).isZero();
    }

    /** 确认行还在，但它确认的是另一份风险（哈希已变）→ 同样拒绝。 */
    @Test
    void a_stale_risk_acknowledgement_does_not_authorise_execution() {
        long id = approvedTicketWithoutExecuteAck(DML_SQL);
        jdbc.update("""
                insert into risk_acknowledgement (ticket_id, stage, operator_id, risk_snapshot_hash)
                values (?, 'EXECUTE', 1, ?)
                """, id, "e".repeat(64));

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("FAILED");
        assertThat(abortReason(id)).contains("RISK_ACK_MISSING").contains("风险信息已变更");
        assertThat(stub.executeCalls).isZero();
    }

    /** drop table 时表已不存在：不是漂移，按"前置状态为空"继续执行。 */
    @Test
    void a_missing_table_is_empty_before_state_not_a_drift() {
        long id = approvedTicket("drop table no_such_table");
        stub.fingerprints = Map.of();

        requestExecution(DEV_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        assertThat(abortReason(id)).isNull();
        assertThat(stub.executeCalls).isEqualTo(1);
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DDL);
    }

    // ------------------------------------------------------------------ 账号与审计

    @Test
    void ddl_statements_use_the_ddl_account_and_dml_statements_the_dml_account() {
        long ddl = approvedTicket("create table t_new (id int)");
        requestExecution(DEV_TOKEN, ddl);

        assertThat(awaitTerminal(ddl)).isEqualTo("SUCCEEDED");
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DDL);

        stub.executedAccountTypes.clear();
        stub.executedSql.clear();

        long dml = approvedTicket(DML_SQL);
        requestExecution(DEV_TOKEN, dml);

        assertThat(awaitTerminal(dml)).isEqualTo("SUCCEEDED");
        assertThat(stub.executedAccountTypes).containsExactly(ExecAccountType.DML);
    }

    /**
     * §FR-08：审计同时记录研发（{@code operator_id}）与数据库账号（{@code exec_identity}），两者是不同的列。
     */
    @Test
    void the_audit_records_the_operator_and_the_database_account_separately() {
        long id = approvedTicket(DML_SQL);

        // 由该系统的运维（ops02，用户 4）点执行：operator_id 必须是他，exec_identity 必须是库账号。
        requestExecution(OPS02_TOKEN, id);

        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");
        Map<String, Object> audit = jdbc.queryForMap("""
                select operator_id, exec_identity, action, payload_json from audit_event
                 where event_type = 'TICKET_EXECUTED' and object_id = ?
                """, String.valueOf(id));

        assertThat(((Number) audit.get("operator_id")).longValue())
                .as("operator_id 是点执行的人").isEqualTo(4L);
        assertThat(audit.get("exec_identity"))
                .as("exec_identity 是实际使用的数据库账号名").isEqualTo("dml_user");
        assertThat(audit.get("action")).isEqualTo("EXECUTE");

        String payload = (String) audit.get("payload_json");
        assertThat(payload)
                .contains("\"succeeded\":1")
                .contains("\"failed\":0")
                .contains("\"skipped\":0")
                .doesNotContain("dml-pass")
                .doesNotContain("dml_user")
                .doesNotContain("password");
    }

    /** 中止也要有审计：{@code TICKET_ABORTED} 的 {@code action} 就是中止原因。 */
    @Test
    void an_aborted_execution_is_audited_with_its_reason() {
        long id = approvedTicket(DML_SQL);
        jdbc.update("update ticket_statement set sql_text = 'update t_order set a = 2 where id = 1' where ticket_id = ?",
                id);

        requestExecution(DEV_TOKEN, id);
        assertThat(awaitTerminal(id)).isEqualTo("FAILED");

        Map<String, Object> audit = jdbc.queryForMap("""
                select operator_id, action, payload_json from audit_event
                 where event_type = 'TICKET_ABORTED' and object_id = ?
                """, String.valueOf(id));
        assertThat(audit.get("action")).isEqualTo("STATEMENT_TAMPERED");
        assertThat(((Number) audit.get("operator_id")).longValue()).isEqualTo(1L);
        assertThat((String) audit.get("payload_json")).contains("STATEMENT_TAMPERED");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 真实提交 + 审批（L3 走两级）+ 执行前风险确认，返回工单 ID。
     *
     * <p>风险确认必须由真实接口完成：执行前的比对用的是服务端重算的哈希，手写一份哈希证明不了两边口径一致。
     */
    private long approvedTicket(String... statements) {
        long id = submitTicket(statements);
        approve(id);
        acknowledgeRisk(DEV_TOKEN, id);
        return id;
    }

    /** 已审批但<b>没有</b>执行前风险确认的工单。 */
    private long approvedTicketWithoutExecuteAck(String... statements) {
        long id = submitTicket(statements);
        approve(id);
        return id;
    }

    /** 停在 REVIEWING_L1 的工单。 */
    private long reviewingTicket() {
        return submitTicket(DML_SQL);
    }

    private long submitTicket(String... statements) {
        List<Classification> classifications = Arrays.stream(statements).map(CLASSIFIER::classify).toList();
        boolean hasDdl = classifications.stream().anyMatch(c -> c.ddlAction() != DdlAction.NONE);
        boolean hasDmlWrite = classifications.stream()
                .anyMatch(c -> c.writeStatement() && c.ddlAction() == DdlAction.NONE);

        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body(String.join(";", statements),
                        hasDdl ? DDL_ROLLBACK_PLAN : null,
                        hasDmlWrite ? DML_ROLLBACK_SQL : null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    /** 一级审批；L3 单再走组长终审。 */
    private void approve(long id) {
        ResponseEntity<String> first = postJson(OPS02_TOKEN, "/api/v1/tickets/" + id + "/approve",
                "{\"comment\":\"通过\"}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        if ("REVIEWING_L2".equals(status(id))) {
            ResponseEntity<String> second = postJson(LEAD01_TOKEN, "/api/v1/tickets/" + id + "/approve",
                    "{\"comment\":\"终审通过\"}");
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(status(id)).as("夹具必须停在 APPROVED").isEqualTo("APPROVED");
    }

    private void acknowledgeRisk(String token, long id) {
        ResponseEntity<String> response = postJson(token, "/api/v1/tickets/" + id + "/risk-ack", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> requestExecution(String token, long ticketId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/v1/tickets/" + ticketId + "/execute", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postJson(String token, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /**
     * 轮询到终态。
     *
     * <p>除了状态不再是 {@code EXECUTING}/{@code APPROVED}，还要求执行锁已经释放：锁在任务体的
     * {@code finally} 里释放，它消失才代表"事件与审计也写完了"。只判状态会在最后几毫秒里返回，
     * 让 {@code executeCalls} 与审计断言随机失败。
     */
    private String awaitTerminal(long id) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = status(id);
            boolean lockHeld = lockHeld(id);
            if (!"EXECUTING".equals(status) && !"APPROVED".equals(status) && !lockHeld) {
                return status;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("工单 " + id + " 未在 10s 内进入终态，当前=" + status(id));
    }

    private boolean lockHeld(long ticketId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from datasource_exec_lock where ticket_id = ?", Integer.class, ticketId);
        return count != null && count > 0;
    }

    private List<String> statementStatuses(long ticketId) {
        return jdbc.queryForList(
                "select status from ticket_statement where ticket_id = ? order by seq_no", String.class, ticketId);
    }

    private Long affectedRows(long ticketId, int seqNo) {
        return jdbc.queryForObject(
                "select affected_rows from ticket_statement where ticket_id = ? and seq_no = ?",
                Long.class, ticketId, seqNo);
    }

    private List<String> eventTypes(long ticketId) {
        return jdbc.queryForList(
                "select event_type from ticket_event where ticket_id = ? order by id", String.class, ticketId);
    }

    /** {@code EXEC_ABORTED} 的明细；没有中止事件时返回 {@code null}。 */
    private String abortReason(long ticketId) {
        List<String> details = jdbc.queryForList("""
                select detail_json from ticket_event where ticket_id = ? and event_type = 'EXEC_ABORTED'
                """, String.class, ticketId);
        return details.isEmpty() ? null : details.get(0);
    }

    private String finishedEventDetail(long ticketId) {
        return jdbc.queryForObject("""
                select detail_json from ticket_event where ticket_id = ? and event_type = 'FINISHED'
                """, String.class, ticketId);
    }
}
