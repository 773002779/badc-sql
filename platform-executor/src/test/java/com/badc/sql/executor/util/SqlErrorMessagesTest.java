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

    /**
     * 1130 是另一种形态：{@code Host 'x' is not allowed to connect ...}。
     *
     * <p>这里没有 {@code '账号'@'主机'} 的形态，只掩前一种正则会漏掉主机名；改动前这条用例是红的。
     */
    @Test
    void a_host_not_allowed_message_loses_the_host() {
        String sanitized = SqlErrorMessages.sanitize(
                "Host '10.1.2.3' is not allowed to connect to this MySQL server");

        assertThat(sanitized).isEqualTo("Host '***' is not allowed to connect to this MySQL server");
    }

    /** 驱动层的"找不到驱动"消息里带着完整连接串（含 host:port，甚至 user 参数）。 */
    @Test
    void a_driver_message_loses_the_connection_string() {
        String sanitized = SqlErrorMessages.sanitize(
                "No suitable driver found for jdbc:mysql://10.1.2.3:3306/order_db?user=root&password=secret");

        assertThat(sanitized)
                .isEqualTo("No suitable driver found for jdbc:mysql://***")
                .doesNotContain("10.1.2.3")
                .doesNotContain("order_db")
                .doesNotContain("secret");
    }

    /** 两种形态同时出现时都要掩掉，且不能互相"吃掉"。 */
    @Test
    void a_message_carrying_several_forms_of_connection_info_is_fully_masked() {
        String sanitized = SqlErrorMessages.sanitize(
                "Host 'db.internal' is not allowed; Access denied for user 'ro'@'db.internal'; "
                        + "url=jdbc:mysql://db.internal:3306/order_db");

        assertThat(sanitized)
                .doesNotContain("db.internal")
                .doesNotContain("order_db")
                .contains("Host '***'")
                .contains("'***'@'***'")
                .contains("jdbc:mysql://***");
    }
}
