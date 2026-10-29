package com.badc.sql.executor.util;

import java.util.regex.Pattern;

/**
 * 数据库错误消息脱敏：把 MySQL 消息里的 {@code '账号'@'主机'} 换成 {@code '***'@'***'}。
 *
 * <p>为什么需要：连接信息（host / port / user / 密码 / 连接串）对研发不可见。MySQL 的 28000
 * （access denied）原文形如
 * {@code Access denied for user 'readonly_user'@'10.1.2.3' (using password: YES)}，这类消息会被拼进
 * {@code BizException}，经控制面落进 API 响应体，等于把数据库账号与主机告诉了研发。
 *
 * <p><b>掩的是账号与主机，不是密码。</b>密码从不出现在 MySQL 的错误消息里——上面那句只有
 * {@code using password: YES/NO}——所以这里不去猜密码，也不整条丢弃：整条丢弃会把权限、库名、
 * 语法错误这些排障信息一起抹掉。只替换 {@code '...'@'...'} 这一形态，其余文本原样保留。
 *
 * <p>所有把 {@code SQLException} 消息拼进 {@code BizException} 的地方都应先过 {@link #sanitize}，
 * 不要再各写一份正则。
 */
public final class SqlErrorMessages {

    /** MySQL 消息里的账号与主机，形如 {@code 'readonly_user'@'10.1.2.3'}。 */
    private static final Pattern CREDENTIALS_IN_MESSAGE = Pattern.compile("'[^']*'@'[^']*'");

    private static final String MASK = "'***'@'***'";

    private SqlErrorMessages() {
    }

    /**
     * 把消息里的 {@code '账号'@'主机'} 全部替换为 {@code '***'@'***'}。
     *
     * @param message 数据库返回的原始消息，可为 {@code null}
     * @return 脱敏后的消息；{@code null} 归一成空串，避免拼出字面量 "null"
     */
    public static String sanitize(String message) {
        return message == null ? "" : CREDENTIALS_IN_MESSAGE.matcher(message).replaceAll(MASK);
    }
}
