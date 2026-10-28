package com.badc.sql.control.risk;

/**
 * 一条命中的"纪律"，决定它如何影响工单：<b>两种口径不能混用</b>（spec §FR-10 "口径"）。
 *
 * <ul>
 *   <li>{@link #BLOCK}：提交阶段直接拒绝，进不到审批。管理员也没有豁免。命中时工单不可提交。</li>
 *   <li>{@link #PIN_LEVEL}：可提交，但等级被<b>钉死</b>在 {@link RiskHit#level()}，只能抬升、不可下调，
 *       配置阈值再宽也不降。结构性破坏与"写语句无 where"走这条。</li>
 *   <li>{@link #WARN}：提示，可能影响性能，不影响等级，也不阻断。</li>
 *   <li>{@link #INFO}：风格提示，纯展示。</li>
 * </ul>
 */
public enum Discipline {

    BLOCK,
    PIN_LEVEL,
    WARN,
    INFO
}
