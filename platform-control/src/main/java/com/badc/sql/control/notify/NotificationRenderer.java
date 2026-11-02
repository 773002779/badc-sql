package com.badc.sql.control.notify;

import java.util.List;
import java.util.Objects;

/**
 * 把 {@link NotificationCommand} 渲染成"标题 + 正文"的纯函数。
 *
 * <p><b>纯函数、无状态、无 Spring 依赖</b>（与 {@code RiskEngine} 同风格）：同一个 command 渲染结果永远
 * 相同，可以直接单测；渲染不做 IO、不看时钟、不读配置。投递器（Task 5）拿 {@link Rendered} 去落库与发送，
 * 但不参与内容生成——模板只有这一处。
 *
 * <h2>消息里不出现 SQL</h2>
 * <p>正文的每一项都来自 {@link NotificationCommand#ALLOWED_FACT_KEYS} 白名单；渲染器不认识任何其它键，
 * 也没有"把语句贴进消息"的分支。白名单的强制在 {@code NotificationCommand} 的紧凑构造器里，这里只负责
 * 按固定顺序取用。
 *
 * <h2>截断（本类最重要的职责）</h2>
 * <p>落库列宽是硬约束：{@code notification.title VARCHAR(200)}、{@code notification.body VARCHAR(2000)}。
 * 超长在 Task 5 会变成 SQL 异常（严格的 MySQL 会直接报 Data too long），而且是在业务事务提交之后——
 * 一条通知发不出去不该让已完成的业务动作看起来失败。所以截断放在渲染器里，它是唯一能拦住的地方：
 * <ul>
 *   <li>按<b>码点</b>截断（{@link String#offsetByCodePoints}），不按 {@code char} 截：😀 这类补充平面字符
 *       在 Java 里占两个 {@code char}、在 MySQL {@code utf8mb4} 里算一个字符，按 char 切会留下半个代理对，
 *       落库时变成 {@code ?} 或直接报错。</li>
 *   <li>截断后以 {@code …} 收尾，且省略号计入宽度上限（总长仍 ≤ 列宽）：收件人能看出这条被截过，
 *       而不是以为系统名本来就这么长。</li>
 *   <li>正文按整行拼接后再整体截断，可能截在某一行中间——这是可接受的：内容都是元数据，没有需要成对出现的
 *       结构；保留"尽量多信息"比"只保留完整行"对收件人更有用。</li>
 * </ul>
 *
 * <h2>正文格式</h2>
 * <p>每项一行、{@code 中文名：值}，缺失（或只有空白）的项整行跳过，行间用 {@code \n} 分隔。
 * 正文里不重复系统名——它已经在标题里了。
 */
public final class NotificationRenderer {

    /** {@code notification.title VARCHAR(200)}，按码点计。 */
    private static final int TITLE_MAX_CODE_POINTS = 200;

    /** {@code notification.body VARCHAR(2000)}，按码点计。 */
    private static final int BODY_MAX_CODE_POINTS = 2000;

    /** 截断标记；计入宽度上限。 */
    private static final String ELLIPSIS = "…";

    private static final String LABEL_SEPARATOR = "：";
    private static final String LINE_SEPARATOR = "\n";

    /** 标题里"系统名"的位置：{@code 【事件名】系统名}。 */
    private static final String KEY_SYSTEM = "system";

    /**
     * 正文的渲染顺序与中文名。
     *
     * <p>{@code system} 不在其中（已进标题）；{@code reason} 排在最后：它是自由文本，可能是本次事件最关键
     * 的一句，但也可能很长，放在末尾被截断时损失最小。
     */
    private static final List<BodyField> BODY_FIELDS = List.of(
            new BodyField("module", "模块"),
            new BodyField("datasource", "数据源"),
            new BodyField("statementType", "语句类型"),
            new BodyField("riskLevel", "风险等级"),
            new BodyField("estimatedRows", "影响行数预估"),
            new BodyField("submitter", "提交人"),
            new BodyField("ticketNo", "工单号"),
            new BodyField("link", "短链"),
            new BodyField("reason", "原因"));

    private NotificationRenderer() {
    }

    /**
     * 渲染一条通知。
     *
     * @param command 事件类型与白名单事实
     * @return 标题与正文；标题形如 {@code 【工单已提交】订单中心}，正文按项换行，缺失项跳过
     */
    public static Rendered render(NotificationCommand command) {
        Objects.requireNonNull(command, "command");
        String title = "【" + command.type().label() + "】" + fact(command, KEY_SYSTEM);
        return new Rendered(ellipsize(title, TITLE_MAX_CODE_POINTS), ellipsize(body(command), BODY_MAX_CODE_POINTS));
    }

    private static String body(NotificationCommand command) {
        StringBuilder body = new StringBuilder();
        for (BodyField field : BODY_FIELDS) {
            String value = fact(command, field.key());
            if (value.isEmpty()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append(LINE_SEPARATOR);
            }
            body.append(field.label()).append(LABEL_SEPARATOR).append(value);
        }
        return body.toString();
    }

    /** 取一个事实值并去掉首尾空白：{@code null}（未提供）与全空白（无意义的占位）都按"缺失"处理。 */
    private static String fact(NotificationCommand command, String key) {
        String value = command.facts().get(key);
        return value == null ? "" : value.strip();
    }

    /**
     * 把文本截到 {@code maxCodePoints} 个码点以内，截断时以 {@code …} 收尾（省略号计入上限）。
     *
     * <p>按码点而非常量 {@code String#length()} 计算，保证不会把一个补充平面字符切成半个代理对；
     * 未超长时原样返回，不做任何改动。
     */
    private static String ellipsize(String text, int maxCodePoints) {
        if (text == null || text.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        // 留一个码点的位置给省略号：结果总宽 ≤ maxCodePoints，正好等于列宽。
        int end = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, end) + ELLIPSIS;
    }

    /** 正文里的一项：白名单键 + 中文名。 */
    private record BodyField(String key, String label) {
    }
}
