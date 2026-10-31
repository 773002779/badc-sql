package com.badc.sql.control.ticket;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.user.UserRoleService;
import com.badc.sql.control.web.RequestMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 审批链：一级审核（运维）与二级终审（组长）。
 *
 * <h2>权限判定的顺序不可调换</h2>
 * <ol>
 *   <li>读工单，不存在 → 404；</li>
 *   <li>状态必须是 {@code REVIEWING_L1} / {@code REVIEWING_L2}，否则 <b>409</b>（不是静默无操作）；</li>
 *   <li>提交人不能审自己的单 → 403；</li>
 *   <li>必须是该工单所属系统的运维（{@code sys_system_operator}）→ 403；</li>
 *   <li>管理员一律不能审 → 403（§4.2：管理员只配置与授权）；</li>
 *   <li>按当前状态与整单等级分流，判定这一级该由谁审（{@link #requireLevelReviewer}，批准与驳回共用）。</li>
 * </ol>
 *
 * <h2>为什么 L3 的一级排除组长</h2>
 * <p>§7.1 是「运维一级 + 组长终审」。若某系统只配一名组长、且他既能做一级又能终审，L3 单会永远停在
 * {@code REVIEWING_L2}。把两级按角色拆开（一级只收 {@code PRIMARY_OPS}/{@code BACKUP_OPS}，二级只收
 * {@code OPS_LEAD}），这个死锁在结构上不可能出现；{@code reviewerId != 一级审核人} 仍保留作纵深防御
 * （今天的单值角色模型下不可达，角色将来变多值就会生效）。
 *
 * <h2>为什么 L3 一级通过前要复查组长</h2>
 * <p>组长可能在提单之后被撤销。让工单停在 {@code REVIEWING_L2} 无人可审是最坏结果，所以一级通过前再查一次
 * {@code count(LEAD)}，为 0 就给 409 和可操作的提示，工单保持 {@code REVIEWING_L1}。
 *
 * <h2>并发</h2>
 * <p>状态迁移用 {@code update ticket set status = ?, approved_at = ? where id = ? and status = ?}；影响行数
 * 为 0 即 409。两个组长同时终审时只有一个生效，另一个收到明确冲突。{@code uk_ticket_review(ticket_id,
 * review_level)} 是第二道闸：同一级重复写入被翻译成 409。
 */
@Service
@Transactional
public class TicketReviewService {

    private static final String L3 = "L3";

    /** L3 的一级只收运维；组长做终审（§7.1）。 */
    private static final Set<String> L3_FIRST_LEVEL_ROLES = Set.of("PRIMARY_OPS", "BACKUP_OPS");

    /** L1/L2 是单级审核：运维与组长都能审（§4.2「审核研发工单·一级」对组长为 ✓）。 */
    private static final Set<String> SINGLE_LEVEL_ROLES = Set.of("PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD");

    /** L3 的终审只收组长。 */
    private static final Set<String> LEAD_ONLY = Set.of("OPS_LEAD");

    private static final String ADMIN = "ADMIN";

    private static final String EVENT_APPROVED_L1 = "APPROVED_L1";
    private static final String EVENT_APPROVED_L2 = "APPROVED_L2";
    private static final String EVENT_REJECTED = "REJECTED";

    private static final String ACK_REVIEW = "REVIEW";
    private static final String ACK_L3_REVIEW = "L3_REVIEW";

    private final TicketMapper ticketMapper;
    private final TicketReviewMapper reviewMapper;
    private final TicketEventMapper eventMapper;
    private final RiskAcknowledgementMapper acknowledgementMapper;
    private final UserRoleService userRoleService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TicketReviewService(
            TicketMapper ticketMapper,
            TicketReviewMapper reviewMapper,
            TicketEventMapper eventMapper,
            RiskAcknowledgementMapper acknowledgementMapper,
            UserRoleService userRoleService,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.ticketMapper = ticketMapper;
        this.reviewMapper = reviewMapper;
        this.eventMapper = eventMapper;
        this.acknowledgementMapper = acknowledgementMapper;
        this.userRoleService = userRoleService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 审批通过一张待审工单。
     *
     * <p>级别与角色由 {@link #requireLevelReviewer} 判定（与 {@link #reject} 共用同一份实现）；本方法只补齐
     * "批准"特有的目标状态与终审可用性检查。
     *
     * <ul>
     *   <li>{@code REVIEWING_L1} + {@code L3} → 一级（主/备运维）→ {@code REVIEWING_L2}，落 {@code review_level=1}；</li>
     *   <li>{@code REVIEWING_L1} + 非 L3 → 一级（运维或组长）→ {@code APPROVED}，置 {@code approved_at}；</li>
     *   <li>{@code REVIEWING_L2} → 组长终审 → {@code APPROVED}，落 {@code review_level=2}。</li>
     * </ul>
     *
     * @param reviewerId 审批人（身份只来自 JWT）
     * @param ticketId   工单 ID
     * @param comment    审核意见，可空
     * @throws BizException 404 / 403 / 409，语义见类注释
     */
    public ReviewOutcome approve(long reviewerId, long ticketId, String comment) {
        TicketEntity ticket = requireReviewable(reviewerId, ticketId);
        TicketStatus from = TicketStatus.valueOf(ticket.status());
        String role = userRoleService.roleOf(reviewerId);
        String normalized = normalizeComment(comment);

        LevelPlan plan = requireLevelReviewer(ticket, reviewerId, role, from);
        if (plan.approvalTarget() == TicketStatus.REVIEWING_L2) {
            // 只有"批准并转入二级"才需要终审可用；直接驳回一张 L3 不该被无组长挡住。
            requireLeadStillPresent(ticket.systemId());
        }
        TicketStateMachine.assertTransition(from, plan.approvalTarget());
        // 一级的确认阶段是 REVIEW，二级是 L3_REVIEW（V3 的 ck_risk_acknowledgement_stage）。
        apply(ticket, reviewerId, plan.level(), TicketReviewMapper.DECISION_APPROVED, normalized,
                plan.approvalTarget(), true);
        return new ReviewOutcome(ticketId, plan.approvalTarget(), ticket.riskLevel());
    }

    /**
     * 驳回一张待审工单：状态 → {@code REJECTED}。
     *
     * <p>权限判定与 {@link #approve} <b>完全一致</b>：同样是前 5 步（状态 / 自审 / 归属 / 管理员）+ 共用的
     * {@link #requireLevelReviewer} 角色闸。L3 的二级终审既包括"批准执行"也包括"驳回不执行"，让一级运维
     * 替组长驳回等于跳过终审；同一人也不能在两级各占一行。审核意见必填（≥1 字）。
     *
     * @param comment 驳回原因，去除首尾空白后不得为空
     * @throws BizException 404 / 403 / 400（意见为空）/ 409（状态已变或该级已有审核记录）
     */
    public ReviewOutcome reject(long reviewerId, long ticketId, String comment) {
        TicketEntity ticket = requireReviewable(reviewerId, ticketId);
        String normalized = normalizeComment(comment);
        if (normalized == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "驳回必须填写审核意见");
        }

        TicketStatus from = TicketStatus.valueOf(ticket.status());
        LevelPlan plan = requireLevelReviewer(ticket, reviewerId, userRoleService.roleOf(reviewerId), from);
        TicketStateMachine.assertTransition(from, TicketStatus.REJECTED);
        apply(ticket, reviewerId, plan.level(), TicketReviewMapper.DECISION_REJECTED, normalized,
                TicketStatus.REJECTED, false);
        return new ReviewOutcome(ticketId, TicketStatus.REJECTED, ticket.riskLevel());
    }

    // ------------------------------------------------------------------ 权限判定

    /** 步骤 1–5：存在、状态可审、非提交人、本系统运维、非管理员。 */
    private TicketEntity requireReviewable(long reviewerId, long ticketId) {
        TicketEntity ticket = ticketMapper.findById(ticketId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工单不存在：" + ticketId));

        boolean reviewing = TicketStatus.REVIEWING_L1.name().equals(ticket.status())
                || TicketStatus.REVIEWING_L2.name().equals(ticket.status());
        if (!reviewing) {
            // 已通过/已驳回/已执行的单再点审批是状态冲突，不是"没有变化"。
            throw new BizException(ErrorCode.CONFLICT,
                    "工单当前状态为 " + ticket.status() + "，只有审核中的工单可以审批");
        }

        if (ticket.submitterId() != null && ticket.submitterId() == reviewerId) {
            throw new BizException(ErrorCode.FORBIDDEN, "提交人不能审核自己的工单");
        }

        // 归属先于管理员判定：管理员即使被误配进 sys_system_operator 也不能审。
        if (!reviewMapper.isOperatorOf(ticket.systemId(), reviewerId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只能审核自己负责的系统里的工单（systemId=" + ticket.systemId() + "）");
        }
        if (ADMIN.equals(userRoleService.roleOf(reviewerId))) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员只负责配置与授权，不参与工单审批");
        }
        return ticket;
    }

    /**
     * 这一级该由谁来审——{@link #approve} 与 {@link #reject} <b>唯一</b>的角色判定。
     *
     * <p>抽成一份的原因是驳回同样具有级别语义：L3 的二级终审既包括"批准执行"也包括"驳回不执行"。若在
     * {@code reject} 里另写一份角色判断，两份迟早分叉（本会话已因"同一判断多处实现"出过事故）。
     *
     * <p>返回审核级别与"若批准"的目标状态；驳回时目标状态由调用方固定为 {@code REJECTED}，这里不看。
     * 组长可用性（{@link #requireLeadStillPresent}）刻意留在 {@code approve}：它只约束"转入二级"，
     * 不该妨碍在无组长时直接驳回一张 L3。
     *
     * @throws BizException 角色不符 / 二级与一级同一人 → 403
     */
    private LevelPlan requireLevelReviewer(TicketEntity ticket, long reviewerId, String role, TicketStatus from) {
        if (from == TicketStatus.REVIEWING_L1) {
            if (L3.equals(ticket.riskLevel())) {
                // 组长不做 L3 的一级：两级按角色分开，单组长系统不会把 L3 单卡在二级。
                requireRole(role, L3_FIRST_LEVEL_ROLES, "L3 工单的一级审核只能由主运维或备运维执行");
                return new LevelPlan(1, TicketStatus.REVIEWING_L2);
            }
            requireRole(role, SINGLE_LEVEL_ROLES, "只有该系统的运维或组长可以审核工单");
            return new LevelPlan(1, TicketStatus.APPROVED);
        }
        // requireReviewable 已保证只剩 REVIEWING_L2。
        requireRole(role, LEAD_ONLY, "L3 工单的终审只能由运维组长执行");
        requireDifferentFromLevelOne(ticket.id(), reviewerId);
        return new LevelPlan(2, TicketStatus.APPROVED);
    }

    private static void requireRole(String role, Set<String> allowed, String message) {
        // Set.of(...).contains(null) 会抛 NPE：先判空。
        if (role == null || !allowed.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, message);
        }
    }

    /** L3 一级通过前复查组长：组长可能在提单后被撤销，不能让单子停在二级无人可审。 */
    private void requireLeadStillPresent(long systemId) {
        if (ticketMapper.countLeads(systemId) == 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该系统已无运维组长，L3 工单无人终审，请联系管理员指定组长");
        }
    }

    /** 纵深防御：两级不能是同一人（今天由角色分离结构性保证）。 */
    private void requireDifferentFromLevelOne(long ticketId, long reviewerId) {
        Optional<Long> firstReviewer = reviewMapper.reviewerIdAtLevel(ticketId, 1);
        if (firstReviewer.isPresent() && firstReviewer.get().longValue() == reviewerId) {
            throw new BizException(ErrorCode.FORBIDDEN, "两级审核不能由同一人完成");
        }
    }

    // ------------------------------------------------------------------ 落库

    /**
     * 写审核行、事件、风险确认、审计，最后用乐观并发迁移状态——全部在同一事务里。
     *
     * <p>状态更新放在最后：它不是"校验"而是"提交点"，0 行就整体回滚，不会留下一条孤立的审核记录。
     */
    private void apply(TicketEntity ticket, long reviewerId, int level, String decision, String comment,
            TicketStatus to, boolean acknowledgeRisk) {
        long ticketId = ticket.id();
        boolean approved = TicketReviewMapper.DECISION_APPROVED.equals(decision);

        writeReview(ticketId, level, reviewerId, decision, comment);
        eventMapper.append(ticketId, eventType(approved, level), reviewerId,
                json(detail(ticket, level, decision, comment)));

        if (acknowledgeRisk) {
            RequestMetadata.Client client = RequestMetadata.current();
            acknowledgementMapper.insert(ticketId, level == 1 ? ACK_REVIEW : ACK_L3_REVIEW, reviewerId,
                    ticket.riskSnapshotHash(), client.ip(), client.userAgent());
        }

        auditService.record(new AuditCommand(
                approved ? "TICKET_APPROVED" : "TICKET_REJECTED",
                reviewerId,
                null,
                ticket.datasourceId(),
                // 级别放进 action（l1/l2），个别理由放 payload；绝不写凭证。
                approved ? "l" + level : "reject",
                String.valueOf(ticketId),
                json(detail(ticket, level, decision, comment))));

        int moved = reviewMapper.moveStatus(ticketId, TicketStatus.valueOf(ticket.status()), to,
                to == TicketStatus.APPROVED ? clock.instant() : null);
        if (moved == 0) {
            throw new BizException(ErrorCode.CONFLICT, "工单状态已被其他审批人改变，请刷新后重试");
        }
    }

    /** 同一级只能有一条审核记录；唯一键冲突翻译成 409，不让原始 SQL 异常漏成 500。 */
    private void writeReview(long ticketId, int level, long reviewerId, String decision, String comment) {
        try {
            reviewMapper.insert(ticketId, level, reviewerId, decision, comment);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该工单的第 " + level + " 级审核记录已存在，不能重复审核");
        }
    }

    private static String eventType(boolean approved, int level) {
        if (!approved) {
            return EVENT_REJECTED;
        }
        return level == 1 ? EVENT_APPROVED_L1 : EVENT_APPROVED_L2;
    }

    /**
     * 事件明细 / 审计载荷：审核级别、结论、整单等级、归属与意见。
     *
     * <p>两者共用同一份内容，字段里没有连接串、账号或密码——审批不该接触凭证。
     */
    private static Map<String, Object> detail(TicketEntity ticket, int level, String decision, String comment) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewLevel", level);
        detail.put("decision", decision);
        detail.put("riskLevel", ticket.riskLevel());
        detail.put("systemId", ticket.systemId());
        detail.put("datasourceId", ticket.datasourceId());
        if (comment != null) {
            detail.put("comment", comment);
        }
        return detail;
    }

    /** 审核意见归一：空白（含全空格）视为没填。 */
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
            throw new IllegalStateException("审核事件序列化失败", e);
        }
    }

    /**
     * 审批结果：直接对应 200 正文。
     *
     * @param id        工单 ID
     * @param status    更新后的状态
     * @param riskLevel 整单风险等级（未变，前端刷新状态时一并拿到）
     */
    public record ReviewOutcome(long id, TicketStatus status, String riskLevel) {
    }

    /**
     * 这一级由谁来审、以及批准后会到哪里。
     *
     * @param level          审核级别（1 = 运维一级，2 = 组长终审）
     * @param approvalTarget 批准时的目标状态；驳回时由调用方固定为 {@code REJECTED}
     */
    private record LevelPlan(int level, TicketStatus approvalTarget) {
    }
}
