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

/**
 * 用 {@code EXPLAIN} 估算一条语句会碰多少行。
 *
 * <p><b>绝不执行原语句</b>：待估算的可能是 {@code update}/{@code delete}/{@code drop}，这里只发
 * {@code explain <sql>}。EXPLAIN 本身不写数据，也不会真正跑语句。
 *
 * <p>例外是 {@code analyze} 开头的语句：{@code EXPLAIN ANALYZE} 会<b>真正执行</b>查询。遇到这种输入
 * 直接返回 {@code -1}，不拼 {@code explain}。
 *
 * <p>取所有结果行的 {@code rows} 求和：单表语句接近实际扫描行数；多表 join 时每个访问路径一行，
 * 求和会<b>低估</b>组合后的量级，只用于风险分档。任一行 {@code rows} 为 NULL、结果为空、语句不合法、
 * 库连不上，一律返回 {@link ExplainEstimate}(-1)，由调用方按最严处理——估算失败不能变成"0 行"。
 */
@Component
public class ExplainRunner {

    /** -1 表示无法估算；调用方必须按最严情况处理。 */
    private static final long UNKNOWN_ROWS = -1L;

    private static final int EXPLAIN_TIMEOUT_SECONDS = 30;

    private static final String ROWS_COLUMN = "rows";

    private static final String ANALYZE_KEYWORD = "analyze";

    public ExplainEstimate estimate(ConnConfig conn, String sql) {
        if (sql == null || sql.isBlank()) {
            return unknown();
        }
        if (startsWithAnalyze(sql)) {
            // EXPLAIN ANALYZE 会执行原语句；这里绝不能拼。
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
            // 语句不合法、库不可达、无权限：都归为"无法估算"，不把异常抛给控制面。
            return unknown();
        }
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

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static ExplainEstimate unknown() {
        return new ExplainEstimate(UNKNOWN_ROWS);
    }
}
