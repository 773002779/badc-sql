package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 脱敏与截断的边界：{@link NotificationErrorText}。
 *
 * <p>为什么值得单独一组用例：投递器那条"连接串不落库"的用例走的是 <b>JDBC URL</b> 分支，
 * 另外两条正则（带引号的 {@code password='…'}、只有 token 的 {@code scheme://token@host}）
 * 在端到端用例里<b>一次都没被触发过</b>——零覆盖的正则等于没写。而 {@code last_error} 会落库、
 * 会在工单详情里给研发看，漏一段口令就是凭证泄露（与切片 2 修过的"错误消息带 '账号'@'主机'"同类）。
 *
 * <p>这些用例直接打在包内静态方法上：它们是纯字符串变换，不需要投递器也不需要数据库。
 * 用例名里的 "quoted / token-only / jdbc" 对应三条正则各自的分支。
 */
class NotificationErrorTextTest {

    @Test
    void a_quoted_password_is_redacted_whatever_the_quotes_are() {
        assertThat(NotificationErrorText.sanitizeText("登录失败 password='s3cr3t'"))
                .as("单引号是最常见的写法（JDBC 客户端、YAML 片段都爱这么写）")
                .doesNotContain("s3cr3t")
                .contains("password=***");

        assertThat(NotificationErrorText.sanitizeText("登录失败 password=\"s3cr3t\""))
                .doesNotContain("s3cr3t")
                .contains("password=***");

        assertThat(NotificationErrorText.sanitizeText("登录失败 password=`s3cr3t`"))
                .doesNotContain("s3cr3t")
                .contains("password=***");

        assertThat(NotificationErrorText.sanitizeText("登录失败 password='s3 cr3t'"))
                .as("引号内的空格不能把口令截成两半，剩下的半截照样是泄露")
                .doesNotContain("s3cr3t")
                .doesNotContain("s3 cr3t");
    }

    @Test
    void an_unquoted_password_user_or_host_is_redacted() {
        assertThat(NotificationErrorText.sanitizeText("连接失败 host=10.0.0.9 user=root password=s3cr3t"))
                .doesNotContain("10.0.0.9")
                .doesNotContain("root")
                .doesNotContain("s3cr3t")
                .contains("password=***");
    }

    @Test
    void a_token_only_userinfo_url_keeps_the_host_but_loses_the_token() {
        assertThat(NotificationErrorText.sanitizeText("克隆仓库失败 https://tok3n@git.internal/repo.git"))
                .as("scheme://token@host 没有冒号，但 token 同样是凭证；host 要留着，排查要看连的是谁")
                .doesNotContain("tok3n")
                .contains("https://***@git.internal/repo.git");
    }

    @Test
    void a_userinfo_url_with_a_password_is_redacted() {
        assertThat(NotificationErrorText.sanitizeText("推送失败 https://ci:s3cr3t@git.internal/repo.git"))
                .doesNotContain("s3cr3t")
                .doesNotContain("ci:")
                .contains("***@git.internal");
    }

    @Test
    void a_jdbc_connection_string_loses_host_user_and_password() {
        assertThat(NotificationErrorText.sanitizeText(
                "jdbc:mysql://root:root@127.0.0.1:3306/badc_sql?user=root&password=s3cr3t"))
                .as("整条连接串一起抹掉：协议后面没有一处是安全的")
                .isEqualTo("jdbc:***");
    }

    @Test
    void an_exception_without_a_message_yields_only_the_class_name() {
        assertThat(NotificationErrorText.sanitize(new IllegalStateException()))
                .as("没有消息时不能落库成「IllegalStateException: null」")
                .isEqualTo("IllegalStateException");
    }

    @Test
    void sanitizing_flattens_multiline_messages() {
        assertThat(NotificationErrorText.sanitizeText("第一行\n第二行\r\n第三行"))
                .as("last_error 是单行展示：换行会让截断后的后半句变成无主噪音")
                .isEqualTo("第一行 第二行 第三行");
    }

    @Test
    void truncation_is_bounded_by_code_points_and_marks_the_cut() {
        String longText = "连".repeat(600);

        String truncated = NotificationErrorText.truncate(longText, NotificationErrorText.COLUMN_MAX_CHARS);

        assertThat(truncated.codePointCount(0, truncated.length()))
                .as("上限是 500 个码点：正好等于 VARCHAR(500) 的容量")
                .isEqualTo(500);
        assertThat(truncated).as("截断要留痕，运维才知道消息被截过").endsWith("…");
    }

    @Test
    void truncation_never_splits_a_supplementary_character() {
        String emoji = "😀".repeat(300);

        String truncated = NotificationErrorText.truncate(emoji, 200);

        assertThat(truncated.codePointCount(0, truncated.length()))
                .as("按 char 切会留下半个代理对：这里必须是 199 个完整 emoji + 省略号")
                .isEqualTo(200);
        assertThat(truncated).endsWith("…");
    }

    @Test
    void an_unclosed_quoted_password_is_still_redacted() {
        assertThat(NotificationErrorText.sanitizeText("登录失败 password='s3cr3t"))
                .as("少了右引号的口令照样是口令——不能因为「引号没配对」就整段放过")
                .doesNotContain("s3cr3t")
                .contains("password=***");

        assertThat(NotificationErrorText.sanitizeText("登录失败 password=\"s3cr3t"))
                .doesNotContain("s3cr3t")
                .contains("password=***");
    }

    @Test
    void a_protocol_relative_userinfo_is_redacted() {
        assertThat(NotificationErrorText.sanitizeText("连不上 //admin:pw@db:3306/orders"))
                .as("协议相对写法没有 scheme，但账号口令一样在里面")
                .doesNotContain("pw")
                .doesNotContain("admin")
                .contains("//***@db:3306");
    }

    @Test
    void a_custom_scheme_userinfo_is_redacted() {
        assertThat(NotificationErrorText.sanitizeText("自定义 my_app://u:p@h:1/x"))
                .as("自定义 scheme（含下划线）不能漏；scheme 本身要保留")
                .doesNotContain("u:p")
                .contains("my_app://***@h:1");

        assertThat(NotificationErrorText.sanitizeText("JDBC 风格 jdbc:mysql://u:p@h/db"))
                .doesNotContain("u:p")
                .doesNotContain("h/db");
    }

    @Test
    void an_email_address_without_a_url_is_not_mistaken_for_userinfo() {
        assertThat(NotificationErrorText.sanitizeText("请把结果发到 ops01@badc.test"))
                .as("脱敏不能反过来把正常邮箱抹掉：userinfo 只存在于 // 之后")
                .isEqualTo("请把结果发到 ops01@badc.test");
    }

    /**
     * 性能断言：这条是本轮的核心，不是锦上添花。
     *
     * <p>{@code sanitizeText} 吃的是<b>异常原文</b>，而 MySQL 的错误消息会把 SQL 片段带回来——SQL 由研发提交、
     * 长度由他们控制。脱敏里任何一条"以字符类开头、量词无上界"的正则，在长串上都会从每个起点回溯整段
     * （O(n²)）：40KB 就能让异步投递线程烧掉约 10 秒 CPU，是可被低成本触发的资源耗尽面。
     * 所以这里钉的是"耗时上界"，不是"结果对不对"。
     */
    @Test
    void a_long_message_without_any_url_is_sanitized_quickly() {
        String payload = "a1b2c3d4".repeat(5_000);

        long elapsed = elapsedMillisOf(() -> NotificationErrorText.sanitizeText(payload));

        assertThat(elapsed)
                .as("40KB、不含 URL 的消息脱敏耗时 %d ms（修复前实测约 10 秒）：O(n²) 会拖垮异步投递线程", elapsed)
                .isLessThan(200);
        assertThat(NotificationErrorText.sanitizeText(payload)).isEqualTo(payload);
    }

    @Test
    void a_long_message_full_of_slashes_is_sanitized_quickly() {
        String payload = "a//".repeat(13_000);

        long elapsed = elapsedMillisOf(() -> NotificationErrorText.sanitizeText(payload));

        assertThat(elapsed)
                .as("含大量 // 的 39KB 消息也要线性：门控挡不住这种情况，靠的是「字面 // 开头」", elapsed)
                .isLessThan(200);
    }

    @Test
    void truncation_is_null_safe_and_handles_a_zero_budget() {
        assertThat(NotificationErrorText.truncate(null, 500)).isNull();
        assertThat(NotificationErrorText.truncate("abc", 0)).isEmpty();
        assertThat(NotificationErrorText.truncate("abc", 500)).isEqualTo("abc");
    }

    private static long elapsedMillisOf(Runnable action) {
        long started = System.nanoTime();
        action.run();
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
