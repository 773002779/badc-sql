package com.badc.sql.control.org;

import com.badc.sql.common.dto.Capability;

/**
 * 数据源详情：在 {@link DataSourceView} 的基础上多一个能力字段。
 *
 * <p>单独建记录而不是给 {@link DataSourceView} 加字段：列表接口有"只返回 5 个字段"的既有契约
 * （前端与测试都依赖），而详情页需要 {@code capabilities} 来决定是否展示 {@code EXPLAIN ANALYZE}、
 * 在线 DDL 等入口。
 *
 * <p>{@code capabilities} 为 {@code null} 表示未知（探测失败或未探测成功）。{@link Capability} 里只有
 * 版本与能力开关，<b>不含任何连接信息</b>，所以可以安全地出现在响应里。
 */
public record DataSourceDetail(
        Long id,
        String name,
        String dbName,
        Long systemId,
        String type,
        Capability capabilities) {
}
