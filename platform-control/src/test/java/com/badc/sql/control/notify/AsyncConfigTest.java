package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * 通知线程池的拒绝策略：<b>记录后丢弃，绝不在业务线程上跑</b>。
 *
 * <p>为什么不用"把线程池真正打满"来测：那要么依赖时序（偶发）、要么让用例跑一遍完整的渠道重试（慢且脆）。
 * 拒绝策略本身是个纯函数式的钩子——直接给它一个记录型 {@link Runnable}，调用
 * {@link AsyncConfig.LoggingDiscardHandler#rejectedExecution}，就能确定性地断言"任务没被执行"。
 *
 * <p>这条断言是 R22 的最后一道守卫：CallerRuns（或任何"池满就在提交线程里跑"的写法）会让
 * HTTP 请求线程承担一次完整投递（含 3 次尝试与退避），而这在用例里表现为 {@code ran == true} → 立刻红。
 */
class AsyncConfigTest {

    @Test
    void a_full_pool_drops_the_task_instead_of_running_it_on_the_caller() {
        AtomicBoolean ran = new AtomicBoolean();
        ThreadPoolExecutor pool = newPool();
        try {
            new AsyncConfig.LoggingDiscardHandler().rejectedExecution(() -> ran.set(true), pool);
        } finally {
            pool.shutdownNow();
        }

        assertThat(ran)
                .as("池满时必须在提交线程上丢弃（记录 error），绝不能把投递跑在业务线程里")
                .isFalse();
    }

    @Test
    void a_shutting_down_pool_drops_the_task_instead_of_running_it() {
        AtomicBoolean ran = new AtomicBoolean();
        ThreadPoolExecutor pool = newPool();
        pool.shutdown();
        try {
            new AsyncConfig.LoggingDiscardHandler().rejectedExecution(() -> ran.set(true), pool);
        } finally {
            pool.shutdownNow();
        }

        assertThat(ran).as("停机时同样只记录、不执行").isFalse();
    }

    /** 一个确有界、但绝不会被本用例真正用到的池（拒绝策略不关心它是否饱和）。 */
    private static ThreadPoolExecutor newPool() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }
}
