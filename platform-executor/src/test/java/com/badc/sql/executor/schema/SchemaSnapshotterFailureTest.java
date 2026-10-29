package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 快照失败消息的脱敏（纯函数，构造 {@link SQLException}，不连库）。
 *
 * <p>为什么必须钉住：权限类错误（28000）的原文是
 * {@code Access denied for user 'readonly_user'@'10.1.2.3'}，它经控制面 {@code ExecutorErrors} 原样进入
 * API 响应体，研发就会看到连接用的账号与主机——这属于"研发不可见连接信息"的硬约束。反过来，把整条
 * 消息都抹掉又会让人无法排障，所以只打掉 {@code '账号'@'主机'} 这一形态，SQLState 与错误码必须保留。
 */
class SchemaSnapshotterFailureTest {

    @Test
    void access_denied_keeps_the_diagnostics_but_masks_the_account_and_host() {
        BizException e = SchemaSnapshotter.failure(new SQLException(
                "Access denied for user 'readonly_user'@'10.1.2.3' (using password: YES)", "28000", 1045));

        assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(e.getMessage())
                .contains("SQLState=28000")
                .contains("errorCode=1045")
                .contains("'***'@'***'")
                .doesNotContain("readonly_user")
                .doesNotContain("10.1.2.3");
    }

    /** 08xxx 走另一条分支：原始消息整条不回显，自然也不会带出 host。 */
    @Test
    void connection_failures_never_echo_the_raw_message() {
        BizException e = SchemaSnapshotter.failure(new SQLException(
                "Communications link failure to 10.1.2.3:3306", "08S01", 0));

        assertThat(e.getMessage())
                .contains("数据源不可达")
                .contains("SQLState=08S01")
                .doesNotContain("10.1.2.3")
                .doesNotContain("Communications link failure");
    }

    /** 不含 {@code '账号'@'主机'} 的消息必须原样保留，否则脱敏会把排障信息一起吃掉。 */
    @Test
    void messages_without_credentials_are_left_intact() {
        BizException e = SchemaSnapshotter.failure(new SQLException(
                "Unknown database 'order_db'", "42000", 1049));

        assertThat(e.getMessage())
                .contains("Unknown database 'order_db'")
                .contains("SQLState=42000")
                .contains("errorCode=1049");
    }

    @Test
    void a_null_message_does_not_render_as_the_literal_null() {
        assertThat(SchemaSnapshotter.failure(new SQLException(null, "42000", 1142)).getMessage())
                .doesNotContain("null")
                .contains("SQLState=42000");
    }
}
