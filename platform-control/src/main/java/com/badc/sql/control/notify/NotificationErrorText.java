package com.badc.sql.control.notify;

import java.util.regex.Pattern;

/**
 * 异常消息 → {@code notification.last_error} 的安全文本：先去敏，再截断。
 *
 * <p><b>为什么不能直接写异常消息</b>：{@code last_error} 是给运维看的，会出现在列表、报表、导出里。
 * 而投递失败最典型的异常来自 SMTP / 企微 / JDBC 客户端，消息里经常整段带着
 * {@code jdbc:mysql://user:pass@host:3306/db?user=root&password=...}。一旦落库，等于把数据库口令
 * 抄进了"人人可查"的通知记录——这是本类存在的唯一理由。
 *
 * <p><b>为什么是"最小实现"而不是复用执行器模块的 {@code SqlErrorMessages.sanitize}</b>：
 * 控制面不能依赖执行器（平台分层），而且那边处理的是 SQL 语法错误（列名、表名），
 * 口径完全不同。这里只做三件事：抹掉连接串、抹掉 URL 里的账号口令、抹掉 {@code key=value} 形式的凭证。
 * 宁可多抹（消息少一点信息），也不要漏一段口令。
 *
 * <p><b>还有一道截断</b>：{@code last_error} 是 {@code VARCHAR(500)}。投递器先把消息压到
 * {@link #DISPATCHER_MAX_CHARS}（给类名前缀和后续口径变化留余量），{@link JdbcNotificationStore}
 * 落库前再按 {@link #COLUMN_MAX_CHARS} 兜一次——两层都在，是因为任何一层被绕过都不该让整行写不进去。
 */
public final class NotificationErrorText {

    /** 投递器写进 {@code last_error} 的上限；远小于列宽 500，留出余量。 */
    static final int DISPATCHER_MAX_CHARS = 200;

    /** {@code notification.last_error VARCHAR(500)}，按字符（码点）计。 */
    static final int COLUMN_MAX_CHARS = 500;

    /** 截断标记；计入长度上限。 */
    private static final String ELLIPSIS = "…";

    /**
     * 整条 JDBC 连接串：协议、主机、端口、库名、查询参数（常含 user/password）一次性抹掉。
     *
     * <p>子协议长度限死 {@code {0,31}}：没有上界的 {@code [A-Za-z0-9]+} 在"jdbc:" 后面接一段长字母数字、
     * 却始终不出现 {@code ://} 的输入上，会从每个起点向后回溯，退化成 O(n²)。这里只是一层保险，
     * {@link #sanitizeText} 另外还有 {@code indexOf("jdbc:")} 的门控。
     */
    private static final Pattern JDBC_URL = Pattern.compile("jdbc:[A-Za-z0-9+._\\-]{0,31}://[^\\s,;\"'）)]*");

