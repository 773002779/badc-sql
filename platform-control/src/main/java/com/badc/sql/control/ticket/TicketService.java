package com.badc.sql.control.ticket;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.notify.TicketNotificationPublisher;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskEngine;
import com.badc.sql.control.risk.RiskLevel;
import com.badc.sql.control.risk.RiskProperties;
import com.badc.sql.control.ticket.TicketAssignmentService.Assignment;
import com.badc.sql.control.ticket.TicketStore.PreparedTicket;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedStatement;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedSubmission;
import com.badc.sql.control.ticket.exec.StatementFingerprint;
import com.badc.sql.control.web.RequestMetadata;

/**
 * 变更工单提交编排。
 *
 * <p>顺序就是任务简报里的 B/C/D 三段，<b>前 1–6 步不写库</b>，任何一步失败都留不下半张工单：
 * <ol>
 *   <li>角色 / 可见性 / 归属推导（{@link TicketAssignmentService}）；</li>
 *   <li>语句数、BLOCK、必填项、回滚 SQL 静态检查（{@link TicketSubmitValidator}）；</li>
 *   <li><b>事务外</b>取结构指纹与 EXPLAIN 估算（{@link ExecutorClient}）；</li>
 *   <li>{@link RiskEngine} 逐条分级，整单取最严；L3 检查终审组长是否可用；</li>
 *   <li>算整单风险快照与语句指纹，交给 {@link TicketStore} 在<b>一个事务</b>里落库。</li>
 * </ol>
 *
 * <h2>事务边界</h2>
 * <p>本类<b>不标</b> {@code @Transactional}：结构指纹与 EXPLAIN 是外部调用，可能耗时数秒，包在事务里会一直
 * 占着连接与行锁。落库段在 {@link TicketStore#create} 上，它会在事务内重跑 1–2 步关掉"外部调用期间状态
 * 已变"的窗口。
 *
 * <h2>提交期没有列类型</h2>
 * <p>{@link RiskEngine.RiskRequest#oldColumnTypesOrNull()} 本期故意传 {@code null}：列类型只存在于执行前的
 * 结构快照里，提交时只有表级指纹。于是 {@code alter ... modify/change} 一律按"无法判定是否收窄"判 L3
 * ——这是刻意的保守（见 {@code PolicyValidator} 的收窄判定），不是漏传。
 */
@Service
public class TicketService {

    /** 默认工单类型：研发提交的普通变更单。 */
    private static final String NORMAL_TICKET_TYPE = "NORMAL";

    /** 运维临时执行转出的工单类型（与 V6 的 CHECK 一致，精确大小写比较）。 */
    private static final String TEMP_EXEC_TICKET_TYPE = "TEMP_EXEC";

    private final TicketAssignmentService assignmentService;
    private final TicketStore ticketStore;
    private final ExecutorClient executorClient;
    private final ConnAccountService connAccountService;
    private final RiskEngine riskEngine;
    private final RiskProperties riskProperties;
    private final TicketNotificationPublisher notifications;
    private final Clock clock;

    public TicketService(
            TicketAssignmentService assignmentService,
            TicketStore ticketStore,
            ExecutorClient executorClient,
            ConnAccountService connAccountService,
            RiskEngine riskEngine,
            RiskProperties riskProperties,
            TicketNotificationPublisher notifications,
            Clock clock) {
        this.assignmentService = assignmentService;
        this.ticketStore = ticketStore;
        this.executorClient = executorClient;
        this.connAccountService = connAccountService;
        this.riskEngine = riskEngine;
        this.riskProperties = riskProperties;
        this.notifications = notifications;
        this.clock = clock;
    }

    /**
     * 提交一张研发变更工单（{@code NORMAL}）。
     *
     * @param submitterId 提交人（必须是 {@code DEVELOPER}）
     * @param command     工单内容
     * @return 新工单的 ID、状态、整单等级与逐条等级（直接组装 201 响应）
     * @throws BizException 403（角色/可见性/归属）、400（校验/BLOCK/L3 无组长）、
     *                      外部依赖失败时原样上抛（库不可达绝不降级成"表不存在"）
     */
    public SubmitResult submit(long submitterId, SubmitCommand command) {
        return submit(submitterId, command, NORMAL_TICKET_TYPE);
    }

