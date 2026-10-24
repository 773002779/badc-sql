package com.badc.sql.control.web;

import org.springframework.dao.DuplicateKeyException;
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

    /**
     * 唯一键冲突（系统/模块/数据源重名）转成 409，而不是让原始 SQL 异常漏成 500。
     *
     * <p>不把数据库异常消息带出去：里面可能含表名与 SQL 片段。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicateKey(DuplicateKeyException e) {
        return ResponseEntity.status(ErrorCode.CONFLICT.httpStatus())
                .body(new ApiError(ErrorCode.CONFLICT.name(), "名称已存在"));
    }
}