    /**
     * URL 里的 userinfo：{@code scheme://user:pass@host}、{@code scheme://token@host}
     * （没有冒号也是凭证）、协议相对写法 {@code //user:pass@host}、以及任意自定义 scheme。
     *
     * <p><b>为什么必须以字面 {@code //} 开头，而不是 {@code (scheme://)}</b>：后者以字符类开头、
     * 量词没有上界，对一段长字母数字串（不含 {@code ://}）会在每个起点回溯整段 → O(n²)。实测 40KB 为
     * 5517 ms（见 §8）。而 {@code sanitizeText} 吃的是<b>异常原文</b>，MySQL 的错误消息会把 SQL 片段带回来，
     * SQL 长度由研发提交的语句决定——那就是一个可被低成本触发的 CPU 耗尽面（投递器在异步线程池里）。
     *
     * <p><b>改这条正则之前先看两个性能用例</b>：{@code a_long_message_without_any_url_is_sanitized_quickly}
     * 与 {@code a_long_message_with_slashes_but_no_at_sign_is_sanitized_quickly}。本类的线性性质<b>完全依赖</b>
     * "正则以字面 {@code //} 开头"这个前提：一旦变成以字符类（或可选的字符类组）开头，长输入就会退回 O(n²)，
     * 而所有行为用例仍然是绿的——只有那两个性能用例会红。
     *
     * <p>以字面 {@code //} 开头同时解决了覆盖面：scheme 前面是什么根本不重要（保留原样），
     * 从 {@code //} 到 {@code @} 之间的整段 userinfo 才是要抹掉的部分。邮箱之类的裸 {@code @}
     * 没有 {@code //} 前缀，不会被误伤。
     */
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)//[^\\s/@]+@");

    /** {@code key=value} 形式的凭证：password / pwd / secret / token / 用户名 / 主机。
     * 值两侧可以有单引号、双引号或反引号，引号内允许空格——{@code password='s3 cr3t'} 只抹一半等于没抹。
     * 收尾引号是可选的：{@code password='s3cr3t}（引号没闭合）照样是口令，不能因为"配对失败"整段放过。 */
    private static final Pattern NAME_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|corpsecret|access_token|token|username|user|host)\\s*=\\s*"
                    + "(?:\"[^\"]*\"?|'[^']*'?|`[^`]*`?|[^\\s,;&\"'`]+)");

    private NotificationErrorText() {
    }

    /**
     * 把异常转成"一行、脱敏、可入库"的文本。
     *
     * <p>只取异常类名 + 顶层消息（不展开 cause 与堆栈）：{@code last_error} 只回答"为什么失败"，
     * 排查细节在应用日志里。换行被压成空格，避免把多行消息截断后只剩半句噪音。
     *
     * @param error 异常；{@code null} 返回 {@code null}
     * @return 形如 {@code IllegalStateException: 邮件发送失败 jdbc:***}；消息为空时只有类名
     */
    public static String sanitize(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        String simpleName = error.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return simpleName;
        }
        return simpleName + ": " + sanitizeText(message);
    }

    /**
     * 对一段<b>文本</b>做脱敏（不含异常类名前缀），用于异常消息与 {@code SKIPPED} 的跳过原因。
     *
     * <p>跳过原因不走异常？它同样会落进 {@code last_error}、同样会展示给研发，所以同样要抹。
     *
     * @param message 原文；{@code null} 返回 {@code null}
     * @return 单行、脱敏后的文本
     */
    static String sanitizeText(String message) {
        if (message == null) {
            return null;
        }
        // 先门控再匹配：两条 URL 正则都以字面量开头，字符串里根本没有那个字面量时就不必进正则引擎。
        // 这同时把"长 SQL 片段"这类输入的代价压到一次 indexOf（线性）上。
        String redacted = message;
        if (redacted.indexOf("jdbc:") >= 0) {
            redacted = JDBC_URL.matcher(redacted).replaceAll("jdbc:***");
        }
        if (redacted.indexOf("//") >= 0) {
            redacted = URL_USERINFO.matcher(redacted).replaceAll("//***@");
        }
        redacted = NAME_VALUE_SECRET.matcher(redacted).replaceAll("$1=***");
        return redacted.replaceAll("\\s+", " ").strip();
    }

    /**
     * 截断到 {@code maxChars} 个码点以内，截断时以 {@code …} 收尾（省略号计入上限）。
     *
     * <p>按码点而非常量 {@code String#length()}：补充平面字符（emoji）在 Java 里占两个 {@code char}、
     * 在 MySQL {@code utf8mb4} 里算一个字符，按 char 切会留下半个代理对。
     *
     * @param text     原文；{@code null} 返回 {@code null}
     * @param maxChars 上限（码点数）；{@code <= 0} 返回空串
     * @return 不超过 {@code maxChars} 个码点的文本
     */
    static String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        if (maxChars <= 0) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars - 1);
        return text.substring(0, end) + ELLIPSIS;
    }
}
