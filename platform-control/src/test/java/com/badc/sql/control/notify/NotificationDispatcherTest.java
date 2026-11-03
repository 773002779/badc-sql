package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.badc.sql.control.notify.channel.ChannelSender;
import com.badc.sql.control.notify.channel.InAppSender;
import com.badc.sql.control.notify.channel.SmtpSender;

/**
 * 投递器：重试、逐接收人落库、失败隔离（R18 一并钉在这里）。
 *
 * <p><b>纯单测，不起 Spring</b>：{@link NotificationStore} 用内存实现记下"投递器到底写了什么"，
 * {@link NotificationRecipients} / {@link NotificationTargetStore} 用 Mockito 提供接收人与联系方式，
 * 渠道则尽量用<b>真实 sender</b>（{@link SmtpSender} / {@code InAppSender}）配一个会抛异常的
 * {@link JavaMailSender} 假实现——{@link NotificationSkippedException} 那条承重断言必须走真实
 * {@code SmtpSender} 的判空分支，用假 sender 自证"我抛了异常"证明不了 R18 被修掉。
 *
 * <p>为什么不真睡：退避通过可注入的 {@code LongConsumer} 记录，断言"睡了多久、睡了几次"。
 * 真睡 500ms + 1000ms 会让这个类的单测变成秒级，且无法断言退避序列。
 *
 * <p>时钟固定：{@code sent_at} 必须是注入时钟的值，{@code Instant.now()} 会让这条断言失去意义。
 */
class NotificationDispatcherTest {

    private static final long SYSTEM_ID = 2L;
    private static final long TICKET_ID = 42L;
    private static final String SYSTEM_NAME = "支付中心";

