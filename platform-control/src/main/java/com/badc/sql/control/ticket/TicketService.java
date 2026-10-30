package com.badc.sql.control.ticket;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.Discipline;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskEngine;
import com.badc.sql.control.risk.RiskHit;
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

    private final TicketAssignmentService assignmentService;
    private final TicketStore ticketStore;
    private final ExecutorClient executorClient;
    private final ConnAccountService connAccountService;
    private final RiskEngine riskEngine;
    private final RiskProperties riskProperties;
    private final Clock clock;

    public TicketService(
            TicketAssignmentService assignmentService,
            TicketStore ticketStore,
            ExecutorClient executorClient,
            ConnAccountService connAccountService,
            RiskEngine riskEngine,
            RiskProperties riskProperties,
            Clock clock) {
        this.assignmentService = assignmentService;
        this.ticketStore = ticketStore;
        this.executorClient = executorClient;
        this.connAccountService = connAccountService;
        this.riskEngine = riskEngine;
        this.riskProperties = riskProperties;
        this.clock = clock;
    }

    /**
     * 提交一张研发变更工单。
     *
     * @param submitterId 提交人（必须是 {@code DEVELOPER}）
     * @param command     工单内容
     * @return 新工单的 ID、状态、整单等级与逐条等级（直接组装 201 响应）
     * @throws BizException 403（角色/可见性/归属）、400（校验/BLOCK/L3 无组长）、
     *                      外部依赖失败时原样上抛（库不可达绝不降级成"表不存在"）
     */
    public SubmitResult submit(long submitterId, SubmitCommand command) {
        // 1–2 步：先做最便宜的权限与静态校验，避免为一个越权请求去连目标库。
        Assignment assignment = assignmentService.assign(submitterId, command.datasourceId());
        ValidatedSubmission validated = TicketSubmitValidator.validate(command);

        // 3 步：指纹与估算都在事务外。写语句必须用 dml 账号估算：MySQL 要求 EXPLAIN UPDATE/INSERT/DELETE
        // 具备对应写权限，只读账号会拿到 1142，执行器吞成 -1，风险引擎就会把每条 DML 判成 L3。
        boolean hasDmlWrite = validated.statements().stream()
                .anyMatch(statement -> isDmlWrite(statement.classification()));
        ConnConfig dml = hasDmlWrite ? dmlConnOrRefuse(command.datasourceId()) : null;
        ConnConfig readonly = connAccountService.readonlyConnConfig(command.datasourceId());
        Map<String, String> tableFingerprints = tableFingerprints(readonly, validated.targetTables());

        boolean withinWindow = riskProperties.toWindow().isWithin(clock.instant());
        List<RiskAssessment> assessments = new ArrayList<>(validated.statements().size());
        List<RiskLevel> statementLevels = new ArrayList<>(validated.statements().size());
        RiskLevel ticketLevel = RiskLevel.L0;
        for (ValidatedStatement statement : validated.statements()) {
            ExplainEstimate estimate = estimateFor(statement, readonly, dml);
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
                throw new BizException(ErrorCode.INVALID_ARGUMENT, blockMessage(assessment));
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
        long ticketId = ticketStore.create(submitterId, command, prepared, RequestMetadata.current());

        return new SubmitResult(ticketId, TicketStatus.REVIEWING_L1, ticketLevel,
                summaries(validated, statementLevels));
    }

    /**
     * 按语句类型选估算账号，DDL 干脆不估。
     *
     * <ul>
     *   <li><b>DDL</b>（{@code ddlAction != NONE}）→ 直接 {@code -1}，<b>不发 explain</b>：DDL 的影响面不由
     *       行数决定，{@code EXPLAIN CREATE/ALTER/DROP} 本身没有意义；结构性动作恒 L3，非结构性动作按
     *       {@link DdlAction} 分档，都不需要估算，省一次网络往返。</li>
     *   <li><b>DML</b>（{@code writeStatement && ddlAction == NONE}）→ <b>dml 账号</b>。只读账号对
     *       {@code EXPLAIN UPDATE/INSERT/DELETE} 会得到 MySQL 1142，执行器吞成 {@code -1}，
     *       整张工单就被误判成 L3——L1/L2 分档在真实环境彻底失效。</li>
     *   <li><b>只读语句</b> → 只读账号。</li>
     * </ul>
     */
    private ExplainEstimate estimateFor(ValidatedStatement statement, ConnConfig readonly, ConnConfig dml) {
        if (statement.classification().ddlAction() != DdlAction.NONE) {
            return new ExplainEstimate(-1);
        }
        ConnConfig conn = statement.classification().writeStatement() ? dml : readonly;
        ExplainEstimate estimate = executorClient.explain(conn, statement.sql());
        return estimate == null ? new ExplainEstimate(-1) : estimate;
    }

    private static boolean isDmlWrite(Classification classification) {
        return classification.writeStatement() && classification.ddlAction() == DdlAction.NONE;
    }

    /**
     * 取 dml 账号；缺失时给 400 而不是让它退化成 L3。
     *
     * <p>没有 dml 账号的数据源本来也执行不了写（任务 11 需要它），提前失败比返回一个"因为估不出来所以
     * L3"的假等级更好：后者会让用户以为这条语句风险很高，而真正的问题是数据源没配好。
     */
    private ConnConfig dmlConnOrRefuse(long datasourceId) {
        try {
            return connAccountService.dmlConnConfig(datasourceId);
        } catch (BizException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "数据源 " + datasourceId + " 未配置 dml 账号，无法估算影响行数，也就无法定级");
            }
            throw e;
        }
    }

    /**
     * 取结构指纹：<b>只为实际存在的表返回值，缺失的表补空串</b>。
     *
     * <p>缺失表补 {@code ""} 与"执行器抛异常"必须严格分开：前者是"这张表还不存在，前置状态为空"，提交继续；
     * 后者是"库不可达 / 权限不足"，必须让异常上抛中止提交。把异常吞成空 map 会让一次网络故障被记成
     * "所有表都是新建的"，之后任务 11 的漂移检测就会空转通过。
     */
    private Map<String, String> tableFingerprints(ConnConfig conn, List<String> tables) {
        if (tables.isEmpty()) {
            return Map.of();
        }
        Map<String, String> returned = executorClient.fingerprint(conn, tables);
        if (returned == null) {
            // 契约要求非空 map / 异常二选一；null 属于执行器违约，按失败处理而不是"没有表"。
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器未返回结构指纹");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String table : tables) {
            String fingerprint = find(returned, table);
            resolved.put(table, fingerprint == null ? "" : fingerprint);
        }
        return resolved;
    }

    /** 执行器回传的键可能与请求的大小写/限定名不完全一致，按"精确 → 忽略大小写 → 裸表名"依次找。 */
    private static String find(Map<String, String> returned, String table) {
        String value = returned.get(table);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : returned.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(table)) {
                return entry.getValue();
            }
        }
        String bare = TicketSubmitValidator.normalizeTable(table);
        for (Map.Entry<String, String> entry : returned.entrySet()) {
            if (bare != null && bare.equalsIgnoreCase(TicketSubmitValidator.normalizeTable(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String blockMessage(RiskAssessment assessment) {
        for (RiskHit hit : assessment.hits()) {
            if (hit.discipline() == Discipline.BLOCK) {
                return hit.message();
            }
        }
        return "语句被禁止提交";
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
