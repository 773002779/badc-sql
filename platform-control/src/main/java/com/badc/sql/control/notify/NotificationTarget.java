package com.badc.sql.control.notify;

/**
 * 一个通知收件人及其可用的联系方式快照。
 *
 * <p>由 {@link NotificationTargetStore#load(java.util.List)} 从 {@code sys_user} 读出。
 * <b>联系方式缺失是正常状态，不是错误</b>：{@code email} / {@code wecomUserId} 允许为 {@code null}
 * （或空白），由各渠道 sender 自己判空跳过。这样"某人没填邮箱"只影响邮件渠道，
 * 不会让他的站内消息也跟着消失——这正是 {@code NotificationTargetStore} 不做过滤的原因。
 *
 * @param userId      用户 ID（{@code sys_user.id}）
 * @param loginName   登录名（站内展示与排查用，非 {@code null}）
 * @param email       通知收件邮箱；未填写时为 {@code null}
 * @param wecomUserId 企微 userid；未填写时为 {@code null}
 */
public record NotificationTarget(long userId, String loginName, String email, String wecomUserId) {
}
