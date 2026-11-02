package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 通知消息体：只由白名单事实字段渲染，<b>永远不含 SQL 正文</b>。
 *
 * <p>这些用例盯住三件事：
 * <ol>
 *   <li><b>结构性保证</b>：{@code facts} 里出现白名单之外的键（{@code sql} / {@code statement} …）
 *       必须在构造 {@link NotificationCommand} 时当场抛错，而不是"渲染时忽略"——忽略等于把
 *       "SQL 曾流进来"这件事藏起来。这是"不含 SQL 正文"唯一的<b>结构</b>保证；
 *       "工单的 SQL 没有出现在消息里"的端到端断言归 Task 7（到那一步 facts 才真正由工单数据构造，
 *       现在这里根本没有可喂的 SQL，写成断言会恒真）。</li>
 *   <li><b>白名单与渲染器同步</b>：{@link NotificationCommand#ALLOWED_FACT_KEYS} 里的每个键都必须真的
 *       出现在消息里。渲染器里的键字面量与白名单是两份，拼错一个（{@code ticketno}）会静默丢字段、
 *       而其它用例全绿——所以逐键断言。</li>
 *   <li><b>列宽</b>：{@code notification.title VARCHAR(200)}、{@code notification.body VARCHAR(2000)}，
 *       渲染器是唯一能拦住超长文本的地方。边界必须"恰好超一个码点才截"：过短就截会丢信息，
 *       恰好等于列宽还补省略号是谎报；截断还必须按码点做，不能把代理对切成半个字符。</li>
 * </ol>
 */
class NotificationRendererTest {

    /** {@code notification.title} 的列宽（按码点计）。 */
    private static final int TITLE_MAX = 200;

    /** {@code notification.body} 的列宽（按码点计）。 */
    private static final int BODY_MAX = 2000;

    /** 标题前缀：{@code 【} + {@link NotificationEventType#TICKET_SUBMITTED} 的 label + {@code 】}。 */
    private static final String TITLE_PREFIX = "【工单已提交】";

    /** 正文里"提交人"这一行的标签（含全角冒号），用来精确凑出正文的边界长度。 */
    private static final String SUBMITTER_LABEL = "提交人：";

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
        Rendered rendered = NotificationRenderer.render(submittedWith(Map.of("system", huge, "submitter", huge)));

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

    /**
     * 恰好等于列宽时必须原样保留：不能"多一个省略号"。
     *
     * <p>用 😀 构造，顺带钉住"按码点计长"：标题 200 个码点的 {@code String#length()} 是 393，
     * 按 char 判宽会把一条不超长的标题越界截断。
     */
    @Test
    void a_message_at_the_exact_column_width_is_kept_verbatim() {
        String system = "😀".repeat(TITLE_MAX - codePoints(TITLE_PREFIX));
        String submitter = "😀".repeat(BODY_MAX - codePoints(SUBMITTER_LABEL));
        Rendered rendered = NotificationRenderer.render(
                submittedWith(Map.of("system", system, "submitter", submitter)));

        assertThat(rendered.title()).isEqualTo(TITLE_PREFIX + system);
        assertThat(codePoints(rendered.title())).isEqualTo(TITLE_MAX);
        // 同一个字符串按 char 数是 393 > 200：证明宽度是按码点算的，不是按 char 算的。
        assertThat(rendered.title()).hasSizeGreaterThan(TITLE_MAX);

        assertThat(rendered.body()).isEqualTo(SUBMITTER_LABEL + submitter);
        assertThat(codePoints(rendered.body())).isEqualTo(BODY_MAX);
        assertThat(rendered.body()).doesNotEndWith("…");
    }

    /**
     * 超出一个码点就必须截：结果长度<b>恰好</b>是列宽，末尾是省略号。
     *
     * <p>只断言上界（≤ 列宽）会让"过度截断"也能通过——例如永远只保留一半。这里两侧都钉死。
     */
    @Test
    void a_message_one_code_point_over_the_width_is_truncated_to_exactly_the_width() {
        String system = "😀".repeat(TITLE_MAX - codePoints(TITLE_PREFIX) + 1);
        String submitter = "😀".repeat(BODY_MAX - codePoints(SUBMITTER_LABEL) + 1);
        Rendered rendered = NotificationRenderer.render(
                submittedWith(Map.of("system", system, "submitter", submitter)));

        assertThat(codePoints(rendered.title())).isEqualTo(TITLE_MAX);
        assertThat(rendered.title()).startsWith(TITLE_PREFIX).endsWith("…");

        assertThat(codePoints(rendered.body())).isEqualTo(BODY_MAX);
        assertThat(rendered.body()).startsWith(SUBMITTER_LABEL).endsWith("…");
    }

    /**
     * 白名单里的每个键都必须被渲染器认识。
     *
     * <p>白名单在 {@link NotificationCommand}，键字面量在 {@link NotificationRenderer}，两份数据靠人工同步：
     * 渲染器里写成 {@code ticketno} 时，这个事实会静默消失，且没有任何现有用例会失败。这里给每个键填一个
     * 唯一值 {@code V_<key>}，逐个断言它确实出现在"标题 + 正文"里。
     */
    @Test
    void every_whitelisted_key_is_actually_rendered() {
        Map<String, String> facts = new LinkedHashMap<>();
        for (String key : NotificationCommand.ALLOWED_FACT_KEYS) {
            facts.put(key, "V_" + key);
        }
        Rendered rendered = NotificationRenderer.render(submittedWith(facts));

        String message = rendered.title() + "\n" + rendered.body();
        for (String key : NotificationCommand.ALLOWED_FACT_KEYS) {
            assertThat(message).as("白名单键 %s 必须出现在标题或正文里", key).contains("V_" + key);
        }
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
        Rendered rendered = NotificationRenderer.render(
                submittedWith(Map.of("system", "订单中心", "submitter", "   ", "ticketNo", "42")));

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

    private static NotificationCommand submittedWith(Map<String, String> facts) {
        return new NotificationCommand(NotificationEventType.TICKET_SUBMITTED, 1L, 42L, null, facts);
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** 未配对的代理项经 UTF-8 编码会被替换成 {@code ?}，往返不相等即说明切坏了。 */
    private static boolean survivesUtf8RoundTrip(String text) {
        return text.equals(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
