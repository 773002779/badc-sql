package com.badc.sql.control.notify.channel;

import org.springframework.lang.Nullable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationProperties;
import com.badc.sql.control.notify.NotificationTarget;

/**
 * 邮件渠道：{@link SimpleMailMessage} + {@link JavaMailSender}，from 取
 * {@code badcsql.notify.mail.from}。
 *
 * <p>{@link #isConfigured()} 只看 {@code mail.enabled}：这是"产品上是否开启邮件通知"的开关。
 * 为 false 时投递器记 {@code SKIPPED}，一次 SMTP 都不碰。
 *
 * <p>{@link JavaMailSender} 参数是 {@link Nullable}：Boot 的 {@code MailSenderAutoConfiguration} 只在
 * 配了 {@code spring.mail.host} 时才创建这个 bean。控制面不应该因为"某个部署没配 SMTP"就起不来——
 * 邮件渠道默认关闭（{@code enabled=false}），没配 SMTP 的人本来也不会开启它。真出现
 * "开启了却没有 JavaMailSender"，在 {@code send} 时抛出带配置提示的异常，由投递器记
 * {@code FAILED} + {@code last_error}，而不是启动期炸掉整个控制面。
 *
 * <p><b>没填邮箱的人必须被跳过而不是报错</b>：{@code target.email()} 为 {@code null} 或空白时直接返回。
 * 一个运维没填邮箱是正常状态，不能让他的这一条通知变成 {@code FAILED}——更不能影响其他收件人。
 */
@Component
public class SmtpSender implements ChannelSender {

    private final NotificationProperties properties;
    private final JavaMailSender mailSender;

    public SmtpSender(NotificationProperties properties, @Nullable JavaMailSender mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean isConfigured() {
        return properties.mail().enabled();
    }

    @Override
    public void send(NotificationTarget target, String title, String body) {
        if (!isConfigured()) {
            throw new IllegalStateException("邮件渠道未启用（badcsql.notify.mail.enabled=false）");
        }
        String email = target.email();
        if (email == null || email.isBlank()) {
            return;
        }
        if (mailSender == null) {
            throw new IllegalStateException(
                    "邮件渠道已启用，但没有可用的 JavaMailSender：请配置 spring.mail.host 与发件凭证");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        String from = properties.mail().from();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(email);
        message.setSubject(title);
        message.setText(body);
        mailSender.send(message);
    }
}
