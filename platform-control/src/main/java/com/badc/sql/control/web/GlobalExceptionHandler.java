package com.badc.sql.control.web;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 把业务异常与请求解析异常转成统一错误体 {@link ApiError}。
 *
 * <p>过滤器链层面的 401/403 不经过这里，由
 * {@link com.badc.sql.control.security.SecurityConfig} 的 entry point / access denied handler 输出同样的结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> handleBizException(BizException e) {
        return ResponseEntity.status(e.code().httpStatus())
                .body(new ApiError(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.httpStatus())
                .body(new ApiError(ErrorCode.INVALID_ARGUMENT.name(),
                        ErrorCode.INVALID_ARGUMENT.defaultMessage()));
    }
}
