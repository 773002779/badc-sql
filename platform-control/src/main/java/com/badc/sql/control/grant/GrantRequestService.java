package com.badc.sql.control.grant;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.org.ModuleEntity;
import com.badc.sql.control.org.ModuleMapper;
import com.badc.sql.control.ticket.TicketReviewMapper;
import com.badc.sql.control.user.UserRoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权申请与审批（§4.4②③）。
 *
 * <h2>提交时的校验顺序是契约</h2>
 * <p>五条校验按"越基础越先"排列，前一条失败就不该看到后一条的报错——否则用户改对了理由却被另一条挡住，
 * 会以为理由没生效：
 * <ol>
 *   <li>模块不存在 → 404；</li>
 *   <li>理由为空 / 超长 → 400；</li>
 *   <li>该系统<b>没有任何运维归属</b> → 400。这一条最好在提交时就拦住：否则申请永远停在 PENDING，
 *       而申请人不知道自己等的那个"审批人"根本不存在（与 §4.7 组长缺位拦截同思路）；</li>
 *   <li>已有同模块的 PENDING → 409；</li>
 *   <li>已持有该模块授权且本次不再需要更多（没申请导出，或已可导出）→ 409。</li>
 * </ol>
 *
 * <h2>审批权限的判定顺序（与工单审批同一口径）</h2>
 * <ol>
 *   <li>申请单不存在 → 404；</li>
 *   <li>不是 PENDING → 409（不是静默无操作）；</li>
 *   <li>审批人 == 申请人 → 403（§4.3-2 四眼原则：自己批自己等于自助提权）；</li>
 *   <li>ADMIN → 403（§4.2：管理员只做配置与授权，审批权在系统运维）；</li>
 *   <li>不是该模块所属系统的运维 → 403。</li>
 * </ol>
 *
 * <h2>通过时写什么</h2>
 * <p>写 {@code user_module_grant}（幂等 upsert，只升不降）+ 迁移申请单状态 + 审计，同一事务。授权写入
 * 与状态迁移必须同生共死：只写授权会让申请永远待办，只迁移状态会让"已通过"却没有权限。
 * 状态迁移用乐观并发，影响行数为 0 即 409 并整体回滚。
 */
@Service
@Transactional
public class GrantRequestService {

    private static final String ADMIN = "ADMIN";

    /** 申请理由与审批意见的列宽（{@code VARCHAR(500)}）。超长在严格模式下会被 MySQL 直接拒绝。 */
    private static final int COMMENT_MAX_LENGTH = 500;

    private static final int MAX_PAGE_SIZE = 100;

    private static final String EVENT_REQUESTED = "GRANT_REQUESTED";
    private static final String EVENT_APPROVED = "GRANT_APPROVED";
    private static final String EVENT_REJECTED = "GRANT_REJECTED";

