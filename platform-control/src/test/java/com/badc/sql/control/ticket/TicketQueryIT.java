package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.JwtTestSupport;
import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工单详情与列表的端到端用例。
 *
 * <p>本类只验证<b>读路径</b>：可见性口径、403/404 的区分、分页与详情里的子表内容。工单数据一律由任务 7a
 * 的提交接口真实产生（{@link #submitAs(String)}）；只有在"另一个系统的工单"这种提交接口造不出来的场景才
 * 直接写 SQL——因为研发只能对自己可见的数据源提单。
 *
 * <p>每个用例先清空工单表：分页与 {@code total} 的断言必须是确定值，共享历史数据会让它们退化成
 * "至少有一条"。
 */
class TicketQueryIT extends TicketITBase {

    /** 备运维（ticket-fixtures 里没有，本类按需补一个，验证与主运维/组长同口径）。 */
    private static final String BACKUP_TOKEN = JwtTestSupport.token(6L, "backup01");

    /** JWT 合法但用户在 sys_user 里不存在：角色为空，什么都看不到。 */
    private static final String UNKNOWN_USER_TOKEN = JwtTestSupport.token(999L, "ghost");

    /** 系统 2 的模块。直接插系统 2 的工单时必须用它，不能拿系统 1 的模块 201 去配系统 2。 */
    private static final long PAY_MODULE_ID = 202L;

    @BeforeAll
    void addQueryOnlyFixtures() {
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (6, 'backup01', '$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW',
                        'BACKUP_OPS', '备运维')
                """);
        jdbc.update("""
                insert into sys_system_operator (id, system_id, user_id, operator_type)
                values (12, 1, 6, 'BACKUP')
                """);
        // 系统 2 在共享夹具里没有模块；直接插工单需要一条合法的 system_id/module_id 归属。
        jdbc.update("insert into sys_module (id, system_id, name) values (?, 2, ?)",
                PAY_MODULE_ID, "支付查询");
    }

    @BeforeEach
    void clearTickets() {
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket_review");
        jdbc.update("delete from ticket");
    }

    // ------------------------------------------------------------------ 可见性：研发

    @Test
    void a_developer_only_sees_the_tickets_they_submitted() throws Exception {
        long first = submitAs(DEV_TOKEN);
        long second = submitAs(DEV_TOKEN);
        // 另一个提交人的工单（ops01 是系统 2 的运维；研发不能跨系统提单，只能直接造数据）。
        insertTicket(9001L, 2L, 2L, 999L, "系统二工单");

        JsonNode page = listAs(DEV_TOKEN, 1, 20);

        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(2);
        // 新的在前：同一毫秒内 id 是稳定次序。
        assertThat(idsOf(page)).containsExactly(second, first);
        for (JsonNode item : page.path("items")) {
            assertThat(item.path("submitterId").asLong()).isEqualTo(1L);
        }
        assertThat(page.toString()).doesNotContain("系统二工单");
    }

    // ------------------------------------------------------------------ 可见性：运维 / 组长 / 备运维 / 管理员

    @Test
    void an_operator_only_sees_tickets_from_the_systems_they_operate() throws Exception {
        long orderTicket = submitAs(DEV_TOKEN);
        long payTicket = insertTicket(9001L, 2L, 2L, 999L, "系统二工单");

        JsonNode ops02Page = listAs(OPS02_TOKEN, 1, 20);
        assertThat(ops02Page.path("total").asLong()).isEqualTo(1);
        assertThat(idsOf(ops02Page)).containsExactly(orderTicket);

        JsonNode ops01Page = listAs(OPS_TOKEN, 1, 20);
        assertThat(ops01Page.path("total").asLong()).isEqualTo(1);
        assertThat(idsOf(ops01Page)).containsExactly(payTicket);
    }

    @Test
    void the_lead_and_the_backup_operator_share_the_operator_scope() throws Exception {
        long orderTicket = submitAs(DEV_TOKEN);
        insertTicket(9001L, 2L, 2L, 999L, "系统二工单");

        for (String token : List.of(LEAD01_TOKEN, BACKUP_TOKEN)) {
            JsonNode page = listAs(token, 1, 20);
            assertThat(page.path("total").asLong()).isEqualTo(1);
            assertThat(idsOf(page)).containsExactly(orderTicket);
        }
    }

    @Test
    void an_admin_sees_every_ticket() throws Exception {
        long orderTicket = submitAs(DEV_TOKEN);
        long payTicket = insertTicket(9001L, 2L, 2L, 999L, "系统二工单");

        JsonNode page = listAs(ADMIN_TOKEN, 1, 20);

        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(idsOf(page)).containsExactlyInAnyOrder(orderTicket, payTicket);
    }

    @Test
    void a_user_without_a_role_sees_nothing_and_cannot_open_a_ticket() throws Exception {
        long orderTicket = submitAs(DEV_TOKEN);

        JsonNode page = listAs(UNKNOWN_USER_TOKEN, 1, 20);
        assertThat(page.path("total").asLong()).isZero();
        assertThat(page.path("items")).isEmpty();

        assertThat(detail(UNKNOWN_USER_TOKEN, orderTicket).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ 详情：403 与 404

    @Test
    void an_invisible_ticket_is_403_without_leaking_its_body_while_a_missing_one_is_404() throws Exception {
        long payTicket = insertTicket(9001L, 2L, 2L, 999L, "系统二工单");

        ResponseEntity<String> forbidden = detail(DEV_TOKEN, payTicket);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody())
                .contains("FORBIDDEN")
                .doesNotContain("系统二工单")
                .doesNotContain("系统二变更");

        // 同一系统（系统 1）的运维能看，系统 2 的运维不能看。
        assertThat(detail(OPS02_TOKEN, payTicket).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(detail(OPS_TOKEN, payTicket).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> missing = detail(DEV_TOKEN, 424242L);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).contains("NOT_FOUND");
    }

    // ------------------------------------------------------------------ 列表分页

    @Test
    void the_list_is_paginated_and_the_total_counts_only_the_visible_scope() throws Exception {
        long first = submitAs(DEV_TOKEN);
        long second = submitAs(DEV_TOKEN);
        long third = submitAs(DEV_TOKEN);
        // 研发看不到的系统 2 工单：必须在 SQL 里被过滤掉，绝不能先查全表再内存过滤。
        insertTicket(9001L, 2L, 2L, 999L, "系统二工单");

        JsonNode pageOne = listAs(DEV_TOKEN, 1, 2);
        assertThat(pageOne.path("total").asLong()).isEqualTo(3);
        assertThat(pageOne.path("page").asInt()).isEqualTo(1);
        assertThat(pageOne.path("size").asInt()).isEqualTo(2);
        assertThat(pageOne.path("items")).hasSize(2);
        assertThat(idsOf(pageOne)).containsExactly(third, second);

        JsonNode pageTwo = listAs(DEV_TOKEN, 2, 2);
        assertThat(pageTwo.path("total").asLong()).isEqualTo(3);
        assertThat(pageTwo.path("items")).hasSize(1);
        assertThat(idsOf(pageTwo)).containsExactly(first);

        JsonNode clamped = listAs(DEV_TOKEN, 1, 1000);
        assertThat(clamped.path("size").asInt()).isEqualTo(100);
        assertThat(clamped.path("items")).hasSize(3);
        assertThat(clamped.path("total").asLong()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 详情内容

    @Test
    void the_detail_carries_ordered_statements_events_and_the_submission_snapshot() throws Exception {
        ResponseEntity<String> created = postTicket(DEV_TOKEN, body(
                "update t_order set a=1 where id=1;\ndelete from t_order where id=2",
                "update t_order set a=0 where id=1"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(created);

        ResponseEntity<String> response = detail(DEV_TOKEN, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = objectMapper.readTree(response.getBody());

        assertThat(detail.path("id").asLong()).isEqualTo(id);
        assertThat(detail.path("status").asText()).isEqualTo("REVIEWING_L1");
        assertThat(detail.path("ticketType").asText()).isEqualTo("NORMAL");
        assertThat(detail.path("systemId").asLong()).isEqualTo(1L);
        assertThat(detail.path("moduleId").asLong()).isEqualTo(201L);
        assertThat(detail.path("datasourceId").asLong()).isEqualTo(101L);
        assertThat(detail.path("title").asText()).isEqualTo("订单数据变更");
        assertThat(detail.path("changeReason").asText()).isEqualTo("修复线上数据");
        assertThat(detail.path("rollbackSql").asText()).isEqualTo("update t_order set a=0 where id=1");
        assertThat(detail.path("statementFingerprint").asText()).hasSize(64);
        assertThat(detail.path("snapshotEnabled").asBoolean()).isTrue();
        assertThat(detail.path("riskSnapshotHash").asText()).hasSize(64);
        assertThat(detail.path("submitterId").asLong()).isEqualTo(1L);
        assertThat(detail.path("originTicketId").isNull()).isTrue();
        assertThat(detail.path("createdAt").asText()).isNotBlank();
        assertThat(detail.path("updatedAt").asText()).isNotBlank();
        assertThat(detail.path("expectedExecuteAt").asText()).isNotBlank();
        assertThat(detail.path("approvedAt").isNull()).isTrue();
        assertThat(detail.path("executedAt").isNull()).isTrue();
        assertThat(detail.path("finishedAt").isNull()).isTrue();

        JsonNode statements = detail.path("statements");
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(statements.get(0).path("statementType").asText()).isEqualTo("UPDATE");
        assertThat(statements.get(0).path("sql").asText()).isEqualTo("update t_order set a=1 where id=1");
        assertThat(statements.get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(statements.get(0).path("affectedRows").isNull()).isTrue();
        assertThat(statements.get(0).path("elapsedMs").isNull()).isTrue();
        assertThat(statements.get(0).path("executedAt").isNull()).isTrue();
        assertThat(statements.get(1).path("seq").asInt()).isEqualTo(2);
        assertThat(statements.get(1).path("statementType").asText()).isEqualTo("DELETE");

        assertThat(detail.path("reviews")).isEmpty();

        JsonNode snapshots = detail.path("snapshots");
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).path("changeSeq").asInt()).isZero();
        assertThat(snapshots.get(0).path("snapshotRef").asText()).isEqualTo("1/101/" + id + "/0");
        assertThat(snapshots.get(0).path("tableFingerprints").isObject()).isTrue();
        assertThat(snapshots.get(0).path("tableFingerprints").path("t_order").asText())
                .isEqualTo("fp-1");
        assertThat(snapshots.get(0).path("capturedAt").asText()).isNotBlank();

        JsonNode events = detail.path("events");
        assertThat(events).isNotEmpty();
        assertThat(eventTypes(events)).contains("SUBMITTED");
        assertThat(events.get(0).path("actorId").asLong()).isEqualTo(1L);
        assertThat(events.get(0).path("createdAt").asText()).isNotBlank();

        // 同系统的运维也能看研发的工单。
        assertThat(detail(OPS02_TOKEN, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void the_detail_exposes_the_risk_snapshot_as_an_object() throws Exception {
        long id = submitAs(DEV_TOKEN);

        JsonNode detail = detailAs(DEV_TOKEN, id);
        JsonNode riskSnapshot = detail.path("riskSnapshot");

        assertThat(riskSnapshot.isObject()).isTrue();
        assertThat(riskSnapshot.path("level").asText()).isEqualTo(detail.path("riskLevel").asText());
        assertThat(riskSnapshot.path("statements")).hasSize(1);
        assertThat(riskSnapshot.path("statements").get(0).path("seq").asInt()).isEqualTo(1);
        assertThat(riskSnapshot.path("statements").get(0).path("level").asText()).isNotBlank();
        assertThat(riskSnapshot.path("statements").get(0).path("rules").isArray()).isTrue();
    }

    // ------------------------------------------------------------------ 坏 JSON 不 500

    @Test
    void a_corrupt_risk_snapshot_does_not_break_the_detail_endpoint() throws Exception {
        long id = submitAs(DEV_TOKEN);
        jdbc.update("update ticket set risk_snapshot_json = '{' where id = ?", id);

        ResponseEntity<String> response = detail(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = objectMapper.readTree(response.getBody());
        assertThat(detail.path("riskSnapshot").isNull()).isTrue();
        // 解析异常只能进日志，不能把原始异常文本（或坏 JSON 的片段）漏给前端。
        assertThat(response.getBody())
                .doesNotContain("JsonParseException")
                .doesNotContain("Unexpected end-of-input")
                .doesNotContain("JsonProcessingException");
    }

    @Test
    void a_corrupt_table_fingerprint_does_not_break_the_detail_endpoint() throws Exception {
        long id = submitAs(DEV_TOKEN);
        jdbc.update("""
                update schema_snapshot set table_fingerprints_json = '{'
                 where ticket_id = ? and change_seq = 0
                """, id);

        ResponseEntity<String> response = detail(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = objectMapper.readTree(response.getBody());
        assertThat(detail.path("snapshots")).hasSize(1);
        assertThat(detail.path("snapshots").get(0).path("tableFingerprints").isNull()).isTrue();
        assertThat(response.getBody())
                .doesNotContain("JsonParseException")
                .doesNotContain("Unexpected end-of-input");
    }

    // ------------------------------------------------------------------ 默认值与被夹取的参数

    @Test
    void the_list_defaults_to_page_one_and_twenty_items() throws Exception {
        submitAs(DEV_TOKEN);
        submitAs(DEV_TOKEN);

        JsonNode page = listWithDefaults(DEV_TOKEN);

        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(2);
    }

    @Test
    void a_page_or_size_below_one_is_clamped() throws Exception {
        submitAs(DEV_TOKEN);
        long newest = submitAs(DEV_TOKEN);

        JsonNode page = listAs(DEV_TOKEN, 0, 0);

        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(page.path("total").asLong()).isEqualTo(2);
        assertThat(idsOf(page)).containsExactly(newest);
    }

    // ------------------------------------------------------------------ helpers

    /** 用真实提交接口造一张工单，返回 ID。 */
    private long submitAs(String token) {
        ResponseEntity<String> response = postTicket(token,
                body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    /**
     * 直接插一张别的系统的工单。
     *
     * <p>提交接口造不出来：研发只能对自己可见的数据源提单（系统 2 的数据源 999 对 dev01 不可见）。
     * 表间没有外键，因此只写 {@code ticket} 一行就足够读路径使用；归属仍必须是合法的
     * {@code system_id=2 / module_id=202}，不能借用系统 1 的模块。
     */
    private long insertTicket(long id, long submitterId, long systemId, long datasourceId, String title) {
        jdbc.update("""
                        insert into ticket
                            (id, system_id, module_id, datasource_id, ticket_type, status, risk_level,
                             risk_snapshot_json, risk_snapshot_hash, title, change_reason,
                             statement_fingerprint, submitter_id)
                        values (?, ?, ?, ?, 'NORMAL', 'REVIEWING_L1', 'L1',
                                '{"level":"L1","statements":[]}', ?, ?, '系统二变更', ?, ?)
                        """,
                id, systemId, PAY_MODULE_ID, datasourceId, "a".repeat(64), title, "b".repeat(64),
                submitterId);
        return id;
    }

    private JsonNode listAs(String token, int page, int size) throws Exception {
        ResponseEntity<String> response = listTickets(token, page, size);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    /** 不带 {@code page}/{@code size} 参数，验证控制器默认值。 */
    private JsonNode listWithDefaults(String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange("/api/v1/tickets", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode detailAs(String token, long id) throws Exception {
        ResponseEntity<String> response = detail(token, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static Set<String> eventTypes(JsonNode events) {
        Set<String> types = new java.util.LinkedHashSet<>();
        events.forEach(event -> types.add(event.path("eventType").asText()));
        return types;
    }
}
