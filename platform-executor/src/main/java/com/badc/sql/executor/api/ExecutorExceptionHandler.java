package com.badc.sql.executor.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 执行器的统一错误出口。
 *
 * <p>没有它，任何异常都会被 Boot 兜成 500，且默认不返回 message——控制面只能看到"执行器不可用"，
 * 用户拿不到"SQL 语法错 / 表不存在 / 权限不足 / 超时"这类可操作的信息。
 *
 * <p>返回体固定 {@code {"code":"...","message":"..."}}，与控制面同构；消息里不含 host / 账号 / 连接串。
 */
@RestControllerAdvice
public class ExecutorExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExecutorExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, String>> onBiz(BizException e) {
        return ResponseEntity.status(e.code().httpStatus()).body(body(e.code(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> onUnexpected(Exception e) {
        log.error("执行器未预期异常", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(body(ErrorCode.INTERNAL_ERROR, "执行器内部错误"));
    }

    private static Map<String, String> body(ErrorCode code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message == null ? code.defaultMessage() : message);
        return body;
    }
}
