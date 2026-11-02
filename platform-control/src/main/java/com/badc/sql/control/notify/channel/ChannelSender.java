package com.badc.sql.control.notify.channel;

import com.badc.sql.control.notify.NotificationChannel;
import com.badc.sql.control.notify.NotificationTarget;

/**
 * 一个投递渠道的统一接口：把站内、企微、邮件藏在同一组方法后面。
 *
 * <p>Task 5 的投递器只认识这个接口：遍历所有实现，{@link #isConfigured()} 为 false 的渠道
 * 记一行 {@code SKIPPED}（渠道没配不算失败），为 true 的才调用
 * {@link #send(NotificationTarget, String, String)}。
 *
 * <p>为什么 {@code send} 允许抛 {@link Exception}：渠道失败是<b>投递层</b>的正常分支
 * （企微 errcode、SMTP 连接失败…），投递器要把异常消息脱敏后写进 {@code notification.last_error}
 * 并按 {@code badcsql.notify.retry.*} 重试。接口自己吞掉异常会让"失败可查"变成不可能；
 * 反过来，单个收件人/渠道抛异常也<b>不能</b>影响其他人——这由投递器保证，不是 sender 的责任。
 *
 * <p>{@code send} 的契约：
 * <ul>
 *   <li>实现必须自己判空联系方式（{@code target.email()} / {@code target.wecomUserId()}）：
 *       缺失时抛 {@code NotificationSkippedException}（R18），<b>不能静默 return</b>——
 *       {@code void send} 的静默返回在投递器眼里与"发送成功"无法区分，会把该行记成 {@code SENT}，
 *       通知没发出去却记成功。也不要把"跳过"做成失败异常：那会消耗重试并留下 FAILED 行；</li>
 *   <li>未配置（{@code isConfigured() == false}）却被直接调用属于调用方 bug，实现应响亮地失败
 *       （{@code IllegalStateException}），而不是静默吞掉；</li>
 *   <li>{@code title} / {@code body} 是渲染好的快照，sender 不再改写内容。</li>
 * </ul>
 */
public interface ChannelSender {

    /** 本实现负责的渠道；取值与 {@code notification.channel} 的 CHECK 一致。 */
    NotificationChannel channel();

    /** 渠道是否已配置（开关 + 必要凭证）。未配置的渠道由投递器记为 {@code SKIPPED}。 */
    boolean isConfigured();

    /**
     * 投递一条通知。
     *
     * @param target 收件人（联系方式可能为 {@code null}，实现自行判空并抛
     *               {@link com.badc.sql.control.notify.NotificationSkippedException}）
     * @param title  标题快照
     * @param body   正文快照
     * @throws com.badc.sql.control.notify.NotificationSkippedException 收件人缺少本渠道的联系方式，
     *         投递器据此记 {@code SKIPPED}（终态，不重试）
     * @throws Exception 渠道侧失败；由投递器记录 {@code last_error} 并重试
     */
    void send(NotificationTarget target, String title, String body) throws Exception;
}
