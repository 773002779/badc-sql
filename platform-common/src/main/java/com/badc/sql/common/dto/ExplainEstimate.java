package com.badc.sql.common.dto;

/**
 * EXPLAIN 行数估算。
 *
 * <p>{@code estimatedRows} 为 {@code -1} 表示<b>无法估算</b>：语句不合法、结果为空、某行 {@code rows}
 * 为 NULL、表不存在，或语句本身不能拿来估算（{@code ANALYZE}、MySQL 可执行注释）。调用方必须把
 * {@code -1} 当作"最严情况"处理，而不是 0 行。
 *
 * <p><b>库不可达、权限不足不是 {@code -1}。</b>它们是故障，由
 * {@link com.badc.sql.common.error.BizException} 表达：{@code -1} 是"这条语句估不出来"，异常是
 * "根本探测不了这个库"。两者处理方式相反——前者继续按最严分档，后者必须中止。
 *
 * <p>注意这是优化器估算值，不是真实影响行数：InnoDB 的统计信息本身有误差，这里只用来做风险分档。
 */
public record ExplainEstimate(long estimatedRows) {
}
