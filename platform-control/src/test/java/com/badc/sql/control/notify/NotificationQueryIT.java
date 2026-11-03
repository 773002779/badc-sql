package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.TicketITBase;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 通知读路径（切片 3-A 任务 8）：{@code GET /api/v1/notifications}、
 * {@code GET /api/v1/tickets/{id}/notifications}，以及工单详情里的失败计数。
 *
 * <p>这三条读路径的共同问题是<b>可见性</b>：通知正文不含 SQL，但"谁在什么时候因为什么被通知"本身是敏感的。
 * 所以断言集中在"看不到不该看的"，而不是渲染结果（那由 {@code NotificationRendererTest} 覆盖）。
 */
class NotificationQueryIT extends TicketITBase {

    @Autowired
    private NotificationStore notificationStore;

    @BeforeEach
    void clearNotifications() {
        // 工单类用例会触发真实的异步投递；不清空则"我的消息"总数不可预测。
        jdbc.update("delete from notification");
    }

    /** 提交一张系统 1 的工单并返回 ID；提交失败时把响应体带进断言消息。 */
    private long submitAsDev() {
        ResponseEntity<String> created = postTicket(DEV_TOKEN,
                body("update t_order set a=1 where id=1", "update t_order set a=0 where id=1"));
        assertThat(created.getStatusCode().value()).as("提交响应：%s", created.getBody()).isEqualTo(201);
        return idOf(created);
    }

    /** GET 请求（{@link TicketITBase} 只有工单专用的几个辅助方法，通知端点用这个）。 */
    private ResponseEntity<String> getJson(String path, String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);
    }

    @Test
    void mine_lists_only_the_notifications_addressed_to_me() throws Exception {
        long idForDev = notificationStore.insert(1L, NotificationEventType.TICKET_APPROVED, 1L, null, null,
                NotificationChannel.IN_APP, "给研发的", "正文");
        notificationStore.insert(1L, NotificationEventType.TICKET_REJECTED, 1L, null, null,
                NotificationChannel.IN_APP, "给研发的第二条", "正文");
        notificationStore.insert(2L, NotificationEventType.TICKET_APPROVED, 2L, null, null,
                NotificationChannel.IN_APP, "给运维的", "正文");

        ResponseEntity<String> response = getJson("/api/v1/notifications?page=1&size=20", DEV_TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode page = objectMapper.readTree(response.getBody());
        assertThat(page.path("total").asLong()).isEqualTo(2);
        // 倒序：后插入的先出现，且两条都属于 dev01。
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").get(0).path("title").asText()).isEqualTo("给研发的第二条");
        assertThat(page.path("items").get(1).path("id").asLong()).isEqualTo(idForDev);

        ResponseEntity<String> opsResponse = getJson("/api/v1/notifications", OPS_TOKEN);
        assertThat(objectMapper.readTree(opsResponse.getBody()).path("total").asLong()).isEqualTo(1);
    }

    @Test
    void a_recipient_cannot_ask_for_someone_elses_notifications_by_parameter() throws Exception {
        notificationStore.insert(2L, NotificationEventType.TICKET_APPROVED, 2L, null, null,
                NotificationChannel.IN_APP, "给运维的", "正文");

        // 身份只来自 JWT：多塞一个 recipientId 不会改变结果（服务端不读这个参数）。
        ResponseEntity<String> response = getJson("/api/v1/notifications?recipientId=2", DEV_TOKEN);

        assertThat(objectMapper.readTree(response.getBody()).path("total").asLong()).isZero();
    }

    @Test
    void ticket_notifications_require_visibility_of_that_ticket() throws Exception {
        long ticketId = submitAsDev();
        long marker = notificationStore.insert(1L, NotificationEventType.TICKET_APPROVED, 1L, ticketId, null,
                NotificationChannel.IN_APP, "E2E 标记", "正文");

        ResponseEntity<String> own = getJson("/api/v1/tickets/" + ticketId + "/notifications", DEV_TOKEN);
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        JsonNode records = objectMapper.readTree(own.getBody());
        assertThat(records.findValuesAsText("id")).contains(String.valueOf(marker));

        // ops01 运维的是系统 2，看不到系统 1 的工单，也看不到它的通知记录（403，不是空列表）。
        ResponseEntity<String> foreign = getJson("/api/v1/tickets/" + ticketId + "/notifications", OPS_TOKEN);
        assertThat(foreign.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void notifications_of_a_missing_ticket_are_a_404() {
        ResponseEntity<String> response = getJson("/api/v1/tickets/999999/notifications", DEV_TOKEN);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void ticket_detail_exposes_the_number_of_failed_notifications() throws Exception {
        long ticketId = submitAsDev();
        awaitDeliverySettled(ticketId);

        // 先清掉真实投递留下的行，再手工造一条 FAILED：计数必须是"只数 FAILED"的精确值。
        jdbc.update("delete from notification where ticket_id = ?", ticketId);
        long failed = notificationStore.insert(4L, NotificationEventType.TICKET_SUBMITTED, 1L, ticketId, null,
                NotificationChannel.EMAIL, "发不出去的", "正文");
        notificationStore.markFailed(failed, 3, "smtp 拒收");
        long skipped = notificationStore.insert(4L, NotificationEventType.TICKET_SUBMITTED, 1L, ticketId, null,
                NotificationChannel.WECOM, "跳过的不算失败", "正文");
        notificationStore.markSkipped(skipped, "渠道未配置");

        ResponseEntity<String> response = detail(DEV_TOKEN, ticketId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(response.getBody()).path("notificationFailures").asInt())
                .as("只数 FAILED；SKIPPED（渠道未配置）不是失败")
                .isEqualTo(1);
    }

    /**
     * 等到该工单的通知行数不再变化（最多 10 秒）。
     *
     * <p>工单提交会触发异步投递；在"已写工单、通知还没发完"的窗口里清表，会让后来的行混进断言。
     */
    private void awaitDeliverySettled(long ticketId) {
        Duration timeout = Duration.ofSeconds(10);
        Instant deadline = Instant.now().plus(timeout);
        int previous = -1;
        while (Instant.now().isBefore(deadline)) {
            int current = jdbc.queryForObject(
                    "select count(*) from notification where ticket_id = ?", Integer.class, ticketId);
            if (current == previous) {
                return;
            }
            previous = current;
            sleep(150);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待通知时被中断", e);
        }
    }
}
