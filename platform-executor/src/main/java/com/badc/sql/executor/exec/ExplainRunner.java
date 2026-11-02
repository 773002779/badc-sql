package com.badc.sql.executor.exec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.stereotype.Component;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;
import com.badc.sql.executor.util.SqlErrorMessages;

/**
 * 用 {@code EXPLAIN} 估算一条语句会碰多少行。
 *
 * <p><b>绝不执行原语句</b>：待估算的可能是 {@code update}/{@code delete}/{@code drop}，这里只发
 * {@code explain <sql>}。EXPLAIN 本身不写数据，也不会真正跑语句。
 *
 * <p>例外是 {@code analyze} 开头的语句：{@code EXPLAIN ANALYZE} 会<b>真正执行</b>查询。遇到这种输入
 * 直接返回 {@code -1}，不拼 {@code explain}。MySQL 的版本注释（{@code /*!80018 ANALYZE *}{@code /}）同理：
 * 服务端会把它展开成可执行 token，所以"剥离注释后仍含 {@code /*!}"也直接放弃，见 {@link #hasExecutableComment}。
 *
 * <p>取所有结果行的 {@code rows} 求和：单表语句接近实际扫描行数；多表 join 时每个访问路径一行，
 * 求和会<b>低估</b>组合后的量级，只用于风险分档。任一行 {@code rows} 为 NULL、结果为空、语句不合法、
 * 表不存在，一律返回 {@link ExplainEstimate}(-1)，由调用方按最严处理——估算失败不能变成"0 行"。
 *
 * <p><b>"库连不上 / 没权限"不是"估不出来"，必须抛 {@link BizException}。</b>它们与 {@link ExplainEstimate}
 * 和 {@code ExecutorClient#explain} 的契约同义：连接类（SQLState {@code 08xxx}）与权限类
 * （错误码 1044/1045/1142）抛异常；语法错、表不存在这类"语句本身估不出来"才返回 {@code -1}。
 * 把连接/权限故障也吞成 {@code -1}，控制面就分不清"这条语句影响面未知"和"根本探测不了这个库"：
 * 前者按最严分档，后者必须中止——混为一谈会让一次故障静默变成一张恒 L3 的工单。
 */
@Component
public class ExplainRunner {

    /** -1 表示无法估算；调用方必须按最严情况处理。 */
    private static final long UNKNOWN_ROWS = -1L;

    private static final int EXPLAIN_TIMEOUT_SECONDS = 30;

    private static final String ROWS_COLUMN = "rows";

    private static final String ANALYZE_KEYWORD = "analyze";

    /**
     * MySQL 的"可执行注释"开头：{@code /*!80018 ANALYZE *}{@code /} 会被服务端展开成可执行 token。
     *
     * <p>它长得像块注释，所以"跳过前导注释再取第一个词"的守卫看不见里面的 {@code ANALYZE}；拼成
     * {@code explain /*!80018 ANALYZE *}{@code / select ...} 之后，服务端展开成 {@code EXPLAIN ANALYZE}，
     * 语句就<b>真的被执行</b>了。因此只要 SQL 里出现这个形态就直接放弃估算。
     */
    private static final String EXECUTABLE_COMMENT = "/*!";

    /** SQLState 的连接类前缀；08xxx 一律视为"库不可达"。 */
    private static final String CONNECTION_STATE_PREFIX = "08";

    /** EXPLAIN 被权限拒绝时 MySQL 的错误码：1044 库级、1045 账号级、1142 命令/表级。 */
    private static final int ACCESS_DENIED_TO_DATABASE = 1044;
    private static final int ACCESS_DENIED = 1045;
    private static final int COMMAND_DENIED = 1142;

