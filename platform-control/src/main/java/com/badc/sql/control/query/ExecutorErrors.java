package com.badc.sql.control.query;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 把执行器返回的 {@code {"code":"...","message":"..."}} 还原成控制面的业务异常。
 *
 * <p>不这样做的话，执行器侧的"表不存在 / SQL 语法错 / 权限不足 / 超时"到了控制面会被统一糊成
 * "执行器不可用"，研发对着一条语法错误去排查网络——这是评审明确指出的可操作性缺陷。
 */
final class ExecutorErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExecutorErrors() {
    }

    static BizException toBizException(int status, String body) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        String message = "执行器返回错误（HTTP " + status + "）";
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(body);
                String rawCode = node.path("code").asText(null);
                if (rawCode != null) {
                    try {
                        code = ErrorCode.valueOf(rawCode);
                    } catch (IllegalArgumentException ignored) {
                        // 未知码按内部错误处理，但保留执行器的原始消息。
                    }
                }
                String rawMessage = node.path("message").asText(null);
                if (rawMessage != null && !rawMessage.isBlank()) {
                    message = rawMessage;
                }
            } catch (Exception ignored) {
                // 非 JSON 错误体：用通用消息，绝不把原始响应拼进面向前端的文本。
            }
        }
        return new BizException(code, message);
    }
}
