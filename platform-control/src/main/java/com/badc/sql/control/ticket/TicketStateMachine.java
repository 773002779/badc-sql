package com.badc.sql.control.ticket;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 工单状态机：判断一个状态能否迁移到另一个状态。
 *
 * <p>允许的迁移全部集中在一张静态表 {@link #ALLOWED} 里，业务代码只问"能不能迁"，不把规则写成
 * 散落各处的 {@code if}：迁移规则一旦分散，就再也说不清"哪些是终态、谁能被谁取消"。
 *
 * <p>表之外的任何组合（含 {@code null}）一律拒绝；{@link #assertTransition} 对非法迁移抛
 * {@link ErrorCode#CONFLICT}——"当前状态下不能这么做"对调用方是状态冲突，不是参数错误。
 *
 * <p>本切片**不实现运维临时执行通道**，也不允许执行中撤销：进入 {@code EXECUTING} 后只能走向
 * 执行结果（成功/部分失败/失败/需人工确认），没有回退路径。
 */
public final class TicketStateMachine {

    /** 源状态 → 允许到达的目标状态；表里没有的源状态即终态（无任何出边）。 */
    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = buildAllowed();

    private TicketStateMachine() {
    }

    /**
     * 是否允许从 {@code from} 迁移到 {@code to}。
     *
     * <p>未知/非法组合（含任一侧为 {@code null}）返回 {@code false}，不抛异常：调用方常用它做
     * 分支判断，异常留给 {@link #assertTransition}。
     */
    public static boolean canTransition(TicketStatus from, TicketStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 断言迁移合法，非法时抛 {@link ErrorCode#CONFLICT}。
     *
     * @throws BizException 迁移不在允许表内，消息为 {@code "<from> 不能迁移到 <to>"}
     */
    public static void assertTransition(TicketStatus from, TicketStatus to) {
        if (!canTransition(from, to)) {
            throw new BizException(ErrorCode.CONFLICT, from + " 不能迁移到 " + to);
        }
    }

    private static Map<TicketStatus, Set<TicketStatus>> buildAllowed() {
        Map<TicketStatus, Set<TicketStatus>> allowed = new EnumMap<>(TicketStatus.class);

        allowed.put(TicketStatus.DRAFT,
                EnumSet.of(TicketStatus.REVIEWING_L1, TicketStatus.CANCELLED));
        allowed.put(TicketStatus.REVIEWING_L1,
                EnumSet.of(TicketStatus.REVIEWING_L2, TicketStatus.APPROVED,
                        TicketStatus.REJECTED, TicketStatus.CANCELLED));
        allowed.put(TicketStatus.REVIEWING_L2,
                EnumSet.of(TicketStatus.APPROVED, TicketStatus.REJECTED));
        allowed.put(TicketStatus.APPROVED,
                EnumSet.of(TicketStatus.EXECUTING, TicketStatus.EXPIRED, TicketStatus.CANCELLED));
        allowed.put(TicketStatus.EXECUTING,
                EnumSet.of(TicketStatus.SUCCEEDED, TicketStatus.PARTIAL_FAILED,
                        TicketStatus.FAILED, TicketStatus.NEEDS_MANUAL_CONFIRM));
        allowed.put(TicketStatus.SUCCEEDED, EnumSet.of(TicketStatus.ROLLED_BACK));
        allowed.put(TicketStatus.PARTIAL_FAILED, EnumSet.of(TicketStatus.ROLLED_BACK));

        return Collections.unmodifiableMap(allowed);
    }
}
