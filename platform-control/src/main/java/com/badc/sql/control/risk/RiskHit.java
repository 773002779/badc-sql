package com.badc.sql.control.risk;

/**
 * 一条规范校验命中：规则码 + 纪律 + 说明。
 *
 * <p><b>{@code level} 只在 {@link Discipline#PIN_LEVEL} 时有值</b>，其余纪律（{@code BLOCK} / {@code WARN} /
 * {@code INFO}）一律传 {@code null}。这里刻意不用 {@link java.util.Optional}、也不用哨兵值：等级只有"被钉死"
 * 这一种含义，用 {@code null} 表示"这条命中不决定等级"，读代码的人不会把 {@code L0} 误当成"钉到 L0"。
 * 需要等级的地方先看 {@link #discipline()}，再决定要不要读 {@link #level()}。
 *
 * <p>{@code ruleCode} 是稳定的规则标识（如 {@code DROP_TABLE}、{@code ALTER_MODIFY_NARROWED}），
 * 用于前端展示、审计与风险快照哈希；断言与配置都按它匹配，不要按 {@code message} 匹配。
 *
 * @param ruleCode   规则码，spec §FR-10 规则表里的名字，非空
 * @param discipline 纪律：怎么处理这条命中，非空
 * @param level      被钉死的等级；仅 {@link Discipline#PIN_LEVEL} 时非空，其余纪律为 {@code null}
 * @param message    给人看的说明：为什么命中、后果是什么，非空
 */
public record RiskHit(String ruleCode, Discipline discipline, RiskLevel level, String message) {
}