    /** 固定时钟：与真实"现在"不同，所以任何 {@code Instant.now()} 的实现都会被抓到。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T01:02:03.456Z"), ZoneOffset.UTC);

    private static final NotificationCommand COMMAND = new NotificationCommand(
            NotificationEventType.TICKET_APPROVED,
            SYSTEM_ID,
            TICKET_ID,
            null,
            Map.of("system", SYSTEM_NAME, "ticketNo", "T-42"));

    private final RecordingStore store = new RecordingStore();
    private final List<Long> slept = new ArrayList<>();
    private final NotificationRecipients recipients = mock(NotificationRecipients.class);
    private final NotificationTargetStore targetStore = mock(NotificationTargetStore.class);
    private final NotificationSwitchService switchService = mock(NotificationSwitchService.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void a_failing_channel_does_not_stop_the_other_channel_and_never_throws() {
        givenRecipients(1L);
        givenTargets(target(1L, "dev01@badc.test", null));
        failEverySendWith(new IllegalStateException("smtp down"));

        NotificationDispatcher dispatcher = dispatcher(new InAppSender(), new SmtpSender(properties(3, 500), mailSender));

        assertThatCode(() -> dispatcher.dispatch(COMMAND))
                .as("投递器在业务事务提交后被调用，任何异常都不能回传给业务")
                .doesNotThrowAnyException();

        assertThat(rowsWith(NotificationStatus.FAILED))
                .as("邮件渠道失败要留一行 FAILED，且只影响这个渠道")
                .hasSize(1);
        assertThat(rowsWith(NotificationStatus.SENT))
                .as("站内渠道不能因为邮件失败而消失")
                .hasSize(1);
        assertThat(rowsWith(NotificationStatus.SENT).get(0).sentAt)
                .as("sent_at 取注入的时钟，而不是调用 Instant.now()")
                .isEqualTo(CLOCK.instant());
        assertThat(store.rows().get(0).title).isEqualTo("【工单已通过】支付中心");
        assertThat(store.rows().get(0).body).isEqualTo("工单号：T-42");
        assertThat(store.rows().get(0).ticketId).isEqualTo(TICKET_ID);
    }

    @Test
    void an_exhausted_retry_marks_failed_after_three_attempts_with_exponential_backoff() {
        givenRecipients(1L);
        givenTargets(target(1L, "dev01@badc.test", null));
        failEverySendWith(new IllegalStateException("smtp down"));

        NotificationDispatcher dispatcher = dispatcher(new SmtpSender(properties(3, 500), mailSender));

        dispatcher.dispatch(COMMAND);

        Row row = store.rows().get(0);
        assertThat(row.status).isEqualTo(NotificationStatus.FAILED);
        assertThat(row.attempts).as("max-attempts=3：首次 + 2 次重试，attempts 记满 3").isEqualTo(3);
        assertThat(row.lastError).as("FAILED 行必须留下可查的原因").isNotBlank();
        assertThat(row.sentAt).isNull();
        assertThat(slept)
                .as("退避只在重试之间发生：两次重试，第 n 次 base * 2^(n-1) = 500, 1000")
                .containsExactly(500L, 1000L);
        verify(mailSender, times(3)).send(any(SimpleMailMessage.class));
    }

    @Test
    void an_unconfigured_channel_is_skipped_without_ever_calling_send() throws Exception {
        givenRecipients(1L);
        givenTargets(target(1L, "dev01@badc.test", null));

        ChannelSender wecom = mock(ChannelSender.class);
        when(wecom.channel()).thenReturn(NotificationChannel.WECOM);
        when(wecom.isConfigured()).thenReturn(false);

        NotificationDispatcher dispatcher = dispatcher(wecom);

        dispatcher.dispatch(COMMAND);

        Row row = store.rows().get(0);
        assertThat(row.status).as("渠道没启用不算失败").isEqualTo(NotificationStatus.SKIPPED);
        assertThat(row.lastError).as("SKIPPED 也要能解释「为什么不发」").contains("渠道未启用");
        assertThat(slept).as("没发送就没有重试，更不能真睡").isEmpty();
        // 注意：isConfigured() 本身是一次必要交互，所以这里不能 verifyNoInteractions(sender)
        // ——能证明"一次都没发"的是 send 的 never()。
        verify(wecom, never()).send(any(NotificationTarget.class), any(), any());
    }

    @Test
    void a_skipped_exception_marks_skipped_instead_of_failed() {
        givenRecipients(1L);
        givenTargets(target(1L, null, null));

        NotificationDispatcher dispatcher = dispatcher(new SmtpSender(properties(3, 500), mailSender));

        dispatcher.dispatch(COMMAND);

        Row row = store.rows().get(0);
        assertThat(row.status)
                .as("R18 承重断言：没邮箱必须记 SKIPPED，记成 SENT 就是「没发出去却记成功」，记成 FAILED 会白耗重试")
                .isEqualTo(NotificationStatus.SKIPPED);
        assertThat(row.lastError).as("跳过原因来自异常消息，运维能看出是缺邮箱").contains("邮箱");
        assertThat(row.attempts).as("联系方式缺失是确定性的，不重试").isZero();
        assertThat(slept).as("跳过不是失败，一次都不该睡").isEmpty();
        verifyNoInteractions(mailSender);
    }

    @Test
    void one_recipient_without_an_email_does_not_block_the_other_recipient() {
        givenRecipients(1L, 2L);
        givenTargets(target(1L, null, null), target(2L, "ops01@badc.test", null));

        NotificationDispatcher dispatcher = dispatcher(new SmtpSender(properties(3, 500), mailSender));

        dispatcher.dispatch(COMMAND);

        assertThat(store.rows()).hasSize(2);
        assertThat(store.rows().get(0).recipientId).isEqualTo(1L);
        assertThat(store.rows().get(0).status)
                .as("缺邮箱的人只该在这一行被跳过")
                .isEqualTo(NotificationStatus.SKIPPED);
        assertThat(store.rows().get(1).recipientId).isEqualTo(2L);
        assertThat(store.rows().get(1).status)
                .as("前一个接收人跳过不能中断后一个")
                .isEqualTo(NotificationStatus.SENT);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void a_store_failure_for_one_recipient_does_not_stop_the_next_recipient() {
        givenRecipients(1L, 2L);
        givenTargets(target(1L, "dev01@badc.test", null), target(2L, "ops01@badc.test", null));
        store.failInsertFor(1L);

        NotificationDispatcher dispatcher = dispatcher(new SmtpSender(properties(3, 500), mailSender));

        assertThatCode(() -> dispatcher.dispatch(COMMAND)).doesNotThrowAnyException();

        assertThat(store.rows()).as("第一个收件人落库就炸了，第二个仍必须照常投递").hasSize(1);
        assertThat(store.rows().get(0).recipientId).isEqualTo(2L);
        assertThat(store.rows().get(0).status).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void no_recipients_writes_no_rows_and_never_throws() {
        givenRecipients();

        NotificationDispatcher dispatcher = dispatcher(new InAppSender());

        assertThatCode(() -> dispatcher.dispatch(COMMAND)).doesNotThrowAnyException();

        assertThat(store.rows()).as("没人可发是正常状态，不是失败，也不该留没有收件人的行").isEmpty();
        verifyNoInteractions(targetStore);
    }

    @Test
    void an_over_long_error_is_truncated_before_it_reaches_the_column() {
        givenRecipients(1L);
        givenTargets(target(1L, "dev01@badc.test", null));
        failEverySendWith(new IllegalStateException("x".repeat(5000)));

        NotificationDispatcher dispatcher =
                dispatcher(properties(1, 500), new SmtpSender(properties(1, 500), mailSender));

        dispatcher.dispatch(COMMAND);

        Row row = store.rows().get(0);
        assertThat(row.status).isEqualTo(NotificationStatus.FAILED);
        assertThat(row.lastError)
                .as("last_error 是 VARCHAR(500)：超长消息必须在这里被截断，而不是让落库报 Data too long")
                .hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void a_connection_string_in_the_error_is_never_persisted() {
        givenRecipients(1L);
        givenTargets(target(1L, "dev01@badc.test", null));
        failEverySendWith(new IllegalStateException(
                "邮件发送失败 jdbc:mysql://10.9.8.7:3306/badc_sql?user=root&password=s3cr3t"));

        NotificationDispatcher dispatcher =
                dispatcher(properties(1, 500), new SmtpSender(properties(1, 500), mailSender));

        dispatcher.dispatch(COMMAND);

        Row row = store.rows().get(0);
        assertThat(row.lastError)
                .as("last_error 会被运维和报表看到：连接串、账号、口令绝不能进去")
                .doesNotContain("10.9.8.7")
                .doesNotContain("s3cr3t")
                .doesNotContain("root")
                .doesNotContain("password=")
                .contains("IllegalStateException");
    }

    @Test
    void a_resolve_failure_never_escapes() {
        when(recipients.resolve(anyLong(), any(NotificationEventType.class)))
                .thenThrow(new IllegalStateException("数据库连不上"));

        NotificationDispatcher dispatcher = dispatcher(new InAppSender());

        assertThatCode(() -> dispatcher.dispatch(COMMAND))
                .as("resolve 是投递的第一步：它炸了也不能回传给已提交的业务事务")
                .doesNotThrowAnyException();
        assertThat(store.rows()).as("连收件人都没解析出来，不该有任何通知行").isEmpty();
    }

    @Test
    void a_target_load_failure_never_escapes() {
        givenRecipients(1L);
        when(targetStore.load(anyList())).thenThrow(new IllegalStateException("sys_user 查询失败"));

        NotificationDispatcher dispatcher = dispatcher(new InAppSender());

        assertThatCode(() -> dispatcher.dispatch(COMMAND))
                .as("联系方式读取失败同样是「这次没发成」，不是「业务失败」")
                .doesNotThrowAnyException();
        assertThat(store.rows()).isEmpty();
    }

    @Test
    void a_skipped_reason_is_sanitized_before_it_reaches_last_error() throws Exception {
        givenRecipients(1L);
        givenTargets(target(1L, "dev01@badc.test", null));

        ChannelSender sender = mock(ChannelSender.class);
        when(sender.channel()).thenReturn(NotificationChannel.EMAIL);
        when(sender.isConfigured()).thenReturn(true);
        doThrow(new NotificationSkippedException("连接被拒 password='s3cr3t'，跳过邮件渠道"))
                .when(sender).send(any(NotificationTarget.class), any(), any());

        dispatcher(sender).dispatch(COMMAND);

        Row row = store.rows().get(0);
        assertThat(row.status).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(row.lastError)
                .as("SKIPPED 的原因也进 last_error、也会给研发看，脱敏口径必须和 FAILED 一致")
                .doesNotContain("s3cr3t")
                .contains("password=***");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private void givenRecipients(Long... userIds) {
        when(recipients.resolve(anyLong(), any(NotificationEventType.class))).thenReturn(List.of(userIds));
    }

    private void givenTargets(NotificationTarget... targets) {
        when(targetStore.load(anyList())).thenReturn(List.of(targets));
    }

    private void failEverySendWith(RuntimeException failure) {
        doThrow(failure).when(mailSender).send(any(SimpleMailMessage.class));
    }

    private NotificationDispatcher dispatcher(ChannelSender... senders) {
        return dispatcher(properties(3, 500), senders);
    }

    private NotificationDispatcher dispatcher(NotificationProperties properties, ChannelSender... senders) {
        // 开关默认开：本类的用例都验投递行为，开关的接线由 NotificationDispatcherSwitchTest 覆盖。
        when(switchService.isEnabled(any(NotificationEventType.class), anyLong())).thenReturn(true);
        return new NotificationDispatcher(recipients, targetStore, store, switchService, List.of(senders),
                properties, CLOCK, slept::add);
    }

    private static NotificationProperties properties(int maxAttempts, int baseBackoffMillis) {
        return new NotificationProperties(
                NotificationProperties.Wecom.defaults(),
                new NotificationProperties.Mail(true, "noreply@badc.test"),
                new NotificationProperties.Retry(maxAttempts, baseBackoffMillis));
    }

    private static NotificationTarget target(long userId, String email, String wecomUserId) {
        return new NotificationTarget(userId, "user" + userId, email, wecomUserId);
    }

    private List<Row> rowsWith(NotificationStatus status) {
        return store.rows().stream().filter(row -> row.status == status).toList();
    }

    /** 内存版 {@code notification} 表：只记投递器交给 store 的东西，不含 SQL。 */
    private static final class RecordingStore implements NotificationStore {

