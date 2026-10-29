package com.badc.sql.common.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次结构快照的抓取结果。
 *
 * <p>{@code ref} 是控制面给的相对引用（{@code {systemId}/{datasourceId}/{ticketId}/{changeSeq}}），
 * 执行器只按它在快照根目录下落盘，不回传绝对路径。
 *
 * <p>{@code tableFingerprints} 只含<b>实际存在</b>的表：新建表（前置状态为空）不会出现在 map 里，
 * 调用方据此区分"没有前置结构"与"抓取失败"——后者是抛 {@code BizException}，不是空 map。
 *
 * <p>{@code byteSize} 是本次落盘的 {@code schema.sql} 与 {@code schema.json} 字节数之和。
 *
 * <p>map 用 {@link LinkedHashMap} 复制：指纹本身不存在顺序语义，但保持入参表序能让落盘、
 * 日志与排障时的输出可复现。
 */
public record SnapshotResult(String ref, Map<String, String> tableFingerprints, long byteSize) {

    public SnapshotResult {
        tableFingerprints = tableFingerprints == null || tableFingerprints.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tableFingerprints));
    }
}
