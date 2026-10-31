package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 执行前风险确认闸的端到端用例。
 *
 * <p>要钉住的是<b>「研发确认过的风险」与「执行时的风险」是同一份</b>：确认行里存的是确认那一刻工单的
 * {@code risk_snapshot_hash}，执行前必须拿工单当前哈希去比对，任何变化（改语句、等级升降）都会让旧确认失效。
 *
 * <p>工单一律走真实提交接口（风险等级、快照哈希、归属都由服务端算出）：手写 {@code risk_snapshot_hash}
 * 会让用例验证的是夹具而不是提交与确认的契约。只有"风险在两次确认之间变了"这种正常流程造不出来的场景，
 * 才用 SQL 改一列。
 *
 * <p><b>确认人规则</b>：提交人本人或该系统的运维/组长；管理员不参与执行链（403）；其它人 403。
 * 重复确认不幂等——每次留一行，最新一条才是有效的那条。
 */
class RiskAckIT extends TicketITBase {

    /** 估算 1 行 → L1，走单级审批即可到 APPROVED。 */
    private static final String L1_SQL = "update t_order set a=1 where id=1";
    private static final String L1_ROLLBACK_SQL = "update t_order set a=0 where id=1";

    /** 用例自己带的 User-Agent：验证它被原样落库（而不是被控制器丢掉）。 */
    private static final String USER_AGENT = "RiskAckIT/1.0";

    /** 与 seed 夹具一致的 BCrypt 哈希（明文 Passw0rd!）。 */
    private static final String PASSWORD_HASH =
            "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW";

    /** 模拟"工单的风险在两次确认之间变了"：换一个合法的 64 位十六进制哈希。 */
    private static final String CHANGED_HASH = "b".repeat(64);

    @Autowired
    private RiskAckService riskAckService;

    @BeforeEach
    void clearTickets() {
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket_review");
        jdbc.update("delete from ticket");
        jdbc.update("delete from audit_event");
    }

    // ------------------------------------------------------------------ 正常流

    @Test
    void acknowledging_at_the_execute_stage_is_recorded_with_ip_and_ua() throws Exception {
        long id = approvedTicket();

        ResponseEntity<String> response = ack(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("ticketId").asLong()).isEqualTo(id);
        assertThat(body.path("stage").asText()).as("端点只有执行阶段").isEqualTo("EXECUTE");
        assertThat(body.path("riskSnapshotHash").asText())
                .as("哈希必须取自工单当前值，而不是请求体")
                .isEqualTo(riskSnapshotHashOf(id));
        Instant acknowledgedAt = Instant.parse(body.path("acknowledgedAt").asText());

        Map<String, Object> row = latestExecuteAck(id);
        assertThat(((Number) row.get("operator_id")).longValue())
                .as("提交人本人可以确认自己的工单")
                .isEqualTo(1L);
        assertThat(row.get("risk_snapshot_hash")).isEqualTo(riskSnapshotHashOf(id));
        assertThat(row.get("ip")).as("来源 IP 必须落库").isNotNull();
        assertThat(row.get("user_agent")).isEqualTo(USER_AGENT);
        assertThat(storedCreatedAt(id))
                .as("响应里的确认时间就是落库的那一行")
                .isEqualTo(acknowledgedAt);

        // 提交写了一行 SUBMIT，审批写了一行 REVIEW，执行前确认补一行 EXECUTE。
        assertThat(ackStages(id)).containsExactly("SUBMIT", "REVIEW", "EXECUTE");
    }

    // ------------------------------------------------------------------ 执行前校验

    /**
     * 只有 SUBMIT 确认时不能执行：提交那一刻的确认是"我要提交"，不是"我知道这条语句现在会怎么跑"。
     */
    @Test
    void a_submit_stage_acknowledgement_does_not_authorise_execution() {
        long id = submittedTicket();

        assertThatThrownBy(() -> riskAckService.assertExecuteAckCurrent(id, riskSnapshotHashOf(id)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(e.getMessage()).contains("必须完成风险确认");
                });
    }

