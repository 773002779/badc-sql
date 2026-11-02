package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * {@link JdbcNotificationStore} 的 SQL 口径：<b>真实 MySQL 8.0</b>，不用 mock。
 *
 * <p>为什么必须有这一层：投递器单测里的 {@code NotificationStore} 是内存实现，它能证明"投递器调了
 * 哪些方法"，但证明不了"这些方法写进数据库的是什么"。列宽、CHECK、NULL 语义、状态过滤条件
 * 只有真库能验：{@code last_error VARCHAR(500)} 的截断如果只靠假 store，删掉截断代码测试照样绿，
 * 而生产会在第一次超长异常时把整行丢掉（严格的 MySQL 直接报 Data too long）。
 *
 * <p>继承 {@link AbstractMySqlIT}：每个测试类建独立随机 schema、跑 Flyway、结束后 DROP。
 * {@code notification} 没有外键，收件人 id 可以用任意值——本类不依赖 {@code sys_user} 夹具。
 *
 * <p>不做 Spring 上下文：这里要验的是 SQL 与映射，不是装配；直接用 {@code JdbcTemplate} 起
 * {@link JdbcNotificationStore} 更快，失败信息也更贴近 SQL。
 */
class NotificationStoreIT extends AbstractMySqlIT {

    private static final long SYSTEM_ID = 2L;
    private static final long TICKET_ID = 42L;
    private static final long GRANT_REQUEST_ID = 7L;
    private static final long RECIPIENT_ID = 2L;
    private static final String TITLE = "【工单已通过】支付中心";
    private static final String BODY = "工单号：T-42\n数据源：支付库";

    /** 毫秒精度：{@code sent_at} 是 {@code DATETIME(3)}。 */
    private static final Instant SENT_AT = Instant.parse("2026-11-03T01:02:03.456Z");

    private NotificationStore store;

    @BeforeEach
    void createStore() {
        store = new JdbcNotificationStore(jdbc);
        jdbc.update("delete from notification");
    }

    @AfterEach
    void removeRows() {
        jdbc.update("delete from notification");
    }

