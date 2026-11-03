package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.badc.sql.control.notify.channel.ChannelSender;

/**
 * 事件开关在投递器里的接线（R3 + R28）。
 *
 * <p><b>纯单测</b>：store 是内存假实现（记下投递器到底写没写行），sender 是只数调用次数的假实现，
 * switch 是 Mockito 桩（{@code isEnabled} 返回 false 或抛异常）。用假实现而不是真实渠道，
 * 是为了让"关闭时零行 / 零发送"与"开关查询失败仍投递"这两个断言只受投递器行为影响。
 *
 * <p><b>R28 的语义</b>：事件关闭时<b>直接返回，不写任何 {@code notification} 行</b>——
 * 不是写一行 {@code SKIPPED}。写成 SKIPPED 会让运维把"故意关掉"看成"投递失败"，
 * 而且每关一个事件就多出成套的行。
 *
 * <p>开关判断必须在 resolve 接收人之前：本类用 {@code verifyNoInteractions(recipients)} 钉住这一点。
 * 若判断被挪到落库之后，关闭的事件会留下 {@code PENDING} 行——那正是本任务要防的。
 */
class NotificationDispatcherSwitchTest {

    private static final long SYSTEM_ID = 2L;
    private static final long TICKET_ID = 42L;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T01:02:03.456Z"), ZoneOffset.UTC);

    private static final NotificationCommand COMMAND = new NotificationCommand(
            NotificationEventType.TICKET_APPROVED,
            SYSTEM_ID,
            TICKET_ID,
            null,
            Map.of("system", "支付中心", "ticketNo", "T-42"));

    private final RecordingStore store = new RecordingStore();
    private final CountingSender sender = new CountingSender();
    private final NotificationRecipients recipients = mock(NotificationRecipients.class);
    private final NotificationTargetStore targetStore = mock(NotificationTargetStore.class);
    private final NotificationSwitchService switchService = mock(NotificationSwitchService.class);

    @BeforeEach
    void givenOneRecipientWhoCanBeDelivered() {
        when(recipients.resolve(anyLong(), any(NotificationEventType.class))).thenReturn(List.of(1L));
        when(targetStore.load(anyList()))
                .thenReturn(List.of(new NotificationTarget(1L, "dev01", "dev01@badc.test", null)));
        when(switchService.isEnabled(any(NotificationEventType.class), anyLong())).thenReturn(true);
    }

    @Test
    void a_disabled_event_writes_no_rows_and_never_calls_the_sender() {
        when(switchService.isEnabled(NotificationEventType.TICKET_APPROVED, SYSTEM_ID)).thenReturn(false);

        assertThatCode(() -> dispatcher().dispatch(COMMAND)).doesNotThrowAnyException();

        assertThat(store.inserts)
                .as("R28 承重断言：事件关闭时一行都不能写（写成 SKIPPED/PENDING 都会让运维误判）")
                .isEmpty();
        assertThat(sender.sendCalls)
                .as("事件关闭时渠道一次都不该被调用")
                .isZero();
        verifyNoInteractions(recipients);
    }

    @Test
    void a_switch_lookup_failure_falls_back_to_enabled_and_still_delivers() {
        when(switchService.isEnabled(NotificationEventType.TICKET_APPROVED, SYSTEM_ID))
                .thenThrow(new IllegalStateException("notification_switch 查询失败"));

        assertThatCode(() -> dispatcher().dispatch(COMMAND))
                .as("开关查询失败不能让异常回传给已提交的业务事务")
                .doesNotThrowAnyException();

        assertThat(store.inserts)
                .as("失败不阻断通知：按「默认开」继续投递，漏发比多发对用户影响更大")
                .hasSize(1);
        assertThat(store.statuses.values())
                .as("既然按开处理，就必须真的走完投递（SENT），而不是留一行 PENDING")
                .containsExactly(NotificationStatus.SENT);
        assertThat(sender.sendCalls).isEqualTo(1);
    }

    private NotificationDispatcher dispatcher() {
        return new NotificationDispatcher(recipients, targetStore, store, switchService, List.of(sender),
                properties(), CLOCK);
    }

    private static NotificationProperties properties() {
        return new NotificationProperties(
                NotificationProperties.Wecom.defaults(),
                NotificationProperties.Mail.defaults(),
                new NotificationProperties.Retry(1, 0),
                NotificationProperties.DEFAULT_LINK_BASE);
    }

    /** 内存版 {@code notification} 表：只记"写了什么行、最后是什么状态"。 */
    private static final class RecordingStore implements NotificationStore {

        private final List<Insert> inserts = new ArrayList<>();
        private final Map<Long, NotificationStatus> statuses = new HashMap<>();
        private long nextId = 1;

        @Override
        public long insert(long recipientId, NotificationEventType type, Long systemId, Long ticketId,
                Long grantRequestId, NotificationChannel channel, String title, String body) {
            long id = nextId++;
            inserts.add(new Insert(recipientId, type, channel));
            statuses.put(id, NotificationStatus.PENDING);
            return id;
        }

        @Override
        public void markSent(long id, int attempts, Instant sentAt) {
            statuses.put(id, NotificationStatus.SENT);
        }

        @Override
        public void markFailed(long id, int attempts, String error) {
            statuses.put(id, NotificationStatus.FAILED);
        }

        @Override
        public void markSkipped(long id, String reason) {
            statuses.put(id, NotificationStatus.SKIPPED);
        }

        @Override
        public int failureCount(long ticketId) {
            return 0;
        }

        @Override
        public List<NotificationRow> listForTicket(long ticketId) {
            return List.of();
        }

        @Override
        public long countForRecipient(long recipientId) {
            return 0;
        }

        @Override
        public List<NotificationRow> pageForRecipient(long recipientId, int limit, int offset) {
            return List.of();
        }
    }

    private record Insert(long recipientId, NotificationEventType type, NotificationChannel channel) {
    }

    /** 只数"真的发了几次"的假渠道。 */
    private static final class CountingSender implements ChannelSender {

        private int sendCalls;

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.IN_APP;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public void send(NotificationTarget target, String title, String body) {
            sendCalls++;
        }
    }
}
