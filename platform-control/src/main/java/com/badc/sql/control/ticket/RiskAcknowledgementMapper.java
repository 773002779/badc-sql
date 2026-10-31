package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code risk_acknowledgement} 的追加写入与按阶段读取。
 *
 * <p>提交阶段写 {@code stage = 'SUBMIT'}，审批阶段写 {@code REVIEW / L3_REVIEW}，执行前由
 * {@link RiskAckService} 写 {@code EXECUTE}。每次确认都留一行、不做幂等：重复确认本身就是证据，合并成一行
 * 等于丢掉"他又确认了一次"。
 *
 * <p><b>最新一行按 {@code id desc} 取，不按 {@code created_at}</b>：{@code datetime(3)} 只到毫秒，两次确认落在
 * 同一毫秒完全可能（测试里连续两次提交更是常态），按时间排序会得到不确定的顺序。自增 id 才是写入顺序。
 */
@Repository
public class RiskAcknowledgementMapper {

    private static final String INSERT = """
            insert into risk_acknowledgement
                (ticket_id, stage, operator_id, risk_snapshot_hash, ip, user_agent)
            values (?, ?, ?, ?, ?, ?)
            """;

    /** 与 V3 的列宽一致；Controller/Service 已截断，这里再兜一层，防止将来新调用点漏截断。 */
    private static final int IP_MAX_LENGTH = 64;
    private static final int USER_AGENT_MAX_LENGTH = 512;

    /**
     * 某工单某阶段的最新一条确认。
     *
     * <p>{@code order by id desc} 而不是 {@code created_at desc}：同一毫秒内的两次确认按时间排不出先后，
     * 自增 id 才是真正的写入顺序（见类注释）。
     */
    private static final String SELECT_LATEST = """
            select id, ticket_id, stage, operator_id, risk_snapshot_hash, ip, user_agent, created_at
              from risk_acknowledgement
             where ticket_id = ? and stage = ?
             order by id desc
             limit 1
            """;

    private static final RowMapper<AckRow> ROW_MAPPER = (rs, rowNum) -> new AckRow(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getString("stage"),
            rs.getLong("operator_id"),
            rs.getString("risk_snapshot_hash"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public RiskAcknowledgementMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long ticketId, String stage, long operatorId, String riskSnapshotHash,
            String ip, String userAgent) {
        jdbc.update(INSERT, ticketId, stage, operatorId, riskSnapshotHash,
                truncate(ip, IP_MAX_LENGTH), truncate(userAgent, USER_AGENT_MAX_LENGTH));
    }

    /** 某工单某阶段的最新一条确认；从未确认过时为空。 */
    public Optional<AckRow> findLatest(long ticketId, String stage) {
        List<AckRow> rows = jdbc.query(SELECT_LATEST, ROW_MAPPER, ticketId, stage);
        return rows.stream().findFirst();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 一条确认记录。
     *
     * @param id               自增主键（写入顺序）
     * @param ticketId         工单 ID
     * @param stage            阶段名（{@link AckStage} 的枚举名）
     * @param operatorId       确认人
     * @param riskSnapshotHash 确认那一刻工单的整单风险快照哈希
     * @param ip               来源 IP
     * @param userAgent        User-Agent 原文
     * @param createdAt        确认时间（UTC）
     */
    public record AckRow(
            long id,
            long ticketId,
            String stage,
            long operatorId,
            String riskSnapshotHash,
            String ip,
            String userAgent,
            Instant createdAt) {
    }
}