    @Test
    void an_inserted_notification_starts_pending_with_zero_attempts() {
        long id = insertTicketNotification(TICKET_ID);

        assertThat(id).isPositive();
        List<NotificationRow> rows = store.listForTicket(TICKET_ID);
        assertThat(rows).hasSize(1);
        NotificationRow row = rows.get(0);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.eventType()).isEqualTo(NotificationEventType.TICKET_APPROVED);
        assertThat(row.systemId()).isEqualTo(SYSTEM_ID);
        assertThat(row.ticketId()).isEqualTo(TICKET_ID);
        assertThat(row.grantRequestId()).isNull();
        assertThat(row.recipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(row.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(row.status()).as("初态必须是 PENDING，不能是 SENT").isEqualTo(NotificationStatus.PENDING);
        assertThat(row.attempts()).as("还没尝试过").isZero();
        assertThat(row.lastError()).as("尚未失败").isNull();
        assertThat(row.sentAt()).as("尚未发送").isNull();
        assertThat(row.createdAt()).as("created_at 由数据库默认值写入").isNotNull();
        assertThat(row.title()).isEqualTo(TITLE);
        assertThat(row.body()).isEqualTo(BODY);
    }

    @Test
    void mark_sent_records_attempts_and_the_sent_instant() {
        long id = insertTicketNotification(TICKET_ID);

        store.markSent(id, 2, SENT_AT);

        NotificationRow row = store.listForTicket(TICKET_ID).get(0);
        assertThat(row.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(row.attempts()).as("第二次尝试才成功：attempts 记 2，不是 1").isEqualTo(2);
        assertThat(row.sentAt()).as("sent_at 必须原样落库（UTC）").isEqualTo(SENT_AT);
        assertThat(row.lastError()).isNull();
    }

    @Test
    void mark_failed_truncates_the_error_to_the_column_width() {
        long id = insertTicketNotification(TICKET_ID);
        String tooLong = "连接支付库失败：".repeat(700);

        store.markFailed(id, 3, tooLong);

        NotificationRow row = store.listForTicket(TICKET_ID).get(0);
        assertThat(row.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(row.attempts()).isEqualTo(3);
        assertThat(row.sentAt()).isNull();
        assertThat(row.lastError())
                .as("last_error 是 VARCHAR(500)；不截断这里会直接 Data too long，整行丢失")
                .hasSize(500);
    }

    @Test
    void mark_skipped_records_the_reason_and_keeps_attempts_at_zero() {
        long id = insertTicketNotification(TICKET_ID);

        store.markSkipped(id, "渠道未启用：WECOM");

        NotificationRow row = store.listForTicket(TICKET_ID).get(0);
        assertThat(row.status()).as("渠道没配不算失败").isEqualTo(NotificationStatus.SKIPPED);
        assertThat(row.attempts()).as("跳过 = 一次都没尝试").isZero();
        assertThat(row.lastError())
                .as("SKIPPED 的原因只能存在 last_error 这一列里，否则运维无从解释「为什么不发」")
                .isEqualTo("渠道未启用：WECOM");
        assertThat(row.sentAt()).isNull();
    }

    @Test
    void failure_count_counts_only_failed_rows_of_that_ticket() {
        long failed = insertTicketNotification(TICKET_ID);
        long sent = insertTicketNotification(TICKET_ID);
        long skipped = insertTicketNotification(TICKET_ID);
        long otherTicket = insertTicketNotification(TICKET_ID + 1);

        store.markFailed(failed, 3, "smtp down");
        store.markSent(sent, 1, SENT_AT);
        store.markSkipped(skipped, "收件人未填写邮箱");
        store.markFailed(otherTicket, 3, "smtp down");

        assertThat(store.failureCount(TICKET_ID))
                .as("失败数只数 FAILED：SENT/SKIPPED 混进来会让健康度看起来更差")
                .isEqualTo(1);
        assertThat(store.failureCount(TICKET_ID + 1)).isEqualTo(1);
        assertThat(store.failureCount(999L)).as("没有记录 = 0，不是异常").isZero();
    }

    @Test
    void list_for_ticket_returns_only_that_tickets_rows_in_insert_order() {
        long first = insertTicketNotification(TICKET_ID);
        long second = insertTicketNotification(TICKET_ID);
        insertGrantNotification(GRANT_REQUEST_ID);

        assertThat(store.listForTicket(TICKET_ID))
                .as("按 id 升序：重试与断言都要可复现，且不能把别的工单/授权申请的通知混进来")
                .extracting(NotificationRow::id)
                .containsExactly(first, second);
        assertThat(store.listForTicket(999L)).isEmpty();
    }

    @Test
    void nullable_business_keys_round_trip_as_nulls() {
        long id = store.insert(RECIPIENT_ID, NotificationEventType.GRANT_REQUESTED, null, null,
                GRANT_REQUEST_ID, NotificationChannel.IN_APP, TITLE, BODY);

        Map<String, Object> row = jdbc.queryForMap(
                "select event_type, system_id, ticket_id, grant_request_id from notification where id = ?", id);

        assertThat(row.get("event_type")).isEqualTo("GRANT_REQUESTED");
        assertThat(row.get("system_id")).as("空系统必须是 NULL，不能写成 0（0 会指向不存在的系统）").isNull();
        assertThat(row.get("ticket_id")).isNull();
        assertThat(row.get("grant_request_id")).isEqualTo(GRANT_REQUEST_ID);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private long insertTicketNotification(long ticketId) {
        return store.insert(RECIPIENT_ID, NotificationEventType.TICKET_APPROVED, SYSTEM_ID, ticketId, null,
                NotificationChannel.EMAIL, TITLE, BODY);
    }

    private long insertGrantNotification(long grantRequestId) {
        return store.insert(RECIPIENT_ID, NotificationEventType.GRANT_REQUESTED, null, null, grantRequestId,
                NotificationChannel.IN_APP, TITLE, BODY);
    }
}
