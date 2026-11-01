package com.badc.sql.control.ticket.exec;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 数据源执行互斥锁（{@code datasource_exec_lock}）：单数据源写并发 = 1。
 *
 * <h2>为什么靠主键冲突而不是"先查再插"</h2>
 * <p>{@link #acquire} 直接 {@code insert}，把"这个库有没有人在执行"交给数据库主键裁决。
 * 先 {@code select} 再 {@code insert} 的写法下，两个同时到达的请求会<b>都</b>读到"没有锁"，然后都去执行——
 * 这正是锁要防的事故，却恰好被这把锁自己放过。主键冲突是唯一原子的判定点。
 *
 * <h2>为什么 release 必须带 ticket_id</h2>
 * <p>只按 {@code datasource_id} 删，一个持有者超时退出、另一个工单接手之后，先退出者的 {@code finally}
 * 会把<b>别人</b>刚拿到的锁删掉——两个工单同时在这一台库上执行。{@code where datasource_id = ? and
 * ticket_id = ?} 让"解锁"只在锁还属于自己时生效。
 *
 * <h2>为什么需要 takeOverStale</h2>
 * <p>进程崩溃时 {@code finally} 不会执行，锁行会永远留在表里，该数据源从此<b>永久不可写</b>。
 * 超过最大持锁时长的行必须能被清理；清理是"接管"而不是"续期"：删掉旧行，让新工单重新 {@code insert}。
 *
 * <p>本类是普通类（构造器吃 {@link JdbcTemplate}）：集成测试直接 {@code new DatasourceExecLock(jdbc)}，
 * 不必为三个 SQL 起一个 Spring 上下文。
 */
@Repository
public class DatasourceExecLock {

    /** 取锁：直接插入，由主键（{@code datasource_id}）裁决唯一性。 */
    private static final String ACQUIRE = """
            insert into datasource_exec_lock (datasource_id, ticket_id, acquired_at)
            values (?, ?, ?)
            """;

    /** 解锁：<b>条件里必须带 ticket_id</b>，否则会替别人解锁。 */
    private static final String RELEASE =
            "delete from datasource_exec_lock where datasource_id = ? and ticket_id = ?";

    /** 清理超时锁：只按获取时间判，不关心持有者是谁（持有者可能已经不存在了）。 */
    private static final String DELETE_STALE =
            "delete from datasource_exec_lock where acquired_at < ?";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 集成测试用：{@code new DatasourceExecLock(jdbc)}，时间取系统 UTC 时钟。 */
    public DatasourceExecLock(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    /** 容器注入用：与其他写路径共用同一个 {@link Clock}，测试可替换以控制时间。 */
    @Autowired
    public DatasourceExecLock(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 尝试取得某数据源的执行锁。
     *
     * @return {@code true} 表示取到锁（调用方必须在 {@code finally} 里 {@link #release}）；
     *         {@code false} 表示该数据源已有工单在执行，调用方必须<b>不做任何状态变更</b>地放弃
     */
    public boolean acquire(long datasourceId, long ticketId) {
        try {
            jdbc.update(ACQUIRE, datasourceId, ticketId, Timestamp.from(clock.instant()));
            return true;
        } catch (DuplicateKeyException e) {
            // 主键冲突即"别人持锁"。这里不能改成先 select 再 insert：两个请求会同时看到"没锁"。
            return false;
        }
    }

    /**
     * 释放锁。<b>只有持锁的工单能释放自己的锁</b>：条件带 {@code ticket_id}，别人的 ticketId 传进来是空操作。
     */
    public void release(long datasourceId, long ticketId) {
        jdbc.update(RELEASE, datasourceId, ticketId);
    }

    /**
     * 清理超过最大持锁时长的锁行（进程崩溃后 {@code finally} 不会执行）。
     *
     * @param maxHold 最大持锁时长；{@code acquired_at} 早于 {@code now - maxHold} 的行被删除
     * @return 被清理的行数
     */
    public int takeOverStale(Duration maxHold) {
        Instant deadline = clock.instant().minus(maxHold);
        return jdbc.update(DELETE_STALE, Timestamp.from(deadline));
    }
}
