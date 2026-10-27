package com.badc.sql.common.dto;

/** 结果集列元数据。{@code type} 是数据库类型名（如 {@code VARCHAR}、{@code BIGINT}）。 */
public record ColumnMeta(String name, String type) {
}
