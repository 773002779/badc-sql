package com.badc.sql.control.notify;

import java.time.Instant;
import java.util.List;

/**
 * {@code notification} 表的写入口：投递器每处理"一个收件人 × 一个渠道"就写一行。
 *
 * <p><b>为什么按行落库</b>：失败必须收敛到最小范围。整批一条记录的话，一个人失败就说不清是谁、
 * 是哪个渠道；按行记录后，重试次数（{@code attempts}）、失败原因（{@code last_error}）、
 * 成功时间（{@code sent_at}）都是行级事实，运维可以只看 {@code FAILED} 行。
 *
 * <p>行先以 {@code PENDING} 写下再发送，而不是发送成功后才写：投递进程若在发送中途崩溃，
 * 留下的 {@code PENDING} 行是<b>唯一</b>能说明"这条可能发出去了也可能没有"的证据。反过来
 * "先发后写"会在写入失败时彻底丢掉痕迹。
 *
 * <p>{@link #markFailed} 的 {@code error} 必须由实现截断到列宽（{@code VARCHAR(500)}）：
 * 调用方传的是异常消息，长度不可控，而严格的 MySQL 会直接用 Data too long 拒绝整行——
 * 那等于在最需要记录失败原因的时候把失败记录本身也丢了。
 */
public interface NotificationStore {

    /**
     * 写一行 {@code PENDING}，{@code attempts = 0}。
     *
     * @param recipientId    收件人用户 ID
     * @param type           事件类型，非 {@code null}（落库写 {@link NotificationEventType#name()}）
     * @param systemId       相关系统，可空
     * @param ticketId       相关工单，可空
     * @param grantRequestId 相关授权申请，可空
     * @param channel        渠道，非 {@code null}
     * @param title          标题快照
     * @param body           正文快照
     * @return 自增主键
     */
    long insert(long recipientId, NotificationEventType type, Long systemId, Long ticketId, Long grantRequestId,
            NotificationChannel channel, String title, String body);

    /**
     * 标记发送成功。
     *
     * @param id       通知 ID
     * @param attempts 实际尝试次数（首次即成功为 1，第二次才成功为 2）
     * @param sentAt   成功时间（UTC），取注入的时钟
     */
    void markSent(long id, int attempts, Instant sentAt);

    /**
     * 标记重试用尽后失败。
     *
     * @param id       通知 ID
     * @param attempts 实际尝试次数
     * @param error    失败原因（<b>脱敏后</b>）；实现负责截断到 {@code last_error} 的列宽
     */
    void markFailed(long id, int attempts, String error);

    /**
     * 标记未发送（渠道未启用 / 收件人缺联系方式）。不算失败，也不改 {@code attempts}。
     *
     * @param id     通知 ID
     * @param reason 跳过原因；落库到 {@code last_error}（表里没有独立的跳过原因列）
     */
    void markSkipped(long id, String reason);

    /**
     * 某个工单的失败通知数（只数 {@code FAILED}）。
     *
     * @param ticketId 工单 ID
     * @return 失败行数；没有记录时 0
     */
    int failureCount(long ticketId);

    /**
     * 某个工单的全部通知记录，按 ID 升序。
     *
     * @param ticketId 工单 ID
     * @return 该工单的通知行；没有记录时返回空列表，绝不为 {@code null}
     */
    List<NotificationRow> listForTicket(long ticketId);
}
