package com.badc.sql.control.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * 审计哈希链：只追加、可校验、篡改可见。
 *
 * <p>三个容易做错的地方，各自有测试：
 * <ul>
 *   <li>{@code seq} 由应用赋值（不是自增），并发写会分叉 → 必须串行化链头；</li>
 *   <li>V1 里 {@code hash} 列是 {@code utf8mb4_0900_ai_ci}（大小写不敏感）→ 校验必须在应用层比较原串；</li>
 *   <li>payload 的 JSON 序列化必须稳定，否则同一内容两次序列化得到不同字节，链条自相矛盾。</li>
 * </ul>
 */
@SpringBootTest
class AuditChainIT extends AbstractMySqlIT {

    @Autowired
    private AuditService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = ensureSchema();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", AbstractMySqlIT::dbUser);
        registry.add("spring.datasource.password", AbstractMySqlIT::dbPass);
    }

    @BeforeAll
    static void loadFixtures() {
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("seed/test-fixtures.sql")),
                jdbc.getDataSource());
    }

    @BeforeEach
    void clearChain() {
        jdbc.update("delete from audit_event");
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    private static AuditCommand cmd(String type, String action, String payloadKey) {
        return new AuditCommand(type, 1L, "dev01", 101L, action, "obj-1", "{\"" + payloadKey + "\":\"v\"}");
    }

    @Test
    void a_fresh_chain_with_several_events_verifies_clean() {
        auditService.record(cmd("QUERY", "select", "sql"));
        auditService.record(cmd("QUERY", "select", "sql"));
        auditService.record(cmd("LOGIN", "login", "user"));

        assertThat(auditService.verify().valid()).isTrue();
    }

    @Test
    void genesis_event_starts_with_an_empty_prev_hash_and_seq_one() {
        auditService.record(cmd("QUERY", "select", "sql"));

        List<String> prev = jdbc.queryForList("select prev_hash from audit_event order by seq", String.class);
        List<Long> seqs = jdbc.queryForList("select seq from audit_event order by seq", Long.class);

        assertThat(prev).containsExactly("");
        assertThat(seqs).containsExactly(1L);
    }

    @Test
    void tampering_with_a_middle_event_is_detected_at_that_seq() {
        auditService.record(cmd("QUERY", "select", "sql"));
        auditService.record(cmd("QUERY", "select", "sql"));
        auditService.record(cmd("QUERY", "select", "sql"));

        jdbc.update("update audit_event set payload_json = '{}' where seq = 2");

        AuditService.VerifyResult result = auditService.verify();
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(2L);
    }

    @Test
    void concurrent_writers_do_not_fork_the_chain() throws Exception {
        int writers = 8;
        int perWriter = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();

        for (int w = 0; w < writers; w++) {
            final int id = w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        auditService.record(cmd("QUERY", "select" + id + "_" + i, "sql"));
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        List<Long> seqs = jdbc.queryForList("select seq from audit_event order by seq", Long.class);
        assertThat(seqs).hasSize(writers * perWriter);
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).as("seq 必须连续无空洞").isEqualTo((long) (i + 1));
        }
        assertThat(auditService.verify().valid()).isTrue();
    }

    /**
     * 审计写入与业务同事务（任务 7 起）之后，链头读取必须是<b>加锁读</b>。
     *
     * <p>普通 {@code select} 在 REPEATABLE READ 下读的是事务第一次读时的快照：两个并发事务都读到同一个
     * {@code seq}，一个成功、另一个的重试<b>仍然</b>读到旧快照，三次都撞唯一键 → 抛异常。加锁读
     * （{@code for update}）总是读最新已提交版本，同时把并发 append 串行化——哈希链本来就不能并行追加。
     *
     * <p>构造方式：A 在事务里写一条后保持事务 300ms 不提交；B 在这段时间里开始自己的事务并尝试写入。
     * 没有 {@code for update} 时 B 的重试永远读到旧快照 → 失败；有它时 B 阻塞到 A 提交，再读到最新链头 →
     * 两条都成功。删掉 {@code for update} 这条用例会稳定失败（不是概率事件）。
     */
    @Test
    void an_append_inside_a_business_transaction_serializes_with_a_concurrent_one() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstRecorded = new CountDownLatch(1);
        CountDownLatch secondReadStarted = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        pool.submit(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    auditService.record(cmd("QUERY", "first", "sql"));
                    firstRecorded.countDown();
                    await(secondReadStarted);
                    // 让 B 的普通读落在 A 提交之前，从而把"快照旧读"的缺陷固定成必现。
                    sleep(300);
                });
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        pool.submit(() -> {
            try {
                await(firstRecorded);
                transactionTemplate.executeWithoutResult(status -> {
                    secondReadStarted.countDown();
                    auditService.record(cmd("QUERY", "second", "sql"));
                });
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).as("并发事务里的两次 append 都必须成功").isEmpty();
        assertThat(jdbc.queryForList("select seq from audit_event order by seq", Long.class))
                .containsExactly(1L, 2L);
        assertThat(auditService.verify().valid()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发线程超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void hashes_are_compared_case_sensitively_despite_the_ci_collation() {
        auditService.record(cmd("QUERY", "select", "sql"));

        String storedHash = jdbc.queryForObject("select hash from audit_event where seq = 1", String.class);
        // 仅大小写不同：在 utf8mb4_0900_ai_ci 下 SQL 比较会认为相等，应用层必须认为不等。
        jdbc.update("update audit_event set hash = ? where seq = 1", storedHash.toUpperCase());

        AuditService.VerifyResult result = auditService.verify();
        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(1L);
    }

    @Test
    void payload_serialisation_is_stable_so_the_same_command_hash_is_reproducible() {
        auditService.record(new AuditCommand("QUERY", 1L, "dev01", 101L, "select", "obj-1",
                "{\"b\":2,\"a\":1}"));
        auditService.record(new AuditCommand("QUERY", 1L, "dev01", 101L, "select", "obj-1",
                "{\"a\":1,\"b\":2}"));

        // 两条 payload 语义相同、书写顺序不同：链条仍然有效，说明规范化生效。
        assertThat(auditService.verify().valid()).isTrue();
    }
}
