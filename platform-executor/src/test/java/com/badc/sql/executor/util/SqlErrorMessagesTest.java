package com.badc.sql.executor.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SQL 错误消息脱敏（纯函数，不连库）。
 *
 * <p>为什么必须钉住：MySQL 的 28000（access denied）原文是
 * {@code Access denied for user 'readonly_user'@'10.1.2.3' (using password: YES)}，这类消息会被拼进
 * {@code BizException}、经控制面落到 API 响应体，研发就能看到连接用的账号与主机——违反"研发不可见连接信息"。
 * 反过来，整条消息抹掉会让排障无从下手，所以只打掉 {@code '账号'@'主机'} 这一形态，其余文本必须保留。
 */
class SqlErrorMessagesTest {

    @Test
    void access_denied_loses_the_account_and_host_but_keeps_the_diagnostics() {
        String sanitized = SqlErrorMessages.sanitize(
                "Access denied for user 'readonly_user'@'10.1.2.3' (using password: YES)");

        assertThat(sanitized)
                .contains("'***'@'***'")
                .contains("Access denied for user")
                .contains("(using password: YES)")
                .doesNotContain("readonly_user")
                .doesNotContain("10.1.2.3");
    }

    /** 单引号本身不是敏感信号：普通消息里带引号的库名、语法片段必须原样保留。 */
    @Test
    void messages_without_credentials_are_returned_verbatim() {
        assertThat(SqlErrorMessages.sanitize("Unknown database 'order_db'"))
                .isEqualTo("Unknown database 'order_db'");
    }

    @Test
    void a_null_message_becomes_an_empty_string() {
        assertThat(SqlErrorMessages.sanitize(null)).isEmpty();
    }

    /** 一条消息里可能同时出现多个 {@code '账号'@'主机'}：不能只换第一处。 */
    @Test
    void every_account_host_pair_is_masked() {
        String sanitized = SqlErrorMessages.sanitize(
                "Access denied for user 'alice'@'10.0.0.1'; retry for 'bob'@'db.internal'");

        assertThat(sanitized.split("'\\*\\*\\*'@'\\*\\*\\*'", -1)).hasSize(3);
        assertThat(sanitized)
                .doesNotContain("alice")
                .doesNotContain("10.0.0.1")
                .doesNotContain("bob")
                .doesNotContain("db.internal");
    }
}
