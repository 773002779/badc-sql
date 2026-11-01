package com.badc.sql.control.ticket.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.badc.sql.control.support.TicketITBase;

/**
 * 过期清理与卡死恢复（任务 13）。
 *
 * <p>三类用例各自对应一个真实后果：
 * <ul>
 *   <li><b>审批通过的单永不失效</b>：一张 24 小时前批过、从没执行的单一直挂在"待执行"里，运维的工作台上
 *       全是僵尸单，"待执行"这个视图就没有意义了；</li>
 *   <li><b>执行中崩溃后永久停在 {@code EXECUTING}</b>：实例在执行中途重启，{@code finally} 不会执行，
 *       锁行留在表里 → 该数据源<b>永久不可写</b>；</li>
 *   <li><b>锁行只按时间清理</b>：一张仍在执行（进程活着）的单，锁被"接管"掉，第二个工单就能在同一条库上
 *       同时写——单数据源写并发 = 1 被打破。</li>
 * </ul>
 *
 * <p><b>时间一律靠回拨时间戳制造</b>：阈值判定读的是注入的 {@code Clock}（生产是系统 UTC 时钟），
 * 用例把 {@code approved_at}/{@code executed_at}/{@code acquired_at} 拨到过去，而不是 {@code Thread.sleep}
 * 等两小时。
 *
 * <p><b>恢复后绝不能自动续跑</b>（§11）：重启后结果未知，可能已经执行了一部分语句。自动重跑会把同一批
 * 更新做两遍（累计型变更直接算错）。所以 {@code executeCalls} 必须仍是 0。
 */
class TicketRecoveryJobIT extends TicketITBase {

    private static final String DML_SQL = "update t_order set a = 1 where id = 1";

    private static final String DML_ROLLBACK_SQL = "update t_order set a = 0 where id = 1";

    @Autowired
    private TicketRecoveryJob job;

    /**
     * 每个用例从空工单表开始：三支用例都要断言"处理了几行"，共享工单会让计数退化成弱断言。
     * 审计表也清掉，才能用"有没有 TICKET_EXPIRED/TICKET_RECOVERY"证明留痕。
     */
    @BeforeEach
    void clearTicketTables() {
        jdbc.update("delete from datasource_exec_lock");
        jdbc.update("delete from risk_acknowledgement");
        jdbc.update("delete from schema_snapshot");
        jdbc.update("delete from ticket_event");
        jdbc.update("delete from ticket_statement");
        jdbc.update("delete from ticket_review");
        jdbc.update("delete from ticket");
        jdbc.update("delete from audit_event");
    }

    // ------------------------------------------------------------------ 过期清理

