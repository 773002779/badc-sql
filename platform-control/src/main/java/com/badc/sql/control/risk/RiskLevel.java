package com.badc.sql.control.risk;

/**
 * 工单的风险等级，{@code L0} 最低（免审批）、{@code L3} 最高（组长终审）。
 *
 * <p>声明顺序即严重度顺序，{@link #stricter(RiskLevel, RiskLevel)} 只做"取更严"，不做任何配置化：
 * 结构性破坏不可下调的口径靠"只抬不降"的合并方式保证——配置再怎么放宽也不会把 L3 算回 L2。
 */
public enum RiskLevel {

    L0,
    L1,
    L2,
    L3;

    /**
     * 两个等级里更严的那个。{@code null} 视为 {@link #L0}（没有等级 = 没有风险），因此两个都为空时返回
     * {@link #L0} 而不是 {@code null}——调用方可以直接拿结果参与后续比较，不必再判空。
     */
    public static RiskLevel stricter(RiskLevel a, RiskLevel b) {
        RiskLevel left = a == null ? L0 : a;
        RiskLevel right = b == null ? L0 : b;
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
