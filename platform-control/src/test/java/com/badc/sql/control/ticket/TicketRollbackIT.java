package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.badc.sql.common.sql.SqlText;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.StatementClassifier;
import com.badc.sql.control.support.JwtTestSupport;
import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 回滚工单的端到端用例（真实 HTTP + 真实元数据库 + 桩执行器）。
 *
 * <p>覆盖四组事：<b>发起</b>（预填原单回滚 SQL、运维补写、原因长度、404/409）、<b>权限</b>（研发/管理员/
 * 跨系统运维一律 403，ADMIN 先于归属）、<b>定级</b>（回滚语句重算，且原单 L3 → 回滚也 L3，两级审批）、
 * <b>落库与联动</b>（语句/快照/事件/审计，执行成功后原单 {@code ROLLED_BACK}）。
 *
 * <p><b>原单一律用 SQL 直接造 {@code SUCCEEDED}</b>：本任务验证的是"基于原单发起回滚"，不是"把原单跑成功"。
 * 走真实提交+执行会让每个用例多一串无关的审批与异步等待，且失败点会混进任务 11 的编排里。
 */
class TicketRollbackIT extends TicketITBase {

    /** 与提交期同一个分类器（纯函数）：造原单语句时填 {@code statement_type}。 */
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();

    /** 回滚原因固定用这一句（≥10 字）。 */
    private static final String REASON = "数据写错了，需要还原到变更前";

    /**
     * 系统 1 的第二位主运维。
     *
     * <p>夹具里系统 1 只有 ops02（主运维）与 lead01（组长）。L3 回滚的一级审核只收主/备运维，而提交人不能审
     * 自己的单——若只让 ops02 发起，L3 回滚在夹具里会无人可审。插入 ops03 正是为了证明"两级审批"能走通，
     * 而不是把死锁写成通过。
     */
    private static final String OPS03_TOKEN = JwtTestSupport.token(6L, "ops03");

    /** 夹具里其它用户密码哈希的同一串（明文 Passw0rd!）。 */
    private static final String PASSWORD_HASH =
            "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW";

    /** CHAR(64) 占位哈希：回滚只读原单的等级与 rollback_sql，不校验原单自己的哈希。 */
    private static final String PLACEHOLDER_HASH = "a".repeat(64);

