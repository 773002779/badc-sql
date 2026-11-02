package com.badc.sql.control.notify;

/**
 * 渠道判定"这条通知<b>不该发</b>"时抛出：收件人没填该渠道的联系方式（邮箱 / 企微 userid）。
 *
 * <h2>为什么必须抛，而不能静默 return（R18）</h2>
 * <p>{@code ChannelSender.send} 的返回类型是 {@code void}，投递器只能靠"有没有抛异常"区分成功与失败。
 * 如果 sender 在联系方式缺失时直接 {@code return}，投递器会把这一行记成 {@code SENT}——
 * <b>通知根本没发出去，数据库里却记着成功</b>。这条错误没有任何症状：没有 FAILED 行、没有重试、
 * 没有日志，收件人只是永远收不到；等到有人问"为什么我没收到邮件"时，记录显示"已发送"。
 *
 * <p>所以"跳过"必须是一种显式的<b>结果</b>，而不是一个安静的返回。投递器捕获本异常后记
 * {@code SKIPPED}（{@code notification.last_error} 存异常消息作为跳过原因），
 * 既不算失败（不该消耗重试），也不会被误记成成功。
 *
 * <p>本异常是<b>终态</b>：联系方式缺失是确定性的，重试一千次也一样，投递器<b>不会</b>重试它。
 * 因此它不继承 {@link Exception} 之外的任何"可重试"语义，也不带 cause——跳过不是一个 bug，
 * 而是一个正常状态。
 *
 * <p><b>不要</b>用它表达"渠道未启用"：那是 {@code ChannelSender.isConfigured()} 的职责，
 * 投递器在调用 {@code send} 之前就据此记 {@code SKIPPED}，根本不会走到这里。
 */
public class NotificationSkippedException extends RuntimeException {

    /**
     * @param message 跳过原因，会原样写进 {@code notification.last_error}；写清是哪个联系方式缺失
     *                （例如"收件人未填写邮箱"），运维才能区分"没配"和"配错了"
     */
    public NotificationSkippedException(String message) {
        super(message);
    }
}
