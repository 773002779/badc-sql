package com.badc.sql.control.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 把 {@link NotificationEvent} 交给投递器：<b>事务提交之后</b>、在<b>通知线程池</b>里。
 *
 * <h2>为什么是 AFTER_COMMIT</h2>
 * <p>业务服务在事务里发布事件（审批链就是典型：状态迁移、审核行、事件、审计、审计载荷都在同一个事务里）。
 * 若监听器在发布点同步执行，通知可能先于提交被发出去，而事务随后回滚——收件人看到一条根本不存在的记录。
 * {@code AFTER_COMMIT} 保证"只有真的提交了才通知"。
 *
 * <h2>为什么 fallbackExecution = true</h2>
 * <p>有几处发布点<b>不在事务里</b>：工单提交（{@code TicketService#submit} 刻意不标
 * {@code @Transactional}，因为结构指纹与 EXPLAIN 是外部调用）与执行终态（{@code TicketExecutionService}
 * 在异步线程里逐条自动提交）。这些地方发布时数据库里的事实<b>已经落地</b>，但
 * {@code TransactionSynchronizationManager} 没有活动同步，默认的 {@code AFTER_COMMIT} 监听器会把事件
 * 直接丢弃（Spring 只打一条 debug 日志）。允许 fallback 之后：
 * <ul>
 *   <li>有事务时仍然严格 AFTER_COMMIT（回滚则不通知）；</li>
 *   <li>没有事务时立即处理——此处"立即"只是把任务提交给线程池，业务线程依旧不等投递。</li>
 * </ul>
 *
 * <h2>为什么 @Async 指名 notificationExecutor</h2>
 * <p>见 {@link AsyncConfig}：上下文里有多个 {@code Executor}，依赖默认解析会退到无界的
 * {@code SimpleAsyncTaskExecutor}。这里点名保证"有界线程池 + 有界队列 + 记录日志的拒绝策略"。
 *
 * <h2>异常边界</h2>
 * <p>{@link NotificationDispatcher#dispatch} 自身绝不抛；这一层的 catch 只防御将来的改动。任何异常都不能
 * 逃进事务回调或线程池：前者会让一次已提交的业务看起来像失败，后者只会杀死工作线程。
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationDispatcher dispatcher;

    public NotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 事件到达后异步投递。
     *
     * @param event 通知事件（工单或授权申请），非 {@code null}
     */
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationEvent event) {
        try {
            dispatcher.dispatch(event.command());
        } catch (RuntimeException e) {
            // dispatch 的契约是"绝不抛"；真抛出来说明有人改坏了它，也只记录不升级。
            log.warn("通知投递出现未预期异常（事件 {}，工单 {}）：{}",
                    event.command().type(), event.command().ticketId(),
                    NotificationErrorText.sanitize(e));
        }
    }
}
