package com.badc.sql.control.ticket;

/**
 * 风险确认阶段，与 V3 迁移里的 {@code ck_risk_acknowledgement_stage} 一一对应。
 *
 * <p>四个阶段不是"同一件事的四种写法"，每个都回答一个不同的问题：
 * <ul>
 *   <li>{@link #SUBMIT}：提交人确认「我愿意为这批变更负责」（提交时写）；</li>
 *   <li>{@link #REVIEW}：一级审核人确认「我看过这份风险，可以往下走」（一级通过时写）；</li>
 *   <li>{@link #L3_REVIEW}：组长终审确认「L3 的风险我兜得住」（二级通过时写）；</li>
 *   <li>{@link #EXECUTE}：执行前确认「我看到的就是即将执行的风险」（{@link RiskAckService} 写）。</li>
 * </ul>
 *
 * <p><b>只有 {@link #EXECUTE} 能放行执行</b>：前三个阶段的确认发生在审批链上，那时工单的语句与风险快照
 * 还可能被重新计算。执行前的校验只看 EXECUTE 行（见 {@link RiskAckService#assertExecuteAckCurrent}）。
 *
 * <p>阶段值是持久化契约的一部分：改名字必须同步改迁移里的 CHECK，否则写入会被数据库拒绝。
 */
public enum AckStage {

    /** 提交时的风险知悉。 */
    SUBMIT,
    /** 一级审核通过时的风险知悉。 */
    REVIEW,
    /** L3 终审通过时的风险知悉。 */
    L3_REVIEW,
    /** 执行前的风险确认：唯一能放行执行的阶段。 */
    EXECUTE
}
