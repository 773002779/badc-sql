package com.badc.sql.executor.exec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;
import com.badc.sql.executor.util.SqlErrorMessages;

/**
 * 写语句执行：一条语句、一条连接、独立提交，结果按条回传。
 *
 * <p><b>每条语句独立提交。</b>每次 {@link #run} 都新开连接并置 {@code autoCommit=true}，不包事务。
 * 这正是"部分失败"的来源：前一条已提交、后一条失败时，前一条<b>不回滚</b>——工单状态必须如实记录
 * "哪几条已经生效"，而不是假装整单是原子的。MySQL 的 DDL 本身也会隐式提交，包事务只会制造假象。
 *
 * <p><b>失败是结果，不是异常。</b>{@code SQLException} 一律转成 {@link ExecuteResult}({@code success=false})
 * 返回，绝不抛给控制面：抛出去只会让控制面丢掉"哪条失败、为什么"，整单只能记一个笼统的 FAILED。
 * 消息过 {@link SqlErrorMessages#sanitize}，掩掉 {@code '账号'@'主机'}——响应体会经控制面流向前端。
 *
 * <p><b>socket 读超时必须不短于语句超时。</b>查询路径的 {@link MySqlAdapter#url} 固定
 * {@code socketTimeout=30000}，那是为 60 秒只读查询定的取舍；照搬到写路径，一条 60 秒的 {@code update}
 * 会在 30 秒被 socket 读超时打断，再被"数据源不可达"的口径报出去，真实原因只是"还在跑"。DDL 更夸张
 * （3600 秒 vs 30 秒）。因此写路径自己拼 URL，把 socketTimeout 抬到不小于该类型的语句超时；
 * {@code MySqlAdapter.url} 本身不动，查询路径的既有取舍保持不变。
 */
@Component
public class WriteRunner {

    /** DML 语句超时：写 300 秒（全局约束）。 */
    static final int DML_TIMEOUT_SECONDS = 300;

    /** DDL 语句超时：结构变更 3600 秒（全局约束）。 */
    static final int DDL_TIMEOUT_SECONDS = 3600;

    /**
     * DML 的 socket 读超时（毫秒）。
     *
     * <p>与语句超时分开写死、不写成 {@code DML_TIMEOUT_SECONDS * 1000}：这个"不小于"是必须被单测钉住的
     * 约束，不该靠算式自动成立——否则有人把语句超时调大时，socket 超时会跟着变大，反而看不出问题。
     */
    static final int DML_SOCKET_TIMEOUT_MILLIS = 300_000;

    /** DDL 的 socket 读超时（毫秒），同样必须 ≥ {@link #DDL_TIMEOUT_SECONDS} × 1000。 */
    static final int DDL_SOCKET_TIMEOUT_MILLIS = 3_600_000;

    /** 基础 URL 里的 socketTimeout 参数（可能出现在 {@code ?} 或 {@code &} 之后）。 */
    private static final Pattern SOCKET_TIMEOUT = Pattern.compile("([?&])socketTimeout=[^&]*");

    /**
     * 执行一条写语句。
     *
     * <p>{@code type} 为空按 {@link ExecAccountType#DML} 处理：类型只决定超时，取较短的那个是保守方向，
     * 不会让一条本该 300 秒的语句跑到 3600 秒。连接为空的校验在端点层完成（与控制面既有口径一致）。
     *
     * @param conn 已解密的连接配置
     * @param sql  单条语句；控制面已保证不含多条语句，执行器不再拆分
     * @param type 账号类型，决定语句超时与 socket 读超时
     * @return 本条语句的结果；失败不抛异常
     */
    public ExecuteResult run(ConnConfig conn, String sql, ExecAccountType type) {
        ExecAccountType effectiveType = type == null ? ExecAccountType.DML : type;
        long startedAt = System.nanoTime();
        if (sql == null || sql.isBlank()) {
            // 空语句不进驱动：Connector/J 对 null 的行为随版本而异，可能抛 NPE 变成笼统的 500。
            // 这里同样按"结果"处理，控制面能明确记下这条语句没内容。
            return new ExecuteResult(false, 0, elapsedMillis(startedAt), null, "语句为空");
        }
        try (Connection connection = DriverManager.getConnection(
                url(conn, effectiveType), conn.username(), conn.password())) {
            // 每条语句独立提交：显式置 true，而不是依赖驱动的默认值。
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds(effectiveType));
                int affectedRows = statement.executeUpdate(sql);
                return new ExecuteResult(true, affectedRows, elapsedMillis(startedAt), null, null);
            }
        } catch (SQLException e) {
            return failure(e, elapsedMillis(startedAt));
        }
    }

    /**
     * 写路径的连接 URL：在 {@link MySqlAdapter#url} 的基础上只替换 {@code socketTimeout}。
     *
     * <p>其余参数（SSL、会话时区 UTC、{@code connectTimeout}、字符集）一律沿用查询路径：各抄一份会在
     * 那边调整参数时悄悄漂移，而写路径与查询路径必须连到同一个"会话时区 UTC"的库上。只覆盖
     * {@code socketTimeout} 这一个<b>必须</b>不同的值。
     *
     * <p>包级可见是为了让单测直接断言字符串——验证"socket 超时够长"不能靠真跑一条 5 分钟的语句。
     */
    static String url(ConnConfig conn, ExecAccountType type) {
        int socketTimeoutMillis = socketTimeoutMillis(type);
        String base = MySqlAdapter.url(conn);
        Matcher matcher = SOCKET_TIMEOUT.matcher(base);
        if (!matcher.find()) {
            // 基础 URL 哪天不再带 socketTimeout 时仍然给写路径补上，而不是退回驱动的无限等待。
            return base + (base.indexOf('?') >= 0 ? "&" : "?") + "socketTimeout=" + socketTimeoutMillis;
        }
        return matcher.replaceFirst("$1socketTimeout=" + socketTimeoutMillis);
    }

    /** 该账号类型的语句超时（秒）。 */
    static int timeoutSeconds(ExecAccountType type) {
        return type == ExecAccountType.DDL ? DDL_TIMEOUT_SECONDS : DML_TIMEOUT_SECONDS;
    }

    /** 该账号类型的 socket 读超时（毫秒），恒不小于语句超时。 */
    static int socketTimeoutMillis(ExecAccountType type) {
        return type == ExecAccountType.DDL ? DDL_SOCKET_TIMEOUT_MILLIS : DML_SOCKET_TIMEOUT_MILLIS;
    }

    /**
     * {@code SQLException} → 结构化失败。
     *
     * <p>{@code errorCode} 只取 {@code SQLState}（可能为 {@code null}，允许）；{@code errorMessage} 只取
     * 清洗后的消息，绝不拼连接串或账号。
     *
     * <p>包级可见：单测要直接喂一条构造的 28000 异常，钉住"响应体里不能出现账号与主机"。
     */
    static ExecuteResult failure(SQLException e, long elapsedMs) {
        return new ExecuteResult(
                false,
                0,
                elapsedMs,
                e.getSQLState(),
                SqlErrorMessages.sanitize(e.getMessage()));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
