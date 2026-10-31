package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.ticket.RiskAcknowledgementMapper.AckRow;
import com.badc.sql.control.user.UserRoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 执行前的风险确认闸：把「研发看到的风险」与「执行时的风险」绑成同一份。
 *
 * <h2>为什么确认只在服务端算哈希</h2>
 * <p>确认行的 {@code risk_snapshot_hash} 一律取自工单当前的 {@code risk_snapshot_hash}，<b>端点不接受客户端
 * 传值</b>。客户端能传哈希，就等于能伪造"我确认过最新风险"——先在旧风险上确认一次，再把风险改大后拿旧
 * 确认去换执行，闸门形同虚设。所以 {@link #acknowledgeExecution} 根本没有哈希参数，这不是遗漏。
 *
 * <h2>为什么执行前要再比对一次</h2>
 * <p>确认之后风险仍可能变化（语句被改、等级因重新估算升降），而确认行是 append-only 的历史。执行前必须把
 * {@code currentHash}（由调用方用 {@link TicketRiskSnapshot} 重新算出）与<b>最新一条</b> EXECUTE 确认比对：
 * 不等就说明"他确认的那份风险"不是"即将执行的那份风险"，必须重新确认。
 *
 * <p>{@link #assertExecuteAckCurrent} 刻意<b>不自己重算哈希</b>：重算需要重新跑 EXPLAIN 与结构指纹，那是执行
 * 编排（任务 11）的职责；藏在本方法里会让"谁算的、算的是哪一刻的风险"变得不可见。本方法只做比对。
 *
 * <h2>确认人</h2>
 * <p>提交人本人，或该系统的运维/组长（{@code sys_system_operator} 有归属）。管理员<b>不参与执行链</b>
 * （§4.2：只配置与授权），即使被误配成运维也拒绝——判定顺序刻意把 ADMIN 放在归属之前，是为了让拒绝理由
 * 直说角色而不是"你不是运维"。
 *
 * <h2>不幂等</h2>
 * <p>重复确认每次都留一行：多确认一次本身就是证据（"他在风险变化后又看了一遍"）。有效性由最新一行决定，
 * 旧行不删不改。
 */
@Service
@Transactional
public class RiskAckService {

    private static final String ADMIN = "ADMIN";

    private static final String AUDIT_RISK_ACKNOWLEDGED = "RISK_ACKNOWLEDGED";

    /** 审计里的 {@code action}：这条确认属于执行阶段。 */
    private static final String AUDIT_ACTION_EXECUTE = "execute";

    /** 审计只留哈希前 12 位：整串 64 位十六进制在审计里是纯噪音，同一条工单的行已经能追到全量。 */
    private static final int HASH_PREFIX_LENGTH = 12;

    private final TicketMapper ticketMapper;
    private final TicketReviewMapper reviewMapper;
    private final RiskAcknowledgementMapper acknowledgementMapper;
    private final UserRoleService userRoleService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public RiskAckService(
            TicketMapper ticketMapper,
            TicketReviewMapper reviewMapper,
            RiskAcknowledgementMapper acknowledgementMapper,
            UserRoleService userRoleService,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.ticketMapper = ticketMapper;
        this.reviewMapper = reviewMapper;
        this.acknowledgementMapper = acknowledgementMapper;
        this.userRoleService = userRoleService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录一次执行前风险确认（{@code POST /api/v1/tickets/{id}/risk-ack}）。
     *
     * <p>哈希取自工单当前值，不接受调用方传入：这是"服务端自己算"的唯一实现方式——只要参数里出现哈希，
     * 就有人会把它当成客户端可以决定的值。
     *
     * <p>判定顺序不可调换：
     * <ol>
     *   <li>读工单，不存在 → 404；</li>
     *   <li>状态必须是 {@code APPROVED}，否则 409（提交前/审核中/已执行/已驳回都不是"执行前"）；</li>
     *   <li>管理员 → 403（即使被误配成本系统运维）；</li>
     *   <li>提交人本人，或该系统的运维/组长 → 放行；其它 → 403。</li>
     * </ol>
     *
     * 全部通过后才写确认行与审计，二者在同一事务里：不会出现"审计说确认了、表里没有"。
     *
     * @param userId    确认人（身份只来自 JWT）
     * @param ticketId  工单 ID
     * @param ip        来源 IP，可空；落库前截断到列宽
     * @param userAgent User-Agent 原文，可空；落库前截断到列宽
     * @return 本次确认（工单 ID、阶段、哈希、确认时间）
     * @throws BizException 404 工单不存在 / 409 状态不是 APPROVED / 403 确认人无权
     */
    public AckOutcome acknowledgeExecution(long userId, long ticketId, String ip, String userAgent) {
        TicketEntity ticket = ticketMapper.findById(ticketId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工单不存在：" + ticketId));

        if (!TicketStatus.APPROVED.name().equals(ticket.status())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "工单当前状态为 " + ticket.status() + "，只有审批通过（APPROVED）的工单可以确认执行风险");
        }

        if (ADMIN.equals(userRoleService.roleOf(userId))) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员只负责配置与授权，不参与执行链");
        }
        if (!mayConfirmExecution(ticket, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有提交人或该系统的运维/组长可以确认执行风险");
        }

        String riskSnapshotHash = ticket.riskSnapshotHash();
        acknowledgementMapper.insert(ticketId, AckStage.EXECUTE.name(), userId, riskSnapshotHash, ip, userAgent);

        // 响应里的确认时间必须是落库的那一行：直接读回来，而不是拿 JVM 时钟另算一个（两者可能差几毫秒，
        // 前端据此判断"我确认的那一行"会落空）。
        AckRow row = acknowledgementMapper.findLatest(ticketId, AckStage.EXECUTE.name())
                .orElseThrow(() -> new IllegalStateException("风险确认写入后读不到刚插入的行"));

        auditService.record(new AuditCommand(
                AUDIT_RISK_ACKNOWLEDGED,
                userId,
                null,
                ticket.datasourceId(),
                AUDIT_ACTION_EXECUTE,
                String.valueOf(ticketId),
                auditPayload(riskSnapshotHash)));

        return new AckOutcome(row.ticketId(), AckStage.valueOf(row.stage()),
                row.riskSnapshotHash(), row.createdAt());
    }

    /**
     * 执行前校验：该工单的最新一条 {@code EXECUTE} 确认必须与 {@code currentHash} 相同。
     *
     * <p>任务 11 在真正下发执行前调用：先用 {@link TicketRiskSnapshot} 重新算出当前哈希，再传进来比对。
     * <b>本方法不重算</b>——重算会把"哈希从哪来"藏进这里，也会让一次校验悄悄多跑一遍 EXPLAIN。
     *
     * @param ticketId    工单 ID
     * @param currentHash 调用方重新算出的整单风险快照哈希
     * @throws BizException 从未确认过（"执行前必须完成风险确认"）或哈希已变（"风险信息已变更，请重新确认后再执行"），
     *                      两者都是 {@link ErrorCode#FORBIDDEN}
     */
    public void assertExecuteAckCurrent(long ticketId, String currentHash) {
        AckRow latest = acknowledgementMapper.findLatest(ticketId, AckStage.EXECUTE.name())
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN, "执行前必须完成风险确认"));

        // 应用层 equals：哈希是小写十六进制，SQL 比较在 _ai_ci 排序规则下不区分大小写，会把 A 与 a 混为一谈。
        if (!latest.riskSnapshotHash().equals(currentHash)) {
            throw new BizException(ErrorCode.FORBIDDEN, "风险信息已变更，请重新确认后再执行");
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 提交人本人，或该系统的运维/组长。
     *
     * <p>归属查询复用 {@link TicketReviewMapper#isOperatorOf}：审批归属与执行归属问的是同一张
     * {@code sys_system_operator}，另写一份 SQL 迟早与它分叉。
     */
    private boolean mayConfirmExecution(TicketEntity ticket, long userId) {
        if (ticket.submitterId() != null && ticket.submitterId() == userId) {
            return true;
        }
        return reviewMapper.isOperatorOf(ticket.systemId(), userId);
    }

    /** 审计载荷：只留阶段与哈希前 12 位。字段里没有连接串、账号或密码——确认风险不该接触凭证。 */
    private String auditPayload(String riskSnapshotHash) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", AckStage.EXECUTE.name());
        payload.put("riskSnapshotHashPrefix", hashPrefix(riskSnapshotHash));
        return json(payload);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("风险确认审计序列化失败", e);
        }
    }

    private static String hashPrefix(String hash) {
        if (hash == null || hash.length() <= HASH_PREFIX_LENGTH) {
            return hash;
        }
        return hash.substring(0, HASH_PREFIX_LENGTH);
    }

    /**
     * 一次执行前确认的结果，直接对应 200 正文。
     *
     * @param ticketId         工单 ID
     * @param stage            确认阶段（端点恒为 {@code EXECUTE}）
     * @param riskSnapshotHash 服务端落库的哈希（工单当前值）
     * @param acknowledgedAt   确认时间（UTC，取自落库行）
     */
    public record AckOutcome(long ticketId, AckStage stage, String riskSnapshotHash, Instant acknowledgedAt) {
    }
}
