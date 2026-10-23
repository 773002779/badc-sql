package com.badc.sql.control.web;

/**
 * 统一错误响应体：{@code {"code":"...","message":"..."}}。
 *
 * <p>{@code code} 取 {@link com.badc.sql.common.error.ErrorCode} 的名称。
 */
public record ApiError(String code, String message) {
}
