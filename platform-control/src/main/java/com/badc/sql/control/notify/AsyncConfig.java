package com.badc.sql.control.notify;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 通知投递的异步执行器（{@code @Async} 用）。
 *
 * <h2>为什么必须有这个类，而不是让 {@code @Async} 用默认执行器</h2>
 * <p>不指定执行器时 Spring 会去找"唯一的一个 {@code TaskExecutor} bean"，找不到就用
 * {@code SimpleAsyncTaskExecutor}——它<b>每条任务新建一个线程且没有上界</b>。通知的触发点是工单提交/审批/执行，
 * 峰值时一屏操作就能把线程数顶到不可控；而且默认执行器连线程名都没有统一前缀，线上排查时无从分辨。
 * 本类给出三个明确的上界：
 * <ul>
 *   <li>线程数有界（core 2 / max 4）；</li>
 *   <li>队列有界（200）：积压不可能是无限的，满了就按拒绝策略处理，而不是把内存吃光；</li>
 *   <li>拒绝策略<b>记录日志</b>并与业务隔离（见 {@link LoggingCallerRunsHandler}）。</li>
 * </ul>
 *
 * <p>为什么 bean 名要显式写在 {@code @Async} 上（{@link #NOTIFICATION_EXECUTOR}）：上下文里还有
 * {@code ticketExecutor} 与 Spring Boot 自动配置的 {@code taskScheduler}，它们都是 {@code Executor}。
 * 依赖"默认执行器"的解析在 bean 数量变化时会安静地退回 {@code SimpleAsyncTaskExecutor}——那正是本类要避免的。
 *
 * <h2>两段投递为什么要分开</h2>
 * <p>业务事务提交（或执行线程落终态）之后才投递，业务线程只负责 {@code publishEvent}；真正的
 * resolve/渲染/落库/发送/重试在 {@link #NOTIFICATION_EXECUTOR} 的线程上跑。企业微信或 SMTP 卡住时，
 * 工单提交仍然是 201。
 *
 * <p>{@code waitForTasksToCompleteOnShutdown} 保持关闭：通知是尽力而为的副作用，停机时再等一轮外部
 * 渠道（最坏是每行 {@code max-attempts} 次退避）只会拖长停机；业务数据早已提交，未发的通知看
 * {@code notification} 里的 {@code PENDING}/{@code FAILED} 行即可。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 通知执行器的 bean 名；{@code @Async} 用它点名，避免落到默认/无界执行器上。 */
    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    /** 前缀独立于 {@code ticket-exec-}：线程栈一眼能看出这是通知而不是工单执行。 */
    static final String THREAD_NAME_PREFIX = "notify-async-";

    /** 核心线程数：通知的常规并发（提交/审批的点操作）远低于它。 */
    static final int CORE_POOL_SIZE = 2;

    /** 最大线程数：有界，防止渠道变慢时线程数无限增长。 */
    static final int MAX_POOL_SIZE = 4;

    /** 队列容量：有界，满了就交给拒绝策略，不把通知无限堆在内存里。 */
    static final int QUEUE_CAPACITY = 200;

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * 通知线程池：有界线程 + 有界队列 + 记录日志的拒绝策略。
     *
     * <p>{@code threadNamePrefix} 同时是测试的观察点：异步投递必须发生在
     * {@code notify-async-*} 线程上，而不是 HTTP 请求线程。
     */
    @Bean(NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new LoggingCallerRunsHandler());
        return executor;
    }

    /**
     * 拒绝策略：<b>先记录，再在提交线程里跑完</b>，绝不静默丢弃。
     *
     * <p>为什么不用 JDK 的 {@code CallerRunsPolicy}：它在执行器已停机时是
     * {@code if (!executor.isShutdown()) task.run();} ——<b>什么都不做、也不打日志</b>，通知就此消失且无人知晓。
     * 通知不像工单执行那样有"状态没落地"的外部证据，静默丢一条就是永久丢一条，所以这里两个分支都必须留下痕迹：
     * <ul>
     *   <li>未停机（4 个线程 + 200 队列都占着）→ 记 warn 后在提交线程里跑。宁可让这次请求多花一次投递的时间，
     *       也不丢通知；</li>
     *   <li>已停机 → 记 error 后放弃。此时业务事务早已提交（AFTER_COMMIT），没有任何东西可以回滚，
     *       唯一能做的是让日志说得清楚：这条通知没发出去。</li>
     * </ul>
     *
     * <p>public：用例要能直接钉住这两个分支——"停机时不静默"正是本策略存在的理由。
     */
    public static final class LoggingCallerRunsHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                log.error("通知线程池已停止，任务被丢弃（业务已提交，通知为尽力而为）；队列积压 {} 条",
                        executor.getQueue().size());
                return;
            }
            log.warn("通知线程池已满（active={}，queue={}），改为在提交线程内同步投递：{}",
                    executor.getActiveCount(), executor.getQueue().size(), task.getClass().getSimpleName());
            task.run();
        }
    }
}
