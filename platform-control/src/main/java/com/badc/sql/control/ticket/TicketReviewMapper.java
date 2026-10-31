package com.badc.sql.control.ticket;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 审批链的读写：{@code ticket_review} 行、某级审批人、工单的全部审核记录，以及审批归属与状态迁移。
 *
 * <p>为什么状态迁移也在这里：{@code TicketMapper} 是提交路径的写入器，本任务不该往里加方法；审批的状态更新
 * 必须与审核行、事件、审计落在<b>同一个事务</b>里，放在同一个 mapper 上最容易看清"一次审批到底写了什么"。
 *
 * <p><b>乐观并发</b>：{@link #moveStatus} 带着 {@code where status = ?}。影响行数为 0 表示有人抢先改了
 * 状态（例如两个组长同时终审），调用方必须把它当成 409——"看起来成功了"比失败更危险。
 *
 * <p><b>唯一键</b>：{@code uk_ticket_review(ticket_id, review_level)} 保证每级至多一条审核记录。重复插入会
 * 抛 {@link org.springframework.dao.DuplicateKeyException}，由服务层翻译成 409。
 */
@Repository
public class TicketReviewMapper {

    /** 审核结论：通过。与 V3 的 {@code ck_ticket_review_decision} 一致。 */
    public static final String DECISION_APPROVED = "APPROVED";

    /** 审核结论：驳回。 */
    public static final String DECISION_REJECTED = "REJECTED";

    private static final String INSERT = """
            insert into ticket_review (ticket_id, review_level, reviewer_id, decision, comment)
            values (?, ?, ?, ?, ?)
            """;

    private static final String REVIEWER_AT_LEVEL = """
            select reviewer_id from ticket_review where ticket_id = ? and review_level = ?
            """;

    private static final String ALL = """
            select review_level, reviewer_id, decision, comment, created_at
              from ticket_review
             where ticket_id = ?
             order by review_level
            """;

    /** 审批归属：只有 {@code sys_system_operator} 里的人能审该系统。 */
    private static final String IS_OPERATOR = """
            select count(*) from sys_system_operator where system_id = ? and user_id = ?
            """;

    /** 乐观并发：只有状态仍等于读到的值时迁移才生效。 */
    private static final String MOVE_STATUS = """
            update ticket set status = ?, approved_at = ? where id = ? and status = ?
            """;

    private final JdbcTemplate jdbc;

    public TicketReviewMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写一条审核记录。同一级重复写入会抛 {@link org.springframework.dao.DuplicateKeyException}。 */
    public void insert(long ticketId, int reviewLevel, long reviewerId, String decision, String comment) {
        jdbc.update(INSERT, ticketId, reviewLevel, reviewerId, decision, comment);
    }

    /** 某级的审批人；还没有该级审核时为空。二级用它做"两级不可同一人"的纵深防御。 */
    public Optional<Long> reviewerIdAtLevel(long ticketId, int reviewLevel) {
        List<Long> ids = jdbc.queryForList(REVIEWER_AT_LEVEL, Long.class, ticketId, reviewLevel);
        return ids.stream().findFirst();
    }

    /** 某工单的全部审核记录，按级别升序（一级在前）。 */
    public List<ReviewRecord> findAll(long ticketId) {
        return jdbc.query(ALL, (rs, rowNum) -> new ReviewRecord(
                rs.getInt("review_level"),
                rs.getLong("reviewer_id"),
                rs.getString("decision"),
                rs.getString("comment"),
                instant(rs, "created_at")), ticketId);
    }

    /** 审批人是否是 {@code systemId} 的运维（主/备/组长都算）。 */
    public boolean isOperatorOf(long systemId, long userId) {
        Integer count = jdbc.queryForObject(IS_OPERATOR, Integer.class, systemId, userId);
        return count != null && count > 0;
    }

    /**
     * 把工单从 {@code from} 迁到 {@code to}，并写入/清空 {@code approved_at}。
     *
     * @return 影响行数；0 表示状态已被别人改过，调用方必须报 409
     */
    public int moveStatus(long ticketId, TicketStatus from, TicketStatus to, Instant approvedAt) {
        return jdbc.update(MOVE_STATUS, to.name(), timestamp(approvedAt), ticketId, from.name());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 一条审核记录。 */
    public record ReviewRecord(
            int reviewLevel,
            long reviewerId,
            String decision,
            String comment,
            Instant createdAt) {
    }
}
