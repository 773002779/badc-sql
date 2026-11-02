package com.badc.sql.control.notify;

/**
 * 一条投递记录的状态。
 *
 * <p><b>取值必须与 V4 迁移里 {@code notification.status} 的 CHECK 逐字一致</b>：
 * {@code 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED'}。落库写的是 {@link #name()}；改名等于改历史数据，
 * 且 MySQL 8.0.16+ 会直接拒绝 CHECK 之外的值。
 *
 * <p>四个状态的语义（这是运维看报表时的口径，不要混用）：
 * <ul>
 *   <li>{@link #PENDING}——已落库、还没出结果。正常投递结束后不该留下这个状态的行；
 *       留下就说明投递进程中途死了，可以被巡检捞出来重投；</li>
 *   <li>{@link #SENT}——渠道确实发出去了。站内渠道的"发送"就是这一行记录本身；</li>
 *   <li>{@link #FAILED}——重试用尽仍失败，{@code last_error} 里有脱敏后的原因；</li>
 *   <li>{@link #SKIPPED}——<b>不是失败</b>：要么渠道没启用，要么收件人没填该渠道的联系方式
 *       （R18）。不算失败、不消耗重试，但必须有记录，否则"这个人为什么没收到"永远查不清。</li>
 * </ul>
 */
public enum NotificationStatus {

    /** 已落库，等待投递结果。 */
    PENDING,

    /** 已发送成功。 */
    SENT,

    /** 重试用尽仍失败。 */
    FAILED,

    /** 未发送（渠道未启用 / 收件人缺联系方式）；不算失败。 */
    SKIPPED
}