    @BeforeAll
    void addSecondPrimaryOperator() {
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (6, 'ops03', ?, 'PRIMARY_OPS', '主运维三号')
                """, PASSWORD_HASH);
        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (1, 6, 'PRIMARY')
                """);
    }

    /** 每个用例从空工单表开始：400/403 用例断言"一张回滚单都没留下"。 */
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

    // ------------------------------------------------------------------ 发起：正常流与 SQL 来源

    @Test
    void a_rollback_ticket_prefills_the_original_rollback_sql() throws Exception {
        long origin = succeededTicket("update t_order set a = 1 where id = 1",
                "update t_order set a = 0 where id = 1");

        ResponseEntity<String> response = rollback(origin, REASON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = objectMapper.readTree(response.getBody());
        long id = body.path("id").asLong();
        assertThat(body.path("status").asText()).isEqualTo("REVIEWING_L1");
        assertThat(body.path("ticketType").asText()).isEqualTo("ROLLBACK");
        assertThat(body.path("originTicketId").asLong()).isEqualTo(origin);
        assertThat(body.path("riskLevel").asText()).isEqualTo("L1");

        assertThat(column(id, "ticket_type")).isEqualTo("ROLLBACK");
        assertThat(column(id, "origin_ticket_id")).isEqualTo(String.valueOf(origin));
        assertThat(column(id, "rollback_reason")).isEqualTo(REASON);
        assertThat(column(id, "status")).as("回滚也要走审批，不能直接执行").isEqualTo("REVIEWING_L1");
        assertThat(column(id, "system_id")).as("归属继承原单").isEqualTo("1");
        assertThat(column(id, "datasource_id")).as("目标库继承原单").isEqualTo("101");
        assertThat(column(id, "submitter_id")).as("发起人就是提交人").isEqualTo("4");

        assertThat(sqlTexts(id)).containsExactly("update t_order set a = 0 where id = 1");
        assertThat(statementStatuses(id)).containsExactly("PENDING");

        // 提交期的结构指纹基线：回滚的"现在"就是抓取时刻。
        assertThat(jdbc.queryForObject("""
                select snapshot_ref from schema_snapshot where ticket_id = ? and change_seq = 0
                """, String.class, id)).isEqualTo("1/101/" + id + "/0");
        assertThat(jdbc.queryForObject("""
                select count(*) from risk_acknowledgement where ticket_id = ? and stage = 'SUBMIT'
                """, Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from ticket_statement where ticket_id = ? and status = 'PENDING'
                """, Integer.class, id)).isEqualTo(1);
    }

    /** 运维补写的 SQL 优先于原单的回滚 SQL（可以改得比原方案更保守）。 */
    @Test
    void the_supplied_rollback_sql_overrides_the_original_one() {
        long origin = succeededTicket("update t_order set a = 1 where id = 1",
                "update t_order set a = 0 where id = 1");

        long id = rollbackOf(origin, REASON, "update t_order set a = -1 where id = 1");

        assertThat(sqlTexts(id)).containsExactly("update t_order set a = -1 where id = 1");
    }

    /** 原单没有回滚 SQL（DDL 只有文本方案）时不能凭空回滚，必须由发起人给出。 */
    @Test
    void a_ticket_without_rollback_sql_cannot_be_rolled_back_unless_the_operator_supplies_it() {
        long ddlish = succeededTicket("create table t_new (id int)", "从快照重建表结构", null);

        ResponseEntity<String> withoutSql = rollback(ddlish, "新表不需要了，删掉它", null);
        assertThat(withoutSql.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(withoutSql.getBody()).contains("请提供回滚语句");
        assertThat(rollbackTicketCount()).isZero();

        long id = rollbackOf(ddlish, "新表不需要了，删掉它", "drop table t_new");
        assertThat(sqlTexts(id)).as("运维补写的 SQL 就是回滚语句").containsExactly("drop table t_new");
    }

    @Test
    void a_rollback_reason_shorter_than_ten_characters_is_refused() {
        long origin = succeededTicket();

        ResponseEntity<String> response = rollback(origin, "回滚一下");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("回滚原因");
        assertThat(rollbackTicketCount()).isZero();
    }

    /** 回滚语句命中别的表：拒绝（不能把另一张表改回去）。 */
    @Test
    void a_rollback_sql_that_targets_another_table_is_refused() {
        long origin = succeededTicket("update t_order set a = 1 where id = 1",
                "update t_order set a = 0 where id = 1");

        ResponseEntity<String> response =
                rollback(origin, REASON, "update t_other set a = 0 where id = 1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("回滚 SQL 未命中目标表");
        assertThat(rollbackTicketCount()).isZero();
    }

    // ------------------------------------------------------------------ 原单状态与类型

    @Test
    void a_ticket_that_has_not_run_cannot_be_rolled_back() {
        long origin = insertOrigin("update t_order set a = 1 where id = 1", null,
                "update t_order set a = 0 where id = 1", "L1", "REVIEWING_L1");

        ResponseEntity<String> response = rollback(origin, REASON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("原单当前状态");
        assertThat(rollbackTicketCount()).isZero();
    }

    @Test
    void an_approved_but_never_executed_ticket_cannot_be_rolled_back() {
        long origin = insertOrigin("update t_order set a = 1 where id = 1", null,
                "update t_order set a = 0 where id = 1", "L1", "APPROVED");

        assertThat(rollback(origin, REASON).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rollbackTicketCount()).isZero();
    }

    /** 部分成功的原单可以回滚：只成功的部分也需要还原。 */
    @Test
    void a_partially_failed_ticket_can_be_rolled_back() {
        long origin = insertOrigin("update t_order set a = 1 where id = 1; update t_order set b = 1 where id = 1",
                null, "update t_order set a = 0 where id = 1", "L1", "PARTIAL_FAILED");

        assertThat(rollbackOf(origin, REASON)).isPositive();
    }

    /** 不允许对回滚单再发回滚：原单置 ROLLED_BACK 的联动不该被"回滚的回滚"绕开。 */
    @Test
    void a_rollback_ticket_cannot_be_rolled_back() {
        long origin = succeededTicket();
        long first = rollbackOf(origin, REASON);
        // 让回滚单本身也满足"已执行成功"，证明拦截理由是类型而不是状态。
        jdbc.update("update ticket set status = 'SUCCEEDED' where id = ?", first);

        ResponseEntity<String> response = rollback(first, REASON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("回滚工单不能再被回滚");
        assertThat(rollbackTicketCount()).isEqualTo(1);
    }

    @Test
    void rolling_back_a_ticket_that_does_not_exist_is_a_404() {
        ResponseEntity<String> response = rollback(999_999L, REASON);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------ 权限

    @Test
    void a_developer_cannot_initiate_a_rollback() {
        long origin = succeededTicket();

        ResponseEntity<String> response = rollbackAs(DEV_TOKEN, origin, REASON, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rollbackTicketCount()).isZero();
    }

    /**
     * 管理员不参与执行链：即使临时给他一条系统 1 的归属行，仍然必须 403 且理由是"管理员"。
     *
     * <p>只测"没有归属"的 403 无法区分是 ADMIN 闸还是归属闸挡的；补上归属行才能证明顺序正确。
     */
    @Test
    void an_admin_cannot_initiate_a_rollback_even_if_configured_as_an_operator() {
        long origin = succeededTicket();
        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (1, 3, 'BACKUP')
                """);
        try {
            ResponseEntity<String> response = rollbackAs(ADMIN_TOKEN, origin, REASON, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).as("归属判定已放行，剩下的必须是 ADMIN 闸").contains("管理员");
            assertThat(rollbackTicketCount()).isZero();
        } finally {
            jdbc.update("delete from sys_system_operator where system_id = 1 and user_id = 3");
        }
    }

    /** 不是本系统的运维也不行（ops01 是系统 2 的运维）。 */
    @Test
    void an_operator_of_another_system_cannot_rollback() {
        long origin = succeededTicket();

        ResponseEntity<String> response = rollbackAs(OPS_TOKEN, origin, REASON, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rollbackTicketCount()).isZero();
    }

    /** 组长也能发起回滚（回滚是一次新的变更，运维与组长同权）。 */
    @Test
    void a_lead_of_the_system_can_initiate_a_rollback() {
        long origin = succeededTicket();

        ResponseEntity<String> response = rollbackAs(LEAD01_TOKEN, origin, REASON, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(idOf(response)).isPositive();
    }

    // ------------------------------------------------------------------ 定级与两级审批

    /** 原单为 L3，回滚同样要组长终审（§FR-15），一级审完不能直接 APPROVED。 */
    @Test
    void a_rollback_of_an_l3_ticket_is_l3_and_requires_the_lead() {
        long origin = succeededTicketWithLevel("L3");

        long id = rollbackOf(origin, "结构性变更回退，需要重建表结构");

        assertThat(column(id, "risk_level")).as("原单 L3 → 回滚也 L3").isEqualTo("L3");

        ResponseEntity<String> first = approve(OPS03_TOKEN, id);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).as("L3 回滚一级审完不能终态").isEqualTo("REVIEWING_L2");

        ResponseEntity<String> second = approve(LEAD01_TOKEN, id);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ 端到端：执行成功后原单 ROLLED_BACK

    @Test
    void completing_a_rollback_marks_the_origin_as_rolled_back() {
        long origin = succeededTicket("update t_order set a = 1 where id = 1",
                "update t_order set a = 0 where id = 1");
        long id = rollbackOf(origin, "数据写错了，需要还原");

        assertThat(approve(LEAD01_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acknowledgeRisk(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> accepted = requestExecution(OPS02_TOKEN, id);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(awaitTerminal(id)).as("回滚单自己执行成功").isEqualTo("SUCCEEDED");
        assertThat(status(origin)).as("任务 11 的联动必须真的接上").isEqualTo("ROLLED_BACK");
        assertThat(stub.executedSql).containsExactly("update t_order set a = 0 where id = 1");
        assertThat(jdbc.queryForList(
                "select event_type from ticket_event where ticket_id = ? order by id", String.class, origin))
                .contains("ROLLED_BACK");
    }

    // ------------------------------------------------------------------ 事件与审计

    @Test
    void the_rollback_submission_is_recorded_in_the_event_stream_and_the_audit_chain() {
        long origin = succeededTicket();

        long id = rollbackOf(origin, REASON);

        String submittedDetail = jdbc.queryForObject("""
                select detail_json from ticket_event where ticket_id = ? and event_type = 'SUBMITTED'
                """, String.class, id);
        assertThat(submittedDetail).contains("\"rolledBackFrom\":" + origin);

        Map<String, Object> audit = jdbc.queryForMap("""
                select operator_id, action, object_id, payload_json from audit_event
                 where event_type = 'TICKET_ROLLBACK_SUBMITTED'
                """);
        assertThat(audit.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat(((Number) audit.get("operator_id")).longValue()).as("发起人").isEqualTo(4L);
        assertThat(audit.get("action")).isEqualTo("ROLLBACK");
        assertThat((String) audit.get("payload_json"))
                .as("审计载荷必须含原单 id")
                .contains("\"originTicketId\":" + origin);
    }

    // ------------------------------------------------------------------ 夹具

    /** 一张 {@code SUCCEEDED} 的 DML 原单：语句与回滚 SQL 都取自默认值。 */
    private long succeededTicket() {
        return succeededTicket("update t_order set a = 1 where id = 1", "update t_order set a = 0 where id = 1");
    }

    private long succeededTicket(String sql, String rollbackSql) {
        return insertOrigin(sql, null, rollbackSql, "L1", "SUCCEEDED");
    }

    /** DDL 原单：{@code rollbackSql} 通常为 {@code null}，只有文本方案 {@code rollbackPlan}。 */
    private long succeededTicket(String sql, String rollbackPlan, String rollbackSql) {
        return insertOrigin(sql, rollbackPlan, rollbackSql, "L1", "SUCCEEDED");
    }

    private long succeededTicketWithLevel(String level) {
        return insertOrigin("update t_order set a = 1 where id = 1", null,
                "update t_order set a = 0 where id = 1", level, "SUCCEEDED");
    }

    /**
     * 直接插一张原单（不跑真执行）。
     *
     * <p>必须同时插 {@code ticket_statement}：回滚的表名命中校验读的是原单的语句，只插 ticket 会让每个用例都
     * 撞上"回滚 SQL 未命中目标表"。
     */
    private long insertOrigin(String sql, String rollbackPlan, String rollbackSql, String riskLevel, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    insert into ticket
                        (system_id, module_id, datasource_id, ticket_type, status, risk_level,
                         risk_snapshot_hash, title, change_reason, rollback_plan, rollback_sql,
                         statement_fingerprint, snapshot_enabled, expected_execute_at, submitter_id)
                    values (1, 201, 101, 'NORMAL', ?, ?, ?, '订单数据变更', '修复线上数据', ?, ?, ?, 1, null, 1)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, status);
            statement.setString(2, riskLevel);
            statement.setString(3, PLACEHOLDER_HASH);
            statement.setString(4, rollbackPlan);
            statement.setString(5, rollbackSql);
            statement.setString(6, PLACEHOLDER_HASH);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入原单未返回自增主键");
        }
        long originId = key.longValue();

        int seq = 1;
        for (String piece : SqlText.splitStatements(sql)) {
            Classification classification = CLASSIFIER.classify(piece);
            jdbc.update("""
                    insert into ticket_statement
                        (ticket_id, seq_no, sql_text, statement_type, risk_level, status)
                    values (?, ?, ?, ?, ?, ?)
                    """, originId, seq++, piece,
                    classification.firstKeyword() == null
                            ? "UNKNOWN"
                            : classification.firstKeyword().toUpperCase(Locale.ROOT),
                    riskLevel,
                    "SUCCEEDED".equals(status) ? "SUCCEEDED" : "PENDING");
        }
        return originId;
    }

    // ------------------------------------------------------------------ HTTP 助手

    /** 以 ops02（系统 1 主运维）发起回滚。 */
    private ResponseEntity<String> rollback(long originId, String reason) {
        return rollbackAs(OPS02_TOKEN, originId, reason, null);
    }

    private ResponseEntity<String> rollback(long originId, String reason, String suppliedSql) {
        return rollbackAs(OPS02_TOKEN, originId, reason, suppliedSql);
    }

    private ResponseEntity<String> rollbackAs(String token, long originId, String reason, String suppliedSql) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("rollbackSql", suppliedSql);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        try {
            return rest.exchange("/api/v1/tickets/" + originId + "/rollback", HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化回滚请求体", e);
        }
    }

    /** 发起回滚并断言 201，返回新工单 ID。 */
    private long rollbackOf(long originId, String reason) {
        return rollbackOf(originId, reason, null);
    }

    private long rollbackOf(long originId, String reason, String suppliedSql) {
        ResponseEntity<String> response = rollback(originId, reason, suppliedSql);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    private ResponseEntity<String> approve(String token, long ticketId) {
        return postJson(token, "/api/v1/tickets/" + ticketId + "/approve", "{\"comment\":\"通过\"}");
    }

    private ResponseEntity<String> acknowledgeRisk(String token, long ticketId) {
        return postJson(token, "/api/v1/tickets/" + ticketId + "/risk-ack", null);
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

    // ------------------------------------------------------------------ 查询助手

    private String column(long ticketId, String column) {
        return jdbc.queryForObject("select " + column + " from ticket where id = ?", String.class, ticketId);
    }

    private List<String> sqlTexts(long ticketId) {
        return statementsOf(ticketId).stream().map(StatementRow::sqlText).toList();
    }

    private List<String> statementStatuses(long ticketId) {
        return statementsOf(ticketId).stream().map(StatementRow::status).toList();
    }

    private int rollbackTicketCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from ticket where ticket_type = 'ROLLBACK'", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 轮询到终态，并等执行锁释放。
     *
     * <p>锁在 {@code finally} 里释放，它消失才代表事件与审计也写完了——只判状态会在最后几毫秒里返回，
     * 让审计断言随机失败。
     */
    private String awaitTerminal(long ticketId) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = status(ticketId);
            boolean lockHeld = jdbc.queryForObject(
                    "select count(*) from datasource_exec_lock where ticket_id = ?", Integer.class, ticketId) > 0;
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
        throw new AssertionError("工单 " + ticketId + " 未在 10s 内进入终态，当前=" + status(ticketId));
    }
}
