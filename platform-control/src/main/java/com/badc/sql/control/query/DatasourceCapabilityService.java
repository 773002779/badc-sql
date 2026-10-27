package com.badc.sql.control.query;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.control.org.ConnAccountService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 数据源能力探测 + 短期缓存。
 *
 * <p>版本这类能力在两次查询之间不会变，但探测要真的连一次库。不缓存的话，每次查询都要多占一个连接，
 * 高并发下直接把目标库的连接数打满；缓存太久又会让"刚升级到 8.0 的库"持续被拒。取 10 分钟。
 *
 * <p><b>探测失败一律降级为"能力未知"（返回 {@code null}）</b>：执行器不可用、库不可达、只读账号没配、
 * 响应解析失败，都只记日志。调用方看到 null 就跳过版本校验、照常执行 SQL——探测是"锦上添花"的
 * 前置检查，绝不能因为它把本来能跑的查询拦死（用户会看到"执行器不可用"，而真正的问题可能只是探测超时）。
 *
 * <p>失败不写缓存：否则一次网络抖动会让该数据源在 10 分钟内失去能力信息；也避免把"暂时连不上"
 * 固化成结论。
 *
 * <p>缓存里只放 {@link Capability}（版本与开关），不放 {@link ConnConfig}——连接与密码不驻留。
 */
@Service
public class DatasourceCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceCapabilityService.class);

    /** 能力的有效期：够久以免每次查询都探测，够短以便升级后能自愈。 */
    private static final Duration TTL = Duration.ofMinutes(10);

    /** 数据源数量有限；设上限只是防止删了又建时缓存无界增长。 */
    private static final int MAX_ENTRIES = 10_000;

    private final ConnAccountService connAccountService;
    private final ExecutorClient executorClient;
    private final Cache<Long, Capability> cache;

    @Autowired
    public DatasourceCapabilityService(
            ConnAccountService connAccountService, ExecutorClient executorClient, Clock clock) {
        this.connAccountService = connAccountService;
        this.executorClient = executorClient;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(TTL)
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    /**
     * 该数据源的能力；{@code null} 表示未知（探测失败或版本无法识别），调用方应跳过版本相关判定。
     *
     * <p>缓存未命中时只探测一次。并发未命中会有少量重复探测，但不会重复缓存，也不会阻塞查询——
     * 用 {@code synchronized} 把查询线程串起来，比多打一次探测的代价大得多。
     */
    public Capability capabilityOf(long datasourceId) {
        Capability cached = cache.getIfPresent(datasourceId);
        if (cached != null) {
            return cached;
        }
        Capability probed = probeQuietly(datasourceId);
        if (probed != null) {
            cache.put(datasourceId, probed);
        }
        return probed;
    }

    private Capability probeQuietly(long datasourceId) {
        try {
            ConnConfig conn = connAccountService.readonlyConnConfig(datasourceId);
            // 本切片没有在线 DDL 账号的读取入口：传 null，onlineDdlAvailable 恒为 false。
            return executorClient.probe(conn, null);
        } catch (RuntimeException e) {
            // 刻意捕获所有运行时异常：探测链路上任何一环坏掉都不该阻断查询。
            log.warn("数据源 {} 能力探测失败，本次跳过版本校验：{}", datasourceId, e.toString());
            return null;
        }
    }
}
