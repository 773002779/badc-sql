package com.badc.sql.control.ticket.exec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.ticket.TicketEventMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工单清理：过期、卡死恢复、超时锁接管。
 *
 * <p>三个方法各自独立、都返回<b>被处理的行数</b>，也都可以被用例直接调用——本类不依赖调度器，
 * {@link #sweep()} 只是把它们按顺序串起来。
 *
 * <h2>为什么必须是"定时把存量扫一遍"，而不是"在读取时顺手判定"</h2>
 * <p>{@code APPROVED} 单过期、{@code EXECUTING} 单卡死，都是<b>没有请求发生</b>的状态变化：没人点、没人查，
 * 工单自己不会动。放在读路径上判定只会让列表显示成"过期"，库里仍是 {@code APPROVED}，
 * 后续的执行入口照样能把它捡起来执行——一张早该作废的单被真正执行，是这套清理要防的事故。
 *
 * <h2>三个方法各自的边界</h2>
 * <ul>
 *   <li>{@link #expireApproved()}：{@code APPROVED} 且 {@code approved_at} 早于 24 小时前 → {@code EXPIRED}
 *       + {@code finished_at}，逐行写 {@code ticket_event(EXPIRED)} 与审计 {@code TICKET_EXPIRED}。</li>
 *   <li>{@link #recoverStuckExecuting()}：{@code EXECUTING} 且 {@code executed_at} 早于 2 小时前 →
 *       {@code NEEDS_MANUAL_CONFIRM}，并释放该数据源的锁。<b>绝不自动续跑</b>（见下）。</li>
 *   <li>{@link #takeOverStaleLocks()}：{@code acquired_at} 早于 2 小时前<b>且</b>工单已不在 {@code EXECUTING}
 *       → 删锁行。工单仍 {@code EXECUTING} 的锁留给 {@link #recoverStuckExecuting()}：见该方法注释。</li>
 * </ul>
 *
 * <h2>为什么恢复绝不自动续跑</h2>
 * <p>实例在执行中途挂掉时，"执行到第几条、有没有副作用"是<b>未知</b>的（执行器逐条独立提交）。
 * 自动重跑会把前面已经成功的语句再做一遍：{@code update t set a = a + 1} 这类累计变更直接算错，
 * {@code insert} 直接撞唯一键。所以恢复只做一件事——把工单交给人，并且在事件与审计里写清
 * "未自动续跑"，避免下一个人误以为系统已经替他把活干完了。
 *
 * <h2>为什么时间一律取注入的 {@link Clock}</h2>
 * <p>阈值判定若用 {@code System.currentTimeMillis()}，用例就只能真的等 24 小时，或者去改生产代码；
 * 用 {@link Clock} 之后，用例把 {@code approved_at}/{@code executed_at}/{@code acquired_at} 回拨即可
 * （生产注册的是 {@code Clock.systemUTC()}，行为不变）。
 *
 * <h2>为什么锁的清理要带状态条件</h2>
 * <p>{@link DatasourceExecLock#takeOverStale(Duration)} 只按 {@code acquired_at} 删。如果清理任务也照这个
 * 口径做，一张<b>仍在执行</b>（进程活着，只是跑得久）的单，锁会被"接管"掉，下一个工单立刻能拿到锁并在同
 * 一条库上同时写——单数据源写并发 = 1 当场失效。所以这里多一个条件：工单仍 {@code EXECUTING} 的锁
 * 不在此处删，由 {@link #recoverStuckExecuting()} 先把工单置为需人工确认，再由它按持有者释放。
 *
 * <h2>为什么没有 {@code @Transactional}</h2>
 * <p>每一行都是"改状态 → 写事件 → 写审计"三步自动提交。整段包一个事务看似更整齐，但会让一次写事件失败
 * 把<b>已经救回来</b>的状态一起回滚：工单继续停在 {@code EXECUTING}、锁继续被占，而这正是本任务要解的问题。
 * 状态迁移是这里唯一有安全意义的一步，先落地；事件与审计是记录，失败应该暴露成告警而不是把修复撤回。
 */
@Service
public class TicketRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(TicketRecoveryJob.class);

    /** 审批通过后多久没执行就算过期（24 小时）。 */
    static final Duration APPROVAL_TTL = Duration.ofHours(24);

    /** 执行中多久没有结果就认为执行进程已经不在了（2 小时，与锁的最大持有时长同一口径）。 */
    static final Duration EXECUTION_HOLD = Duration.ofHours(2);

    /** 审计 {@code action}：审批超时作废。 */
    static final String EXPIRY_REASON = "APPROVAL_TIMEOUT";

    /** 审计 {@code action}：执行卡死转人工确认。 */
    static final String RECOVERY_REASON = "STUCK_EXECUTING";

    /**
     * 待作废的审批单。
     *
     * <p>{@code approved_at} 为 {@code NULL} 的行不会被选中（SQL 里 {@code NULL < ?} 是 {@code NULL}，
     * 不为真）：没有审批时间的单无从判断"批了多久"，不能凭猜测作废。
     */
    private static final String STALE_APPROVALS = """
            select id, datasource_id, approved_at as event_at from ticket
             where status = 'APPROVED' and approved_at < ?
            """;

    /**
     * 作废一张审批单。
     *
     * <p><b>带 {@code status = 'APPROVED'} 条件</b>：从查出来到改之间，可能有人刚好点了执行（→
     * {@code EXECUTING}）。无条件更新会把一张正在执行的单盖成 {@code EXPIRED}——它明明在跑，账上却已作废。
     */
    private static final String EXPIRE = """
            update ticket set status = 'EXPIRED', finished_at = ?
             where id = ? and status = 'APPROVED'
            """;

    /** 卡死的执行单：{@code executed_at} 早于宽限期。 */
    private static final String STUCK_EXECUTIONS = """
            select id, datasource_id, executed_at as event_at from ticket
             where status = 'EXECUTING' and executed_at < ?
            """;

    /** 转人工确认。同样带状态条件：这期间它可能已经跑完（{@code SUCCEEDED}），不能把结果盖掉。 */
    private static final String MARK_NEEDS_MANUAL_CONFIRM = """
            update ticket set status = 'NEEDS_MANUAL_CONFIRM'
             where id = ? and status = 'EXECUTING'
            """;

    /**
     * 接管超时锁：<b>只删"持有者已经不在执行"的那把</b>。
     *
     * <p>{@code not in (select id from ticket where status = 'EXECUTING')} 覆盖三种情况：
     * 工单还是 {@code APPROVED}（锁是残留）、工单已终态（进程崩了，没人会再释放）、
     * 以及工单行根本不存在（元数据被删）。三种都该清掉，否则该数据源永久不可写。
     *
     * <p>子查询取的是 {@code ticket}、被删的是 {@code datasource_exec_lock}，不是同一张表，
     * MySQL 允许；{@code id} 是非空主键，{@code NOT IN} 不会因为 NULL 变成恒假。
     */
    private static final String TAKE_OVER_STALE_LOCKS = """
            delete from datasource_exec_lock
             where acquired_at < ?
               and ticket_id not in (select id from ticket where status = 'EXECUTING')
            """;

    private final JdbcTemplate jdbc;
    private final TicketEventMapper eventMapper;
    private final DatasourceExecLock lock;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TicketRecoveryJob(
            JdbcTemplate jdbc,
            TicketEventMapper eventMapper,
            DatasourceExecLock lock,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.eventMapper = eventMapper;
        this.lock = lock;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 定时扫一遍：过期 → 卡死恢复 → 超时锁接管。
     *
     * <p>三步都幂等，重复跑没有副作用。先恢复再清锁只是为了少一次无用功：恢复用的是带 {@code ticket_id}
     * 的条件释放（不会误删别人的锁），刚被恢复的工单锁已由它自己释放，第三步就没得删；顺序反过来也只是
     * 本次少删一把、下一次扫描补上——真正保证正确性的是各自的 {@code status} 条件，不是顺序。
     *
     * <p><b>{@code initialDelayString = "PT5M"}</b>：{@code @EnableScheduling} 对所有集成测试同样生效
     * （它们加载完整的 {@code ControlApplication}），固定延迟的任务默认会在上下文启动时立刻跑第一次。
     * 那样清理线程会与用例的"回拨时间戳 → 断言"并发，用例被迫与调度器赛跑（且审计/事件计数会被别人
     * 先写掉）。给一个 5 分钟的初始延迟后，测试进程里这一任务在类结束前不会触发，生产上首次清理
     * 也只晚 5 分钟——维护型任务的这个延迟没有任何代价。
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void sweep() {
        int expired = expireApproved();
        int recovered = recoverStuckExecuting();
        int locks = takeOverStaleLocks();
        if (expired + recovered + locks > 0) {
            log.info("工单清理：过期 {} 张，卡死转人工 {} 张，清理超时锁 {} 把", expired, recovered, locks);
        }
    }

    /**
     * 把审批通过超过 24 小时仍未执行的工单置为 {@code EXPIRED}。
     *
     * @return 本次作废的行数
     */
    public int expireApproved() {
        Instant now = clock.instant();
        List<DueTicket> due = jdbc.query(STALE_APPROVALS, DueTicket::map,
                Timestamp.from(now.minus(APPROVAL_TTL)));
        int expired = 0;
        for (DueTicket ticket : due) {
            if (jdbc.update(EXPIRE, Timestamp.from(now), ticket.id()) == 0) {
                // 这期间有人点了执行（或撤销）：不是本方法的对象，下一次扫描会按当时的状态重新判定。
                continue;
            }
            expired++;
            String detail = detailJson(
                    "reason", EXPIRY_REASON,
                    "approvedAt", instantText(ticket.at()),
                    "ttlHours", APPROVAL_TTL.toHours());
            eventMapper.append(ticket.id(), "EXPIRED", null, detail);
            auditService.record(new AuditCommand(
                    "TICKET_EXPIRED", null, null, ticket.datasourceId(),
                    EXPIRY_REASON, String.valueOf(ticket.id()), detail));
        }
        return expired;
    }

    /**
     * 把执行超过 2 小时没有结果的工单置为 {@code NEEDS_MANUAL_CONFIRM}，并释放它持有的锁。
     *
     * <p><b>只释放"还属于这张单"的锁</b>：{@link DatasourceExecLock#release(long, long)} 的条件里带
     * {@code ticket_id}，别人的锁一行都不会动。这很重要——恢复发生在"以为进程死了"的时刻，
     * 而进程可能只是慢；若按数据源无条件删锁，慢进程与新工单会同时在同一条库上写。
     *
     * @return 本次转为人工确认的行数
     */
    public int recoverStuckExecuting() {
        Instant now = clock.instant();
        List<DueTicket> stuck = jdbc.query(STUCK_EXECUTIONS, DueTicket::map,
                Timestamp.from(now.minus(EXECUTION_HOLD)));
        int recovered = 0;
        for (DueTicket ticket : stuck) {
            if (jdbc.update(MARK_NEEDS_MANUAL_CONFIRM, ticket.id()) == 0) {
                continue; // 已经跑完/已被人处理过，别盖掉真实结果。
            }
            recovered++;
            lock.release(ticket.datasourceId(), ticket.id());
            String detail = detailJson(
                    "reason", RECOVERY_REASON,
                    "executedAt", instantText(ticket.at()),
                    "holdHours", EXECUTION_HOLD.toHours(),
                    "message", "执行超过 " + EXECUTION_HOLD.toHours()
                            + " 小时未结束，已置为需人工确认；未自动续跑，请人工核对该库的实际状态");
            eventMapper.append(ticket.id(), "RECOVERY", null, detail);
            auditService.record(new AuditCommand(
                    "TICKET_RECOVERY", null, null, ticket.datasourceId(),
                    RECOVERY_REASON, String.valueOf(ticket.id()), detail));
        }
        return recovered;
    }

    /**
     * 清掉持有者已不在执行的超时锁（进程崩溃后 {@code finally} 不会执行，锁会永久留着）。
     *
     * <p>工单仍 {@code EXECUTING} 的锁<b>不在这里删</b>——交给 {@link #recoverStuckExecuting()}：
     * 先确认"这单确实卡死了"并把它交给人，再释放锁。否则一张跑了两小时的大 DDL（进程还活着、语句还在跑），
     * 锁会被这里抢走，第二个工单进来就是双写。
     *
     * @return 本次删除的锁行数
     */
    public int takeOverStaleLocks() {
        return jdbc.update(TAKE_OVER_STALE_LOCKS,
                Timestamp.from(clock.instant().minus(EXECUTION_HOLD)));
    }

    // ------------------------------------------------------------------ 小工具

    /** 一条待处理工单：ID、所属数据源、以及用于判定的那个时间点。 */
    private record DueTicket(long id, long datasourceId, Instant at) {

        /** 两个查询都把判定用时间列别名成 {@code event_at}，共用同一个映射。 */
        static DueTicket map(ResultSet rs, int rowNum) throws SQLException {
            Timestamp timestamp = rs.getTimestamp("event_at");
            return new DueTicket(
                    rs.getLong("id"),
                    rs.getLong("datasource_id"),
                    timestamp == null ? null : timestamp.toInstant());
        }
    }

    /** 时间进 JSON 一律用 ISO-8601（UTC）：{@code Timestamp.toString()} 会带上本地时区，审计里不可比。 */
    private static String instantText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** 按 key/value 交替构造明细；参数为奇数时忽略最后一个。 */
    private static Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            detail.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return detail;
    }

    private String detailJson(Object... pairs) {
        try {
            return objectMapper.writeValueAsString(detail(pairs));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工单清理的事件/审计明细序列化失败", e);
        }
    }
}
