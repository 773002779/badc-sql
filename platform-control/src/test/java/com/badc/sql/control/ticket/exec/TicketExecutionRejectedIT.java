package com.badc.sql.control.ticket.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.badc.sql.control.support.TicketITBase;

/**
 * 执行线程池已停机时的入口语义：<b>503 + 退回 {@code APPROVED}</b>，不是"谎称已进入 EXECUTING"。
 *
 * <p>为什么必须单独一个类：这条用例要把 {@code ticketExecutor} 这个 bean 真的 {@code shutdown()} 掉，
 * 之后同一 Spring 上下文里任何异步执行都不会再被受理。{@link TicketITBase} 已带
 * {@code @DirtiesContext(AFTER_CLASS)}，本类跑完上下文即被丢弃，不会污染其它 IT 类。
 *
 * <p>要钉住的事实（缺一条都会让"停机丢任务"以另一种形式回来）：
 * <ul>
 *   <li>HTTP 是 <b>503</b>（{@code SERVICE_UNAVAILABLE}），不是 500：请求没被受理，调用方应稍后重试；</li>
 *   <li>工单退回 {@code APPROVED} 且 {@code executed_at} 为 {@code NULL}——它没有被执行过；</li>
 *   <li>{@code datasource_exec_lock} 里没有该数据源的锁行：没交付就没理由占着锁；</li>
 *   <li>没有 {@code schema_snapshot(change_seq = 1)} 行：异步任务一个字都没跑。</li>
 * </ul>
 */
class TicketExecutionRejectedIT extends TicketITBase {

    private static final String DML_SQL = "update t_order set a = 1 where id = 1";

    private static final String DML_ROLLBACK_SQL = "update t_order set a = 0 where id = 1";

    @Autowired
    @Qualifier("ticketExecutor")
    private ThreadPoolTaskExecutor ticketExecutor;

    @Test
    void a_stopped_executor_refuses_the_request_with_503_and_rolls_the_ticket_back() {
        long id = approvedTicket(DML_SQL);

        // 真的停机：拒绝策略（InlineOrRejectHandler）从此走"抛"这一支，任务不会被静默丢弃。
        ticketExecutor.shutdown();

        ResponseEntity<String> response = requestExecution(DEV_TOKEN, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .contains("SERVICE_UNAVAILABLE")
                .contains("执行器正在停止");

        assertThat(status(id)).as("没被受理就不能停在 EXECUTING").isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select executed_at from ticket where id = ?", Timestamp.class, id))
                .as("退回 APPROVED 必须同时清掉 executed_at")
                .isNull();

        assertThat(jdbc.queryForList(
                "select 1 from datasource_exec_lock where datasource_id = ?", Integer.class, ORDER_DATASOURCE_ID))
                .as("拒绝路径必须释放锁，否则该库永久不可写")
                .isEmpty();
        assertThat(jdbc.queryForList(
                "select 1 from schema_snapshot where ticket_id = ? and change_seq = 1", Integer.class, id))
                .as("任务一个字都没跑，不得留下执行前快照")
                .isEmpty();
        assertThat(stub.executeCalls).as("一条语句都不许下发").isZero();
    }

    private long approvedTicket(String... statements) {
        ResponseEntity<String> response = postTicket(DEV_TOKEN, body(String.join(";", statements), DML_ROLLBACK_SQL));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(response);

        ResponseEntity<String> first = postJson(OPS02_TOKEN, "/api/v1/tickets/" + id + "/approve",
                "{\"comment\":\"通过\"}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        if ("REVIEWING_L2".equals(status(id))) {
            ResponseEntity<String> second = postJson(LEAD01_TOKEN, "/api/v1/tickets/" + id + "/approve",
                    "{\"comment\":\"终审通过\"}");
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(status(id)).as("夹具必须停在 APPROVED").isEqualTo("APPROVED");
        return id;
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
}
