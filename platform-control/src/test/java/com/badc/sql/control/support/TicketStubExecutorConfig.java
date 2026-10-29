package com.badc.sql.control.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.badc.sql.control.query.ExecutorClient;

/**
 * 把 {@link TicketStubExecutor} 注册成 {@link ExecutorClient} 的测试 bean。
 *
 * <p>工单类 IT 用 {@code @Import(TicketStubExecutorConfig.class)} 引入（同
 * {@code QueryControllerIT.StubExecutorConfig} 的写法）。两个 {@code @Bean} 返回<b>同一个实例</b>：
 * 容器把它注入给被测服务，用例则按类型注入 {@link TicketStubExecutor} 去设字段，两边必须是同一份状态，
 * 否则"桩里改了漂移指纹"传不到控制面。
 */
@TestConfiguration
public class TicketStubExecutorConfig {

    @Bean
    TicketStubExecutor ticketStubExecutor() {
        return new TicketStubExecutor();
    }

    @Bean
    ExecutorClient executorClient(TicketStubExecutor stub) {
        return stub;
    }
}
