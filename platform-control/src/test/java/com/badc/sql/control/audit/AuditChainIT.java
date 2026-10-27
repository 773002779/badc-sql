package com.badc.sql.control.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
