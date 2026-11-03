package com.badc.sql.control.ticket.exec;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.notify.TicketNotificationPublisher;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskEngine;
import com.badc.sql.control.risk.RiskLevel;
import com.badc.sql.control.risk.RiskProperties;
import com.badc.sql.control.risk.StatementClassifier;
import com.badc.sql.control.ticket.RiskAckService;
import com.badc.sql.control.ticket.SchemaSnapshotMapper;
import com.badc.sql.control.ticket.TicketEntity;
import com.badc.sql.control.ticket.TicketEventMapper;
import com.badc.sql.control.ticket.TicketMapper;
import com.badc.sql.control.ticket.TicketReviewMapper;
import com.badc.sql.control.ticket.TicketRiskSnapshot;
import com.badc.sql.control.ticket.TicketStatementMapper;
import com.badc.sql.control.ticket.TicketStatus;
import com.badc.sql.control.user.UserRoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工单执行编排：把一次"点执行"变成"逐条语句真实下发"，并把所有闸一次性装上。
 *
 * <h2>两段式：同步入口必须短，异步执行负责全部重活</h2>
 * <p>{@link #requestExecution} 只做四件事：权限 → 取锁 → 乐观置 {@code EXECUTING} → 提交线程池，然后立刻
 * 返回 202。它<b>不连目标库</b>、不跑 EXPLAIN、不抓快照——那些都可能耗时数秒到数十分钟，放在请求线程里会让
 * 前端以为请求失败。
 *
 * <p>顺序不可调换的两个理由：
 * <ul>
 *   <li><b>ADMIN 先于归属判定</b>：{@code isOperatorOf} 只看 {@code sys_system_operator} 有没有这个人，
 *       <b>不带角色过滤</b>。被误配成运维的管理员会直接通过归属判定，所以 ADMIN 必须在归属之前拦下。</li>
 *   <li><b>取锁先于任何状态变更</b>：拿不到锁的请求必须<b>零副作用</b>——工单仍是 {@code APPROVED}、
 *       语句仍是 {@code PENDING}；否则"被拒的单"会留下一条不像任何合法状态的执行痕迹。</li>
 * </ul>
 *
 * <h2>执行期的闸门顺序</h2>
 * <p>风险重算 → 语句指纹 → 结构漂移 → 执行前快照 → 风险确认 → 逐条执行 → 汇总。每一道失败都走同一个
 * {@link #abort}：工单置 {@code FAILED} + {@code finished_at}、写 {@code ticket_event(EXEC_ABORTED,
 * {reason, detail})}、写审计 {@code TICKET_ABORTED}。{@code reason} 只取五个值：{@code RISK_ESCALATED}、
 * {@code RISK_ACK_MISSING}、{@code STATEMENT_TAMPERED}、{@code SCHEMA_DRIFT}、{@code SNAPSHOT_FAILED}。
 *
 * <p><b>风险确认闸为什么紧贴执行、而不是排在第二位</b>：确认行里存的哈希由
 * {@link TicketRiskSnapshot#hash} 覆盖"重算出的风险快照 + 当前语句原文"。任何一次语句改动都会同时改变它，
 * 若把确认闸放在指纹闸之前，那么"审批后语句被改"的工单会先撞上 {@code RISK_ACK_MISSING}，
 * {@code STATEMENT_TAMPERED} 永远不可达——排障时看到的是"没确认风险"，而真实事实是"语句被改了"。
 * 所以先证语句没变（指纹）、再证结构没变（漂移）、再抓快照，最后用最便宜的一次比对确认"他要执行的那份
 * 风险就是他确认过的那份"，然后立刻下发。这也满足"中间不得插入任何可能长时间阻塞的调用"。
 *
 * <h2>失败是结果，不是"没执行"</h2>
 * <p>执行器逐条独立提交。某条失败时控制面把该条记 {@code FAILED}，其余未执行的语句记 {@code SKIPPED} 并
 * <b>停止</b>（不继续跑后面的语句），工单按"成功数"汇总成 {@code SUCCEEDED} / {@code PARTIAL_FAILED} /
 * {@code FAILED}。
 *
 * <p><b>绝不断言"失败的语句没有生效"</b>：{@code autoCommit=true} 下 {@code executeUpdate} 返回即已提交，
 * 执行器在连接关闭阶段抛出的异常同样会落到 {@code success=false}。因此错误信息只说"该条执行失败、
 * 结果可能已生效、请人工核对"，不说"未执行"或"已回滚"——系统在这里<b>无法确定事实</b>。
 *
 * <h2>回滚工单联动</h2>
 * <p>{@code ticket_type == ROLLBACK} 且本次执行 {@code SUCCEEDED} 时，把原单（{@code origin_ticket_id}）置
 * {@code ROLLED_BACK} 并写 {@code ticket_event(ROLLED_BACK)}。回滚单本身部分失败时原单保持原状——没还原的
 * 数据不能被标成已回滚。任务 12 会在此基础上加回滚单的发起入口。
 */
@Service
public class TicketExecutionService {

    /** 与 §4.2 一致：管理员只配置与授权，不参与执行链。 */
    private static final String ADMIN = "ADMIN";

    /** 中止原因（写入 {@code ticket_event.detail_json.reason} 与审计 {@code action}）。 */
    static final String RISK_ESCALATED = "RISK_ESCALATED";
    static final String RISK_ACK_MISSING = "RISK_ACK_MISSING";
    static final String STATEMENT_TAMPERED = "STATEMENT_TAMPERED";
    static final String SCHEMA_DRIFT = "SCHEMA_DRIFT";
    static final String SNAPSHOT_FAILED = "SNAPSHOT_FAILED";

    /** 回滚单成功后回写原单的事件类型。 */
    private static final String ROLLBACK_TICKET_TYPE = "ROLLBACK";

    /** {@code audit_event.exec_identity} 的列宽（VARCHAR(128)）。 */
    private static final int EXEC_IDENTITY_MAX_LENGTH = 128;

    /** {@code schema_snapshot.snapshot_ref} 的列宽（VARCHAR(512)）。 */
    private static final int SNAPSHOT_REF_MAX_LENGTH = 512;

    private static final String MARK_EXECUTING = """
            update ticket set status = 'EXECUTING', executed_at = ?
             where id = ? and status = 'APPROVED'
            """;

    /**
     * 交付失败时把状态退回 {@code APPROVED}（连同清掉 {@code executed_at}）。
     *
     * <p>只回退仍为 {@code EXECUTING} 的行：若这期间有人把它改成了别的状态（例如任务 13 的恢复），
     * 不能把它盖回 APPROVED。
     */
    private static final String MARK_BACK_TO_APPROVED = """
            update ticket set status = 'APPROVED', executed_at = null
             where id = ? and status = 'EXECUTING'
            """;

    private static final String MARK_ABORTED =
            "update ticket set status = 'FAILED', finished_at = ? where id = ?";

    private static final String MARK_FINISHED =
            "update ticket set status = ?, finished_at = ? where id = ?";

    /**
     * 回滚单成功后回写原单。
     *
     * <p><b>带状态条件</b>：只有"已执行成功/部分成功"的原单才谈得上"已回滚"。无条件更新会把一张本该
     * {@code FAILED}（或从未执行、仍 {@code APPROVED}）的原单标成"已回滚"，运维会据此认为数据已经还原。
     */
    private static final String MARK_ORIGIN_ROLLED_BACK = """
            update ticket set status = 'ROLLED_BACK', finished_at = ?
             where id = ? and status in ('SUCCEEDED', 'PARTIAL_FAILED')
            """;

    private static final String MARK_STATEMENT_RUNNING = """
            update ticket_statement set status = 'RUNNING', executed_at = ?
             where ticket_id = ? and seq_no = ?
            """;

    private static final String MARK_STATEMENT_SUCCEEDED = """
            update ticket_statement
               set status = 'SUCCEEDED', affected_rows = ?, elapsed_ms = ?, error_message = null
             where ticket_id = ? and seq_no = ?
            """;

    /**
     * 失败语句落库。
     *
     * <p><b>{@code affected_rows} 写 {@code NULL} 而不是 {@code 0}</b>：{@code 0} 是"确定改了 0 行"，而
     * {@code success=false} 的事实是"结果未知"（{@code autoCommit=true} 下 {@code executeUpdate} 返回即已提交，
     * 连接关闭阶段的异常也落到失败分支）。V3 的列注释也写着"失败或未执行时为空"。
     *
     * <p>{@code error_message} 同理只说"这条执行失败、结果可能已生效"，<b>不写"未执行"</b>。
     */
    private static final String MARK_STATEMENT_FAILED = """
            update ticket_statement
               set status = 'FAILED', affected_rows = null, elapsed_ms = ?, error_message = ?
             where ticket_id = ? and seq_no = ?
            """;

    /** 其余未执行的语句：只改仍为 {@code PENDING} 的行，绝不动已经 {@code FAILED}/{@code RUNNING} 的。 */
    private static final String MARK_REMAINING_SKIPPED =
            "update ticket_statement set status = 'SKIPPED' where ticket_id = ? and status = 'PENDING'";

    /** 提交时的结构指纹（{@code change_seq = 0}）。{@code SchemaSnapshotMapper} 只写不读，这里直读一次。 */
    private static final String SNAPSHOT_ZERO_FINGERPRINTS = """
            select table_fingerprints_json from schema_snapshot
             where ticket_id = ? and change_seq = 0
             limit 1
            """;

    private static final StatementClassifier CLASSIFIER = new StatementClassifier();

    private final JdbcTemplate jdbc;
    private final TicketMapper ticketMapper;
    private final TicketStatementMapper statementMapper;
    private final TicketEventMapper eventMapper;
    private final SchemaSnapshotMapper snapshotMapper;
    private final TicketReviewMapper reviewMapper;
    private final RiskAckService riskAckService;
    private final RiskEngine riskEngine;
    private final RiskProperties riskProperties;
    private final ExecutorClient executorClient;
    private final ConnAccountService connAccountService;
    private final AuditService auditService;
    private final UserRoleService userRoleService;
    private final TicketNotificationPublisher notifications;
    private final DatasourceExecLock lock;
    private final ThreadPoolTaskExecutor ticketExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TicketExecutionService(
            JdbcTemplate jdbc,
            TicketMapper ticketMapper,
            TicketStatementMapper statementMapper,
            TicketEventMapper eventMapper,
            SchemaSnapshotMapper snapshotMapper,
            TicketReviewMapper reviewMapper,
            RiskAckService riskAckService,
            RiskEngine riskEngine,
            RiskProperties riskProperties,
            ExecutorClient executorClient,
            ConnAccountService connAccountService,
            AuditService auditService,
            UserRoleService userRoleService,
            TicketNotificationPublisher notifications,
            DatasourceExecLock lock,
            @Qualifier("ticketExecutor") ThreadPoolTaskExecutor ticketExecutor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.ticketMapper = ticketMapper;
        this.statementMapper = statementMapper;
        this.eventMapper = eventMapper;
        this.snapshotMapper = snapshotMapper;
        this.reviewMapper = reviewMapper;
        this.riskAckService = riskAckService;
        this.riskEngine = riskEngine;
        this.riskProperties = riskProperties;
        this.executorClient = executorClient;
        this.connAccountService = connAccountService;
        this.auditService = auditService;
        this.userRoleService = userRoleService;
        this.notifications = notifications;
        this.lock = lock;
        this.ticketExecutor = ticketExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 同步入口

    /**
     * 申请执行一张已审批的工单：权限 → 取锁 → 置 {@code EXECUTING} → 提交线程池。
     *
     * <p><b>同步部分不碰目标库</b>：任何一次外部调用都可能拖住请求线程，而执行本身可能跑几十分钟。
     *
     * @param userId   点执行的人（身份只来自 JWT）
     * @param ticketId 工单 ID
     * @return 202 正文 {@code {id, status:"EXECUTING"}}
     * @throws BizException 404 工单不存在 / 409 状态不是 APPROVED 或该数据源已有工单在跑 / 403 无权执行
     */
    public ExecutionAccepted requestExecution(long userId, long ticketId) {
        TicketEntity ticket = ticketMapper.findById(ticketId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工单不存在：" + ticketId));

        if (!TicketStatus.APPROVED.name().equals(ticket.status())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "工单当前状态为 " + ticket.status() + "，只有审批通过（APPROVED）的工单可以执行");
        }

        // ADMIN 必须先拦：下面的归属判定只看 sys_system_operator，不带角色过滤，
        // 一个被误配成运维的管理员会顺着归属判定通过。
        if (ADMIN.equals(userRoleService.roleOf(userId))) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员只负责配置与授权，不参与执行链");
        }
        if (!mayExecute(ticket, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有提交人或该系统的运维/组长可以执行工单");
        }

        long datasourceId = ticket.datasourceId();
        // 取锁在任何状态变更之前：失败方必须零副作用。
        if (!lock.acquire(datasourceId, ticketId)) {
            throw new BizException(ErrorCode.CONFLICT, "该数据源有工单正在执行，请稍后重试");
        }

        boolean accepted = false;
        try {
            int moved = jdbc.update(MARK_EXECUTING, now(), ticketId);
            if (moved == 0) {
                // 读工单到改状态之间被别人改过（取消/过期）：释放锁并报 409，不留下 EXECUTING。
                throw new BizException(ErrorCode.CONFLICT, "工单状态已被改变，无法执行");
            }
            ticketExecutor.execute(() -> {
                try {
                    executeNow(ticketId, userId);
                } catch (RuntimeException e) {
                    // 任务体不允许外抛：线程池饱和时它是**在调用方线程里**跑的（InlineOrRejectHandler），
                    // 抛出去就变成对调用方的 500 —— 而那张工单可能已经执行成功。异步路径外抛则只会杀掉
                    // 工作线程，工单停在 EXECUTING 却没有任何记录。这里只记录事实，不伪造状态：
                    // "停在 EXECUTING" 的清理归任务 13 的卡死恢复。
                    eventMapper.append(ticketId, "EXEC_FAILED", userId,
                            json(detail("reason", "执行编排抛出未预期异常，工单可能仍停在 EXECUTING",
                                    "message", messageOf(e))));
                } finally {
                    // 锁必须在 finally 里释放：任何一条语句抛出都不能把这台库永久锁死。
                    lock.release(datasourceId, ticketId);
                }
            });
            accepted = true;
        } catch (RejectedExecutionException e) {
            // 线程池已停机：任务一个字都没跑（InlineOrRejectHandler 在停机时抛出而不是静默丢弃）。
            // 既然没能交付，就不能声称进入 EXECUTING —— 状态退回去，锁由下面的 finally 释放。
            jdbc.update(MARK_BACK_TO_APPROVED, ticketId);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "执行器正在停止，请稍后重试");
        } finally {
            // 只有"没交付"才在这里释放。受理之后（哪怕工作线程还没调度到它）锁归任务体所有：
            // 用"任务是否已开始跑"判会在排队窗口里提前放锁，新工单会在旧任务真正开跑之前就取得锁，
            // 单数据源并发=1 立刻失效。任务被丢弃时 execute(...) 会抛，走不到 accepted。
            if (!accepted) {
                lock.release(datasourceId, ticketId);
            }
        }
        // 202 正文里的状态取"返回那一刻"的真实值：正常异步路径恒为 EXECUTING；若线程池饱和导致本次请求
        // 线程内联跑完，这里会如实报出终态，而不是硬编码一个可能已经过期的 EXECUTING。
        return new ExecutionAccepted(ticketId, currentStatus(ticketId));
    }

    /** 工单当前状态；读不到（不可能，除非行被删）时按 {@code EXECUTING} 兜底。 */
    private String currentStatus(long ticketId) {
        List<String> statuses = jdbc.queryForList("select status from ticket where id = ?", String.class, ticketId);
        return statuses.isEmpty() ? TicketStatus.EXECUTING.name() : statuses.get(0);
    }

    /** 提交人本人，或该系统的运维/组长（{@code sys_system_operator} 有归属）。 */
    private boolean mayExecute(TicketEntity ticket, long userId) {
        if (ticket.submitterId() != null && ticket.submitterId() == userId) {
            return true;
        }
        return reviewMapper.isOperatorOf(ticket.systemId(), userId);
    }

    // ------------------------------------------------------------------ 异步执行

    /**
     * 真正的执行：七道闸 + 逐条下发 + 汇总。
     *
     * <p>包级可见：集成测试可以直接驱动它，但正常路径一律经由 {@link #requestExecution}（锁与状态迁移只在那里）。
     *
     * @param ticketId 工单 ID
     * @param operatorId 点执行的人，写入事件与审计
     */
    void executeNow(long ticketId, long operatorId) {
        TicketEntity ticket = ticketMapper.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("执行中的工单读不到了：" + ticketId));
        long datasourceId = ticket.datasourceId();
        List<String> statements = statementMapper.sqlTexts(ticketId);
        List<Classification> classifications = statements.stream().map(CLASSIFIER::classify).toList();
        List<String> tables = targetTables(classifications);

        // ---- 执行账号先解析：后面三道闸（估算、漂移、快照）与逐条执行都要用；账号缺失属于配置错误，
        //      同样必须让工单进入终态，而不是把它留在一个永远不再变化的 EXECUTING 上。
        Accounts accounts;
        try {
            accounts = resolveAccounts(datasourceId, classifications);
        } catch (BizException e) {
            abort(ticketId, operatorId, datasourceId, SNAPSHOT_FAILED,
                    detail("reason", "执行账号不可用，本次执行未下发任何语句", "message", e.getMessage()));
            return;
        }

        // ---- 闸 1：风险重算。审批之后等级可能因重新估算而上升（统计信息变化、数据量增长）。
        //      只升不降：等级下降不是中止理由（审核人已经按当时更高的风险批过）。
        List<RiskAssessment> assessments = new ArrayList<>(statements.size());
        RiskLevel currentLevel = RiskLevel.L0;
        boolean withinWindow = riskProperties.toWindow().isWithin(clock.instant());
        for (int i = 0; i < statements.size(); i++) {
            Classification classification = classifications.get(i);
            ExplainEstimate estimate;
            if (classification.ddlAction() != DdlAction.NONE) {
                // DDL 的影响面不由行数决定，EXPLAIN CREATE/ALTER/DROP 也没有意义（与提交期口径一致）。
                estimate = new ExplainEstimate(-1);
            } else {
                try {
                    estimate = executorClient.explain(
                            classification.writeStatement() ? accounts.dml() : accounts.readonly(),
                            statements.get(i));
                } catch (RuntimeException e) {
                    // 估不出来就不能证明风险没变：按最严处理，中止而不是放行。
                    abort(ticketId, operatorId, datasourceId, RISK_ESCALATED,
                            detail("reason", "无法估算当前影响行数，不能确认风险等级未上升",
                                    "message", messageOf(e)));
                    return;
                }
                if (estimate == null) {
                    estimate = new ExplainEstimate(-1);
                }
            }
            RiskAssessment assessment = riskEngine.assess(new RiskEngine.RiskRequest(
                    statements.get(i), classification, estimate, withinWindow,
                    // 本切片快照不可关；这里恒为 true。
                    true,
                    // 执行期同样没有列类型可比：收窄判定保守按 L3（与提交期一致）。
                    null));
            assessments.add(assessment);
            currentLevel = RiskLevel.stricter(currentLevel, assessment.level());
        }
        RiskLevel committedLevel = RiskLevel.valueOf(ticket.riskLevel());
        if (currentLevel.ordinal() > committedLevel.ordinal()) {
            abort(ticketId, operatorId, datasourceId, RISK_ESCALATED,
                    detail("from", committedLevel.name(), "to", currentLevel.name()));
            return;
        }

        // ---- 闸 2：语句指纹。审批后改语句 → 一条都不许发出去。
        String currentFingerprint = StatementFingerprint.of(datasourceId, statements);
        if (!currentFingerprint.equals(ticket.statementFingerprint())) {
            abort(ticketId, operatorId, datasourceId, STATEMENT_TAMPERED,
                    detail("expected", ticket.statementFingerprint(), "actual", currentFingerprint));
            return;
        }

        // ---- 闸 3：结构漂移。提交时的指纹与现在的指纹逐表比对。
        Map<String, Map<String, String>> drift =
                driftDetail(ticketId, operatorId, datasourceId, accounts.execution(), tables);
        if (drift == null) {
            return; // 已在 driftDetail 内 abort（读不到提交时指纹，或库不可达）
        }
        if (!drift.isEmpty()) {
            abort(ticketId, operatorId, datasourceId, SCHEMA_DRIFT, drift);
            return;
        }

        // ---- 闸 4：抓执行前快照。抓不到就不许执行（没有可回退的前置结构）。
        String reference = ticket.systemId() + "/" + datasourceId + "/" + ticketId + "/1";
        SnapshotResult snapshot;
        try {
            snapshot = executorClient.snapshot(accounts.execution(), tables, reference);
        } catch (RuntimeException e) {
            abort(ticketId, operatorId, datasourceId, SNAPSHOT_FAILED,
                    detail("reason", "执行前结构快照抓取失败，本次执行未下发任何语句",
                            "message", messageOf(e)));
            return;
        }
        if (snapshot == null) {
            abort(ticketId, operatorId, datasourceId, SNAPSHOT_FAILED,
                    detail("reason", "执行器未返回快照结果，本次执行未下发任何语句"));
            return;
        }
        snapshotMapper.insert(ticketId, 1, datasourceId,
                truncate(snapshot.ref() == null ? reference : snapshot.ref(), SNAPSHOT_REF_MAX_LENGTH),
                json(resolvedFingerprints(tables, snapshot.tableFingerprints())));

        // ---- 闸 5：风险确认，紧贴执行之前。
        //
        // 哈希的等级分量取"重算等级"与"存库等级"中更严者——与提交期（TicketService / TicketRollbackService）
        // 完全同一口径：提交时整单等级 = max(各条重算等级)，回滚单还会再与原单等级取更严。若这里只用
        // currentLevel，一张 L3 回滚单（语句重算 L1、存库 L3）会在提交期按 L3 算哈希、执行期按 L1 算哈希，
        // 两者永不相等 → RISK_ACK_MISSING，工单能审批却永远执行不了。
        //
        // 注意：上面的"升档就中止"（currentLevel > committedLevel）仍然只看重算结果，不能用更严者替代——
        // 那是判"风险是否变高"，不是判"哈希怎么算"。
        RiskLevel hashLevel = RiskLevel.stricter(currentLevel, committedLevel);
        String currentHash = TicketRiskSnapshot.hash(hashLevel, assessments, statements);
        try {
            riskAckService.assertExecuteAckCurrent(ticketId, currentHash);
        } catch (BizException e) {
            abort(ticketId, operatorId, datasourceId, RISK_ACK_MISSING,
                    detail("expected", currentHash, "message", e.getMessage()));
            return;
        }

        // ---- 闸 6：逐条执行（串行，失败即停）。
        int succeeded = 0;
        int failed = 0;
        Set<String> execIdentities = new LinkedHashSet<>();
        for (int i = 0; i < statements.size(); i++) {
            int seq = i + 1;
            boolean ddlStatement = classifications.get(i).ddlAction() != DdlAction.NONE;
            ConnConfig conn = ddlStatement ? accounts.ddl() : accounts.dml();
            ExecAccountType accountType = ddlStatement ? ExecAccountType.DDL : ExecAccountType.DML;
            // 审计要记"实际用的是哪个库账号"：执行前就记下来，失败的语句同样用了这个账号。
            execIdentities.add(conn.username());

            jdbc.update(MARK_STATEMENT_RUNNING, now(), ticketId, seq);
            ExecuteResult result;
            try {
                result = executorClient.execute(conn, statements.get(i), accountType);
            } catch (RuntimeException e) {
                // 执行器不可用：这条算失败，而且**无法确定它是否已经生效**。
                result = new ExecuteResult(false, 0, 0, null,
                        "执行器调用失败，结果可能已生效，请人工核对：" + messageOf(e));
            }
            if (result != null && result.success()) {
                jdbc.update(MARK_STATEMENT_SUCCEEDED, result.affectedRows(), result.elapsedMs(), ticketId, seq);
                succeeded++;
            } else {
                jdbc.update(MARK_STATEMENT_FAILED,
                        result == null ? 0L : result.elapsedMs(),
                        failedMessage(result), ticketId, seq);
                failed++;
                // 不继续跑后面的语句：一单内语句串行且有依赖，失败点之后的结果不可预期。
                break;
            }
        }

        // 其余未执行的语句置 SKIPPED（返回值就是被跳过的条数）。
        int skipped = jdbc.update(MARK_REMAINING_SKIPPED, ticketId);

        // ---- 闸 7：汇总。
        TicketStatus finalStatus = succeeded == statements.size()
                ? TicketStatus.SUCCEEDED
                : (succeeded > 0 ? TicketStatus.PARTIAL_FAILED : TicketStatus.FAILED);
        jdbc.update(MARK_FINISHED, finalStatus.name(), now(), ticketId);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("succeeded", succeeded);
        counts.put("failed", failed);
        counts.put("skipped", skipped);
        eventMapper.append(ticketId, "FINISHED", operatorId, json(finishedDetail(finalStatus, counts)));

        // §FR-08：审计同时记录"谁点的执行"（operator_id）与"用的是哪个数据库账号"（exec_identity）。
        // 两者是不同的两列，不能合并：一个是研发，一个是凭证里的账号。
        auditService.record(new AuditCommand(
                "TICKET_EXECUTED",
                operatorId,
                execIdentity(execIdentities, accounts.execution()),
                datasourceId,
                "EXECUTE",
                String.valueOf(ticketId),
                json(counts)));

        // 回滚单成功 → 原单置 ROLLED_BACK。部分失败时原单不动：没还原的数据不能被标成已回滚。
        // 更新带状态条件（只有 SUCCEEDED/PARTIAL_FAILED 的原单才可回滚），影响行数为 0 时不写事件：
        // 一张从未成功过的原单被标成"已回滚"，比不标更危险。
        if (ROLLBACK_TICKET_TYPE.equals(ticket.ticketType())
                && finalStatus == TicketStatus.SUCCEEDED
                && ticket.originTicketId() != null) {
            int rolledBack = jdbc.update(MARK_ORIGIN_ROLLED_BACK, now(), ticket.originTicketId());
            if (rolledBack > 0) {
                eventMapper.append(ticket.originTicketId(), "ROLLED_BACK", operatorId,
                        json(Map.of("rollbackTicketId", ticketId)));
            }
        }

        // 终态通知（发布即返回；投递在通知线程池里）。放在状态与审计落库之后：收件人点开短链时
        // 看到的必须是已经稳定的终态，而不是还停在 EXECUTING 的工单。消息里不带任何错误文本。
        notifications.executionFinished(ticketId, finalStatus);
    }

    /**
     * 中止：工单置 {@code FAILED} + {@code finished_at}，写事件与审计。
     *
     * <p>三条记录必须同一出口：分别写在各个闸里，早晚会出现"事件有、工单状态没改"这种半截中止。
     *
     * @param code   中止原因（五个值之一）
     * @param detail 明细；会被序列化进 {@code detail_json} 与审计载荷
     */
    private void abort(long ticketId, long operatorId, long datasourceId, String code, Map<String, ?> detail) {
        jdbc.update(MARK_ABORTED, now(), ticketId);
        String detailJson = json(Map.of("reason", code, "detail", detail));
        eventMapper.append(ticketId, "EXEC_ABORTED", operatorId, detailJson);
        auditService.record(new AuditCommand(
                "TICKET_ABORTED", operatorId, null, datasourceId, code, String.valueOf(ticketId), detailJson));
        // 中止是执行终态之一：运维需要知道"这单没跑成功"。事件类型是 TICKET_EXEC_FAILED，
        // 但不把 code/detail 塞进消息——那里面可能有 SQL 片段与库的结构信息，只留短链回详情页看。
        notifications.executionFinished(ticketId, TicketStatus.FAILED);
    }

    // ------------------------------------------------------------------ 结构漂移

    /**
     * 逐表比对"提交时指纹"与"现在指纹"。
     *
     * <p>比对规则（四条各自对应一个真实事故）：
     * <ul>
     *   <li><b>提交时为空串的表不参与比对</b>：那是"前置状态为空"（表还不存在，本单要新建它），
     *       没有可比对的旧结构，不是漂移；</li>
     *   <li><b>执行时表消失了</b>：提交时有指纹、现在没有 → 漂移（有人删了表，或本单的执行账号看不到它）；</li>
     *   <li><b>取指纹抛异常</b>：库不可达/权限不足，<b>不能</b>当成"没有表"。当成空 map 会让一次网络故障
     *       被读成"所有表都被删了"或反过来放行一次无法验证的执行——所以直接中止；</li>
     *   <li><b>提交时的指纹 JSON 解析不出来</b>：库里的那一行是提交路径自己写的规范化 JSON，读不出来说明
     *       元数据已损坏。此时"每张表的 before 都是 null"会让所有比对被跳过 → <b>闸门静默放行</b>，
     *       所以这里同样中止（fail-closed），而不是当成"提交时没有表"。</li>
     * </ul>
     *
     * @return 空 map 表示没有漂移；{@code null} 表示已经 abort（调用方直接返回）
     */
    private Map<String, Map<String, String>> driftDetail(long ticketId, long operatorId, long datasourceId,
            ConnConfig execConn, List<String> tables) {
        Map<String, String> before;
        try {
            Optional<Map<String, String>> parsed =
                    parseFingerprints(jdbc.queryForObject(SNAPSHOT_ZERO_FINGERPRINTS, String.class, ticketId));
            if (parsed.isEmpty()) {
                abort(ticketId, operatorId, datasourceId, SCHEMA_DRIFT,
                        detail("reason", "存库的提交期结构指纹无法解析，无法确认表结构未变"));
                return null;
            }
            before = parsed.get();
        } catch (EmptyResultDataAccessException e) {
            abort(ticketId, operatorId, datasourceId, SCHEMA_DRIFT,
                    detail("reason", "缺少提交时的结构指纹，无法确认表结构未变"));
            return null;
        }

        Map<String, String> now = Map.of();
        if (!tables.isEmpty()) {
            try {
                Map<String, String> returned = executorClient.fingerprint(execConn, tables);
                now = returned == null ? Map.of() : returned;
            } catch (RuntimeException e) {
                abort(ticketId, operatorId, datasourceId, SCHEMA_DRIFT,
                        detail("reason", "无法读取当前表结构（库不可达或权限不足），不能确认结构未漂移",
                                "message", messageOf(e)));
                return null;
            }
        }

        Map<String, Map<String, String>> drift = new LinkedHashMap<>();
        for (String table : tables) {
            String beforeFingerprint = before.get(table);
            if (beforeFingerprint == null || beforeFingerprint.isEmpty()) {
                // 前置状态为空：这张表在提交时还不存在（或结构没抓到），没有可比对的旧值。
                continue;
            }
            String nowFingerprint = fingerprintFor(now, table);
            if (nowFingerprint == null) {
                nowFingerprint = "";
            }
            if (!beforeFingerprint.equals(nowFingerprint)) {
                Map<String, String> change = new LinkedHashMap<>();
                change.put("before", beforeFingerprint);
                change.put("now", nowFingerprint);
                drift.put(table, change);
            }
        }
        return drift;
    }

    /**
     * 从提交时的 {@code table_fingerprints_json} 解析表名 → 指纹。
     *
     * <p>返回 {@code empty} 表示<b>解析不出来</b>，调用方必须据此中止：那个 JSON 是提交路径自己写的规范化
     * 文本，读不出来说明元数据已损坏；若把它当成空 map，每张表的 {@code before} 都会是 {@code null}，
     * 逐表比对全部被跳过——漂移闸静默放行，正是本闸要防的事。
     *
     * <p>{@code null}/空白同样视为解析失败：提交路径至少会写 {@code "{}"}。
     */
    private Optional<Map<String, String>> parseFingerprints(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            }));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** 按"精确 → 忽略大小写 → 裸表名"依次找；执行器回传的键可能是限定名或大小写不同。 */
    private static String fingerprintFor(Map<String, String> fingerprints, String table) {
        String exact = fingerprints.get(table);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(table)) {
                return entry.getValue();
            }
        }
        String bare = bareTable(table);
        for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
            if (bare != null && bare.equalsIgnoreCase(bareTable(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 与 {@code TicketSubmitValidator#normalizeTable} 同一规则：去反引号、取最后一个点之后的部分。 */
    private static String bareTable(String table) {
        if (table == null) {
            return null;
        }
        String withoutBackticks = table.replace("`", "").strip();
        int dot = withoutBackticks.lastIndexOf('.');
        String name = dot >= 0 ? withoutBackticks.substring(dot + 1) : withoutBackticks;
        return name.isBlank() ? null : name;
    }

    /** 本单涉及的表（按首次出现顺序去重，已归一的裸表名）——提交时写进 {@code change_seq=0} 的就是这组键。 */
    private static List<String> targetTables(List<Classification> classifications) {
        Set<String> tables = new LinkedHashSet<>();
        for (Classification classification : classifications) {
            String table = bareTable(classification.table());
            if (table != null) {
                tables.add(table);
            }
        }
        return List.copyOf(tables);
    }

    /** 快照返回的指纹按请求表序补齐：不存在的表记空串，与提交时的 JSON 形状保持一致。 */
    private static Map<String, String> resolvedFingerprints(List<String> tables, Map<String, String> returned) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String table : tables) {
            String fingerprint = returned == null ? null : fingerprintFor(returned, table);
            resolved.put(table, fingerprint == null ? "" : fingerprint);
        }
        return resolved;
    }

    // ------------------------------------------------------------------ 执行账号

    /** 一次执行会用到的账号。{@code ddl} 仅在含 DDL 语句时解析。 */
    private record Accounts(ConnConfig dml, ConnConfig ddl, ConnConfig readonly) {

        /** 漂移检测与快照用的"执行账号"：有 DDL 就用 ddl，否则用 dml。 */
        ConnConfig execution() {
            return ddl != null ? ddl : dml;
        }
    }

    /**
     * 按语句类型解析需要的账号。
     *
     * <p>只解析真正需要的：一张纯 DDL 工单不该因为数据源没配 dml 账号而执行不了，反之亦然。
     * 账号缺失抛 {@code BizException}（配置错误），由调用方转成中止。
     */
    private Accounts resolveAccounts(long datasourceId, List<Classification> classifications) {
        boolean needsDdl = classifications.stream().anyMatch(c -> c.ddlAction() != DdlAction.NONE);
        boolean needsDmlExecute = classifications.stream()
                .anyMatch(c -> c.ddlAction() == DdlAction.NONE);
        boolean needsReadonly = classifications.stream()
                .anyMatch(c -> !c.writeStatement() && c.ddlAction() == DdlAction.NONE);

        ConnConfig dml = (needsDmlExecute || !needsDdl)
                ? connAccountService.dmlConnConfig(datasourceId)
                : null;
        ConnConfig ddl = needsDdl ? connAccountService.ddlConnConfig(datasourceId) : null;
        ConnConfig readonly = needsReadonly ? connAccountService.readonlyConnConfig(datasourceId) : null;
        return new Accounts(dml, ddl, readonly);
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 审计里的执行身份：本次实际用过的数据库账号名。
     *
     * <p>混合工单（先 DML 后 DDL）会用到两个账号，按使用顺序以逗号连接——把两者合并成一个"平均身份"
     * 会让"哪条语句用的是哪个账号"在审计里消失。
     */
    private static String execIdentity(Set<String> identities, ConnConfig executionConn) {
        String joined = identities.isEmpty()
                ? executionConn.username()
                : String.join(",", identities);
        return truncate(joined, EXEC_IDENTITY_MAX_LENGTH);
    }

    /** 失败语句的 {@code error_message}：只说事实，不断言"没生效"。 */
    private static String failedMessage(ExecuteResult result) {
        if (result == null) {
            return "执行器未返回该条语句的结果，结果可能已生效，请人工核对";
        }
        String message = result.errorMessage();
        if (message == null || message.isBlank()) {
            return "该条语句执行失败（错误码 " + (result.errorCode() == null ? "未知" : result.errorCode())
                    + "），结果可能已生效，请人工核对";
        }
        return message;
    }

    private static Map<String, Object> finishedDetail(TicketStatus status, Map<String, Object> counts) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status.name());
        detail.putAll(counts);
        return detail;
    }

    /** 按 key/value 交替构造明细，{@code null} 值也保留（JSON 里就是 null）；参数为奇数时忽略最后一个。 */
    private static Map<String, Object> detail(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            detail.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return detail;
    }

    private static String messageOf(Throwable e) {
        return e == null || e.getMessage() == null ? "未知错误" : e.getMessage();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("执行事件/审计序列化失败", e);
        }
    }

    /**
     * 202 正文。
     *
     * @param id     工单 ID
     * @param status 同步阶段结束后的状态，恒为 {@code EXECUTING}（真正的结果异步写入）
     */
    public record ExecutionAccepted(long id, String status) {
    }
}
