package com.badc.sql.common.dto;

import java.util.List;

/**
 * 查询的原始结果（执行器产出，控制面缓存与展示）。
 *
 * <p>类型映射约定：
 * <ul>
 *   <li>SQL {@code NULL} → {@code null}（不是字符串 "null"）；</li>
 *   <li>{@code BLOB}/{@code BINARY} → {@code {"hexLen": n}}，不返回原始字节；</li>
 *   <li>超长文本 → 截断并加 {@code [truncated]} 后缀；</li>
 *   <li>{@code DATETIME} → UTC 的 ISO-8601 字符串（以 {@code Z} 结尾）。</li>
 * </ul>
 *
 * @param truncated 是否因触达行数上限而被截断
 */
public record RawResult(
        List<ColumnMeta> columns,
        List<List<Object>> rows,
        boolean truncated,
        long elapsedMs) {
}
