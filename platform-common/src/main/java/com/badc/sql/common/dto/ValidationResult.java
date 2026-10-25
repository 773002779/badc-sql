package com.badc.sql.common.dto;

/**
 * SQL 校验结果。
 *
 * <p>{@code ok=false} 时带上错误码与可读消息；行号 / 列号在解析器能给出时才有值（当前方言规则按关键字
 * 判定，给不出精确位置，保留字段给后续接真正的 SQL 解析器）。
 */
public record ValidationResult(boolean ok, String errorCode, String message, Integer line, Integer column) {

    public static ValidationResult success() {
        return new ValidationResult(true, null, null, null, null);
    }

    public static ValidationResult reject(String errorCode, String message) {
        return new ValidationResult(false, errorCode, message, null, null);
    }
}
