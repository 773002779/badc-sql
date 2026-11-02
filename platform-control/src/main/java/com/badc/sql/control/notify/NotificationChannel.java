package com.badc.sql.control.notify;

/**
 * 投递渠道。
 *
 * <p><b>取值必须与 V4 迁移里 {@code notification.channel} 的 CHECK 逐字一致</b>：
 * {@code 'IN_APP' | 'WECOM' | 'EMAIL'}。落库写的是 {@link #name()}，差一个下划线或大小写，
 * MySQL 8.0.16+ 会直接拒绝这一行；更低版本会静默接受——而平台元数据库最低要求恰好是 8.0.16，
 * 所以这里没有"偷偷写进去"的余地。新增渠道时，迁移的 CHECK 与 enum 必须同一个提交里改。
 *
 * <p>{@code IN_APP} 的"投递"就是 {@code notification} 表里那一行记录本身（见
 * {@code InAppSender}），{@code WECOM} / {@code EMAIL} 才真正往外发。
 */
public enum NotificationChannel {

    /** 站内通知：落一行 {@code notification}，用户在平台里看到。 */
    IN_APP,

    /** 企业微信应用消息。 */
    WECOM,

    /** 邮件。 */
    EMAIL
}
