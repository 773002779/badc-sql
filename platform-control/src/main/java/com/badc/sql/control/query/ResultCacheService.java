package com.badc.sql.control.query;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.badc.sql.common.dto.RawResult;

/**
 * 查询结果的短期缓存：保留 30 分钟、最多 20 条（Q-17）。
 *
 * <p>缓存的是<b>结果本身</b>，不是 SQL 文本——同一 SQL 重跑要重新执行，否则用户看到的是旧数据。
 *
 * <p>过期由注入的 {@link Clock} 驱动（映射到 Caffeine ticker），测试可以推进时间去验证，而不是
 * {@code Thread.sleep} 等 30 分钟。
 */
@Service
public class ResultCacheService {

    private final Cache<String, RawResult> cache;

    @Autowired
    public ResultCacheService(Clock clock) {
        this(clock, 20, Duration.ofMinutes(30));
    }

    ResultCacheService(Clock clock, int maximumSize, Duration retention) {
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(retention)
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    public void put(String resultId, RawResult result) {
        cache.put(resultId, result);
    }

    public Optional<RawResult> get(String resultId) {
        return Optional.ofNullable(cache.getIfPresent(resultId));
    }

    /**
     * 当前缓存条数。
     *
     * <p>Caffeine 的容量淘汰与过期清理是异步维护的，{@code estimatedSize()} 不会立刻反映。
     * 先 {@code cleanUp()} 强制做一次维护，读数才是确定的（也用于监控口径一致）。
     */
    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
