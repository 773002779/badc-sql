package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.List;

/**
 * 提交变更工单的入参。
 *
 * <p>请求体只给数据源，<b>系统与模块由服务端推导</b>（见 {@link TicketAssignmentService}）：让前端传
 * {@code systemId}/{@code moduleId} 迟早会传出"数据源属于 A 系统、工单挂到 B 系统"的错单。
 *
 * <p>{@link #statements()} 在构造时复制，且<b>保持原文不做任何改写</b>——语句指纹、风险快照哈希与
 * 执行内容都以它为准；调用方拿到后也无法通过修改原列表影响已提交的工单。
 *
 * @param datasourceId      目标数据源 ID
 * @param title             工单标题（必填，≤200）
 * @param changeReason      变更原因（必填）
 * @param rollbackPlan      回滚方案（DDL 必填，文本描述）
 * @param rollbackSql       回滚 SQL 原文（DML 必填，可含多条语句）
 * @param statements        按执行顺序排列的语句原文（1..5000 条）
 * @param expectedExecuteAt 期望执行时间（必填）
 */
public record SubmitCommand(
        long datasourceId,
        String title,
        String changeReason,
        String rollbackPlan,
        String rollbackSql,
        List<String> statements,
        Instant expectedExecuteAt) {

    public SubmitCommand {
        statements = statements == null ? List.of() : List.copyOf(statements);
    }
}
