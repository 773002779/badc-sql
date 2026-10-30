package com.badc.sql.control.ticket;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_statement} 的写入与读取。
 *
 * <p>{@code seq_no} 从 1 开始，就是执行顺序；与 {@code ticket_id} 组成唯一键，重复插入会被数据库拒绝
 * ——这比在应用层检查更早发现"同一张工单被写了两遍"。
 */
@Repository
public class TicketStatementMapper {

    private static final String INSERT = """
            insert into ticket_statement (ticket_id, seq_no, sql_text, statement_type, risk_level, status)
            values (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_SQL_TEXTS =
            "select sql_text from ticket_statement where ticket_id = ? order by seq_no";

    private final JdbcTemplate jdbc;

    public TicketStatementMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long ticketId, int seqNo, String sqlText, String statementType,
            String riskLevel, String status) {
        jdbc.update(INSERT, ticketId, seqNo, sqlText, statementType, riskLevel, status);
    }

    /** 按执行顺序取语句原文；任务 11 用它重算指纹以发现"审批后被改语句"。 */
    public List<String> sqlTexts(long ticketId) {
        return jdbc.queryForList(SELECT_SQL_TEXTS, String.class, ticketId);
    }
}
