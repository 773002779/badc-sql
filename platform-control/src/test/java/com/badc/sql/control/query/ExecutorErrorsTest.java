package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 执行器错误体的还原：用户的报错必须是可操作的，而不是统一糊成"执行器不可用"。
 */
class ExecutorErrorsTest {

    @Test
    void forwards_the_executor_code_and_message() {
        BizException e = ExecutorErrors.toBizException(400,
                "{\"code\":\"INVALID_ARGUMENT\",\"message\":\"SQL 语法错误：Unknown column 'x'\"}");

        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.getMessage()).contains("Unknown column 'x'");
    }

    @Test
    void keeps_timeout_distinguishable_from_a_dead_executor() {
        BizException e = ExecutorErrors.toBizException(504,
                "{\"code\":\"QUERY_TIMEOUT\",\"message\":\"查询执行超时\"}");

        assertThat(e.code()).isEqualTo(ErrorCode.QUERY_TIMEOUT);
    }

    @Test
    void unknown_code_falls_back_to_internal_error_but_keeps_the_message() {
        BizException e = ExecutorErrors.toBizException(500,
                "{\"code\":\"SOMETHING_NEW\",\"message\":\"库连接被拒绝\"}");

        assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(e.getMessage()).isEqualTo("库连接被拒绝");
    }

    @Test
    void non_json_body_produces_a_safe_generic_message() {
        BizException e = ExecutorErrors.toBizException(502, "<html>bad gateway</html>");

        assertThat(e.getMessage()).doesNotContain("<html>").contains("502");
    }

    @Test
    void empty_body_does_not_throw() {
        assertThatThrownBy(() -> {
            throw ExecutorErrors.toBizException(500, "");
        }).isInstanceOf(BizException.class);
    }
}
