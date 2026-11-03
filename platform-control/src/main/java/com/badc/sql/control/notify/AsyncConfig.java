package com.badc.sql.control.notify;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

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
 *   <li>拒绝策略<b>记录 error 后丢弃</b>：不静默、也绝不在业务线程上跑（见 {@link LoggingDiscardHandler}）。</li>
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
        executor.setRejectedExecutionHandler(new LoggingDiscardHandler());
        return executor;
    }

    /**
     * 拒绝策略：<b>记录 error，然后丢弃</b>——既不在业务线程上跑，也不静默。
     *
     * <p>为什么<b>不</b>用 CallerRuns（JDK 的或自己写的）：池满时在提交线程里跑投递，意味着
     * <b>HTTP 请求线程要跑完整个渠道重试</b>（最坏 3 次尝试 + 500/1000ms 退避），直接违反 R22
     * "通知不阻塞业务线程"。更隐蔽的后果：发布点将来若带事务，这个 inline run 会发生在
     * {@code afterCommit} 里、连接仍绑定，投递器写下的 {@code notification} 行会随连接释放而丢失——
     * 通知没发出去，连失败记录都没有。
     *
     * <p>两个分支都是"丢弃 + 留痕"：
     * <ul>
     *   <li>池满（4 线程 + 200 队列都占着）→ {@code log.error}，带池状态与任务标识，便于事后按工单补发；</li>
     *   <li>已停机 → {@code log.error}，说明进程正在退出。</li>
     * </ul>
     *
     * <p><b>能记到哪一层</b>：拒绝发生在 {@code @Async} 代理把任务交给线程池的那一刻，监听器方法体还没进入，
     * 所以这里拿不到 eventType/systemId——能拿到的是任务对象的类名（通常就是监听器 lambda）与池状态。
     * 要带完整事件上下文，只能放弃 {@code @Async} 改由监听器自己 {@code execute}（那会偏离任务简报的
     * 异步设计），故保留现状并在日志里给出足够的定位线索。
     *
     * <p>public：用例要直接钉住"被拒绝的任务一次都没跑"——这正是本策略存在的理由。
     */
    public static final class LoggingDiscardHandler implements RejectedExecutionHandler {

        /** 丢弃计数：进程内累计，便于用日志/指标观察"通知池是否长期打满"。 */
        private static final AtomicLong DROPPED = new AtomicLong();

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            long dropped = DROPPED.incrementAndGet();
            if (executor.isShutdown()) {
                log.error("通知线程池已停止，任务被丢弃（业务已提交，通知为尽力而为）；累计丢弃 {} 条，队列 {} 条，任务 {}",
                        dropped, executor.getQueue().size(), task.getClass().getName());
                return;
            }
            log.error("通知线程池已满（active={}，queue={}），任务被丢弃；累计丢弃 {} 条，任务 {}。"
                            + "业务不受影响，但这条通知不会重发——需要时按工单手工补发",
                    executor.getActiveCount(), executor.getQueue().size(), dropped, task.getClass().getName());
        }
    }
}
