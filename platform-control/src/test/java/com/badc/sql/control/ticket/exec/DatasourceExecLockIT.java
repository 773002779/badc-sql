package com.badc.sql.control.ticket.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * 数据源执行互斥锁的并发语义（单数据源写并发 = 1）。
 *
 * <p>不起 Spring 上下文：{@link DatasourceExecLock} 就是三条 SQL，用真实 MySQL 的主键行为验证它比用 mock
 * 更有价值——"两个请求同时取锁"能不能互斥，取决于数据库的主键冲突，不取决于 Java 侧的 if。
 *
 * <p>三条用例各自对应一个真实事故：
 * <ul>
 *   <li>第二个工单拿到锁 → 同一台库上两个工单同时写；</li>
 *   <li>释放时不带 ticket_id → 崩掉的工单替接手的工单解锁；</li>
 *   <li>超时锁不能被清理 → 实例崩过一次之后该库永久不可写。</li>
 * </ul>
 */
class DatasourceExecLockIT extends AbstractMySqlIT {

    private static final long DATASOURCE_ID = 101L;

    private DatasourceExecLock lock;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from datasource_exec_lock");
        lock = new DatasourceExecLock(jdbc);
    }

    @Test
    void only_one_ticket_can_hold_a_datasource() {
        assertThat(lock.acquire(DATASOURCE_ID, 1L)).isTrue();
        assertThat(lock.acquire(DATASOURCE_ID, 2L))
                .as("同一数据源同一时刻只能有一个执行者")
                .isFalse();
        assertThat(lockCount()).isOne();

        lock.release(DATASOURCE_ID, 1L);

        assertThat(lock.acquire(DATASOURCE_ID, 2L)).as("释放后下一个工单可以接手").isTrue();
        assertThat(lockCount()).isOne();
    }

    /**
     * 实例崩了不会释放锁：超时后必须能被接管，否则该库永久不可写。
     *
     * <p>回拨 {@code acquired_at} 3 小时而不是等待——"超时"是时间问题，不是循环问题。
     */
    @Test
    void a_stale_lock_can_be_taken_over_after_the_hold_deadline() {
        assertThat(lock.acquire(DATASOURCE_ID, 1L)).isTrue();
        jdbc.update("update datasource_exec_lock set acquired_at = ? where datasource_id = ?",
                Timestamp.from(Instant.now().minus(3, ChronoUnit.HOURS)), DATASOURCE_ID);

        assertThat(lock.takeOverStale(Duration.ofHours(2))).isEqualTo(1);
        assertThat(lockCount()).isZero();
        assertThat(lock.acquire(DATASOURCE_ID, 2L)).as("接管之后新工单能取到锁").isTrue();
    }

    /** 没超时的锁不能被清理：否则一次误清理会让两个工单同时执行。 */
    @Test
    void a_lock_that_is_still_within_the_hold_deadline_is_kept() {
        assertThat(lock.acquire(DATASOURCE_ID, 1L)).isTrue();

        assertThat(lock.takeOverStale(Duration.ofHours(2))).isZero();
        assertThat(lockCount()).isOne();
    }

    /** 释放必须认持有者：传别人的 ticketId 不能把持有者的锁删掉。 */
    @Test
    void only_the_holder_can_release() {
        assertThat(lock.acquire(DATASOURCE_ID, 1L)).isTrue();

        lock.release(DATASOURCE_ID, 999L);

        assertThat(lockCount()).as("别人的 ticketId 不得解锁").isOne();
        lock.release(DATASOURCE_ID, 1L);
        assertThat(lockCount()).isZero();
    }

    /** 不同数据源互不影响。 */
    @Test
    void different_datasources_do_not_block_each_other() {
        assertThat(lock.acquire(101L, 1L)).isTrue();
        assertThat(lock.acquire(102L, 2L)).isTrue();
        assertThat(lockCount()).isEqualTo(2);
    }

    private int lockCount() {
        Integer count = jdbc.queryForObject("select count(*) from datasource_exec_lock", Integer.class);
        return count == null ? 0 : count;
    }
}
