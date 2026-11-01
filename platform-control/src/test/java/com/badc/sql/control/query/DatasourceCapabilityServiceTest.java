package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.org.ConnAccountService;

/**
 * 能力缓存的边界：缓存多久、缓存什么、失败怎么办。
 *
 * <p>这里每一条都对应一个具体的失败模式：
 * <ul>
 *   <li>不缓存 → 每次查询都多打一次探测连接，库的 <b>连接数</b>直接翻倍；</li>
 *   <li>缓存不失效 → 数据源升级到 8.0 后，用户最多 10 分钟仍被"版本不支持"挡住；</li>
 *   <li>把失败也缓存 → 一次网络抖动会让该库在 10 分钟内<b>完全无法查询</b>（版本未知本应放行）；</li>
 *   <li>把失败往上抛 → 探测失败会变成查询 500，违反"探测失败不得阻断查询"。</li>
 * </ul>
 */
class DatasourceCapabilityServiceTest {

    private static final ConnConfig READONLY = new ConnConfig(
            "127.0.0.1", 3306, "order_db", "readonly_user", "readonly-pass", "utf8mb4", "+00:00");

    private static final Capability MYSQL_57 = Capability.fromVersionString("5.7.44", false);

    private MutableClock clock;
    private ConnAccountService connAccounts;
    private RecordingExecutor executor;
    private DatasourceCapabilityService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        connAccounts = mock(ConnAccountService.class);
        given(connAccounts.readonlyConnConfig(101L)).willReturn(READONLY);
        executor = new RecordingExecutor();
        service = new DatasourceCapabilityService(connAccounts, executor, clock);
    }

    @Test
    void repeated_lookups_are_served_from_the_cache_instead_of_probing_again() {
        Capability first = service.capabilityOf(101L);
        Capability second = service.capabilityOf(101L);

        assertThat(first).isEqualTo(MYSQL_57);
        assertThat(second).isEqualTo(MYSQL_57);
        assertThat(executor.probeCalls).isEqualTo(1);
    }

    @Test
    void the_cache_expires_after_ten_minutes_so_an_upgraded_datasource_is_not_stuck_forever() {
        service.capabilityOf(101L);
        clock.advance(Duration.ofMinutes(9));
        service.capabilityOf(101L);
        assertThat(executor.probeCalls).as("10 分钟内只探测一次").isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        service.capabilityOf(101L);
        assertThat(executor.probeCalls).as("超过 10 分钟必须重新探测").isEqualTo(2);
    }

    @Test
    void each_datasource_is_cached_and_probed_separately() {
        given(connAccounts.readonlyConnConfig(102L)).willReturn(READONLY);

        service.capabilityOf(101L);
        service.capabilityOf(102L);
        service.capabilityOf(101L);
        service.capabilityOf(102L);

        assertThat(executor.probeCalls).isEqualTo(2);
    }

    /**
     * 缓存必须能被显式丢弃：数据源被改指向（5.7 → 8.0）或换了只读账号之后，旧结论就是错的。
     *
     * <p>没有这一步，把库从 5.7 迁到 8.0 后的 10 分钟里，正确的 CTE 仍会被 422 拒掉——平台拿自己的
     * 过期元数据去否用户的正确 SQL，比不校验更糟。
     */
    @Test
    void evicting_a_datasource_forces_the_next_lookup_to_probe_again() {
        service.capabilityOf(101L);
        assertThat(executor.probeCalls).isEqualTo(1);

        service.evict(101L);

        assertThat(service.capabilityOf(101L)).isEqualTo(MYSQL_57);
        assertThat(executor.probeCalls).isEqualTo(2);
    }

    /** 丢弃一个数据源不能顺手清掉别人的缓存：否则改一台库会让全平台的查询都多打一次探测。 */
    @Test
    void evicting_one_datasource_leaves_the_other_cached_capabilities_intact() {
        given(connAccounts.readonlyConnConfig(102L)).willReturn(READONLY);
        service.capabilityOf(101L);
        service.capabilityOf(102L);

        service.evict(101L);
        service.capabilityOf(102L);

        assertThat(executor.probeCalls).as("102 仍在缓存里，不该重新探测").isEqualTo(2);
    }

    /**
     * 探测失败必须是"能力未知"，而不是"查询失败"：返回 null，调用方跳过校验，让 SQL 继续执行。
     * 用非 {@link BizException} 的运行时异常来钉住"任何探测故障"这条约束，避免以后把 catch 收窄。
     */
    @Test
    void any_probe_failure_is_swallowed_so_the_query_can_still_run() {
        executor.failure = new IllegalStateException("probe exploded");

        assertThat(service.capabilityOf(101L)).isNull();
    }

    /** 失败的探测不写缓存：网络恢复后下一次查询就该拿到能力，而不是等 10 分钟。 */
    @Test
    void a_failed_probe_is_not_cached_so_recovery_is_immediate() {
        executor.failure = new BizException(ErrorCode.INTERNAL_ERROR, "执行器不可用，请稍后重试");
        service.capabilityOf(101L);

        executor.failure = null;
        assertThat(service.capabilityOf(101L)).isEqualTo(MYSQL_57);
        assertThat(executor.probeCalls).isEqualTo(2);
    }

    /**
     * 只读账号没配时，能力探测同样返回 null；真正的"未配置只读账号"由执行查询时的
     * {@code ConnAccountService} 报 404——这里不能抢先把错误变成别的东西。
     */
    @Test
    void a_missing_readonly_account_makes_the_capability_unknown_instead_of_throwing() {
        given(connAccounts.readonlyConnConfig(102L))
                .willThrow(new BizException(ErrorCode.NOT_FOUND, "数据源 102 未配置只读账号"));

        assertThat(service.capabilityOf(102L)).isNull();
        assertThat(executor.probeCalls).isZero();
    }

    /** 探测用的是只读账号；本切片没有在线 DDL 账号读接口，必须传 null 而不是拿只读账号去顶替。 */
    @Test
    void probe_uses_the_readonly_account_and_no_online_ddl_account() {
        service.capabilityOf(101L);

        assertThat(executor.lastProbeConn).isEqualTo(READONLY);
        assertThat(executor.lastProbeOnlineDdlConn).isNull();
    }

    /** 可推进的时钟：证明 TTL 靠注入的时间，而不是靠 {@code Thread.sleep} 等 10 分钟。 */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** 记录探测次数与入参的执行器桩；{@code failure} 非空时让 probe 抛出。 */
    static final class RecordingExecutor implements ExecutorClient {

        int probeCalls;
        RuntimeException failure;
        ConnConfig lastProbeConn;
        ConnConfig lastProbeOnlineDdlConn;

        @Override
        public RawResult query(ConnConfig conn, String sql, int maxRows) {
            throw new UnsupportedOperationException("能力缓存不应发起查询");
        }

        @Override
        public Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull) {
            probeCalls++;
            lastProbeConn = conn;
            lastProbeOnlineDdlConn = onlineDdlOrNull;
            if (failure != null) {
                throw failure;
            }
            return MYSQL_57;
        }

        @Override
        public ValidationResult validate(ConnConfig conn, String sql, DbVersion version) {
            throw new UnsupportedOperationException("能力缓存不应发起版本校验");
        }

        @Override
        public Map<String, String> fingerprint(ConnConfig conn, List<String> tables) {
            throw new UnsupportedOperationException("能力缓存不应取指纹");
        }

        @Override
        public ExplainEstimate explain(ConnConfig conn, String sql) {
            throw new UnsupportedOperationException("能力缓存不应做行数估算");
        }

        @Override
        public SnapshotResult snapshot(ConnConfig conn, List<String> tables, String relativeRef) {
            throw new UnsupportedOperationException("能力缓存不应抓取结构快照");
        }

        @Override
        public ExecuteResult execute(ConnConfig conn, String sql, ExecAccountType type) {
            throw new UnsupportedOperationException("能力缓存不应执行写语句");
        }
    }
}