    private final GrantRequestStore store;
    private final UserModuleGrantStore grantStore;
    private final ModuleMapper moduleMapper;
    private final TicketReviewMapper reviewMapper;
    private final UserRoleService userRoleService;
    private final AuditService auditService;
    private final GrantNotificationPublisher notifications;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GrantRequestService(
            GrantRequestStore store,
            UserModuleGrantStore grantStore,
            ModuleMapper moduleMapper,
            TicketReviewMapper reviewMapper,
            UserRoleService userRoleService,
            AuditService auditService,
            GrantNotificationPublisher notifications,
            ObjectMapper objectMapper,
            Clock clock) {
        this.store = store;
        this.grantStore = grantStore;
        this.moduleMapper = moduleMapper;
        this.reviewMapper = reviewMapper;
        this.userRoleService = userRoleService;
        this.auditService = auditService;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 提交一条授权申请。
     *
     * @param userId  申请人（只来自 JWT）
     * @param command 模块、是否要导出、理由
     * @throws BizException 404 / 400 / 409，语义见类注释
     */
    public SubmitOutcome submit(long userId, GrantRequestCommand command) {
        if (command == null || command.moduleId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少模块 ID");
        }
        long moduleId = command.moduleId();
        ModuleEntity module = moduleMapper.findById(moduleId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "模块不存在：" + moduleId));

        String reason = normalizeReason(command.reason(), "申请理由");

        if (!store.hasOperator(module.systemId())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "该系统还没有运维，申请无人可批，请联系管理员");
        }
        if (store.findPending(userId, moduleId).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "你已有一条待审批的申请");
        }
        // 已有授权时只有"升级到导出"这一种申请是有意义的；其余都是重复申请。
        Optional<Boolean> existingExport = grantStore.findCanExport(userId, moduleId);
        if (existingExport.isPresent() && (!command.canExport() || existingExport.get())) {
            throw new BizException(ErrorCode.CONFLICT, "你已有该模块权限，无需申请");
        }

        long id = store.insert(userId, moduleId, command.canExport(), reason);
        GrantRequestRow row = new GrantRequestRow(id, userId, moduleId, command.canExport(), reason,
                GrantRequestStatus.PENDING.name(), null, null, clock.instant(), null);

        audit(EVENT_REQUESTED, userId, "submit", row, module.systemId(), null);
        notifications.requested(row);
        return new SubmitOutcome(id, GrantRequestStatus.PENDING.name());
    }

    // ------------------------------------------------------------------ 审批

    /**
     * 通过一条申请：写入授权 + 迁移状态 + 审计，同一事务。
     *
     * @throws BizException 404 / 409 / 403，语义见类注释
     */
    public ReviewOutcome approve(long reviewerId, long requestId, String comment) {
        Approval approval = requireApprovable(reviewerId, requestId);
        String normalized = normalizeComment(comment);

        // 先迁移状态（乐观并发闸门）：0 行说明别人已经处理过，此时不该留下任何授权写入。
        move(approval.request(), GrantRequestStatus.APPROVED, reviewerId, normalized);
        // 授权写入必须在事务内紧跟状态迁移：只改状态会让"已通过"却没有权限。
        grantStore.upsert(approval.request().userId(), approval.request().moduleId(),
                approval.request().canExport(), reviewerId);

        audit(EVENT_APPROVED, reviewerId, "approve", approval.request(), approval.systemId(), normalized);
        notifications.approved(approval.request(), normalized);
        return new ReviewOutcome(requestId, GrantRequestStatus.APPROVED.name(), approval.request().canExport());
    }

    /**
     * 驳回一条申请：只迁移状态与写审计，<b>不写任何授权</b>。
     *
     * @throws BizException 404 / 409 / 403 / 400（驳回原因为空），语义见类注释
     */
    public ReviewOutcome reject(long reviewerId, long requestId, String comment) {
        Approval approval = requireApprovable(reviewerId, requestId);
        String normalized = normalizeComment(comment);
        if (normalized == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "驳回必须填写审批意见");
        }

        move(approval.request(), GrantRequestStatus.REJECTED, reviewerId, normalized);
        audit(EVENT_REJECTED, reviewerId, "reject", approval.request(), approval.systemId(), normalized);
        notifications.rejected(approval.request(), normalized);
        return new ReviewOutcome(requestId, GrantRequestStatus.REJECTED.name(), approval.request().canExport());
    }

    /**
     * 审批前的全部检查（批准与驳回共用同一份，避免两份判断分叉）。
     *
     * @return 申请单与该模块所属系统
     */
    private Approval requireApprovable(long reviewerId, long requestId) {
        GrantRequestRow row = store.findById(requestId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "授权申请不存在：" + requestId));

        if (!row.isPending()) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该申请已处理（当前状态 " + row.status() + "）");
        }
        if (row.userId() == reviewerId) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能审批自己的申请");
        }
        // 归属先于管理员判定：管理员即使被误配进 sys_system_operator 也不能批。
        ModuleEntity module = moduleMapper.findById(row.moduleId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "申请单关联的模块已不存在：" + row.moduleId()));
        if (!reviewMapper.isOperatorOf(module.systemId(), reviewerId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只能审批自己负责的系统里的申请（systemId=" + module.systemId() + "）");
        }
        if (ADMIN.equals(userRoleService.roleOf(reviewerId))) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不参与授权审批");
        }
        return new Approval(row, module.systemId());
    }

    /** 状态迁移；0 行 → 409 并整体回滚（此时调用方已写的授权/审计都不会留下）。 */
    private void move(GrantRequestRow row, GrantRequestStatus to, long reviewerId, String comment) {
        int moved = store.moveStatus(row.id(), GrantRequestStatus.PENDING, to, reviewerId, comment,
                clock.instant());
        if (moved == 0) {
            throw new BizException(ErrorCode.CONFLICT, "该申请已被其他运维处理，请刷新后重试");
        }
    }

    // ------------------------------------------------------------------ 查询

    /** 我提交的申请，倒序分页。 */
    @Transactional(readOnly = true)
    public GrantPage mine(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = store.countForUser(userId);
        List<Item> items = store.pageForUser(userId, safeSize, (int) offset(safePage, safeSize)).stream()
                .map(GrantRequestService::itemOf).toList();
        return new GrantPage(total, safePage, safeSize, items);
    }

    /**
     * 待我审批：模块所属系统在我的 {@code sys_system_operator} 归属内的 PENDING。
     *
     * <p>不按角色过滤：研发调用时自然返回空列表——他不运维任何系统，语义上就是"没有待我批的"，
     * 不该是 403。归属是唯一判据（与审批时的 {@code isOperatorOf} 同一张表）。
     */
    @Transactional(readOnly = true)
    public GrantPage pending(long reviewerId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        long total = store.countPendingForReviewer(reviewerId);
        List<Item> items = store.pagePendingForReviewer(reviewerId, safeSize,
                (int) offset(safePage, safeSize)).stream().map(GrantRequestService::itemOf).toList();
        return new GrantPage(total, safePage, safeSize, items);
    }

    private static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    private static Item itemOf(GrantRequestRow row) {
        return new Item(row.id(), row.userId(), row.moduleId(), row.canExport(), row.reason(), row.status(),
                row.reviewerId(), row.reviewComment(), row.createdAt(), row.reviewedAt());
    }

    // ------------------------------------------------------------------ 工具

    private void audit(String eventType, long operatorId, String action, GrantRequestRow row, long systemId,
            String comment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grantRequestId", row.id());
        payload.put("moduleId", row.moduleId());
        payload.put("userId", row.userId());
        payload.put("systemId", systemId);
        payload.put("canExport", row.canExport());
        payload.put("status", eventType);
        if (comment != null) {
            payload.put("comment", comment);
        }
        auditService.record(new AuditCommand(eventType, operatorId, null, null, action,
                String.valueOf(row.id()), json(payload)));
    }

    /** 申请理由：去首尾空白后必须非空且不超列宽。 */
    private static String normalizeReason(String reason, String field) {
        String normalized = normalizeComment(reason);
        if (normalized == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        if (normalized.length() > COMMENT_MAX_LENGTH) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    field + "不能超过 " + COMMENT_MAX_LENGTH + " 字");
        }
        return normalized;
    }

    /** 空白（含全空格）视为没填。 */
    private static String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String stripped = comment.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("授权申请审计载荷序列化失败", e);
        }
    }

    /** 提交结果：{@code 201 {id, status}}。 */
    public record SubmitOutcome(long id, String status) {
    }

    /** 审批结果：状态与本次申请的导出权（前端据此提示"已授权导出"）。 */
    public record ReviewOutcome(long id, String status, boolean canExport) {
    }

    /** 一页申请。 */
    public record GrantPage(long total, int page, int size, List<Item> items) {

        public GrantPage {
            items = List.copyOf(items);
        }
    }

    /** 一条申请（响应视图）。 */
    public record Item(
            long id,
            long userId,
            long moduleId,
            boolean canExport,
            String reason,
            String status,
            Long reviewerId,
            String reviewComment,
            java.time.Instant createdAt,
            java.time.Instant reviewedAt) {
    }

    /** 申请单 + 模块所属系统。 */
    private record Approval(GrantRequestRow request, long systemId) {
    }
}