        private final Map<Long, Row> rows = new LinkedHashMap<>();
        private long nextId = 1;
        private long failInsertForRecipient = -1;

        void failInsertFor(long recipientId) {
            this.failInsertForRecipient = recipientId;
        }

        @Override
        public long insert(long recipientId, NotificationEventType type, Long systemId, Long ticketId,
                Long grantRequestId, NotificationChannel channel, String title, String body) {
            if (recipientId == failInsertForRecipient) {
                throw new IllegalStateException("模拟落库失败");
            }
            long id = nextId++;
            rows.put(id, new Row(id, recipientId, type, systemId, ticketId, grantRequestId, channel, title, body));
            return id;
        }

        @Override
        public void markSent(long id, int attempts, Instant sentAt) {
            Row row = row(id);
            row.status = NotificationStatus.SENT;
            row.attempts = attempts;
            row.sentAt = sentAt;
            row.lastError = null;
        }

        @Override
        public void markFailed(long id, int attempts, String error) {
            Row row = row(id);
            row.status = NotificationStatus.FAILED;
            row.attempts = attempts;
            row.lastError = error;
        }

        @Override
        public void markSkipped(long id, String reason) {
            Row row = row(id);
            row.status = NotificationStatus.SKIPPED;
            row.lastError = reason;
        }

