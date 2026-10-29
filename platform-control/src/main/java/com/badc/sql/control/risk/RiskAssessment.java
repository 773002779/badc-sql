package com.badc.sql.control.risk;

import java.util.List;
import java.util.Objects;

/**
 * 一条语句的风险评估结果：等级 + 命中明细 + 风险快照哈希。
 *
 * <p>这是审批链、执行账号（L2 用只读+在线 DDL 账号、L3 用高权限账号的操作口径）、审计标签共同的唯一输入源，
 * 所以它是不可变的：{@link #hits()} 返回的是构造时复制的列表，调用方改不动评估结果本身。
 *
 * <p><b>{@link #blocked()} 与 {@link #level()} 是两件事</b>：被 BLOCK 的语句同样会给出 {@link RiskLevel#L3}
 * （见 {@link RiskEngine} 的合并顺序），但"最高级"不等于"可提交"——{@code blocked()} 为 {@code true} 时
 * 工单在提交阶段就被拒绝，根本不进审批。反过来只看 {@code level} 会把它当成一条普通 L3 变更。
 *
 * <p>{@link #snapshotHash()} 是<b>风险快照</b>，不是语句指纹：它随等级、命中构成与 SQL 文本一起变化，
 * 用于执行前比对"风险是否变过"（任务 9/11）。改一个字但等级不变，哈希也必须变。
 *
 * @param level       合并后的等级，非空
 * @param hits        全部命中：先是 {@link PolicyValidator} 的稳定顺序（BLOCK → PIN_LEVEL → WARN → INFO），
 *                    引擎追加的 {@code OUT_OF_WINDOW_ACK} / {@code SNAPSHOT_DISABLED} 排在末尾；
 *                    不可变，可以是空列表
 * @param snapshotHash 风险快照哈希：sha256 十六进制，非空
 */
public record RiskAssessment(RiskLevel level, List<RiskHit> hits, String snapshotHash) {

    public RiskAssessment {
        Objects.requireNonNull(level, "level");
        // 复制而非包装：调用方拿到的是一份快照，{@code hits().clear()} 改不到评估结果。
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        Objects.requireNonNull(snapshotHash, "snapshotHash");
    }

    /**
     * 是否存在 {@link Discipline#BLOCK} 命中：有就<b>不可提交</b>（管理员也没有豁免）。
     *
     * <p>按纪律判而不是按等级判：BLOCK 命中的 {@code level} 是 {@code null}，用等级判会把它当成"没有风险"。
     */
    public boolean blocked() {
        return hits.stream().anyMatch(hit -> hit.discipline() == Discipline.BLOCK);
    }
}
