package com.badc.sql.control.notify;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@link NotificationStore} 的 JDBC 实现（真实 MySQL，列定义见 {@code V4__notification.sql}）。
 *
 * <p>几个不显眼但必要的地方：
 * <ul>
 *   <li>自增主键用 {@link Statement#RETURN_GENERATED_KEYS} 取回，不用 {@code last_insert_id()}：
 *       连接池下后者可能落到另一条连接上，拿到的不是本行的 id（与 {@code TicketMapper} 同口径）。</li>
 *   <li>三个可空业务键（{@code system_id/ticket_id/grant_request_id}）用
 *       {@link Types#BIGINT} 显式 setNull："没有"必须是 {@code NULL}，写成 0 会指向一个不存在的系统/工单，
 *       报表里就成了另一条业务线的记录。</li>
 *   <li>{@link #markFailed} 落库前按列宽截断：{@code last_error} 是 {@code VARCHAR(500)}，严格的 MySQL
 *       会直接拒绝超长值——那会在最需要留下失败原因的时候把整行也弄丢。</li>
 *   <li>{@link #markSkipped} 把跳过原因写进 {@code last_error}。表里没有独立的跳过原因列，
 *       而"为什么不发"必须可查；{@code SKIPPED} 不算失败，{@link #failureCount} 又只数 {@code FAILED}，
 *       所以两者不会互相污染。</li>
 * </ul>
 */
@Repository
public class JdbcNotificationStore implements NotificationStore {

    private static final String COLUMNS = """
            id, event_type, system_id, ticket_id, grant_request_id, recipient_id, channel, status,
            title, body, attempts, last_error, created_at, sent_at
            """;

    private static final String INSERT = """
            insert into notification
                (event_type, system_id, ticket_id, grant_request_id, recipient_id, channel, status, title, body,
                 attempts)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """;

    private static final String MARK_SENT = """
            update notification
               set status = ?, attempts = ?, sent_at = ?, last_error = null
             where id = ?
            """;

    private static final String MARK_FAILED = """
            update notification
               set status = ?, attempts = ?, last_error = ?
             where id = ?
            """;

    private static final String MARK_SKIPPED = """
            update notification
               set status = ?, last_error = ?
             where id = ?
            """;

    private static final String FAILURE_COUNT =
            "select count(*) from notification where ticket_id = ? and status = ?";

    private static final String SELECT_FOR_TICKET =
            "select " + COLUMNS + " from notification where ticket_id = ? order by id";

    private static final RowMapper<NotificationRow> ROW_MAPPER = (rs, rowNum) -> new NotificationRow(
            rs.getLong("id"),
            NotificationEventType.valueOf(rs.getString("event_type")),
            nullableLong(rs, "system_id"),
            nullableLong(rs, "ticket_id"),
            nullableLong(rs, "grant_request_id"),
            rs.getLong("recipient_id"),
            NotificationChannel.valueOf(rs.getString("channel")),
            NotificationStatus.valueOf(rs.getString("status")),
            rs.getString("title"),
            rs.getString("body"),
            rs.getInt("attempts"),
            rs.getString("last_error"),
            instant(rs, "created_at"),
            instant(rs, "sent_at"));

    private final JdbcTemplate jdbc;

    public JdbcNotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(long recipientId, NotificationEventType type, Long systemId, Long ticketId,
            Long grantRequestId, NotificationChannel channel, String title, String body) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, type.name());
            setNullableLong(statement, 2, systemId);
            setNullableLong(statement, 3, ticketId);
            setNullableLong(statement, 4, grantRequestId);
            statement.setLong(5, recipientId);
            statement.setString(6, channel.name());
            statement.setString(7, NotificationStatus.PENDING.name());
            statement.setString(8, title);
            statement.setString(9, body);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入通知未返回自增主键");
        }
        return key.longValue();
    }

    @Override
    public void markSent(long id, int attempts, Instant sentAt) {
        jdbc.update(MARK_SENT, NotificationStatus.SENT.name(), attempts, timestamp(sentAt), id);
    }

    @Override
    public void markFailed(long id, int attempts, String error) {
        jdbc.update(MARK_FAILED, NotificationStatus.FAILED.name(), attempts,
                NotificationErrorText.truncate(error, NotificationErrorText.COLUMN_MAX_CHARS), id);
    }

    @Override
    public void markSkipped(long id, String reason) {
        jdbc.update(MARK_SKIPPED, NotificationStatus.SKIPPED.name(),
                NotificationErrorText.truncate(reason, NotificationErrorText.COLUMN_MAX_CHARS), id);
    }

    @Override
    public int failureCount(long ticketId) {
        Integer count = jdbc.queryForObject(FAILURE_COUNT, Integer.class, ticketId, NotificationStatus.FAILED.name());
        return count == null ? 0 : count;
    }

    @Override
    public List<NotificationRow> listForTicket(long ticketId) {
        return jdbc.query(SELECT_FOR_TICKET, ROW_MAPPER, ticketId);
    }

    /** "没有"写成 SQL NULL；写成 0 会指向不存在的业务对象。 */
    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
