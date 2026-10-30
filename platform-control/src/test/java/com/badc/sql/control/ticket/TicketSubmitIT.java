package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 变更工单提交的端到端用例。
 *
 * <p>覆盖三组事：<b>拒绝</b>（必填项、回滚要求、BLOCK、越权）、<b>分级与落库</b>（最严等级、状态、快照、
 * 风险确认、审计）、<b>外部依赖的失败语义</b>（库不可达必须中止，表不存在按"前置状态为空"继续）。
 */
class TicketSubmitIT extends TicketITBase {

    /**
     * 每个用例从空工单表开始：{@code BLOCK 不落库} 与 {@code 外部调用失败不落库} 两条断言都用
     * {@code count(*)==0} 表达，如果让用例之间共享工单，它们就只能退化成"只多了一条"的弱断言。
     */
    @BeforeEach
    void clearTickets() {
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket");
        jdbc.update("delete from audit_event");
    }

    // ------------------------------------------------------------------ 必填与回滚要求

    @Test
    void a_dml_ticket_without_rollback_sql_is_refused() {
        assertBadRequest(postTicket(DEV_TOKEN, body("update t_order set a=1 where id=1", null)), "回滚 SQL");
        assertThat(ticketCount()).isZero();
    }

    @Test
    void a_ddl_ticket_without_a_rollback_plan_is_refused() {
        assertBadRequest(
                postTicket(DEV_TOKEN, body("create table t_new (id int)", null, null)),
                "回滚方案");
        assertThat(ticketCount()).isZero();
    }

    @Test
    void a_structural_drop_without_the_confirmation_phrase_is_refused() {
        assertBadRequest(
                postTicket(DEV_TOKEN, body("drop table t_order", "重建表", null)),
                "将从快照重建表结构");
        assertThat(ticketCount()).isZero();
    }

    // ------------------------------------------------------------------ BLOCK 与回滚 SQL 静态检查

    @Test
    void drop_database_is_blocked_at_submit_and_no_ticket_is_created() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body("drop database order_db", "重建"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("drop database");
        assertThat(ticketCount()).as("BLOCK 不允许落任何行").isZero();
    }

    @Test
    void a_rollback_that_targets_another_table_is_refused() {
        assertBadRequest(
                postTicket(DEV_TOKEN,
                        body("update t_order set a=1 where id=1", "update t_other set a=0 where id=1")),
                "回滚 SQL 未命中目标表");
        assertThat(ticketCount()).isZero();
    }

    @Test
    void a_rollback_that_is_itself_blocked_is_refused() {
        assertBadRequest(
                postTicket(DEV_TOKEN,
                        body("update t_order set a=1 where id=1", "drop database order_db")),
                "drop database");
        assertThat(ticketCount()).isZero();
    }

    // ------------------------------------------------------------------ 分级与落库

