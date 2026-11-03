package com.badc.sql.control.notify;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.security.CurrentUser;

/**
 * 通知查询接口。
 *
 * <p>两个端点挂在同一个控制器上，但它们<b>不属于同一个前缀</b>：'我的消息'是全局的
 * （{@code /api/v1/notifications}），'某张工单的通知'是工单的子资源
 * （{@code /api/v1/tickets/{id}/notifications}）。刻意不在类级别写 {@code @RequestMapping}：
 * 若按类前缀拼成 {@code /api/v1/tickets/{id}} + {@code /notifications}，两个前缀就得二选一，
 * 要么把'我的消息'塞进工单命名空间，要么在 {@code TicketController} 里注入通知服务——
 * 后者会让工单控制器认识通知实现，这正是本切片刻意避免的耦合。
 *
 * <p>身份只来自 JWT（{@link CurrentUser}），请求里不接收 {@code recipientId}：否则任何人都能读别人的消息。
 */
@RestController
public class NotificationController {

    private final NotificationQueryService queryService;

    public NotificationController(NotificationQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 我收到的通知，按时间倒序。
     *
     * @param page 页码，默认 1
     * @param size 页大小，默认 20，上限 100
     */
    @GetMapping("/api/v1/notifications")
    public NotificationQueryService.NotificationPage mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.mine(CurrentUser.id(), page, size);
    }

    /**
     * 某张工单的全部通知记录。
     *
     * <p>需要能查看该工单（404 / 403 语义与工单详情一致）：通知记录会暴露'谁被通知了'，
     * 这不该比工单本身更开放。
     */
    @GetMapping("/api/v1/tickets/{id}/notifications")
    public List<NotificationQueryService.Item> byTicket(@PathVariable long id) {
        return queryService.byTicket(CurrentUser.id(), id);
    }
}