    /**
     * 提交一张指定类型的工单。
     *
     * <p>与 {@link #submit(long, SubmitCommand)} 唯一的差别是类型带来的两处口径：
     * {@code TEMP_EXEC} 不要求回滚 SQL / 回滚方案，且提交人按运维口径推导归属（见
     * {@link TicketAssignmentService#assignTempExecution}）。<b>整条链路（指纹、EXPLAIN、风险快照）不复制</b>：
     * 临时执行转出的工单必须由提交路径自己算指纹与快照，外部凭猜测塞进来的数据会让执行前的漂移比对失效。
     *
     * @param ticketType {@code NORMAL} / {@code TEMP_EXEC}（{@code ROLLBACK} 走回滚单自己的路径）
     */
    public SubmitResult submit(long submitterId, SubmitCommand command, String ticketType) {
        boolean tempExecution = TEMP_EXEC_TICKET_TYPE.equals(ticketType);

        // 1–2 步：先做最便宜的权限与静态校验，避免为一个越权请求去连目标库。
        Assignment assignment = tempExecution
                ? assignmentService.assignTempExecution(submitterId, command.datasourceId())
                : assignmentService.assign(submitterId, command.datasourceId());
        ValidatedSubmission validated = TicketSubmitValidator.validate(command, !tempExecution);

        // 3 步：指纹与估算都在事务外。写语句必须用 dml 账号估算：MySQL 要求 EXPLAIN UPDATE/INSERT/DELETE
        // 具备对应写权限，只读账号会拿到 1142，执行器吞成 -1，风险引擎就会把每条 DML 判成 L3。
        boolean hasDmlWrite = validated.statements().stream()
                .anyMatch(statement -> TicketPipelineSupport.isDmlWrite(statement.classification()));
        ConnConfig dml = hasDmlWrite
                ? TicketPipelineSupport.dmlConnOrRefuse(connAccountService, command.datasourceId())
                : null;
        ConnConfig readonly = connAccountService.readonlyConnConfig(command.datasourceId());
        // 跨库表引用必须在<b>取任何指纹之前</b>拒掉：归一成裸名之后，指纹与漂移比对都会落到当前库的同名表上
        // （或者被当成"表不存在"静默放行），见 TicketSubmitValidator#rejectForeignDatabases。
        TicketSubmitValidator.rejectForeignDatabases(
                validated.statements(), command.rollbackSql(), readonly.dbName());
        Map<String, String> tableFingerprints =
                TicketPipelineSupport.tableFingerprints(executorClient, readonly, validated.targetTables());

        boolean withinWindow = riskProperties.toWindow().isWithin(clock.instant());
        List<RiskAssessment> assessments = new ArrayList<>(validated.statements().size());
        List<RiskLevel> statementLevels = new ArrayList<>(validated.statements().size());
        RiskLevel ticketLevel = RiskLevel.L0;
        for (ValidatedStatement statement : validated.statements()) {
            ExplainEstimate estimate = TicketPipelineSupport.estimateFor(executorClient, statement, readonly, dml);
            RiskAssessment assessment = riskEngine.assess(new RiskEngine.RiskRequest(
                    statement.sql(),
                    statement.classification(),
                    estimate == null ? new ExplainEstimate(-1) : estimate,
                    withinWindow,
                    // 本切片提交时不可关闭快照；true 是唯一合法值。
                    true,
                    // 提交期没有列类型可比：modify/change 一律按"无法判定收窄"保守判 L3。
                    null));
            if (assessment.blocked()) {
                // 静态校验已拦过 BLOCK。这里兜底：将来分类器/校验器若分叉，也绝不把 BLOCK 落成工单。
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        TicketPipelineSupport.blockMessage(assessment));
            }
            assessments.add(assessment);
            statementLevels.add(assessment.level());
            ticketLevel = RiskLevel.stricter(ticketLevel, assessment.level());
        }

        // 4 步：L3 必须有组长可终审。先查一次给出 400；落库段会再查一次关窗口。
        if (ticketLevel == RiskLevel.L3) {
            ticketStore.requireLead(assignment.systemId());
        }

        TicketRiskSnapshot.Snapshot snapshot = TicketRiskSnapshot.of(ticketLevel, assessments);
        String riskSnapshotJson = TicketRiskSnapshot.json(snapshot);
        String riskSnapshotHash = TicketRiskSnapshot.hash(snapshot, command.statements());
        String fingerprint = StatementFingerprint.of(command.datasourceId(), command.statements());

        PreparedTicket prepared = new PreparedTicket(
                ticketLevel, statementLevels, riskSnapshotJson, riskSnapshotHash,
                fingerprint, tableFingerprints);

        // 5 步：一个事务落库；归属与静态校验在事务内重跑。
        long ticketId = ticketStore.create(submitterId, command, prepared, RequestMetadata.current(), ticketType);

        // 6 步：通知。发布点紧跟在落库之后，但<b>不等投递</b>：事件在提交后由通知线程池消费，
        // 发布本身也把异常收在 publisher 内。通知失败不能让已落库的工单看起来像提交失败。
        // L3 单在这里只通知运维一审；组长要等一审通过（R15，见 TicketReviewService）。
        notifications.submitted(ticketId);

        return new SubmitResult(ticketId, TicketStatus.REVIEWING_L1, ticketLevel,
                summaries(validated, statementLevels));
    }

    private static List<StatementSummary> summaries(ValidatedSubmission validated, List<RiskLevel> levels) {
        List<StatementSummary> summaries = new ArrayList<>(validated.statements().size());
        for (int i = 0; i < validated.statements().size(); i++) {
            ValidatedStatement statement = validated.statements().get(i);
            summaries.add(new StatementSummary(statement.seq(), statement.statementType(), levels.get(i)));
        }
        return summaries;
    }

    /**
     * 提交结果：直接对应 {@code 201} 正文。
     *
     * @param id         工单 ID
     * @param status     提交后的状态（恒为 {@code REVIEWING_L1}）
     * @param riskLevel  整单等级（各条最严）
     * @param statements 逐条序号、类型、等级
     */
    public record SubmitResult(long id, TicketStatus status, RiskLevel riskLevel,
            List<StatementSummary> statements) {

        public SubmitResult {
            statements = List.copyOf(statements);
        }
    }

    /** 响应里的单条语句摘要。 */
    public record StatementSummary(int seq, String statementType, RiskLevel riskLevel) {
    }
}
