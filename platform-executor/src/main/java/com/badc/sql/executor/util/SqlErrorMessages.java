package com.badc.sql.executor.util;

import java.util.regex.Pattern;

/**
 * 数据库错误消息脱敏：把 MySQL 消息里的连接信息换成不可反推的占位符。
 *
 * <p>为什么需要：连接信息（host / port / user / 密码 / 连接串）对研发不可见。MySQL 的错误原文里有三种
 * 形态会把它带出去：
 * <ul>
 *   <li>28000（access denied）：{@code Access denied for user 'readonly_user'@'10.1.2.3' (using password: YES)}；</li>
 *   <li>1130（host 不允许连接）：{@code Host '10.1.2.3' is not allowed to connect to this MySQL server}；</li>
 *   <li>驱动层失败：{@code No suitable driver found for jdbc:mysql://10.1.2.3:3306/order_db}。</li>
 * </ul>
 * 这类消息会被拼进 {@code BizException}，经控制面落进 API 响应体，等于把数据库账号与主机告诉了研发。
 *
 * <p><b>掩的是连接信息，不是密码。</b>密码从不出现在 MySQL 的错误消息里——上面第一句只有
 * {@code using password: YES/NO}——所以这里不去猜密码，也不整条丢弃：整条丢弃会把权限、库名、
 * 语法错误这些排障信息一起抹掉。只替换上面三种固定形态，其余文本原样保留。
 *
 * <p>所有把 {@code SQLException} 消息拼进 {@code BizException} 的地方都应先过 {@link #sanitize}，
 * 不要再各写一份正则。
 */
public final class SqlErrorMessages {

    /** MySQL 消息里的账号与主机，形如 {@code 'readonly_user'@'10.1.2.3'}。 */
    private static final Pattern CREDENTIALS_IN_MESSAGE = Pattern.compile("'[^']*'@'[^']*'");

    private static final String MASKED_CREDENTIALS = "'***'@'***'";

    /** 1130 的主机名，形如 {@code Host '10.1.2.3'}（大小写不敏感：不同版本措辞大小写不一致）。 */
    private static final Pattern HOST_IN_MESSAGE = Pattern.compile("Host\\s+'[^']*'", Pattern.CASE_INSENSITIVE);

    private static final String MASKED_HOST = "Host '***'";

    /**
     * 驱动层的连接串，形如 {@code jdbc:mysql://10.1.2.3:3306/order_db?user=root}。
     *
     * <p>整段（authority + 库名 + 参数）一起掩掉：参数里可能带 {@code user}/{@code password}，
     * 只掩 host:port 会留下另一种泄露。保留 scheme 只是为了让消息还能读懂"是哪类连接串"。
     */
    private static final Pattern JDBC_URL_IN_MESSAGE =
            Pattern.compile("jdbc:mysql://[^\\s'\"]*", Pattern.CASE_INSENSITIVE);

    private static final String MASKED_JDBC_URL = "jdbc:mysql://***";

    private SqlErrorMessages() {
    }

    /**
     * 把消息里的 {@code '账号'@'主机'}、{@code Host '…'} 与 {@code jdbc:mysql://…} 全部替换为占位符。
     *
     * @param message 数据库返回的原始消息，可为 {@code null}
     * @return 脱敏后的消息；{@code null} 归一成空串，避免拼出字面量 "null"
     */
    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String sanitized = CREDENTIALS_IN_MESSAGE.matcher(message).replaceAll(MASKED_CREDENTIALS);
        sanitized = HOST_IN_MESSAGE.matcher(sanitized).replaceAll(MASKED_HOST);
        return JDBC_URL_IN_MESSAGE.matcher(sanitized).replaceAll(MASKED_JDBC_URL);
    }
}
