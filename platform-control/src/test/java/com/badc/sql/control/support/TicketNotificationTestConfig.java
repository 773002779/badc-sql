package com.badc.sql.control.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 把 {@link RecordingChannelSender} 注册成上下文里的一个额外 {@code ChannelSender}。
 *
 * <p>投递器注入的是 {@code List<ChannelSender>}，所以再加一个实现即可，不需要替换
 * {@code InAppSender} / {@code WeComSender} / {@code SmtpSender}：真实渠道在测试配置下全关
 * （{@code badcsql.notify.*.enabled=false}），只会落 {@code SKIPPED} 行、不碰网络。
 *
 * <p>只有通知类 IT 用 {@code @Import} 引入本类——其它工单 IT 不该凭空多出一条渠道。
 */
@TestConfiguration
public class TicketNotificationTestConfig {

    @Bean
    RecordingChannelSender recordingChannelSender() {
        return new RecordingChannelSender();
    }
}
