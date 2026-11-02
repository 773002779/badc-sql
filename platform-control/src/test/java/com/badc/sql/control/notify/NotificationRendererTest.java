package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 通知消息体：只由白名单事实字段渲染，<b>永远不含 SQL 正文</b>。
 *
 * <p>这些用例盯住三件事：
 * <ol>
 *   <li><b>结构性保证</b>：{@code facts} 里出现白名单之外的键（{@code sql} / {@code statement} …）
 *       必须在构造 {link NotificationCommand} 时当场抛错，而不是"渲染时忽略"——忽略等于把
 *       "SQL 曾流进来"这件事藏起来。</li>
 *   <li><b>不含正文</b>：渲染结果只能来自白名单事实，字面量（身份证号之类）没有进入消息的路径。</li>
 *   <li><b>列宽</b>：{@code notification.title VARCHAR(200)}、{@code notification.body VARCHAR(2000)}，
 *       渲染器是唯一能拦住超长文本的地方；截断必须按码点做，不能把代理对切成半个字符。</li>
 * </ol>
 */
class NotificationRendererTest {

    /** {@code notification.title} 的列宽。 */
    private static final int TITLE_MAX = 200;

    /** {@code notification.body} 的列宽。 */
    private static final int BODY_MAX = 2000;

    @Test
    void a_statement_with_literals_never_reaches_the_message() {
        NotificationCommand command = new NotificationCommand(
                NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null,
                Map.of("system", "订单中心", "statementType", "UPDATE", "riskLevel", "L2",
                       "submitter", "dev01", "ticketNo", "42"));
        Rendered rendered = NotificationRenderer.render(command);
        assertThat(rendered.title()).contains("订单中心");
        assertThat(rendered.body()).doesNotContain("身份证").doesNotContain("id_card");
    }

    @Test
    void a_fact_outside_the_whitelist_is_refused() {
        assertThatThrownBy(() -> new NotificationCommand(
                NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null, Map.of("sql", "update t set a=1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql");
    }

    /**
     * 一个 5000 字符的事实值（比如被拼错的系统名、或者调用方把整段说明塞进 reason）不能把落库撑爆：
     * 标题 ≤ 200、正文 ≤ 2000，且截断处留省略号，让人看得出"这条被截过"。
     */
    @Test
    void an_oversized_fact_is_truncated_to_the_column_width() {
        String huge = "长".repeat(5000);
        Rendered rendered = NotificationRenderer.render(new NotificationCommand(
                NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null,
                Map.of("system", huge, "submitter", huge)));

        assertThat(rendered.title()).hasSizeLessThanOrEqualTo(TITLE_MAX).endsWith("…");
        assertThat(rendered.body()).hasSizeLessThanOrEqualTo(BODY_MAX).endsWith("…");
    }

    /**
     * 截断按码点切：😀 在 UTF-8 里是 4 字节、在 Java 里是两个 char，按 char 切会留下半个代理对，
     * 落库时变成 {@code ?} 或直接抛错。
     */
    @Test
    void truncation_never_cuts_a_character_in_half() {
        String huge = "😀".repeat(5000);
        Rendered rendered = NotificationRenderer.render(new NotificationCommand(
                NotificationEventType.TICKET_EXEC_FAILED, 1L, null, null,
                Map.of("system", huge, "module", huge)));

        assertThat(codePoints(rendered.title())).isLessThanOrEqualTo(TITLE_MAX);
        assertThat(codePoints(rendered.body())).isLessThanOrEqualTo(BODY_MAX);
        assertThat(survivesUtf8RoundTrip(rendered.title())).isTrue();
        assertThat(survivesUtf8RoundTrip(rendered.body())).isTrue();
    }

    @Test
    void an_empty_fact_map_is_legal_and_renders_a_title_only() {
        Rendered rendered = NotificationRenderer.render(
                new NotificationCommand(NotificationEventType.TICKET_APPROVED, 7L, 9L, null, Map.of()));

        assertThat(rendered.title()).isEqualTo("【工单已通过】");
        assertThat(rendered.body()).isEmpty();
    }

    @Test
    void every_event_type_renders_a_non_blank_title() {
        for (NotificationEventType type : NotificationEventType.values()) {
            Rendered rendered = NotificationRenderer.render(
                    new NotificationCommand(type, 1L, null, null, Map.of()));
            assertThat(rendered.title()).as(type.name()).isNotBlank();
        }
    }

    /** 缺失项跳过；只由空白组成的值也算缺失（"-" 之类的占位符不进消息）。 */
    @Test
    void a_missing_or_blank_item_is_skipped() {
        Rendered rendered = NotificationRenderer.render(new NotificationCommand(
                NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null,
                Map.of("system", "订单中心", "submitter", "   ", "ticketNo", "42")));

        assertThat(rendered.body()).contains("工单号：42").doesNotContain("提交人");
    }

    @Test
    void a_null_fact_value_is_refused() {
        Map<String, String> facts = new HashMap<>();
        facts.put("system", null);

        assertThatThrownBy(() -> new NotificationCommand(
                NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null, facts))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("system");
    }

    @Test
    void a_command_without_a_type_is_refused() {
        assertThatThrownBy(() -> new NotificationCommand(null, 1L, 42L, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 未配对的代理项经 UTF-8 编码会被替换成 {@code ?}，往返不相等即说明切坏了。 */
    private static boolean survivesUtf8RoundTrip(String text) {
        return text.equals(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
