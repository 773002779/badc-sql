package com.badc.sql.control.ticket;

import java.time.Instant;

/**
 * {@code ticket} 的一行（含所有列）。
 *
 * <p>为什么不用"插入用的小对象 + 详情用的视图"两套类型：工单详情（任务 7b）几乎要用到全部列，两套类型
 * 只会让字段名与列名漂移。插入时用不到的列（{@code origin_ticket_id}、各时间戳）写 {@code null}，由数据库
 * 默认值补齐。
 *
 * <p>时间是 {@link Instant}（UTC）：库里是 {@code datetime(3)} 且会话时区固定 UTC，读出来直接
 * {@code Timestamp.toInstant()}，不在这一层做展示时区转换。
 *
 * @param id                  工单 ID
 * @param systemId            归属业务系统
 * @param moduleId            发起模块
 * @param datasourceId        目标数据源
 * @param ticketType          {@code NORMAL|ROLLBACK}
 * @param status              工单状态（{@link TicketStatus} 的枚举名）
 * @param riskLevel           整单风险等级（{@code L0..L3}）
 * @param riskSnapshotJson    整单风险快照规范化 JSON
 * @param riskSnapshotHash    整单风险快照 SHA-256
 * @param title               标题
 * @param changeReason        变更原因
 * @param rollbackPlan        回滚方案（DDL 必填）
 * @param rollbackSql         回滚 SQL（DML 必填）
 * @param statementFingerprint 全部语句按序拼接的 SHA-256
 * @param snapshotEnabled     提交时是否抓取结构快照（本切片恒为 true）
 * @param expectedExecuteAt   期望执行时间（UTC）
 * @param submitterId         提交人
 * @param originTicketId      回滚单对应的原工单（NORMAL 单为 null）
 * @param rollbackReason      回滚原因（ROLLBACK 单填写）
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 * @param approvedAt          审批通过时间
 * @param executedAt          开始执行时间
 * @param finishedAt          进入终态时间
 */
public record TicketEntity(
        Long id,
        Long systemId,
        Long moduleId,
        Long datasourceId,
        String ticketType,
        String status,
        String riskLevel,
        String riskSnapshotJson,
        String riskSnapshotHash,
        String title,
        String changeReason,
        String rollbackPlan,
        String rollbackSql,
        String statementFingerprint,
        boolean snapshotEnabled,
        Instant expectedExecuteAt,
        Long submitterId,
        Long originTicketId,
        String rollbackReason,
        Instant createdAt,
        Instant updatedAt,
        Instant approvedAt,
        Instant executedAt,
        Instant finishedAt) {
}