    public ExplainEstimate estimate(ConnConfig conn, String sql) {
        if (sql == null || sql.isBlank()) {
            return unknown();
        }
        if (startsWithAnalyze(sql)) {
            // EXPLAIN ANALYZE 会执行原语句；这里绝不能拼。
            return unknown();
        }
        if (hasExecutableComment(sql)) {
            // /*!80018 ANALYZE */ 会被服务端展开成可执行 token：拼成 explain 之后仍然会真的执行。
            return unknown();
        }
        if (conn == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        try (Connection connection = DriverManager.getConnection(
                MySqlAdapter.url(conn), conn.username(), conn.password());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(EXPLAIN_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery("explain " + sql)) {
                return sumRows(rs);
            }
        } catch (SQLException e) {
            // 连接类/权限类是故障（抛），语法错、表不存在是"估不出来"（-1）。两者不能混为一谈。
            throwIfUnavailable(e);
            return unknown();
        }
    }

    /**
     * 连接类（{@code 08xxx}）与权限类（1044/1045/1142）异常翻译成 {@link BizException}，其余不动。
     *
     * <p>连接类的原始消息带 host:port，权限类的原始消息带 {@code '账号'@'主机'}，都不能原样流向前端；
     * 这里与 {@code QueryRunner#translate}、{@code SchemaSnapshotter#failure} 保持同一口径：
     * 08xxx 不回显原文，其余过 {@link SqlErrorMessages#sanitize(String)}。
     *
     * <p>包级可见：单测直接喂构造的 {@link SQLException}，钉住"哪类抛、哪类返回 -1"。
     */
    static void throwIfUnavailable(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith(CONNECTION_STATE_PREFIX)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "数据源不可达（SQLState=" + state + "），请确认网络与白名单配置", e);
        }
        if (isPermissionDenied(e.getErrorCode())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "EXPLAIN 被数据库拒绝（SQLState=" + state + ", errorCode=" + e.getErrorCode()
                            + "）：" + SqlErrorMessages.sanitize(e.getMessage()), e);
        }
    }

    private static boolean isPermissionDenied(int errorCode) {
        return errorCode == ACCESS_DENIED_TO_DATABASE
                || errorCode == ACCESS_DENIED
                || errorCode == COMMAND_DENIED;
    }

    private static ExplainEstimate sumRows(ResultSet rs) throws SQLException {
        int rowsColumn = rowsColumn(rs.getMetaData());
        if (rowsColumn < 0) {
            return unknown();
        }
        long total = 0;
        boolean any = false;
        while (rs.next()) {
            any = true;
            Object rows = rs.getObject(rowsColumn);
            if (rows == null) {
                return unknown();
            }
            total += ((Number) rows).longValue();
        }
        return any ? new ExplainEstimate(total) : unknown();
    }

    /** 按列标签找 {@code rows}（不同版本列顺序一致，但不该依赖序号）。 */
    private static int rowsColumn(ResultSetMetaData meta) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (ROWS_COLUMN.equalsIgnoreCase(meta.getColumnLabel(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 首个可执行 token 是不是 {@code analyze}。
     *
     * <p>不能只看 {@code sql.trim()} 是否以 analyze 开头：前面垫一段块注释、{@code --} 行注释或 {@code #}
     * 行注释再跟 analyze，一样会拼成 {@code EXPLAIN ANALYZE} 并把语句真的执行掉。这里跳过前导空白与三种
     * 注释后再取第一个词。
     */
    static boolean startsWithAnalyze(String sql) {
        int start = skipLeadingNoise(sql);
        int end = start;
        while (end < sql.length() && isWordChar(sql.charAt(end))) {
            end++;
        }
        return end > start && ANALYZE_KEYWORD.equalsIgnoreCase(sql.substring(start, end));
    }

    private static int skipLeadingNoise(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLine(sql, index + 2);
            } else if (current == '#') {
                index = skipLine(sql, index + 1);
            } else if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int commentEnd = sql.indexOf("*/", index + 2);
                if (commentEnd < 0) {
                    // 未闭合的块注释：后面没有可执行 token，按"不是 analyze"处理也无妨——拼出来的
                    // EXPLAIN 会因语法错误返回 -1。
                    return sql.length();
                }
                index = commentEnd + 2;
            } else {
                break;
            }
        }
        return index;
    }

    private static int skipLine(String sql, int from) {
        int newline = sql.indexOf('\n', from);
        return newline < 0 ? sql.length() : newline + 1;
    }

    /**
     * SQL 里是否含 MySQL 可执行注释（{@code /*!...*}{@code /}）。
     *
     * <p>不做语法级扫描、只做子串判定：宁可对"字符串字面量里恰好出现 {@code /*!}"这种极少数语句放弃估算
     * （返回 -1，按最严处理），也不能漏掉一次真正的执行。漏掉的代价是"EXPLAIN 承诺不执行"这个安全属性失效。
     */
    static boolean hasExecutableComment(String sql) {
        return sql != null && sql.contains(EXECUTABLE_COMMENT);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static ExplainEstimate unknown() {
        return new ExplainEstimate(UNKNOWN_ROWS);
    }
}
