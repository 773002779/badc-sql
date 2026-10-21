package com.badc.sql.common.error;

/**
 * 业务异常：携带 {@link ErrorCode}，由控制面统一转换为 HTTP 响应。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public BizException(ErrorCode code) {
        super(code.defaultMessage());
        this.code = code;
    }

    public BizException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
