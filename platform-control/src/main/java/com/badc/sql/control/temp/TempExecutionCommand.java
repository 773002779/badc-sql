package com.badc.sql.control.temp;

import java.util.List;

/**
 * 运维临时执行请求体：{@code POST /api/v1/temp-executions}。
 *
 * <p><b>没有操作人字段</b>：身份只来自 JWT（{@code CurrentUser.id()}）。请求体里放 {@code operatorId} 等于让
 * 调用方自报身份，与 {@code TicketController} 的 {@code SubmitRequest} 同一条纪律。
 *
 * <p><b>{@code confirmed} 是"人已在弹框里看过风险等级并同意"的唯一凭据</b>：缺省 {@code false}，服务端不替
 * 调用方默认同意。它只对 L1/L2 与"组长自提的 L3"有意义——其余 L3 在本切片里直接 409 要求组长审批。
 *
 * @param datasourceId 目标数据源（写库）
 * @param statements   要执行的语句，按顺序逐条下发；空/全空白由服务层给 400
 * @param confirmed    是否已由本人确认风险；{@code false} 时服务层给 400 且一条语句都不执行
 */
public record TempExecutionCommand(Long datasourceId, List<String> statements, boolean confirmed) {
}
