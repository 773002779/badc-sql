package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ColumnMeta;
import com.badc.sql.common.dto.RawResult;

/**
 * 结果缓存的存活与容量规则：保留 30 分钟、最多 20 条。
 *
 * <p>用可注入的 {@link Clock} 驱动过期判定，不靠 {@code Thread.sleep} 等真实时间——否则这类测试要么很慢，
 * 要么不稳定。
 */
class ResultCacheServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");

    private static RawResult result(String value) {
        return new RawResult(List.of(new ColumnMeta("c", "VARCHAR")), List.of(List.of(value)), false, 1L);
    }

    private static ResultCacheService cacheAt(Clock clock) {
        return new ResultCacheService(clock, 20, Duration.ofMinutes(30));
    }

    @Test
    void entry_is_available_within_the_retention_window() {
        MutableClock clock = new MutableClock(T0);
        ResultCacheService cache = cacheAt(clock);
        cache.put("r1", result("v"));

        clock.plus(Duration.ofMinutes(29));

        assertThat(cache.get("r1")).isPresent();
    }

    @Test
    void entry_expires_after_thirty_minutes() {
        MutableClock clock = new MutableClock(T0);
        ResultCacheService cache = cacheAt(clock);
        cache.put("r1", result("v"));

        clock.plus(Duration.ofMinutes(31));

        assertThat(cache.get("r1")).isEmpty();
    }

    @Test
    void keeps_at_most_twenty_results() {
        MutableClock clock = new MutableClock(T0);
        ResultCacheService cache = cacheAt(clock);
        for (int i = 0; i < 25; i++) {
            cache.put("r" + i, result("v" + i));
        }

        assertThat(cache.size()).isLessThanOrEqualTo(20);
        assertThat(cache.get("r0")).as("最旧的应被淘汰").isEmpty();
        assertThat(cache.get("r24")).isPresent();
    }

    /**
     * 满仓后再写：新条目<b>必定</b>可读。
     *
     * <p>这是不用 W-TinyLFU 准入的理由：它可能在满仓时拒收新条目，用户刚执行完的查询就导不出来了。
     */
    @Test
    void a_write_into_a_full_cache_is_always_readable() {
        MutableClock clock = new MutableClock(T0);
        ResultCacheService cache = cacheAt(clock);
        for (int i = 0; i < 20; i++) {
            cache.put("r" + i, result("v" + i));
        }
        assertThat(cache.size()).isEqualTo(20);

        cache.put("fresh", result("fresh"));

        assertThat(cache.get("fresh")).isPresent();
        assertThat(cache.size()).isLessThanOrEqualTo(20);
        assertThat(cache.get("r0")).as("最旧的被淘汰，新写入的不会").isEmpty();
    }

    /** 重复写入算"最近写入"：不能被当成最旧的立刻淘汰掉。 */
    @Test
    void rewriting_an_existing_id_keeps_it_among_the_newest() {
        MutableClock clock = new MutableClock(T0);
        ResultCacheService cache = cacheAt(clock);
        for (int i = 0; i < 20; i++) {
            cache.put("r" + i, result("v" + i));
        }

        cache.put("r0", result("again"));
        cache.put("r20", result("v20"));

        assertThat(cache.get("r0")).isPresent();
        assertThat(cache.get("r1")).as("真正最旧的被淘汰").isEmpty();
    }

    /** 过期条目读不到，也不计入条数——{@code size()} 先清理再读数。 */
    @Test
    void size_does_not_count_expired_entries() {
        MutableClock clock = new MutableClock(T0);
        ResultCacheService cache = cacheAt(clock);
        cache.put("r1", result("v"));
        cache.put("r2", result("v"));

        clock.plus(Duration.ofMinutes(31));

        assertThat(cache.size()).isZero();
    }

    @Test
    void unknown_id_returns_empty_rather_than_throwing() {
        MutableClock clock = new MutableClock(T0);
        assertThat(cacheAt(clock).get("nope")).isEmpty();
    }

    /** 可手动推进的时钟。 */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void plus(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
