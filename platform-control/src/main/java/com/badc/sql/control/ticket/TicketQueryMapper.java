package com.badc.sql.control.ticket;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 工单读路径的 SQL。
 *
 * <p>为什么单独一个 Mapper：写路径的 Mapper 各自只做一张表的插入，读路径却要一次取主表 + 四张子表；
 * 把它们塞进任一个写 Mapper 都会让那个类承担两种职责。这里只放查询，不写任何一行。
 *
 * <p><b>可见性过滤必须留在 SQL 里</b>：如果把全表查出来再在内存里筛，{@code total} 与分页就会与
 * {@code items} 不一致（"第 3 页共 1 页"）。{@link Scope} 由服务层按角色判定，本类只把它翻译成
 * {@code where} 谓词，计数与分页共用同一个谓词——两处各写一份迟早分叉。
 */
@Repository
public class TicketQueryMapper {

    /**
     * 读路径的可见性范围。
     *
     * <p>{@link #SELF}：只看自己提交的（研发）；{@link #OPERATED_SYSTEMS}：只看自己运维的系统
     * （主运维/备运维/组长）；{@link #ALL}：全部（管理员）；{@link #NONE}：什么都看不到（其它角色、
     * 用户不存在）。
     */
    public enum Scope {
        SELF, OPERATED_SYSTEMS, ALL, NONE
    }

    private static final String COUNT = "select count(*) from ticket t ";

    private static final String SUMMARY_SELECT = """
            select t.id, t.status, t.risk_level, t.system_id, t.module_id, t.datasource_id,
                   t.title, t.submitter_id, t.ticket_type, t.created_at, t.expected_execute_at
              from ticket t
            """;

    private static final String ORDER_AND_PAGE = " order by t.created_at desc, t.id desc limit ? offset ?";

    private static final String STATEMENTS = """
            select seq_no, statement_type, risk_level, status, affected_rows, error_message,
                   elapsed_ms, executed_at, sql_text
              from ticket_statement
             where ticket_id = ?
             order by seq_no
            """;

    private static final String REVIEWS = """
            select review_level, reviewer_id, decision, comment, created_at
              from ticket_review
             where ticket_id = ?
             order by review_level
            """;

    private static final String SNAPSHOTS = """
            select change_seq, snapshot_ref, table_fingerprints_json, captured_at
              from schema_snapshot
             where ticket_id = ?
             order by change_seq
            """;

    private static final String EVENTS = """
            select event_type, actor_id, detail_json, created_at
              from ticket_event
             where ticket_id = ?
             order by created_at, id
            """;

    private static final RowMapper<SummaryRow> SUMMARY_MAPPER = (rs, rowNum) -> new SummaryRow(
            rs.getLong("id"),
            rs.getString("status"),
            rs.getString("risk_level"),
            rs.getLong("system_id"),
            rs.getLong("module_id"),
            rs.getLong("datasource_id"),
            rs.getString("title"),
            rs.getLong("submitter_id"),
            rs.getString("ticket_type"),
            instant(rs, "created_at"),
            instant(rs, "expected_execute_at"));

    private final JdbcTemplate jdbc;

