package com.badc.sql.control.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code ticket_event} 的追加写入。
 *
 * <p>事件流只追加、不改不删：工单详情页按时间顺序展示"谁在什么时候做了什么"，与审计链是两套记录——
 * 审计链防篡改，事件流给业务看。
 */
@Repository
public class TicketEventMapper {

    private static final String INSERT = """
            insert into ticket_event (ticket_id, event_type, actor_id, detail_json)
            values (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public TicketEventMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条事件。
     *
     * @param actorId 操作人；系统事件传 {@code null}
     * @param detailJson 事件明细 JSON 原文；没有明细传 {@code null}
     */
    public void append(long ticketId, String eventType, Long actorId, String detailJson) {
        jdbc.update(INSERT, ticketId, eventType, actorId, detailJson);
    }
}
