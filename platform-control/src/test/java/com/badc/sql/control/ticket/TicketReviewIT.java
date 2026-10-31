package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.JwtTestSupport;
import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 审批链的端到端用例。
 *
 * <p>工单一律由真实提交接口产生（{@link #l1Ticket()} / {@link #l3Ticket()}）：审批要拿工单里的
 * {@code risk_level}、{@code risk_snapshot_hash} 与 {@code system_id} 做判断，手写这些值会让用例验证的是
 * 夹具而不是提交与审批的契约。只有"提交人就是本系统运维"这种正常提交流程造不出来的单，才用 SQL 改一列。
 *
 * <p>覆盖的闸门：状态必须可审（否则 409）、提交人不可自审（403）、跨系统运维不可审（403）、管理员不可审
 * （403）、L3 一级只收主/备运维（组长 403）、L3 二级只收组长（403）、L3 一级通过前系统必须仍有组长
 * （409）、驳回必须写意见（400）、重复审批/重复审核行是冲突（409）、迁移是乐观并发（0 行即 409）。
 */
class TicketReviewIT extends TicketITBase {

    private static final String L1_SQL = "update t_order set a=1 where id=1";
    private static final String L1_ROLLBACK_SQL = "update t_order set a=0 where id=1";
    private static final String L3_SQL = "drop table t_order";
    private static final String L3_ROLLBACK_PLAN = "将从快照重建表结构";

    /** 备运维（本类按需补，验证 BACKUP_OPS 对 L3 一级与单级审核都有效）。 */
    private static final String BACKUP_TOKEN = JwtTestSupport.token(6L, "backup01");

    /** 与 seed 夹具一致的 BCrypt 哈希（明文 Passw0rd!）。 */
    private static final String PASSWORD_HASH =
            "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW";

    @Autowired
    private TicketReviewMapper reviewMapper;

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

    // ------------------------------------------------------------------ 一级：L1/L2 单一次审完

    @Test
    void a_l1_ticket_is_approved_by_one_operator_review() throws Exception {
        long id = l1Ticket();

        ResponseEntity<String> response = approve(OPS02_TOKEN, id, "一级审核通过");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("status").asText()).isEqualTo("APPROVED");
        assertThat(body.path("riskLevel").asText()).isEqualTo("L1");

        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(reviewCount(id)).isEqualTo(1);
        assertThat(reviewLevels(id)).containsExactly(1);
        assertThat(reviewerIdAt(id, 1)).isEqualTo(4L);
        assertThat(decisionAt(id, 1)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select approved_at from ticket where id = ?", Timestamp.class, id))
                .as("单级审批通过必须置 approved_at")
                .isNotNull();

        // 事件：APPROVED_L1，actor 是审批人，明细带审核意见与级别。
        String detail = jdbc.queryForObject("""
                select detail_json from ticket_event
                 where ticket_id = ? and event_type = 'APPROVED_L1'
                """, String.class, id);
        JsonNode event = objectMapper.readTree(detail);
        assertThat(event.path("comment").asText()).isEqualTo("一级审核通过");
        assertThat(event.path("reviewLevel").asInt()).isEqualTo(1);
        assertThat(event.path("riskLevel").asText()).isEqualTo("L1");
        assertThat(jdbc.queryForObject("""
                select actor_id from ticket_event
                 where ticket_id = ? and event_type = 'APPROVED_L1'
                """, Long.class, id)).isEqualTo(4L);

        // 风险确认：一级写 REVIEW，快照哈希取工单当前值。
        Map<String, Object> ack = jdbc.queryForMap("""
                select operator_id, risk_snapshot_hash from risk_acknowledgement
                 where ticket_id = ? and stage = 'REVIEW'
                """, id);
        assertThat(((Number) ack.get("operator_id")).longValue()).isEqualTo(4L);
        assertThat(ack.get("risk_snapshot_hash")).isEqualTo(riskSnapshotHashOf(id));

        // 审计：TICKET_APPROVED，action 带级别。
        Map<String, Object> audit = jdbc.queryForMap("""
                select action, payload_json from audit_event
                 where event_type = 'TICKET_APPROVED' and object_id = ?
                """, String.valueOf(id));
        assertThat(audit.get("action")).isEqualTo("l1");
        assertThat((String) audit.get("payload_json"))
                .contains("\"reviewLevel\":1")
                .contains("\"riskLevel\":\"L1\"");
    }

    @Test
    void a_lead_can_approve_an_l1_ticket_in_one_level() {
        long id = l1Ticket();

        ResponseEntity<String> response = approve(LEAD01_TOKEN, id, "组长直接通过");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(reviewerIdAt(id, 1)).isEqualTo(5L);
    }

    // ------------------------------------------------------------------ 两级：L3 运维一级 + 组长终审

    @Test
    void an_l3_ticket_needs_the_lead_after_the_operator() throws Exception {
        long id = l3Ticket();

        ResponseEntity<String> first = approve(OPS02_TOKEN, id, "运维一级通过");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(first.getBody()).path("status").asText()).isEqualTo("REVIEWING_L2");
        assertThat(status(id)).as("运维审完不是终态").isEqualTo("REVIEWING_L2");
        assertThat(reviewLevels(id)).containsExactly(1);
        assertThat(reviewerIdAt(id, 1)).isEqualTo(4L);
        assertThat(decisionAt(id, 1)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select approved_at from ticket where id = ?", Timestamp.class, id))
                .as("一级通过还不能置 approved_at")
                .isNull();

        ResponseEntity<String> second = approve(LEAD01_TOKEN, id, "组长终审通过");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(second.getBody()).path("status").asText()).isEqualTo("APPROVED");
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(reviewLevels(id)).containsExactly(1, 2);
        assertThat(reviewerIdAt(id, 2)).isEqualTo(5L);
        assertThat(ackStages(id)).contains("REVIEW", "L3_REVIEW");
        assertThat(jdbc.queryForObject("select approved_at from ticket where id = ?", Timestamp.class, id))
                .isNotNull();
        assertThat(eventTypes(id)).contains("APPROVED_L1", "APPROVED_L2");
        assertThat(jdbc.queryForList("""
                select action from audit_event
                 where event_type = 'TICKET_APPROVED' and object_id = ?
                 order by seq
                """, String.class, String.valueOf(id))).containsExactly("l1", "l2");
    }

    /** §4.3-2：提交人 ≠ 审核人。研发没有审核权，这一条先被角色闸拦下。 */
    @Test
    void a_developer_cannot_approve_any_ticket() {
        long id = l1Ticket();

        assertThat(approve(DEV_TOKEN, id, "自己审").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(reviewCount(id)).isZero();
        assertThat(ackStages(id)).containsExactly("SUBMIT");
    }

    /**
     * 自审闸的独立验证：直接造一张"提交人就是本系统运维"的工单（§4.2 下正常提交流程不会产生，
     * 但回滚工单与切片 3 的运维通道会产生），必须仍然被拒。
     */
    @Test
    void the_submitter_is_refused_even_when_they_are_an_operator_of_that_system() {
        long id = l1Ticket();
        jdbc.update("update ticket set submitter_id = 4 where id = ?", id);

        ResponseEntity<String> response = approve(OPS02_TOKEN, id, "自己的单");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("FORBIDDEN");
        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(reviewCount(id)).isZero();
    }

    /** L3 的一级审核按 §7.1「运维一级 + 组长终审」拆开：组长不做一级，只做终审。 */
    @Test
    void a_lead_cannot_be_the_first_level_reviewer_of_an_l3_ticket() {
        long id = l3Ticket();

        ResponseEntity<String> response = approve(LEAD01_TOKEN, id, "组长来做一级");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(reviewCount(id)).isZero();
    }

    /**
     * 一级由主运维签，主运维再去点终审必须被拒：L3 的二级只收 {@code OPS_LEAD}，角色分离让
     * "两级同一人"在结构上不可能。
     */
    @Test
    void only_a_lead_can_close_level_two_after_an_operator_signed_level_one() {
        long id = l3Ticket();
        assertThat(approve(OPS02_TOKEN, id, "一级通过").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewerIdAt(id, 1)).isEqualTo(4L);

        ResponseEntity<String> second = approve(OPS02_TOKEN, id, "主运维想终审");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("REVIEWING_L2");
        assertThat(reviewLevels(id)).containsExactly(1);
    }

    /** 只有本系统的运维能审：ops01 是系统 2 的运维，审系统 1 的单 → 403。 */
    @Test
    void an_operator_of_another_system_cannot_review() {
        long id = l1Ticket();

        ResponseEntity<String> response = approve(OPS_TOKEN, id, "跨系统审批");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).as("被拒的审批不能改状态").isEqualTo("REVIEWING_L1");
        assertThat(reviewCount(id)).isZero();
    }

    /**
     * 管理员只能配置与授权，不参与审批（§4.2）。
     *
     * <p>夹具里 admin01 没有 {@code sys_system_operator} 归属，第一段 403 来自"不是本系统运维"的成员判定。
     * 为了让 {@code TicketReviewService} 里那道显式 ADMIN 闸真正执行，这里临时给他插一条归属行
     * （{@code try/finally} 删除）：成员判定放行后，必须仍然 403，且消息是 ADMIN 那条而不是成员那条。
     */
    @Test
    void an_admin_cannot_review() {
        long id = l1Ticket();

        ResponseEntity<String> byMembership = approve(ADMIN_TOKEN, id, "管理员审批");
        assertThat(byMembership.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byMembership.getBody()).contains("只能审核自己负责的系统");

        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (1, 3, 'BACKUP')
                """);
        try {
            ResponseEntity<String> byAdminGate = approve(ADMIN_TOKEN, id, "管理员审批");

            assertThat(byAdminGate.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(byAdminGate.getBody())
                    .as("成员判定已放行，剩下的必须是 ADMIN 闸")
                    .contains("管理员")
                    .doesNotContain("只能审核自己负责的系统");
            assertThat(status(id)).isEqualTo("REVIEWING_L1");
            assertThat(reviewCount(id)).isZero();
            assertThat(ackStages(id)).containsExactly("SUBMIT");
        } finally {
            jdbc.update("delete from sys_system_operator where system_id = 1 and user_id = 3");
        }
    }

    /**
     * 备运维与主运维同权（§4.2）：{@code BACKUP_OPS} 既能做 L3 的一级，也能做 L1/L2 的单级审核。
     *
     * <p>夹具里没有备运维，这里临时建一个（{@code finally} 清理），避免影响其它用例的用户集合。
     */
    @Test
    void a_backup_operator_can_review_an_l3_first_level_and_an_l1_ticket() {
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (6, 'backup01', ?, 'BACKUP_OPS', '备运维')
                """, PASSWORD_HASH);
        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (1, 6, 'BACKUP')
                """);
        try {
            long l3 = l3Ticket();
            assertThat(approve(BACKUP_TOKEN, l3, "备运维一级").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(l3)).isEqualTo("REVIEWING_L2");
            assertThat(reviewerIdAt(l3, 1)).isEqualTo(6L);

            long l1 = l1Ticket();
            assertThat(approve(BACKUP_TOKEN, l1, "备运维单级").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(l1)).isEqualTo("APPROVED");
            assertThat(reviewerIdAt(l1, 1)).isEqualTo(6L);
        } finally {
            jdbc.update("delete from sys_system_operator where system_id = 1 and user_id = 6");
            jdbc.update("delete from sys_user where id = 6");
        }
    }

    // ------------------------------------------------------------------ 驳回

    @Test
    void a_rejection_requires_a_comment() {
        long id = l1Ticket();

        ResponseEntity<String> blank = reject(OPS02_TOKEN, id, "");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody()).contains("INVALID_ARGUMENT");

        ResponseEntity<String> whitespace = reject(OPS02_TOKEN, id, "   ");
        assertThat(whitespace.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> missing = postJson(OPS02_TOKEN,
                "/api/v1/tickets/" + id + "/reject", "{}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(reviewCount(id)).isZero();
    }

    @Test
    void a_rejection_writes_the_review_row_event_and_audit() throws Exception {
        long id = l1Ticket();

        ResponseEntity<String> response = reject(OPS02_TOKEN, id, "变更说明不清");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).path("status").asText()).isEqualTo("REJECTED");
        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(reviewCount(id)).isEqualTo(1);
        assertThat(reviewLevels(id)).containsExactly(1);
        assertThat(decisionAt(id, 1)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select comment from ticket_review where ticket_id = ?", String.class, id))
                .isEqualTo("变更说明不清");
        assertThat(eventTypes(id)).contains("REJECTED");
        String detail = jdbc.queryForObject("""
                select detail_json from ticket_event
                 where ticket_id = ? and event_type = 'REJECTED'
                """, String.class, id);
        assertThat(objectMapper.readTree(detail).path("comment").asText()).isEqualTo("变更说明不清");
        Map<String, Object> audit = jdbc.queryForMap("""
                select action, payload_json from audit_event
                 where event_type = 'TICKET_REJECTED' and object_id = ?
                """, String.valueOf(id));
        assertThat(audit.get("action")).isEqualTo("reject");
        assertThat((String) audit.get("payload_json")).contains("变更说明不清");
    }

    @Test
    void approving_a_rejected_ticket_is_a_conflict_not_a_silent_noop() {
        long id = l1Ticket();
        assertThat(reject(OPS02_TOKEN, id, "变更说明不清").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = approve(OPS02_TOKEN, id, "还想通过");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(reviewCount(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 驳回同样按级别分角色

    /**
     * L3 的第二级是组长终审，它既包括"批准执行"也包括"驳回不执行"。一级运维替组长做这个决定，等于终审
     * 环节被跳过——只是方向是拒绝。
     */
    @Test
    void an_operator_cannot_reject_an_l3_ticket_at_level_two() {
        long id = l3Ticket();
        assertThat(approve(OPS02_TOKEN, id, "一级通过").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = reject(OPS02_TOKEN, id, "主运维替组长驳回");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("REVIEWING_L2");
        assertThat(reviewLevels(id)).containsExactly(1);
    }

    @Test
    void a_lead_can_reject_an_l3_ticket_at_level_two() throws Exception {
        long id = l3Ticket();
        assertThat(approve(OPS02_TOKEN, id, "一级通过").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = reject(LEAD01_TOKEN, id, "组长终审驳回");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(response.getBody()).path("status").asText()).isEqualTo("REJECTED");
        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(reviewLevels(id)).containsExactly(1, 2);
        assertThat(reviewerIdAt(id, 1)).isEqualTo(4L);
        assertThat(reviewerIdAt(id, 2)).isEqualTo(5L);
        assertThat(decisionAt(id, 2)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select approved_at from ticket where id = ?", Timestamp.class, id))
                .isNull();
        // 驳回不是风险知悉：不写 L3_REVIEW 确认行。
        assertThat(ackStages(id)).containsExactly("SUBMIT", "REVIEW");
        Map<String, Object> audit = jdbc.queryForMap("""
                select action, payload_json from audit_event
                 where event_type = 'TICKET_REJECTED' and object_id = ?
                """, String.valueOf(id));
        assertThat(audit.get("action")).isEqualTo("reject");
        assertThat((String) audit.get("payload_json"))
                .contains("组长终审驳回")
                .contains("\"reviewLevel\":2");
    }

    /**
     * §4.3-2「两级不可同一人」的独立用例。
     *
     * <p>按当前单值角色模型，ops02 是主运维，二级角色闸已经会挡住他——那样这条用例就测不到"同一人"规则
     * 本身。这里在批完一级后把 ops02 的角色临时改成 {@code OPS_LEAD}（模拟角色模型将来变多值／账号同时
     * 具备两种资格），让角色闸放行，唯一能拦住他的就是"二级不得等于一级审核人"这道纵深防御。
     */
    @Test
    void an_operator_who_signed_level_one_cannot_reject_level_two() {
        long id = l3Ticket();
        assertThat(approve(OPS02_TOKEN, id, "一级通过").getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("update sys_user set role = 'OPS_LEAD' where id = 4");
        try {
            ResponseEntity<String> response = reject(OPS02_TOKEN, id, "同一人驳回终审");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).contains("同一人");
            assertThat(status(id)).isEqualTo("REVIEWING_L2");
            assertThat(reviewLevels(id)).containsExactly(1);
        } finally {
            jdbc.update("update sys_user set role = 'PRIMARY_OPS' where id = 4");
        }
    }

    // ------------------------------------------------------------------ 乐观并发与重复审核

    @Test
    void a_second_approval_after_the_status_moved_is_a_conflict() {
        long id = l1Ticket();
        assertThat(approve(OPS02_TOKEN, id, "第一次").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = approve(OPS02_TOKEN, id, "第二次");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(id)).isEqualTo("APPROVED");
        assertThat(reviewCount(id)).isEqualTo(1);
    }

    /**
     * 唯一键 {@code uk_ticket_review(ticket_id, review_level)} 是最后一道闸：状态被拨回待审后再次审批，
     * 必须被重复审核行拦下（409），而不是写第二条一级记录或静默覆盖。
     */
    @Test
    void a_duplicate_review_row_at_the_same_level_is_a_conflict() {
        long id = l1Ticket();
        assertThat(approve(OPS02_TOKEN, id, "第一次").getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("update ticket set status = 'REVIEWING_L1', approved_at = null where id = ?", id);

        ResponseEntity<String> second = approve(OPS02_TOKEN, id, "第二次");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        assertThat(reviewCount(id)).isEqualTo(1);
    }

    /**
     * 状态迁移的乐观并发：{@code update ... where status = ?} 只在读到的状态仍是当前状态时生效。
     * 两个组长同时终审时，只有第一个能更新到行；第二个拿到 0 行，服务层据此报 409。
     */
    @Test
    void the_status_update_only_applies_while_the_expected_status_is_still_current() {
        long id = l1Ticket();

        int first = reviewMapper.moveStatus(id, TicketStatus.REVIEWING_L1, TicketStatus.APPROVED, Instant.now());
        int second = reviewMapper.moveStatus(id, TicketStatus.REVIEWING_L1, TicketStatus.APPROVED, Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).as("状态已被改走，第二个审批人必须拿到 0 行而不是覆盖").isZero();
        assertThat(status(id)).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ L3 终审可用性

    /**
     * §7.1：组长可能在提单后被撤销。L3 一级通过前必须复查该系统仍有 LEAD，否则工单会停在
     * {@code REVIEWING_L2} 无人可审——最坏的结果。
     */
    @Test
    void an_l3_first_level_approval_is_refused_when_the_system_has_no_lead() {
        long id = l3Ticket();
        jdbc.update("delete from sys_system_operator where system_id = 1 and operator_type = 'LEAD'");
        try {
            ResponseEntity<String> response = approve(OPS02_TOKEN, id, "一级通过");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("组长");
            assertThat(status(id)).isEqualTo("REVIEWING_L1");
            assertThat(reviewCount(id)).isZero();
            assertThat(ackStages(id)).containsExactly("SUBMIT");
        } finally {
            jdbc.update("""
                    insert into sys_system_operator (id, system_id, user_id, operator_type)
                    values (11, 1, 5, 'LEAD')
                    """);
        }
    }

    /**
     * 组长被撤销时，驳回一张 L3 必须仍然可用：组长可用性只约束"批准并转入二级"，不该把唯一的止损动作
     * （驳回）也堵死。这道非对称是刻意设计，用例把它钉住。
     */
    @Test
    void an_l3_ticket_can_still_be_rejected_when_the_system_has_no_lead() {
        long id = l3Ticket();
        jdbc.update("delete from sys_system_operator where system_id = 1 and operator_type = 'LEAD'");
        try {
            ResponseEntity<String> response = reject(OPS02_TOKEN, id, "系统已无组长，先驳回");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(status(id)).isEqualTo("REJECTED");
            assertThat(decisionAt(id, 1)).isEqualTo("REJECTED");
        } finally {
            jdbc.update("""
                    insert into sys_system_operator (id, system_id, user_id, operator_type)
                    values (11, 1, 5, 'LEAD')
                    """);
        }
    }

    // ------------------------------------------------------------------ 审计不夹带凭证

    @Test
    void the_approval_is_audited_without_any_credential() {
        long id = l1Ticket();
        assertThat(approve(OPS02_TOKEN, id, "同意执行").getStatusCode()).isEqualTo(HttpStatus.OK);

        String payload = jdbc.queryForObject("""
                select payload_json from audit_event
                 where event_type = 'TICKET_APPROVED' and object_id = ?
                """, String.class, String.valueOf(id));

        assertThat(payload)
                .contains("\"riskLevel\":\"L1\"")
                .doesNotContain("readonly_user")
                .doesNotContain("readonly-pass")
                .doesNotContain("password");
    }

    // ------------------------------------------------------------------ 404

    @Test
    void approving_a_missing_ticket_is_not_found() {
        ResponseEntity<String> response = approve(OPS02_TOKEN, 987654L, "不存在的单");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("NOT_FOUND");
    }

    // ------------------------------------------------------------------ helpers

    /** 真实提交一张 L1 工单（估算 1 行 → L1），返回 ID。 */
    private long l1Ticket() {
        long id = submittedTicket(L1_SQL, null, L1_ROLLBACK_SQL);
        assertThat(riskLevelOf(id)).as("夹具必须是 L1").isEqualTo("L1");
        return id;
    }

    /** 真实提交一张 L3 工单（drop table 结构性破坏恒 L3），返回 ID。 */
    private long l3Ticket() {
        long id = submittedTicket(L3_SQL, L3_ROLLBACK_PLAN, null);
        assertThat(riskLevelOf(id)).as("夹具必须是 L3").isEqualTo("L3");
        return id;
    }

    private long submittedTicket(String sql, String rollbackPlan, String rollbackSql) {
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body(sql, rollbackPlan, rollbackSql));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(response);
        assertThat(status(id)).isEqualTo("REVIEWING_L1");
        return id;
    }

    private ResponseEntity<String> approve(String token, long ticketId, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (comment != null) {
            body.put("comment", comment);
        }
        return postJson(token, "/api/v1/tickets/" + ticketId + "/approve", json(body));
    }

    private ResponseEntity<String> reject(String token, long ticketId, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comment", comment);
        return postJson(token, "/api/v1/tickets/" + ticketId + "/reject", json(body));
    }

    private ResponseEntity<String> postJson(String token, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    private int reviewCount(long ticketId) {
        return jdbc.queryForObject("select count(*) from ticket_review where ticket_id = ?",
                Integer.class, ticketId);
    }

    private List<Integer> reviewLevels(long ticketId) {
        return jdbc.queryForList("select review_level from ticket_review where ticket_id = ? order by review_level",
                Integer.class, ticketId);
    }

    private long reviewerIdAt(long ticketId, int level) {
        return jdbc.queryForObject("""
                select reviewer_id from ticket_review where ticket_id = ? and review_level = ?
                """, Long.class, ticketId, level);
    }

    private String decisionAt(long ticketId, int level) {
        return jdbc.queryForObject("""
                select decision from ticket_review where ticket_id = ? and review_level = ?
                """, String.class, ticketId, level);
    }

    private List<String> ackStages(long ticketId) {
        return jdbc.queryForList("select stage from risk_acknowledgement where ticket_id = ? order by id",
                String.class, ticketId);
    }

    private List<String> eventTypes(long ticketId) {
        return jdbc.queryForList("select event_type from ticket_event where ticket_id = ? order by id",
                String.class, ticketId);
    }

    private String riskLevelOf(long ticketId) {
        return jdbc.queryForObject("select risk_level from ticket where id = ?", String.class, ticketId);
    }

    private String riskSnapshotHashOf(long ticketId) {
        return jdbc.queryForObject("select risk_snapshot_hash from ticket where id = ?", String.class, ticketId);
    }
}