    /** 确认之后风险变了（改了语句或等级升降），旧确认不再放行。 */
    @Test
    void an_acknowledgement_for_an_older_risk_hash_does_not_authorise_execution() {
        long id = approvedTicket();
        assertThat(ack(DEV_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatThrownBy(() -> riskAckService.assertExecuteAckCurrent(id, CHANGED_HASH))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(e.getMessage()).contains("风险信息已变更");
                });
    }

    @Test
    void a_matching_hash_passes_the_execution_gate() {
        long id = approvedTicket();
        assertThat(ack(DEV_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThatCode(() -> riskAckService.assertExecuteAckCurrent(id, riskSnapshotHashOf(id)))
                .doesNotThrowAnyException();
    }

    /**
     * 最新一条说了算：确认后风险变了、用户又确认了一次，旧哈希校验必须失败、新哈希通过。
     * 若实现按 {@code created_at} 或直接取第一条，这里就会红。
     */
    @Test
    void the_latest_acknowledgement_wins() {
        long id = approvedTicket();
        String oldHash = riskSnapshotHashOf(id);
        assertThat(ack(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 模拟风险变化：整单快照哈希被重新计算（改语句、等级升降都走这条路径）。
        jdbc.update("update ticket set risk_snapshot_hash = ? where id = ?", CHANGED_HASH, id);
        assertThat(ack(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(executeAckCount(id)).as("确认不幂等：两次确认留两行").isEqualTo(2);
        assertThat(latestExecuteAck(id).get("risk_snapshot_hash")).isEqualTo(CHANGED_HASH);

        assertThatThrownBy(() -> riskAckService.assertExecuteAckCurrent(id, oldHash))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("风险信息已变更");
        assertThatCode(() -> riskAckService.assertExecuteAckCurrent(id, CHANGED_HASH))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ 状态闸

    @Test
    void an_unapproved_ticket_cannot_be_acknowledged() {
        long id = submittedTicket();

        ResponseEntity<String> response = ack(OPS02_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("APPROVED");
        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(executeAckCount(id)).isZero();
        assertThat(jdbc.queryForList("select 1 from audit_event where event_type = 'RISK_ACKNOWLEDGED'",
                Integer.class)).isEmpty();
    }

    @Test
    void acknowledging_a_missing_ticket_is_not_found() {
        ResponseEntity<String> response = ack(OPS02_TOKEN, 987654L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("NOT_FOUND");
    }

    // ------------------------------------------------------------------ 谁可以确认

    @Test
    void an_operator_of_the_ticket_system_can_acknowledge_but_an_operator_of_another_system_cannot() {
        long own = approvedTicket();
        long foreign = approvedTicket();

        assertThat(ack(OPS02_TOKEN, own).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) latestExecuteAck(own).get("operator_id")).longValue()).isEqualTo(4L);

        ResponseEntity<String> refused = ack(OPS_TOKEN, foreign);
        assertThat(refused.getStatusCode()).as("ops01 是系统 2 的运维").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(executeAckCount(foreign)).isZero();
    }

    /** §4.2：管理员只配置与授权，不可进入执行链——即使被误配成某系统的运维也不行。 */
    @Test
    void an_admin_cannot_acknowledge() {
        long id = approvedTicket();

        ResponseEntity<String> response = ack(ADMIN_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("管理员");
        assertThat(executeAckCount(id)).isZero();
    }

    /** 研发只能确认自己提交的单：把提交人改成另一个研发，原提交人再确认就是 403。 */
    @Test
    void a_developer_who_is_not_the_submitter_cannot_acknowledge() {
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (7, 'dev02', ?, 'DEVELOPER', '研发二号')
                """, PASSWORD_HASH);
        try {
            long id = approvedTicket();
            jdbc.update("update ticket set submitter_id = 7 where id = ?", id);

            ResponseEntity<String> response = ack(DEV_TOKEN, id);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).contains("提交人");
            assertThat(executeAckCount(id)).isZero();
        } finally {
            jdbc.update("delete from sys_user where id = 7");
        }
    }

    // ------------------------------------------------------------------ 审计

    @Test
    void the_acknowledgement_is_audited_with_only_a_hash_prefix_and_no_credential() {
        long id = approvedTicket();
        String hash = riskSnapshotHashOf(id);
        assertThat(ack(DEV_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> audit = jdbc.queryForMap("""
                select action, payload_json from audit_event
                 where event_type = 'RISK_ACKNOWLEDGED' and object_id = ?
                """, String.valueOf(id));

        assertThat(audit.get("action")).isEqualTo("execute");
        String payload = (String) audit.get("payload_json");
        assertThat(payload)
                .contains("\"stage\":\"EXECUTE\"")
                .contains(hash.substring(0, 12))
                .as("审计只留前 12 位，整串哈希是噪音")
                .doesNotContain(hash)
                .doesNotContain("readonly_user")
                .doesNotContain("dml_user")
                .doesNotContain("password");
    }

    // ------------------------------------------------------------------ helpers

    /** 真实提交一张 L1 工单并审批通过（APPROVED），返回 ID。 */
    private long approvedTicket() {
        long id = submittedTicket();
        ResponseEntity<String> approved = postJson(OPS02_TOKEN,
                "/api/v1/tickets/" + id + "/approve", "{\"comment\":\"通过\"}");
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("APPROVED");
        return id;
    }

    /** 真实提交一张 L1 工单（停在 REVIEWING_L1），返回 ID。 */
    private long submittedTicket() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body(L1_SQL, L1_ROLLBACK_SQL));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(response);
        assertThat(riskLevelOf(id)).as("夹具必须是 L1").isEqualTo("L1");
        return id;
    }

    /** 确认执行风险；请求体为空，User-Agent 用于验证留痕。 */
    private ResponseEntity<String> ack(String token, long ticketId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return rest.exchange("/api/v1/tickets/" + ticketId + "/risk-ack", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> postJson(String token, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private List<Map<String, Object>> executeAcks(long ticketId) {
        return jdbc.queryForList("""
                select * from risk_acknowledgement
                 where ticket_id = ? and stage = 'EXECUTE'
                 order by id
                """, ticketId);
    }

    private Map<String, Object> latestExecuteAck(long ticketId) {
        List<Map<String, Object>> rows = executeAcks(ticketId);
        assertThat(rows).as("应当已有 EXECUTE 确认").isNotEmpty();
        return rows.get(rows.size() - 1);
    }

    private int executeAckCount(long ticketId) {
        return jdbc.queryForObject("""
                select count(*) from risk_acknowledgement where ticket_id = ? and stage = 'EXECUTE'
                """, Integer.class, ticketId);
    }

    /** 最新一条 EXECUTE 确认的落库时间（UTC）。 */
    private Instant storedCreatedAt(long ticketId) {
        Timestamp createdAt = jdbc.queryForObject("""
                select created_at from risk_acknowledgement
                 where ticket_id = ? and stage = 'EXECUTE'
                 order by id desc
                 limit 1
                """, Timestamp.class, ticketId);
        return createdAt.toInstant();
    }

    private List<String> ackStages(long ticketId) {
        return jdbc.queryForList("select stage from risk_acknowledgement where ticket_id = ? order by id",
                String.class, ticketId);
    }

    private String riskSnapshotHashOf(long ticketId) {
        return jdbc.queryForObject("select risk_snapshot_hash from ticket where id = ?", String.class, ticketId);
    }

    private String riskLevelOf(long ticketId) {
        return jdbc.queryForObject("select risk_level from ticket where id = ?", String.class, ticketId);
    }
}
