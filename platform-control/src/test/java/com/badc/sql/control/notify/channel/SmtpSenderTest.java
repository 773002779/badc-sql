package com.badc.sql.control.notify.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationProperties;
import com.badc.sql.control.notify.NotificationTarget;

/**
 * 邮件渠道：{@link JavaMailSender} 用 Mockito 假造，不发真实 SMTP。
 *
 * <p>钉住三件事：
 * <ol>
 *   <li>{@code enabled=false} 时 {@code isConfigured()} 为 false，且一次 SMTP 都不碰；</li>
 *   <li>开启时恰好发一封，收件人是 {@code target.email()}，from 取配置；</li>
 *   <li>{@code target.email()} 为空/null 时<b>跳过而不是抛异常</b>——"某人没填邮箱"是正常状态，
 *       不能让它变成"这个人的整条通知投递失败"，更不能中断其他收件人。</li>
 * </ol>
 */
class SmtpSenderTest {

    private static final String TITLE = "【工单已通过】订单中心";
    private static final String BODY = "工单号：T-2";
    private static final String FROM = "noreply@badc.test";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void a_disabled_smtp_channel_is_not_configured_and_sends_nothing() {
        SmtpSender sender = sender(false);

        assertThat(sender.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(sender.isConfigured()).as("enabled=false 的渠道必须被 Task 5 记成 SKIPPED").isFalse();
        assertThatThrownBy(() -> sender.send(target("dev01@badc.test"), TITLE, BODY))
                .as("未配置的渠道被直接调用属于调用方 bug，要响亮地失败而不是静默吞掉")
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mailSender);
    }

    @Test
    void a_configured_smtp_channel_sends_exactly_one_message_to_the_target_email() throws Exception {
        SmtpSender sender = sender(true);

        sender.send(target("dev01@badc.test"), TITLE, BODY);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("dev01@badc.test");
        assertThat(message.getFrom()).as("from 取配置，而不是发件服务器随机兜底").isEqualTo(FROM);
        assertThat(message.getSubject()).isEqualTo(TITLE);
        assertThat(message.getText()).isEqualTo(BODY);
    }

    @Test
    void a_target_without_an_email_is_skipped_instead_of_failing() {
        SmtpSender sender = sender(true);

        assertThatCode(() -> sender.send(target(null), TITLE, BODY)).doesNotThrowAnyException();
        assertThatCode(() -> sender.send(target("   "), TITLE, BODY)).doesNotThrowAnyException();

        verifyNoInteractions(mailSender);
    }

    private SmtpSender sender(boolean enabled) {
        return new SmtpSender(properties(enabled), mailSender);
    }

    private static NotificationProperties properties(boolean enabled) {
        return new NotificationProperties(
                new NotificationProperties.Wecom(false, "", 0L, "", NotificationProperties.DEFAULT_WECOM_BASE_URL),
                new NotificationProperties.Mail(enabled, FROM),
                new NotificationProperties.Retry(3, 500));
    }

    private static NotificationTarget target(String email) {
        return new NotificationTarget(1L, "dev01", email, "dev01-wecom");
    }
}
