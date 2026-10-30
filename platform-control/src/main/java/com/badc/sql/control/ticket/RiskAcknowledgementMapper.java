package com.badc.sql.control.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code risk_acknowledgement} 的追加写入。
 *
 * <p>提交阶段写 {@code stage = 'SUBMIT'}，任务 9 会补 {@code REVIEW / L3_REVIEW / EXECUTE}。每次确认都留一行、
 * 不做幂等：重复确认本身就是证据，合并成一行等于丢掉"他又确认了一次"。
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

    private final JdbcTemplate jdbc;

    public RiskAcknowledgementMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long ticketId, String stage, long operatorId, String riskSnapshotHash,
            String ip, String userAgent) {
        jdbc.update(INSERT, ticketId, stage, operatorId, riskSnapshotHash,
                truncate(ip, IP_MAX_LENGTH), truncate(userAgent, USER_AGENT_MAX_LENGTH));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