        @Override
        public int failureCount(long ticketId) {
            return (int) rows.values().stream()
                    .filter(row -> row.status == NotificationStatus.FAILED)
                    .filter(row -> row.ticketId != null && row.ticketId == ticketId)
                    .count();
        }

        @Override
        public List<NotificationRow> listForTicket(long ticketId) {
            return List.of();
        }

        List<Row> rows() {
            return List.copyOf(rows.values());
        }

        private Row row(long id) {
            Row row = rows.get(id);
            if (row == null) {
                throw new IllegalStateException("没有 id=" + id + " 的通知行");
            }
            return row;
        }
    }

    private static final class Row {

        final long id;
        final long recipientId;
        final NotificationEventType type;
        final Long systemId;
        final Long ticketId;
        final Long grantRequestId;
        final NotificationChannel channel;
        final String title;
        final String body;

        NotificationStatus status = NotificationStatus.PENDING;
        int attempts;
        String lastError;
        Instant sentAt;

        Row(long id, long recipientId, NotificationEventType type, Long systemId, Long ticketId,
                Long grantRequestId, NotificationChannel channel, String title, String body) {
            this.id = id;
            this.recipientId = recipientId;
            this.type = type;
            this.systemId = systemId;
            this.ticketId = ticketId;
            this.grantRequestId = grantRequestId;
            this.channel = channel;
            this.title = title;
            this.body = body;
        }
    }
}
