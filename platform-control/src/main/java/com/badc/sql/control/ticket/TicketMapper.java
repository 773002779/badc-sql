package com.badc.sql.control.ticket;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket} 的读写。
 *
 * <p>自增主键用 {@link Statement#RETURN_GENERATED_KEYS} 取回（不要 {@code last_insert_id()}：连接池下可能
 * 落到另一条连接上）——{@code schema_snapshot.snapshot_ref} 必须在拿到 ticketId 之后才能拼。
 */
@Repository
public class TicketMapper {

    private static final String COLUMNS = """
            id, system_id, module_id, datasource_id, ticket_type, status, risk_level,
            risk_snapshot_json, risk_snapshot_hash, title, change_reason, rollback_plan, rollback_sql,
            statement_fingerprint, snapshot_enabled, expected_execute_at, submitter_id, origin_ticket_id,
            rollback_reason, created_at, updated_at, approved_at, executed_at, finished_at
            """;

    private static final String INSERT = """
            insert into ticket
                (system_id, module_id, datasource_id, ticket_type, status, risk_level,
                 risk_snapshot_json, risk_snapshot_hash, title, change_reason, rollback_plan, rollback_sql,
                 statement_fingerprint, snapshot_enabled, expected_execute_at, submitter_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID = "select " + COLUMNS + " from ticket where id = ?";

    /** 终审可用性（§4.7-6）：L3 工单至少要有一个人能终审。 */
    private static final String COUNT_LEADS =
            "select count(*) from sys_system_operator where system_id = ? and operator_type = 'LEAD'";

    private static final RowMapper<TicketEntity> ROW_MAPPER = (rs, rowNum) -> new TicketEntity(
            rs.getLong("id"),
            rs.getLong("system_id"),
            rs.getLong("module_id"),
            rs.getLong("datasource_id"),
            rs.getString("ticket_type"),
            rs.getString("status"),
            rs.getString("risk_level"),
            rs.getString("risk_snapshot_json"),
            rs.getString("risk_snapshot_hash"),
            rs.getString("title"),
            rs.getString("change_reason"),
            rs.getString("rollback_plan"),
            rs.getString("rollback_sql"),
            rs.getString("statement_fingerprint"),
            rs.getBoolean("snapshot_enabled"),
            instant(rs, "expected_execute_at"),
            rs.getLong("submitter_id"),
            nullableLong(rs, "origin_ticket_id"),
            rs.getString("rollback_reason"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "approved_at"),
            instant(rs, "executed_at"),
            instant(rs, "finished_at"));

    private final JdbcTemplate jdbc;

    public TicketMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一张新工单，返回自增 ID。 */
    public long insert(TicketEntity ticket) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            Object[] args = {
                    ticket.systemId(),
                    ticket.moduleId(),
                    ticket.datasourceId(),
                    ticket.ticketType(),
                    ticket.status(),
                    ticket.riskLevel(),
                    ticket.riskSnapshotJson(),
                    ticket.riskSnapshotHash(),
                    ticket.title(),
                    ticket.changeReason(),
                    ticket.rollbackPlan(),
                    ticket.rollbackSql(),
                    ticket.statementFingerprint(),
                    ticket.snapshotEnabled() ? 1 : 0,
                    timestamp(ticket.expectedExecuteAt()),
                    ticket.submitterId()};
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入工单未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<TicketEntity> findById(long id) {
        List<TicketEntity> rows = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 该系统配置的运维组长人数；0 表示 L3 工单无人终审。 */
    public int countLeads(long systemId) {
        Integer count = jdbc.queryForObject(COUNT_LEADS, Integer.class, systemId);
        return count == null ? 0 : count;
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
