package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.dto.SchemaDiff.Change;
import com.badc.sql.common.dto.SchemaDiff.FieldChange;
import com.badc.sql.common.dto.SchemaDiff.TableChange;
import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 变更前后结构对比（FR-02 验收 1）：{@code GET /api/v1/tickets/{id}/schema-diff}。
 *
 * <p>差异算法本身（按名字对齐、加/删/改的判定）由执行器的 {@code SnapshotDifferTest} 用两份真实形态的
 * {@code schema.json} 覆盖；本类验证的是控制面这一段：<b>执行后快照真的被抓下来并落库</b>、两个引用被正确
 * 传给执行器、以及"没有可比对的东西"时给出的是明确错误而不是空差异。
 */
class SchemaDiffIT extends TicketITBase {

    private static final String DML_SQL = "update t_order set a=1 where id=1";
    private static final String DML_ROLLBACK_SQL = "update t_order set a=0 where id=1";

    @Test
    void a_ticket_that_never_executed_has_no_diff_and_says_why() {
        long id = submitTicket();

        ResponseEntity<String> response = getSchemaDiff(DEV_TOKEN, id);

        // 409 而不是 200 + 空列表：空差异会被读成"这次变更没改任何结构"，而事实是还没执行过。
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("执行前快照");
    }

    @Test
    void executing_captures_the_post_snapshot_and_exposes_the_diff() throws Exception {
        long id = submitTicket();
        approve(id);
        postJson(DEV_TOKEN, "/api/v1/tickets/" + id + "/risk-ack", null);
        assertThat(requestExecution(id).getStatusCode().value()).isEqualTo(202);
        assertThat(awaitTerminal(id)).isEqualTo("SUCCEEDED");

        // 执行后快照落库：change_seq = 2，引用以 /2 结尾。
        List<String> refs = jdbc.queryForList("""
                select snapshot_ref from schema_snapshot
                 where ticket_id = ? and change_seq = 2
                """, String.class, id);
        assertThat(refs).as("执行后必须抓一份快照").hasSize(1);
        assertThat(refs.get(0)).endsWith("/" + id + "/2");
        assertThat(stub.snapshotRefs).contains("1/101/" + id + "/2");


        stub.schemaDiffResult = new SchemaDiff(List.of(new TableChange("t_order", Change.CHANGED,
                List.of(new FieldChange("remark", Change.ADDED, null, "varchar(32) NULL")),
                List.of(), List.of())));

        ResponseEntity<String> response = getSchemaDiff(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 执行器被要求比对的就是 "1 号快照 -> 2 号快照"。
        assertThat(stub.schemaDiffRefs).containsExactly("1/101/" + id + "/1->1/101/" + id + "/2");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("tables").get(0).path("table").asText()).isEqualTo("t_order");
        assertThat(body.path("tables").get(0).path("columns").get(0).path("name").asText()).isEqualTo("remark");
        assertThat(body.path("tables").get(0).path("columns").get(0).path("status").asText()).isEqualTo("ADDED");
        assertThat(body.path("tables").get(0).path("columns").get(0).path("after").asText())
                .isEqualTo("varchar(32) NULL");
    }

    @Test
    void an_operator_of_another_system_cannot_see_the_diff() {
        long id = submitTicket();

        ResponseEntity<String> response = getSchemaDiff(OPS_TOKEN, id);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void a_ticket_that_does_not_exist_is_a_404() {
        assertThat(getSchemaDiff(DEV_TOKEN, 999_999L).getStatusCode().value()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private long submitTicket() {
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body(DML_SQL, DML_ROLLBACK_SQL));
        assertThat(response.getStatusCode()).as("提交响应：%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    /** 一级审批（若升到 L2 由组长终审），要求最终停在 {@code APPROVED}。 */
    private void approve(long id) {
        postJson(OPS02_TOKEN, "/api/v1/tickets/" + id + "/approve", "{\"comment\":\"通过\"}");
        if ("REVIEWING_L2".equals(status(id))) {
            postJson(LEAD01_TOKEN, "/api/v1/tickets/" + id + "/approve", "{\"comment\":\"终审通过\"}");
        }
        assertThat(status(id)).as("夹具必须停在 APPROVED").isEqualTo("APPROVED");
    }

    private ResponseEntity<String> requestExecution(long id) {
        return rest.exchange("/api/v1/tickets/" + id + "/execute", HttpMethod.POST,
                new HttpEntity<>(bearer(DEV_TOKEN)), String.class);
    }

    private ResponseEntity<String> getSchemaDiff(String token, long id) {
        return rest.exchange("/api/v1/tickets/" + id + "/schema-diff", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), String.class);
    }

    private ResponseEntity<String> postJson(String token, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 轮询到终态，并要求执行锁已释放（锁在 {@code finally} 里释放，它消失才代表后续写入也完成了）。 */
    private String awaitTerminal(long id) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            String current = status(id);
            Integer locks = jdbc.queryForObject(
                    "select count(*) from datasource_exec_lock where datasource_id = ?", Integer.class,
                    ORDER_DATASOURCE_ID);
            if (!"EXECUTING".equals(current) && !"APPROVED".equals(current) && locks != null && locks == 0) {
                return current;
            }
            sleep();
        }
        throw new AssertionError("工单 " + id + " 未在 10s 内进入终态，当前=" + status(id));
    }

    private static void sleep() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
