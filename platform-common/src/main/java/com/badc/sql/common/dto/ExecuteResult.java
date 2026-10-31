package com.badc.sql.common.dto;

/**
 * 单条写语句的执行结果（执行器产出，控制面逐条落库、逐条回显）。
 *
 * <p><b>"失败"是结果，不是异常。</b>{@code success=false} 表示这条语句没执行成功，不表示执行器挂了：
 * 执行器对 {@code SQLException} 一律转成本记录而不抛，控制面才能逐条记录"哪条失败、为什么"；
 * 一旦抛出去，整单只剩一个笼统的 FAILED，排障与部分失败的判定都无从谈起。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code affectedRows}：{@code executeUpdate} 的返回值。DDL 返回 0 是正常的，不是"没生效"；</li>
 *   <li>{@code elapsedMs}：本条语句的墙钟耗时（毫秒）；</li>
 *   <li>{@code errorCode}：驱动给的 {@code SQLState}，<b>可能为 {@code null}</b>（驱动未提供时）；
 *       成功时为 {@code null}；</li>
 *   <li>{@code errorMessage}：数据库返回的消息，<b>已脱敏</b>（{@code '账号'@'主机'} 掩成
 *       {@code '***'@'***'}），绝不含连接串、账号或密码；成功时为 {@code null}。</li>
 * </ul>
 */
public record ExecuteResult(
        boolean success,
        long affectedRows,
        long elapsedMs,
        String errorCode,
        String errorMessage) {
}
