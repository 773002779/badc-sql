package com.badc.sql.control.grant;

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
 * {@code grant_request} 的读写（列定义见 {@code V5__grant_request.sql}）。
 *
 * <p><b>乐观并发</b>：{@link #moveStatus} 带 {@code where status = ?}，返回影响行数。0 表示有人抢先处理过
 * （两个运维同时点通过），调用方必须报 409 并整体回滚——不要读回来发现状态变了再抛，
 * 那样"写了授权却没迁移状态"的窗口会留在事务里。
 *
 * <p><b>为什么没有唯一键约束"待审批只有一条"</b>：MySQL 无法表达"仅对 PENDING 去重"的条件索引。若对
 * {@code (user_id, module_id)} 建唯一键，历史已处理的记录会让下一次申请直接撞键——研发被驳回过一次就再也
 * 申请不了。所以由服务层在事务内先查后插；并发下最坏结果是两条 PENDING，审批第一条时会因为
 * {@link #findPending} 的语义（取最早一条）而写一次授权，第二条仍可被审批，但授权 upsert 是幂等的，
 * 不会产生重复授权行。这个取舍写在 V5 的注释里。
 *
 * <p><b>自增主键</b>用 {@code RETURN_GENERATED_KEYS} 取回，不用 {@code last_insert_id()}
 * （连接池下可能落到另一条连接上，与 {@code TicketMapper} / {@code JdbcNotificationStore} 同口径）。
 */
@Repository
public class GrantRequestStore {

    private static final String COLUMNS = """
            id, user_id, module_id, can_export, reason, status, reviewer_id, review_comment,
            created_at, reviewed_at
            """;

    private static final String INSERT = """
            insert into grant_request (user_id, module_id, can_export, reason, status)
            values (?, ?, ?, ?, 'PENDING')
            """;

    private static final String SELECT_BY_ID =
            "select " + COLUMNS + " from grant_request where id = ?";

    /** 该人该模块最早的待审批申请；去重判定与"撤回哪一条"都用它。 */
    private static final String SELECT_PENDING = """
            select %s
              from grant_request
             where user_id = ? and module_id = ? and status = 'PENDING'
             order by created_at, id
             limit 1
            """.formatted(COLUMNS);

    private static final String MOVE_STATUS = """
            update grant_request
               set status = ?, reviewer_id = ?, review_comment = ?, reviewed_at = ?
             where id = ? and status = ?
            """;

    private static final String PAGE_FOR_USER = """
            select %s
              from grant_request
             where user_id = ?
             order by created_at desc, id desc
             limit ? offset ?
            """.formatted(COLUMNS);

    private static final String COUNT_FOR_USER =
            "select count(*) from grant_request where user_id = ?";

    /**
     * 待我审批：模块所属系统在本人的 {@code sys_system_operator} 归属内。
     *
     * <p>用 {@code exists} 而不是 {@code join sys_system_operator}：同一个人可能同时是某系统的 PRIMARY 与
     * LEAD（两行），{@code join} 会让同一张申请重复出现，分页会因此漏行/串页。
     */
    private static final String PAGE_PENDING_FOR_REVIEWER = """
            select %s
              from grant_request gr
              join sys_module m on m.id = gr.module_id
             where gr.status = 'PENDING'
               and exists (select 1 from sys_system_operator o
                            where o.system_id = m.system_id and o.user_id = ?)
             order by gr.created_at desc, gr.id desc
             limit ? offset ?
            """.formatted(prefixColumns("gr."));

    private static final String COUNT_PENDING_FOR_REVIEWER = """
            select count(*)
              from grant_request gr
              join sys_module m on m.id = gr.module_id
             where gr.status = 'PENDING'
               and exists (select 1 from sys_system_operator o
                            where o.system_id = m.system_id and o.user_id = ?)
            """;

    /** 该系统是否至少有一个运维归属（主/备/组长都算）。提交申请前用它回答"申请会不会无人可批"。 */
    private static final String HAS_OPERATOR =
            "select count(*) from sys_system_operator where system_id = ?";

    private static final RowMapper<GrantRequestRow> ROW_MAPPER = (rs, rowNum) -> new GrantRequestRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("module_id"),
            rs.getBoolean("can_export"),
            rs.getString("reason"),
            rs.getString("status"),
            nullableLong(rs, "reviewer_id"),
            rs.getString("review_comment"),
            instant(rs, "created_at"),
            instant(rs, "reviewed_at"));

    private final JdbcTemplate jdbc;

    public GrantRequestStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条 {@code PENDING} 申请。
     *
     * @return 自增主键
     */
    public long insert(long userId, long moduleId, boolean canExport, String reason) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, moduleId);
            statement.setBoolean(3, canExport);
            statement.setString(4, reason);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入授权申请未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<GrantRequestRow> findById(long id) {
        return jdbc.query(SELECT_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    /** 该人该模块是否已有待审批申请（取最早一条）。 */
    public Optional<GrantRequestRow> findPending(long userId, long moduleId) {
        return jdbc.query(SELECT_PENDING, ROW_MAPPER, userId, moduleId).stream().findFirst();
    }

    /**
     * 迁移状态。
     *
     * @return 影响行数；0 表示当前状态已不是 {@code from}（被并发处理过），调用方必须报 409
     */
    public int moveStatus(long id, GrantRequestStatus from, GrantRequestStatus to, Long reviewerId,
            String comment, Instant reviewedAt) {
        return jdbc.update(MOVE_STATUS, to.name(), reviewerId, comment, timestamp(reviewedAt),
                id, from.name());
    }

    /** 我的申请，倒序分页。 */
    public List<GrantRequestRow> pageForUser(long userId, int limit, int offset) {
        return jdbc.query(PAGE_FOR_USER, ROW_MAPPER, userId, limit, offset);
    }

    public long countForUser(long userId) {
        Long count = jdbc.queryForObject(COUNT_FOR_USER, Long.class, userId);
        return count == null ? 0L : count;
    }

    /** 待我审批（模块所属系统在我的归属内），倒序分页。 */
    public List<GrantRequestRow> pagePendingForReviewer(long reviewerId, int limit, int offset) {
        return jdbc.query(PAGE_PENDING_FOR_REVIEWER, ROW_MAPPER, reviewerId, limit, offset);
    }

    public long countPendingForReviewer(long reviewerId) {
        Long count = jdbc.queryForObject(COUNT_PENDING_FOR_REVIEWER, Long.class, reviewerId);
        return count == null ? 0L : count;
    }

    /** 该系统是否至少有一个运维归属。 */
    public boolean hasOperator(long systemId) {
        Integer count = jdbc.queryForObject(HAS_OPERATOR, Integer.class, systemId);
        return count != null && count > 0;
    }

    /** 给列名加表别名前缀：两条分页 SQL 都 join 了 {@code sys_module}，未限定的列名会歧义。 */
    private static String prefixColumns(String prefix) {
        StringBuilder prefixed = new StringBuilder();
        for (String column : COLUMNS.split(",")) {
            if (!prefixed.isEmpty()) {
                prefixed.append(", ");
            }
            prefixed.append(prefix).append(column.strip());
        }
        return prefixed.toString();
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
