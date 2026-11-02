package com.badc.sql.control.notify;

import java.time.Instant;

/**
 * {@code notification} 表一行的只读快照，供运维查询与测试断言。
 *
 * <p>字段与 V4 的列一一对应，顺序不重要、名字要能对上：排查时对着这一行就能回答
 * "发给谁、走哪个渠道、成没成、试了几次、为什么没成"。
 *
 * <p>时间统一是 {@link Instant}（UTC）。{@code created_at} 由数据库默认值写入，因此插入时不需要传；
 * {@code sent_at} 只在 {@code SENT} 行上有值。
 *
 * @param id             通知 ID
 * @param eventType      事件类型（落库值 {@code event_type}）
 * @param systemId       相关业务系统；授权类事件可能为 {@code null}
 * @param ticketId       相关工单；非工单事件为 {@code null}
 * @param grantRequestId 相关授权申请；非授权事件为 {@code null}
 * @param recipientId    收件人用户 ID
 * @param channel        投递渠道
 * @param status         投递状态
 * @param title          标题快照（渲染结果，不含 SQL 正文）
 * @param body           正文快照（渲染结果，不含 SQL 正文）
 * @param attempts       已尝试投递次数；{@code SKIPPED} 行保持 0
 * @param lastError      最后一次失败原因（{@code FAILED}）或跳过原因（{@code SKIPPED}）；其余为 {@code null}
 * @param createdAt      创建时间（UTC）
 * @param sentAt         发送成功时间（UTC）；未成功为 {@code null}
 */
public record NotificationRow(
        long id,
        NotificationEventType eventType,
        Long systemId,
        Long ticketId,
        Long grantRequestId,
        long recipientId,
        NotificationChannel channel,
        NotificationStatus status,
        String title,
        String body,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant sentAt) {
}
