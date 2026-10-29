package com.badc.sql.common.dto;

/**
 * EXPLAIN 行数估算。
 *
 * <p>{@code estimatedRows} 为 {@code -1} 表示<b>无法估算</b>（语句不合法、结果为空、某行 rows 为 NULL、
 * 或目标库不可达）。调用方必须把 {@code -1} 当作"最严情况"处理，而不是 0 行。
 *
 * <p>注意这是优化器估算值，不是真实影响行数：InnoDB 的统计信息本身有误差，这里只用来做风险分档。
 */
public record ExplainEstimate(long estimatedRows) {
}
