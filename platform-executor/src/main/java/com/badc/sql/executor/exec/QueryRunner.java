package com.badc.sql.executor.exec;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.badc.sql.common.dto.ColumnMeta;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;
import com.badc.sql.executor.util.SqlErrorMessages;

/**
 * 只读查询执行。
 *
 * <p>三道闸：按数据源限流（默认 5，防止一个库被并发打爆）、语句超时 60 秒、结果行数上限由调用方给定
 * （研发固定 1001）。走到上限就停止读取并标记 {@code truncated}——不是把全部行拉回来再截断。
 */
@Component
public class QueryRunner {

    private static final int MAX_CONCURRENCY = 5;
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final int MAX_TEXT_CHARS = 64 * 1024;
    private static final String TRUNCATED_SUFFIX = "[truncated]";

    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();

    public RawResult run(ConnConfig conn, String sql, int maxRows) {
        if (maxRows <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "行数上限必须大于 0");
        }
        Semaphore gate = gates.computeIfAbsent(conn.host() + ":" + conn.port() + "/" + conn.dbName(),
                key -> new Semaphore(MAX_CONCURRENCY));
        boolean acquired = false;
        long startedAt = System.nanoTime();
        try {
            acquired = gate.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "该数据源并发查询已满，请稍后重试");
            }
            try (Connection connection = DriverManager.getConnection(
                    MySqlAdapter.url(conn), conn.username(), conn.password());
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = statement.executeQuery(sql)) {
                    return read(rs, maxRows, startedAt);
                }
            }
        } catch (SQLException e) {
            throw translate(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "查询被中断", e);
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    }

    private RawResult read(ResultSet rs, int maxRows, long startedAt) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<ColumnMeta> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnMeta(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(mapValue(rs, i, meta.getColumnTypeName(i)));
            }
            rows.add(row);
        }
        long elapsedMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        return new RawResult(List.copyOf(columns), List.copyOf(rows), truncated, elapsedMs);
    }

    private static Object mapValue(ResultSet rs, int index, String typeName) throws SQLException {
        String type = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);

        if (type.contains("BLOB") || type.contains("BINARY")) {
            byte[] bytes = rs.getBytes(index);
            if (bytes == null) {
                return null;
            }
            Map<String, Object> placeholder = new HashMap<>(1);
            placeholder.put("hexLen", bytes.length);
            return placeholder;
        }

        // DATETIME 直接从字符串取值再格式化：会话已强制 UTC，避免 JVM 默认时区参与换算。
        if (type.contains("DATETIME") || type.contains("TIMESTAMP")) {
            String raw = rs.getString(index);
            return raw == null ? null : toIsoUtc(raw);
        }

        String value = rs.getString(index);
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_TEXT_CHARS) {
            return value.substring(0, MAX_TEXT_CHARS) + TRUNCATED_SUFFIX;
        }
        return value;
    }

    /** {@code 2026-10-26 12:34:56[.789]} → {@code 2026-10-26T12:34:56.789Z}。 */
    static String toIsoUtc(String raw) {
        String normalized = raw.trim().replace(' ', 'T');
        int dot = normalized.indexOf('.');
        if (dot < 0) {
            return normalized + ".000Z";
        }
        String fraction = normalized.substring(dot + 1);
        String millis = (fraction + "000").substring(0, 3);
        return normalized.substring(0, dot) + "." + millis + "Z";
    }

    /**
     * 数据库异常翻译。
     *
     * <p>包级可见而非 {@code private}：单测要钉住"错误消息里不能带账号与主机"，为可测性放宽。
     */
    static BizException translate(SQLException e) {
        // 1044/1142 是权限；08xxx 是连接类；其余归为执行失败。消息里不带连接串与账号。
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("08")) {
            return new BizException(ErrorCode.INTERNAL_ERROR, "数据源不可达，请确认网络与白名单", e);
        }
        if ("42000".equals(state) || "07001".equals(state)) {
            return new BizException(ErrorCode.INVALID_ARGUMENT,
                    "SQL 语法或参数错误：" + SqlErrorMessages.sanitize(e.getMessage()), e);
        }
        // 28000（access denied）落在这里：原文含 '账号'@'主机'，必须脱敏后才能进 BizException。
        return new BizException(ErrorCode.INTERNAL_ERROR,
                "查询执行失败：" + SqlErrorMessages.sanitize(e.getMessage()), e);
    }
}
