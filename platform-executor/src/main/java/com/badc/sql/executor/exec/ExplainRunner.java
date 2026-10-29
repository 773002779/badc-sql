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
 * <p>取所有结果行的 {@code rows} 求和：多表 join 时每张表一行，总行数是各行的乘积量级，加起来已经是
 * 偏保守的近似。任一行 {@code rows} 为 NULL、结果为空、语句不合法、库连不上，一律返回
 * {@link ExplainEstimate}(-1)，由调用方按最严处理——估算失败不能变成"0 行"。
 */
@Component
public class ExplainRunner {

    /** -1 表示无法估算；调用方必须按最严情况处理。 */
    private static final long UNKNOWN_ROWS = -1L;

    private static final int EXPLAIN_TIMEOUT_SECONDS = 30;

    private static final String ROWS_COLUMN = "rows";

    public ExplainEstimate estimate(ConnConfig conn, String sql) {
        if (sql == null || sql.isBlank()) {
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

    private static ExplainEstimate unknown() {
        return new ExplainEstimate(UNKNOWN_ROWS);
    }
}
