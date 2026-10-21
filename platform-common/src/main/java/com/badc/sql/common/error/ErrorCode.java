package com.badc.sql.common.error;

/**
 * 平台统一错误码。
 *
 * <p>{@code httpStatus} 供控制面把业务异常映射为 HTTP 响应；{@code defaultMessage} 可被
 * {@link BizException} 的具体消息覆盖。
 */
public enum ErrorCode {

    INVALID_ARGUMENT(400, "请求参数不合法"),
    UNAUTHORIZED(401, "未认证或凭证已失效"),
    FORBIDDEN(403, "无权访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源已存在或状态冲突"),
    DB_VERSION_UNSUPPORTED(422, "数据库版本不支持该语法"),
    QUERY_TIMEOUT(504, "查询执行超时"),
    INTERNAL_ERROR(500, "服务内部错误");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