    @Test
    void a_structural_drop_creates_a_reviewing_l3_ticket_with_its_snapshot_and_acknowledgement() throws Exception {
        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body("drop table t_order", "将从快照重建表结构", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(response);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("status").asText()).isEqualTo("REVIEWING_L1");
        assertThat(body.path("riskLevel").asText()).isEqualTo("L3");
        assertThat(body.path("statements")).hasSize(1);
        assertThat(body.path("statements").get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(body.path("statements").get(0).path("statementType").asText()).isEqualTo("DROP_TABLE");
        assertThat(body.path("statements").get(0).path("riskLevel").asText()).isEqualTo("L3");

        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(columnOf(id, "risk_level")).isEqualTo("L3");
        assertThat(columnOf(id, "ticket_type")).isEqualTo("NORMAL");
        assertThat(jdbc.queryForObject("select snapshot_enabled from ticket where id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(columnOf(id, "statement_fingerprint")).matches("[0-9a-f]{64}");
        assertThat(columnOf(id, "risk_snapshot_hash")).matches("[0-9a-f]{64}");
        assertThat(columnOf(id, "risk_snapshot_json")).contains("DROP_TABLE").contains("\"level\":\"L3\"");
        assertThat(columnOf(id, "submitter_id")).isEqualTo("1");
        assertThat(columnOf(id, "system_id")).isEqualTo("1");
        assertThat(columnOf(id, "module_id")).isEqualTo("201");

        List<StatementRow> statements = statementsOf(id);
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0).seqNo()).isEqualTo(1);
        assertThat(statements.get(0).sqlText()).isEqualTo("drop table t_order");
        assertThat(statements.get(0).statementType()).isEqualTo("DROP_TABLE");
        assertThat(statements.get(0).riskLevel()).isEqualTo("L3");
        assertThat(statements.get(0).status()).isEqualTo("PENDING");

        String snapshotRef = jdbc.queryForObject(
                "select snapshot_ref from schema_snapshot where ticket_id = ? and change_seq = 0",
                String.class, id);
        assertThat(snapshotRef).isEqualTo("1/101/" + id + "/0");

        String ackHash = jdbc.queryForObject(
                "select risk_snapshot_hash from risk_acknowledgement where ticket_id = ? and stage = 'SUBMIT'",
                String.class, id);
        assertThat(ackHash).isEqualTo(columnOf(id, "risk_snapshot_hash"));
        assertThat(jdbc.queryForObject(
                "select operator_id from risk_acknowledgement where ticket_id = ? and stage = 'SUBMIT'",
                Long.class, id)).isEqualTo(1L);
    }

    /** 多语句里最严重的那条决定整单等级（§7.2-1）。 */
    @Test
    void the_ticket_level_is_the_strictest_of_its_statements() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body("update t_order set a=1 where id=1;\ntruncate table t_order",
                        "从快照重建", "update t_order set a=0 where id=1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(response);

        assertThat(columnOf(id, "risk_level")).isEqualTo("L3");

        List<StatementRow> statements = statementsOf(id);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0).seqNo()).isEqualTo(1);
        assertThat(statements.get(0).statementType()).isEqualTo("UPDATE");
        assertThat(statements.get(0).riskLevel()).isEqualTo("L1");
        assertThat(statements.get(1).seqNo()).isEqualTo(2);
        assertThat(statements.get(1).statementType()).isEqualTo("TRUNCATE");
        assertThat(statements.get(1).riskLevel()).isEqualTo("L3");
    }

    // ------------------------------------------------------------------ 外部依赖：指纹与估算

    /** 提交时记录结构指纹：审批后有人改表，执行时才比得出来。 */
    @Test
    void submitting_records_the_table_fingerprint_from_the_executor() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stub.lastFingerprintTables).containsExactly("t_order");
        assertThat(stub.lastExplainedSql).isEqualTo("update t_order set a=1 where id=1");

        JsonNode fingerprints = snapshotFingerprints(idOf(response));
        assertThat(fingerprints.path("t_order").asText()).isEqualTo("fp-1");
    }

    /** 表不存在不是失败：记录"前置状态为空"（空串），提交继续。 */
    @Test
    void a_table_that_does_not_exist_yet_is_recorded_as_an_empty_fingerprint() {
        stub.fingerprints = new LinkedHashMap<>();

        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body("update t_new set a=1 where id=1", "update t_new set a=0 where id=1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stub.lastFingerprintTables).containsExactly("t_new");

        JsonNode fingerprints = snapshotFingerprints(idOf(response));
        assertThat(fingerprints.path("t_new").asText()).as("前置状态为空").isEmpty();
    }

    /**
     * 库不可达 / 执行器不可用绝不能静默成"表不存在"：那会把一次网络故障变成"这是一张新表"，
     * 之后的漂移检测就会空转通过。
     */
    @Test
    void a_fingerprint_failure_aborts_the_submission_and_writes_nothing() {
        stub.fingerprintThrows = true;

        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));

        assertThat(response.getStatusCode().is5xxServerError())
                .as("执行器故障必须是失败，不能降级成空指纹")
                .isTrue();
        assertThat(ticketCount()).isZero();
    }

    // ------------------------------------------------------------------ 权限

    @Test
    void an_operator_cannot_submit_a_developer_ticket() {
        assertThat(postTicket(OPS_TOKEN, body("update t_order set a=1 where id=1", "x")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ticketCount()).isZero();
    }

    @Test
    void an_admin_cannot_submit_a_developer_ticket() {
        assertThat(postTicket(ADMIN_TOKEN, body("update t_order set a=1 where id=1", "x")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** 研发只能对自己可见的数据源提单（存在但无权 → 403）。 */
    @Test
    void submitting_against_an_invisible_datasource_is_forbidden() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                bodyFor(999L, "update pay_db.t set a=1", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ticketCount()).isZero();
    }

    /** 不存在的库是 404，与"存在但无权"的 403 区分开。 */
    @Test
    void submitting_against_a_missing_datasource_is_not_found() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                bodyFor(12345L, "update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ticketCount()).isZero();
    }

    // ------------------------------------------------------------------ L3 终审可用性

    /** §4.7-6：没有组长的系统，L3 工单提交时就拦下，而不是让它卡在 REVIEWING_L2。 */
    @Test
    void an_l3_ticket_is_refused_when_the_system_has_no_lead() {
        jdbc.update("delete from sys_system_operator where system_id = 1 and operator_type = 'LEAD'");
        try {
            ResponseEntity<String> response = postTicket(DEV_TOKEN,
                    body("drop table t_order", "将从快照重建表结构", null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("组长");
            assertThat(ticketCount()).isZero();
        } finally {
            jdbc.update("""
                    insert into sys_system_operator (id, system_id, user_id, operator_type)
                    values (11, 1, 5, 'LEAD')
                    """);
        }
    }

    /** L1/L2 工单不需要组长，不能被上面的检查连带拒掉。 */
    @Test
    void an_l1_ticket_does_not_require_a_lead() {
        jdbc.update("delete from sys_system_operator where system_id = 1 and operator_type = 'LEAD'");
        try {
            ResponseEntity<String> response = postTicket(DEV_TOKEN,
                    body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(columnOf(idOf(response), "risk_level")).isEqualTo("L1");
        } finally {
            jdbc.update("""
                    insert into sys_system_operator (id, system_id, user_id, operator_type)
                    values (11, 1, 5, 'LEAD')
                    """);
        }
    }

    // ------------------------------------------------------------------ 归属与审计

    /**
     * 归属推导：可见性来自多个模块时取最小 module_id，并留一条 MODULE_AMBIGUOUS 事件说明为什么。
     *
     * <p>这里临时给 101 再挂一个模块 202，用例结束在 {@code finally} 里删掉，不污染其他用例。
     */
    @Test
    void an_ambiguous_module_grant_takes_the_smallest_module_and_records_an_event() throws Exception {
        jdbc.update("insert into sys_module (id, system_id, name) values (202, 1, '订单写入')");
        jdbc.update("insert into module_datasource (id, module_id, datasource_id) values (3, 202, 101)");
        jdbc.update("""
                insert into user_module_grant (id, user_id, module_id, can_export, granted_by)
                values (2, 1, 202, 0, 2)
                """);
        try {
            ResponseEntity<String> response = postTicket(DEV_TOKEN,
                    body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            long id = idOf(response);

            assertThat(columnOf(id, "module_id")).isEqualTo("201");
            String detail = jdbc.queryForObject("""
                    select detail_json from ticket_event
                     where ticket_id = ? and event_type = 'MODULE_AMBIGUOUS'
                    """, String.class, id);
            assertThat(detail).isNotNull();
            JsonNode event = objectMapper.readTree(detail);
            assertThat(event.path("chosenModuleId").asLong()).isEqualTo(201L);
            assertThat(event.path("moduleCount").asInt()).isEqualTo(2);
        } finally {
            jdbc.update("delete from user_module_grant where id = 2");
            jdbc.update("delete from module_datasource where id = 3");
            jdbc.update("delete from sys_module where id = 202");
        }
    }

    /** 审计只记工单事实，不得夹带任何连接凭证（研发永远看不到账号/密码）。 */
    @Test
    void the_submission_is_audited_without_any_credential() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN,
                body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String payload = jdbc.queryForObject(
                "select payload_json from audit_event where event_type = 'TICKET_SUBMITTED'",
                String.class);
        assertThat(payload)
                .contains("\"riskLevel\":\"L1\"")
                .contains("\"statementCount\":1")
                .contains("\"datasourceId\":101");
        assertThat(payload)
                .doesNotContain("readonly_user")
                .doesNotContain("readonly-pass")
                .doesNotContain("password");
    }

    // ------------------------------------------------------------------ helpers

    private int ticketCount() {
        return jdbc.queryForObject("select count(*) from ticket", Integer.class);
    }

    private String columnOf(long ticketId, String column) {
        return jdbc.queryForObject("select " + column + " from ticket where id = ?", String.class, ticketId);
    }

    private JsonNode snapshotFingerprints(long ticketId) {
        String json = jdbc.queryForObject("""
                select table_fingerprints_json from schema_snapshot
                 where ticket_id = ? and change_seq = 0
                """, String.class, ticketId);
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("快照指纹不是合法 JSON: " + json, e);
        }
    }
}
