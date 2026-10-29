package com.badc.sql.control.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.badc.sql.control.query.ExecutorClient;

/**
 * {@link TicketStubExecutorConfig} 的装配：容器里的两个 bean 必须指向<b>同一个</b>桩实例。
 *
 * <p>工单 IT 拿不到同一个实例时，用例改的 {@code fingerprints} 传不到被测服务，漂移用例会静默地跑在默认值上
 * ——测试仍然绿，但什么都没验。这里不起库、不起 Web 容器，只验装配。
 */
class TicketStubExecutorConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TicketStubExecutorConfig.class);

    @Test
    void exposes_the_same_stub_under_both_types() {
        runner.run(context -> {
            TicketStubExecutor stub = context.getBean("ticketStubExecutor", TicketStubExecutor.class);
            ExecutorClient client = context.getBean("executorClient", ExecutorClient.class);

            assertThat(client).isSameAs(stub);

            // 用例改桩的字段，服务侧必须立刻看到——两个 bean 共享状态的意义就在这里。
            stub.fingerprints = Map.of("t_order", "fp-2");
            assertThat(client.fingerprint(null, List.of("t_order"))).containsEntry("t_order", "fp-2");
        });
    }
}
