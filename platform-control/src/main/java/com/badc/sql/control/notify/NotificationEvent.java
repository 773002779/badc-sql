package com.badc.sql.control.notify;

import java.util.Objects;

/**
 * 业务侧发给通知投递的 Spring 事件：只是给 {@link NotificationCommand} 套一层。
 *
 * <p><b>工单与授权申请共用</b>：两者都只是"有事发生，请按命令投递"，投递链路（开关、收件人、渲染、渠道、
 * 重试）完全一致。事件里没有任何"这是工单还是授权"的额外字段——那已经由
 * {@link NotificationCommand#type()} 表达，另加一个来源标记只会让两侧各自解释一遍。
 *
 * <p>为什么不直接把 {@link NotificationCommand} 本身当事件发布：Spring 的
 * {@code ApplicationEventPublisher} 允许任意对象作为事件，但"事件载荷为 {@code null}"是一个无法诊断的
 * 静默失败——容器会把它当成一次没有内容的分发，监听器要么收到 null 后 NPE，要么被过滤掉。用一个
 * record 显式包一层，并在这里拒绝 {@code null}：调用方写错时在<b>发布点</b>就炸，而不是在异步线程里
 * 留下一句没有上下文的 NPE。
 *
 * <p>它同时把业务与投递实现解耦：业务方法只知道"有事发生"，不需要认识 {@code NotificationDispatcher}，
 * 更不能自己决定"发不出去就算了"——投递策略（渠道、重试、开关、脱敏）全部收在监听器之后的链路里。
 *
 * @param command 待投递的通知命令，非 {@code null}
 */
public record NotificationEvent(NotificationCommand command) {

    public NotificationEvent {
        Objects.requireNonNull(command, "command 不能为空：发布一个空事件等于通知静默丢失");
    }
}