    public TicketQueryMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 可见范围内的工单总数；与 {@link #page} 用同一个过滤谓词。 */
    public long count(long userId, Scope scope) {
        Long count = jdbc.queryForObject(COUNT + filter(scope), Long.class, filterArgs(scope, userId));
        return count == null ? 0 : count;
    }

    /**
     * 可见范围内的工单摘要，按创建时间倒序。
     *
     * @param size   页大小
     * @param offset 偏移量（已由服务层按 {@code (page-1)*size} 算好）
     */
    public List<SummaryRow> page(long userId, Scope scope, int size, long offset) {
        Object[] filterArgs = filterArgs(scope, userId);
        Object[] args = Arrays.copyOf(filterArgs, filterArgs.length + 2);
        args[filterArgs.length] = size;
        args[filterArgs.length + 1] = offset;
        return jdbc.query(SUMMARY_SELECT + filter(scope) + ORDER_AND_PAGE, SUMMARY_MAPPER, args);
    }

    /**
     * 该工单是否落在用户的可见范围内。
     *
     * <p>详情不能只靠列表过滤：知道 id 就能绕过列表，所以详情必须按 id 再做一次同样的判定。
     */
    public boolean isVisible(long ticketId, long userId, Scope scope) {
        Object[] filterArgs = filterArgs(scope, userId);
        Object[] args = Arrays.copyOf(filterArgs, filterArgs.length + 1);
        args[filterArgs.length] = ticketId;
        Integer count = jdbc.queryForObject(
                COUNT + filter(scope) + idPredicate(scope), Integer.class, args);
        return count != null && count > 0;
    }

    /** 工单语句，按 {@code seq_no} 升序（即执行顺序）。 */
    public List<StatementRow> statements(long ticketId) {
        return jdbc.query(STATEMENTS, (rs, rowNum) -> new StatementRow(
                rs.getInt("seq_no"),
                rs.getString("statement_type"),
                rs.getString("risk_level"),
                rs.getString("status"),
                nullableLong(rs, "affected_rows"),
                rs.getString("error_message"),
                nullableLong(rs, "elapsed_ms"),
                instant(rs, "executed_at"),
                rs.getString("sql_text")), ticketId);
    }

    /** 审核记录，按审核级别升序（一级在前）。 */
    public List<ReviewRow> reviews(long ticketId) {
        return jdbc.query(REVIEWS, (rs, rowNum) -> new ReviewRow(
                rs.getInt("review_level"),
                rs.getLong("reviewer_id"),
                rs.getString("decision"),
                rs.getString("comment"),
                instant(rs, "created_at")), ticketId);
    }

    /** 结构快照，按变更序号升序（0=提交时，1=执行前）。 */
    public List<SnapshotRow> snapshots(long ticketId) {
        return jdbc.query(SNAPSHOTS, (rs, rowNum) -> new SnapshotRow(
                rs.getInt("change_seq"),
                rs.getString("snapshot_ref"),
                rs.getString("table_fingerprints_json"),
                instant(rs, "captured_at")), ticketId);
    }

    /** 事件流水，按时间升序（同一毫秒内用自增 id 稳定次序）。 */
    public List<EventRow> events(long ticketId) {
        return jdbc.query(EVENTS, (rs, rowNum) -> new EventRow(
                rs.getString("event_type"),
                nullableLong(rs, "actor_id"),
                rs.getString("detail_json"),
                instant(rs, "created_at")), ticketId);
    }

    private static String filter(Scope scope) {
        return switch (scope) {
            case SELF -> " where t.submitter_id = ? ";
            case OPERATED_SYSTEMS -> """
                     where exists (
                         select 1 from sys_system_operator o
                          where o.user_id = ? and o.system_id = t.system_id)
                    """;
            case ALL -> "";
            case NONE -> " where 1 = 0 ";
        };
    }

    /** 只有 SELF / OPERATED_SYSTEMS 的谓词需要 userId。 */
    private static Object[] filterArgs(Scope scope, long userId) {
        return switch (scope) {
            case SELF, OPERATED_SYSTEMS -> new Object[] {userId};
            case ALL, NONE -> new Object[0];
        };
    }

    /** 在过滤谓词之后追加主键条件；ALL 还没有 where，需要自己起一个。 */
    private static String idPredicate(Scope scope) {
        return scope == Scope.ALL ? " where t.id = ?" : " and t.id = ?";
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * 列表行。
     *
     * <p>字段名与响应一致，服务层原样拷进 {@code Summary}；{@code created_at} 必须参与排序，所以它也在这里。
     */
    public record SummaryRow(
            long id,
            String status,
            String riskLevel,
            long systemId,
            long moduleId,
            long datasourceId,
            String title,
            long submitterId,
            String ticketType,
            Instant createdAt,
            Instant expectedExecuteAt) {
    }

    /** 详情里的一条语句（JSON 字段名与响应一致）。 */
    public record StatementRow(
            int seq,
            String statementType,
            String riskLevel,
            String status,
            Long affectedRows,
            String errorMessage,
            Long elapsedMs,
            Instant executedAt,
            String sql) {
    }

    /** 详情里的一条审核记录。 */
    public record ReviewRow(
            int reviewLevel,
            long reviewerId,
            String decision,
            String comment,
            Instant createdAt) {
    }

    /** 详情里的一条结构快照；{@code tableFingerprintsJson} 由服务层解析成对象。 */
    public record SnapshotRow(
            int changeSeq,
            String snapshotRef,
            String tableFingerprintsJson,
            Instant capturedAt) {
    }

    /** 详情里的一条事件；{@code detailJson} 原样返回，不参与可见性判定。 */
    public record EventRow(
            String eventType,
            Long actorId,
            String detailJson,
            Instant createdAt) {
    }
}
