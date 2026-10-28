package com.badc.sql.control.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.RawResult;

/**
 * 查询结果的短期缓存：保留 30 分钟、最多 20 条（Q-17）。
 *
 * <p>缓存的是<b>结果本身</b>，不是 SQL 文本——同一 SQL 重跑要重新执行，否则用户看到的是旧数据。
 *
 * <p><b>为什么不用 Caffeine 的 {@code maximumSize}</b>：Caffeine 的 W-TinyLFU 是一种"准入"策略，满仓时
 * 它按频率新老对比决定是否接收新条目，可能<b>拒绝刚写进来的那一条</b>。对结果缓存这是错的语义——用户刚执行完
 * 的查询结果必须能按 {@code resultId} 导出，被丢掉的偏偏是最该留住的那条。这里要的是"最近写入的 20 条"：
 * 插入序淘汰最旧，写入必定可用。
 *
 * <p>过期由注入的 {@link Clock} 驱动（替代 Caffeine 的 ticker），测试推进时钟即可验证，而不是
 * {@code Thread.sleep} 等 30 分钟。条目数极小，直接 {@code synchronized} 保护一张 {@link LinkedHashMap}；
 * 没有维护线程，也不需要 {@code cleanUp()}。
 */
@Service
public class ResultCacheService {

    private final Clock clock;

    private final int maximumSize;

    private final Duration retention;

    /** 按写入先后排列（插入序）：队首最旧，淘汰从队首开始。重复写入会先移除再放入，即"最近写入"移到队尾。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Autowired
    public ResultCacheService(Clock clock) {
        this(clock, 20, Duration.ofMinutes(30));
    }

    ResultCacheService(Clock clock, int maximumSize, Duration retention) {
        this.clock = clock;
        this.maximumSize = Math.max(0, maximumSize);
        this.retention = retention;
    }

    public synchronized void put(String resultId, RawResult result) {
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(result, "result");
        purgeExpired();
        // 先移除再放入：重复写入的条目算"最近写入"，不会被当成最旧的立刻淘汰。
        entries.remove(resultId);
        entries.put(resultId, new Entry(result, clock.instant().plus(retention)));
        // 插入序淘汰最旧，直到不超容量。写入后的条目一定在新队尾，因此一定会被保留。
        Iterator<Map.Entry<String, Entry>> oldestFirst = entries.entrySet().iterator();
        while (entries.size() > maximumSize && oldestFirst.hasNext()) {
            oldestFirst.next();
            oldestFirst.remove();
        }
    }

    public synchronized Optional<RawResult> get(String resultId) {
        Entry entry = entries.get(resultId);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(resultId);
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    /**
     * 当前缓存条数。
     *
     * <p>先清掉已过期的条目再读数，口径与 {@link #get(String)} 一致（过期的读不到，也不该计入）。
     */
    public synchronized long size() {
        purgeExpired();
        return entries.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !now.isBefore(entry.expiresAt()));
    }

    private record Entry(RawResult result, Instant expiresAt) {
    }
}