    @Test
    void an_approved_ticket_older_than_twenty_four_hours_expires() {
        long id = approvedTicket();
        jdbc.update("update ticket set approved_at = ? where id = ?", utcMinusHours(25), id);

        int handled = job.expireApproved();

        assertThat(handled).as("返回被处理的行数").isEqualTo(1);
        assertThat(status(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select finished_at from ticket where id = ?", Timestamp.class, id))
                .as("进入终态必须写 finished_at")
                .isNotNull();
        assertThat(eventCount(id, "EXPIRED")).isEqualTo(1);

        Map<String, Object> audit = jdbc.queryForMap("""
                select operator_id, action, object_id, payload_json from audit_event
                 where event_type = 'TICKET_EXPIRED'
                """);
        assertThat(audit.get("operator_id")).as("过期是系统事件，没有操作人").isNull();
        assertThat(audit.get("action")).isEqualTo("APPROVAL_TIMEOUT");
        assertThat(audit.get("object_id")).isEqualTo(String.valueOf(id));
        assertThat((String) audit.get("payload_json")).contains("approvedAt");
    }

    @Test
    void a_ticket_approved_one_hour_ago_is_left_alone() {
        long id = approvedTicket();
        jdbc.update("update ticket set approved_at = ? where id = ?", utcMinusHours(1), id);

        assertThat(job.expireApproved()).isZero();

        assertThat(status(id)).as("还没到期，不能提前作废").isEqualTo("APPROVED");
        assertThat(eventCount(id, "EXPIRED")).isZero();
    }

    // ------------------------------------------------------------------ 卡死恢复

    /**
     * 实例在执行中途重启：不能永久停在 {@code EXECUTING}，也不能把该数据源永久锁死。
     */
    @Test
    void an_executing_ticket_older_than_the_hold_deadline_needs_manual_confirmation_and_releases_the_lock() {
        long id = approvedTicket();
        holdLock(id);
        jdbc.update("update ticket set status = 'EXECUTING', executed_at = ? where id = ?",
                utcMinusHours(3), id);

        assertThat(job.recoverStuckExecuting()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("NEEDS_MANUAL_CONFIRM");
        assertThat(lockCount())
                .as("恢复后必须释放锁，否则该库永久不可写")
                .isZero();
        assertThat(eventCount(id, "RECOVERY")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select detail_json from ticket_event where ticket_id = ? and event_type = 'RECOVERY'",
                String.class, id))
                .as("事件里必须写清「未自动续跑」")
                .contains("未自动续跑");

        Map<String, Object> audit = jdbc.queryForMap("""
                select operator_id, action, object_id, payload_json from audit_event
                 where event_type = 'TICKET_RECOVERY'
                """);
        assertThat(audit.get("operator_id")).isNull();
        assertThat(audit.get("action")).isEqualTo("STUCK_EXECUTING");
        assertThat(audit.get("object_id")).isEqualTo(String.valueOf(id));
    }

    /** §11：重启后需人工确认，绝不能让清理任务顺手把执行续上。 */
    @Test
    void recovery_does_not_resume_execution_automatically() {
        long id = approvedTicket();
        jdbc.update("update ticket set status = 'EXECUTING', executed_at = ? where id = ?",
                utcMinusHours(3), id);

        job.recoverStuckExecuting();

        assertThat(stub.executeCalls).as("恢复只改状态，不重新下发任何语句").isZero();
        assertThat(stub.executedSql).isEmpty();
    }

    /** 锁还在宽限期内：即使工单已经不在执行，也不该被清理（那可能是一次正常的执行）。 */
    @Test
    void a_lock_still_within_the_hold_deadline_is_kept() {
        long id = approvedTicket();
        jdbc.update("insert into datasource_exec_lock (datasource_id, ticket_id, acquired_at) values (?, ?, ?)",
                ORDER_DATASOURCE_ID, id, Timestamp.from(Instant.now()));

        assertThat(job.takeOverStaleLocks()).isZero();
        assertThat(lockCount()).isOne();
    }

    // ------------------------------------------------------------------ 清理超时锁

    @Test
    void a_stale_lock_whose_ticket_is_no_longer_executing_is_taken_over() {
        long id = approvedTicket();
        holdStaleLock(id);

        assertThat(job.takeOverStaleLocks()).isEqualTo(1);

        assertThat(lockCount()).isZero();
        assertThat(status(id)).as("清锁不改变工单状态").isEqualTo("APPROVED");
    }

    /**
     * 工单仍 {@code EXECUTING} 时，锁<b>不能</b>在这里被删：进程可能还活着、语句还在跑。
     * 这种锁留给 {@link TicketRecoveryJob#recoverStuckExecuting()}，由它把工单置为需人工确认后再释放。
     */
    @Test
    void a_stale_lock_on_a_ticket_that_is_still_executing_is_left_for_recovery() {
        long id = approvedTicket();
        jdbc.update("update ticket set status = 'EXECUTING', executed_at = ? where id = ?",
                Timestamp.from(Instant.now()), id);
        holdStaleLock(id);

        assertThat(job.takeOverStaleLocks())
                .as("仍在执行的工单，其锁不能按时间被抢走")
                .isZero();
        assertThat(lockCount()).isOne();

        // 超过执行宽限期之后，由恢复任务接手：先置状态，再释放锁。
        jdbc.update("update ticket set executed_at = ? where id = ?", utcMinusHours(3), id);
        job.recoverStuckExecuting();
        assertThat(status(id)).isEqualTo("NEEDS_MANUAL_CONFIRM");
        assertThat(lockCount()).isZero();
    }

    // ------------------------------------------------------------------ 终态不动

    @Test
    void a_finished_ticket_is_never_touched() {
        long id = approvedTicket();
        jdbc.update("""
                update ticket set status = 'SUCCEEDED', approved_at = ?, executed_at = ?, finished_at = ?
                 where id = ?
                """, utcMinusHours(48), utcMinusHours(48), utcMinusHours(48), id);

        assertThat(job.expireApproved()).isZero();
        assertThat(job.recoverStuckExecuting()).isZero();
        assertThat(job.takeOverStaleLocks()).isZero();

        assertThat(status(id)).isEqualTo("SUCCEEDED");
        assertThat(eventCount(id, "EXPIRED")).isZero();
        assertThat(eventCount(id, "RECOVERY")).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event
                 where event_type in ('TICKET_EXPIRED', 'TICKET_RECOVERY')
                """, Integer.class))
                .as("终态工单不该产生任何清理审计")
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 真实提交 + 审批，返回停在 {@code APPROVED} 的工单 ID。
     *
     * <p>走真实接口而不是手写一行 {@code insert into ticket}：{@code approved_at} 由审批路径写入，
     * 本类才好把它回拨来制造"过期"；手插的行证明不了审批路径真的写了这个时间。
     */
    private long approvedTicket() {
        ResponseEntity<String> created = postTicket(DEV_TOKEN, body(DML_SQL, DML_ROLLBACK_SQL));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = idOf(created);

        ResponseEntity<String> approved = postJson(OPS02_TOKEN,
                "/api/v1/tickets/" + id + "/approve", "{\"comment\":\"通过\"}");
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).as("夹具必须停在 APPROVED").isEqualTo("APPROVED");
        return id;
    }

    /** 该工单持有数据源 101 的执行锁（时间取真实现在）。 */
    private void holdLock(long ticketId) {
        jdbc.update("insert into datasource_exec_lock (datasource_id, ticket_id, acquired_at) values (?, ?, ?)",
                ORDER_DATASOURCE_ID, ticketId, Timestamp.from(Instant.now()));
    }

    /** 一把 3 小时前取得、已经超过持锁上限的锁。 */
    private void holdStaleLock(long ticketId) {
        jdbc.update("insert into datasource_exec_lock (datasource_id, ticket_id, acquired_at) values (?, ?, ?)",
                ORDER_DATASOURCE_ID, ticketId, utcMinusHours(3));
    }

    private int lockCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from datasource_exec_lock where datasource_id = ?",
                Integer.class, ORDER_DATASOURCE_ID);
        return count == null ? 0 : count;
    }

    private int eventCount(long ticketId, String eventType) {
        Integer count = jdbc.queryForObject(
                "select count(*) from ticket_event where ticket_id = ? and event_type = ?",
                Integer.class, ticketId, eventType);
        return count == null ? 0 : count;
    }

    private static Timestamp utcMinusHours(long hours) {
        return Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS));
    }

    private ResponseEntity<String> postJson(String token, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
