package com.badc.sql.control.notify.channel;

import org.springframework.stereotype.Component;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationTarget;

/**
 * 站内渠道：<b>投递就是 {@code notification} 表里那一行记录本身</b>。
 *
 * <p>{@link #isConfigured()} 恒为 true——站内消息不需要任何外部凭证，也不需要用户填邮箱或企微账号；
 * 只要平台在跑，每个人就都能收到站内通知。这条恒真是"缺联系方式的人至少还有站内消息"的最后一道保障。
 *
 * <p>{@link #send(NotificationTarget, String, String)} 是<b>空实现</b>，不是遗漏：Task 5 的投递器
 * 在调用 sender 之前已经按"收件人 × 渠道"写好了 {@code PENDING} 行，站内渠道没有第二个外部动作，
 * 成功后把它标成 {@code SENT} 即可。若在这里再写一次库，就会变成两行记录、两套状态。
 */
@Component
public class InAppSender implements ChannelSender {

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
        // 站内投递 = 已落库的那行 notification；这里没有外部副作用。
    }
}
