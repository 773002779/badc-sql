package com.badc.sql.control.notify;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.control.ticket.TicketQueryService;

/**
 * 通知读路径：'我的消息' 与 '某张工单的通知记录'。
 *
 * <h2>两条路径的可见性口径</h2>
 * <ul>
 *   <li>{@code mine}：只按 {@code recipient_id = 当前用户} 过滤。没有'给我看的全部通知'之外的入口——
 *       通知正文虽然不含 SQL，但"谁在什么时候因为什么被通知"本身是业务敏感信息。</li>
 *   <li>{@code byTicket}：复用 {@link TicketQueryService#requireVisible} 的工单可见性判据。
 *       能看工单的人才能看它通知了谁，两者是同一个谓词，不会一条严一条松。</li>
 * </ul>
 *
 * <p>分页与工单列表同口径（{@code total} 与 {@code items} 在同一个只读事务里，REPEATABLE READ 下取自同一
 * 快照），避免"total=5 但只有 4 条"的自相矛盾。页大小上限 100 与 {@code TicketQueryService} 一致。
 */
@Service
@Transactional(readOnly = true)
public class NotificationQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationStore store;
    private final TicketQueryService ticketQueryService;

    public NotificationQueryService(NotificationStore store, TicketQueryService ticketQueryService) {
        this.store = store;
        this.ticketQueryService = ticketQueryService;
    }

    /**
     * 当前用户收到的通知，按时间倒序。
     *
     * @param userId 当前登录用户
     * @param page   页码，从 1 开始；小于 1 按 1 处理
     * @param size   页大小；小于 1 按 1、大于 100 按 100 处理
     */
    public NotificationPage mine(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = store.countForRecipient(userId);
        long offset = (long) (safePage - 1) * safeSize;
        List<Item> items = store.pageForRecipient(userId, safeSize, (int) offset).stream()
                .map(NotificationQueryService::itemOf)
                .toList();
        return new NotificationPage(total, safePage, safeSize, items);
    }

    /**
     * 某张工单的全部通知记录（按 ID 升序，即投递发生顺序）。
     *
     * @throws com.badc.sql.common.error.BizException 工单不存在 → 404；存在但无权 → 403
     */
    public List<Item> byTicket(long userId, long ticketId) {
        ticketQueryService.requireVisible(userId, ticketId);
        return store.listForTicket(ticketId).stream().map(NotificationQueryService::itemOf).toList();
    }

    private static Item itemOf(NotificationRow row) {
        return new Item(
                row.id(),
                row.eventType().name(),
                row.systemId(),
                row.ticketId(),
                row.grantRequestId(),
                row.channel().name(),
                row.status().name(),
                row.title(),
                row.body(),
                row.attempts(),
                row.lastError(),
                row.createdAt(),
                row.sentAt());
    }

    /**
     * 一页通知。
     *
     * @param total 该收件人的通知总数
     * @param page  实际生效的页码
     * @param size  实际生效的页大小
     * @param items 当前页
     */
    public record NotificationPage(long total, int page, int size, List<Item> items) {

        public NotificationPage {
            items = List.copyOf(items);
        }
    }

    /** 一条通知（响应用；枚举以字符串给出，避免前端与后端枚举成员名字面量耦合）。 */
    public record Item(
            long id,
            String eventType,
            Long systemId,
            Long ticketId,
            Long grantRequestId,
            String channel,
            String status,
            String title,
            String body,
            int attempts,
            String lastError,
            Instant createdAt,
            Instant sentAt) {
    }
}
