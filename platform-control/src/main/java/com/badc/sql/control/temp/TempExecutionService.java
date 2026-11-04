package com.badc.sql.control.temp;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import com.badc.sql.control.org.DataSourceEntity;
import com.badc.sql.control.org.DataSourceMapper;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.Discipline;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskEngine;
import com.badc.sql.control.risk.RiskHit;
import com.badc.sql.control.risk.RiskLevel;
import com.badc.sql.control.risk.RiskProperties;
import com.badc.sql.control.risk.StatementClassifier;
import com.badc.sql.control.ticket.TicketReviewMapper;
import com.badc.sql.control.ticket.exec.DatasourceExecLock;
import com.badc.sql.control.ticket.exec.ExecAccountsResolver;
import com.badc.sql.control.user.UserRoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 运维临时执行：<b>不建工单</b>，主/备运维与组长直接对写库执行 DML/DDL。
 *
 * <h2>谁能执行什么（§4.2 / §4.3）</h2>
 * <ul>
 *   <li><b>研发与管理员一律 403</b>，而且是接口级拒绝（不是前端藏按钮）：研发的写路径只有变更工单，
 *       管理员只配置与授权、不参与执行链；</li>
 *   <li>运维必须<b>是该数据源所属系统的运维归属</b>（{@code sys_system_operator}），与工单审批同一张表、
 *       同一判据；同角色但属于别的系统同样是 403（横向越权）；</li>
 *   <li><b>L1/L2</b>：本人确认风险（{@code confirmed=true}）即执行——临时执行不使用四眼原则（§4.3-3）；</li>
 *   <li><b>L3 且提交人就是本系统组长</b>：直接执行并在审计里记 {@code selfApproved=true}。组长是该系统 L3 的
 *       最终把关人，让他等自己批会把只有一名组长的系统永久卡死；</li>
 *   <li><b>L3 且提交人不是组长</b>：本任务返回 409 {@code TEMP_EXEC_NEEDS_APPROVAL}，一条语句都不下发。
 *       转工单是后续任务（Task 3）在这个分支上做，<b>不要在这里补</b>。</li>
 * </ul>
 *
 * <h2>校验顺序是契约</h2>
 * <p>数据源为空 → 数据源不存在 → 角色 → 系统归属 → 条数上限 → 分级 → 硬规则 → L3 分流 → 自确认。
 * 前一条失败就不该看到后一条：一个研发对不存在的数据库发起请求，必须看到"研发只能通过变更工单修改数据"
 * 或"数据源不存在"，而不是"请先确认风险"。
 *
 * <h2>为什么必须逐条 EXPLAIN</h2>
 * <p>{@link RiskEngine} 把"估不出来"（{@code -1}）按最严处理（L3）。不估算时每条 DML 都会拿到 {@code -1}，
 * 于是所有 DML 都变 L3、L1/L2 直执行永远走不到，这个功能等于没用。所以除 DDL 外每条语句都要先估算；
 * 估算抛异常或返回 {@code null} 时<b>不要</b>当成放弃——保留 {@code -1} 交给引擎按最严处理。
 *
 * <p><b>写语句的 EXPLAIN 必须用 dml 账号</b>（只读账号会被 MySQL 以 1142 拒绝 → {@code -1} → 整单误判 L3），
 * 只读语句才用只读账号。DDL 不估算：它的影响面不由行数决定，{@code EXPLAIN CREATE/ALTER/DROP} 也没有意义
 * （与 {@code TicketExecutionService} 执行期同口径）。列类型收窄传 {@code null}：临时执行没有"提交时的列
 * 类型"可比，收窄判定保守按 L3。
 *
 * <h2>本类<b>不加</b> {@code @Transactional}</h2>
 * <p>三条理由，任一条都足够：
 * <ol>
 *   <li>执行语句是<b>外部 IO</b>，一次 DDL 可能几分钟。包在事务里等于让一个数据库事务活几分钟，
 *       占着连接、也占着被锁行所在的事务；</li>
 *   <li>更关键的是锁语义：{@link DatasourceExecLock#acquire} 是一句<b>独立提交</b>的 {@code INSERT}，别人立刻
 *       撞主键<b>快速失败</b>（"立即 409"）。一旦包进长事务，后来的人会<b>阻塞等待</b>到本事务提交，
 *       然后才失败——从"立即 409"退化成"卡住不动"；</li>
 *   <li>本服务没有任何"多步写库且必须同生共死"的步骤：审计走 {@link AuditService#record}（它自己管事务），
 *       锁是一行独立插入，语句由执行器逐条独立提交。</li>
 * </ol>
 *
 * <h2>DDL 先抓快照</h2>
 * <p>含 DDL 时先抓执行前结构快照，抓不到就 500 且<b>一条语句都不执行</b>。临时执行不建工单，所以不写
 * {@code schema_snapshot} 行——快照引用与逐表指纹进审计 payload，那是"执行前结构是什么样"的唯一凭据。
 * 引用形状 {@code {systemId}/{datasourceId}/temp/{uuid}} 与工单的 {@code {systemId}/{datasourceId}/{ticketId}/n}
 * 刻意分开。
 *
 * <h2>审计</h2>
 * <p>每次直执行写一条 {@code TEMP_EXECUTED}，payload 只放等级、条数、成功/失败数、快照引用与
 * {@code selfApproved}。**不放 SQL 原文，不放数据库错误文本**（错误里可能带 SQL 片段与账号）。
 */
@Service
public class TempExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TempExecutionService.class);

    /**
     * 临时执行在锁表里的合成持有者：临时执行没有工单 ID。
     *
     * <p>锁表主键是 {@code datasource_id}，互斥不依赖 {@code ticket_id}；{@code release} 的归属校验用同一个
     * -1 成立。卡死恢复任务的接管条件是 {@code acquired_at < 截止时刻}，所以新鲜的临时锁不会被抢，
     * 崩溃超时后会被正常接管。
     */
    private static final long TEMP_EXEC_LOCK_OWNER = -1L;

    /** 一次最多执行的语句条数：防止一次塞几百条把执行器与目标库压住。 */
    private static final int MAX_STATEMENTS = 50;

    private static final String DEVELOPER = "DEVELOPER";
    private static final String ADMIN = "ADMIN";
    private static final String OPS_LEAD = "OPS_LEAD";

    /** 允许临时执行的角色：主运维、备运维、组长。研发与管理员不在其中（各有专门的拒绝文案）。 */
    private static final Set<String> TEMP_EXEC_ROLES = Set.of("PRIMARY_OPS", "BACKUP_OPS", OPS_LEAD);

    /** {@code audit_event.exec_identity} 的列宽（VARCHAR(128)）。 */
    private static final int EXEC_IDENTITY_MAX_LENGTH = 128;

    /** 分类器无状态，可安全复用一个实例。 */
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();

    private final DataSourceMapper dataSourceMapper;
    private final UserRoleService userRoleService;
    private final TicketReviewMapper reviewMapper;
    private final ExecAccountsResolver accountsResolver;
    private final RiskEngine riskEngine;
    private final RiskProperties riskProperties;
    private final ExecutorClient executorClient;
    private final DatasourceExecLock execLock;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TempExecutionService(
            DataSourceMapper dataSourceMapper,
            UserRoleService userRoleService,
            TicketReviewMapper reviewMapper,
            ExecAccountsResolver accountsResolver,
            RiskEngine riskEngine,
            RiskProperties riskProperties,
            ExecutorClient executorClient,
            DatasourceExecLock execLock,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.dataSourceMapper = dataSourceMapper;
        this.userRoleService = userRoleService;
        this.reviewMapper = reviewMapper;
        this.accountsResolver = accountsResolver;
        this.riskEngine = riskEngine;
        this.riskProperties = riskProperties;
        this.executorClient = executorClient;
        this.execLock = execLock;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行一次临时执行请求（同步：语句逐条下发完才返回）。
     *
     * @param userId  操作人，身份只来自 JWT
     * @param command 目标数据源、语句与自确认标记
     * @return 200 正文：{@code {executed:true, riskLevel, selfApproved, results:[...]}}
     * @throws BizException 400 参数/条数/未确认/硬规则禁止、403 角色或归属、404 数据源不存在、
     *                      409 该库有变更正在执行或 L3 需要组长审批、500 快照抓取失败
     */
    public ExecutionResult execute(long userId, TempExecutionCommand command) {
        // ---- 1. 入参。清洗（去空白、丢空串）后为空同样算"没有要执行的语句"。
        if (command == null || command.datasourceId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少数据源 ID");
        }
        List<String> statements = cleanStatements(command.statements());
        if (statements.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "没有要执行的语句");
        }

        // ---- 2. 数据源必须存在。
        long datasourceId = command.datasourceId();
        DataSourceEntity datasource = dataSourceMapper.findById(datasourceId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "数据源不存在：" + datasourceId));

        // ---- 3. 角色闸。ADMIN 与 DEVELOPER 各有专门文案：他们不是"没权限"，是这条路径根本不给他们走。
        String role = userRoleService.roleOf(userId);
        if (DEVELOPER.equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "研发只能通过变更工单修改数据");
        }
        if (ADMIN.equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不可执行生产语句");
        }
        if (role == null || !TEMP_EXEC_ROLES.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "没有临时执行权限");
        }

        // ---- 4. 系统归属：只能在自己负责的系统上临时执行（与工单审批同一张表、同一判据）。
        if (!reviewMapper.isOperatorOf(datasource.systemId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能在自己负责的系统上临时执行");
        }

        // ---- 5. 条数上限。放在分级之前：超限的请求不该先去跑 50+ 次 EXPLAIN。
        if (statements.size() > MAX_STATEMENTS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "一次最多 " + MAX_STATEMENTS + " 条语句");
        }

        // ---- 6. 分级。
        List<Classification> classifications = statements.stream().map(CLASSIFIER::classify).toList();
        ExecAccountsResolver.Accounts accounts = accountsResolver.resolve(datasourceId, classifications);
        Grading grading = grade(statements, classifications, accounts);
        RiskLevel riskLevel = grading.level();

        // 硬规则禁止的语句（drop database、多语句拼接等）：一条都不执行。
        if (!grading.blockedRuleCodes().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "语句被硬规则禁止执行：" + String.join(",", grading.blockedRuleCodes()));
        }

        // 组长自提的 L3：他自己就是该系统 L3 的最终把关人，直接执行并留痕 selfApproved。
        boolean selfApproved = riskLevel == RiskLevel.L3 && OPS_LEAD.equals(role);

        // L3 且提交人不是组长：本任务先 409（Task 3 换成转工单）。一条语句都不下发。
        if (riskLevel == RiskLevel.L3 && !OPS_LEAD.equals(role)) {
            throw new BizException(ErrorCode.CONFLICT,
                    "TEMP_EXEC_NEEDS_APPROVAL：该变更的风险等级为 L3，需要运维组长审批后才能执行");
        }

        // 自确认闸放在分级之后：前端弹框要显示"你正在执行一条 L?_ 变更"，所以未确认的请求也要先算出等级。
        if (!command.confirmed()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请先确认风险（风险等级：" + riskLevel.name() + "）");
        }

        return executeUnderLock(userId, datasource, datasourceId, statements, classifications, accounts,
                riskLevel, selfApproved);
    }

    // ------------------------------------------------------------------ 执行

    /**
     * 取锁之后的一切：快照 → 逐条下发 → 审计，锁在 {@code finally} 里释放。
     *
     * <p>取锁在任何外部调用之前、在任何副作用之前：拿不到锁的请求必须零副作用（不抓快照、不发语句、不写审计）。
     */
    private ExecutionResult executeUnderLock(long userId, DataSourceEntity datasource, long datasourceId,
            List<String> statements, List<Classification> classifications,
            ExecAccountsResolver.Accounts accounts, RiskLevel riskLevel, boolean selfApproved) {

        if (!execLock.acquire(datasourceId, TEMP_EXEC_LOCK_OWNER)) {
            throw new BizException(ErrorCode.CONFLICT, "该数据源有变更正在执行，请稍后重试");
        }
        try {
            // ---- 结构快照：任何一条是 DDL 就必须先抓。抓不到 → 500，一条语句都不执行。
            String snapshotRef = null;
            Map<String, String> snapshotFingerprints = null;
            boolean ddlInvolved = classifications.stream()
                    .anyMatch(c -> c.ddlAction() != DdlAction.NONE);
            if (ddlInvolved) {
                List<String> tables = targetTables(classifications);
                String reference = datasource.systemId() + "/" + datasourceId + "/temp/" + UUID.randomUUID();
                SnapshotResult snapshot;
                try {
                    snapshot = executorClient.snapshot(accounts.execution(), tables, reference);
                } catch (RuntimeException e) {
                    // 没有可回退的前置结构就不能做结构变更。原始异常文本不外泄（可能含连接串与账号），
                    // 但服务端日志要留下原因，否则运维只拿到一个没有任何线索的 500。
                    log.warn("临时执行的结构快照抓取失败（数据源 {}）：{}", datasourceId, messageOf(e));
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "结构快照抓取失败，本次执行未下发任何语句");
                }
                if (snapshot == null) {
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器未返回快照结果，本次执行未下发任何语句");
                }
                snapshotRef = snapshot.ref() == null ? reference : snapshot.ref();
                // 记执行器原样返回的指纹：审计只做留存（临时执行没有 schema_snapshot 行可写），
                // 没有漂移比对要求它对齐某种形状，所以不按请求表序补空串。
                snapshotFingerprints = snapshot.tableFingerprints() == null
                        ? Map.<String, String>of()
                        : snapshot.tableFingerprints();
            }

            // ---- 逐条下发（串行，失败即停）。
            List<StatementResult> results = new ArrayList<>(statements.size());
            Set<String> execIdentities = new LinkedHashSet<>();
            int succeeded = 0;
            int failed = 0;
            for (int i = 0; i < statements.size(); i++) {
                int seq = i + 1;
                Classification classification = classifications.get(i);
                boolean ddlStatement = classification.ddlAction() != DdlAction.NONE;
                ConnConfig conn = ddlStatement
                        ? accounts.ddl()
                        : (classification.writeStatement() ? accounts.dml() : accounts.readonly());
                // 只读语句也走 DML 的超时档：ExecAccountType 只有 DML/DDL 两档，而它明显不是结构变更。
                ExecAccountType accountType = ddlStatement ? ExecAccountType.DDL : ExecAccountType.DML;
                // 审计要记"实际用的是哪个库账号"：执行前就记下来，失败的语句同样用了这个账号。
                execIdentities.add(conn.username());

                ExecuteResult result;
                try {
                    result = executorClient.execute(conn, statements.get(i), accountType);
                } catch (RuntimeException e) {
                    // 执行器不可用：这条算失败，而且**无法确定它是否已经生效**。
                    log.warn("临时执行第 {} 条语句调用执行器失败（数据源 {}）：{}", seq, datasourceId, messageOf(e));
                    result = new ExecuteResult(false, 0L, 0L, null,
                            "执行器调用失败，结果可能已生效，请人工核对");
                }

                boolean success = result != null && result.success();
                results.add(new StatementResult(
                        seq,
                        success,
                        result == null ? 0L : result.affectedRows(),
                        result == null ? 0L : result.elapsedMs(),
                        success ? null : failedMessage(result)));
                if (success) {
                    succeeded++;
                } else {
                    failed++;
                    // 不继续跑后面的语句：一单内语句串行且有依赖，失败点之后的结果不可预期。
                    break;
                }
            }

            // ---- 审计。payload 只放元数据：不放 SQL 原文，不放数据库错误文本。
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("riskLevel", riskLevel.name());
            payload.put("statementCount", statements.size());
            payload.put("succeeded", succeeded);
            payload.put("failed", failed);
            if (snapshotRef != null) {
                payload.put("snapshotRef", snapshotRef);
                // 工单路径把指纹写进 schema_snapshot；临时执行没有工单行，审计是它唯一的留存处。
                payload.put("tableFingerprints", snapshotFingerprints);
            }
            payload.put("selfApproved", selfApproved);

            auditService.record(new AuditCommand(
                    "TEMP_EXECUTED",
                    userId,
                    execIdentity(execIdentities, accounts),
                    datasourceId,
                    "execute",
                    String.valueOf(datasourceId),
                    json(payload)));

            return new ExecutionResult(true, riskLevel.name(), selfApproved, List.copyOf(results));
        } finally {
            // 锁必须在 finally 里释放：任何一条语句或审计抛出都不能把这台库永久锁死。
            execLock.release(datasourceId, TEMP_EXEC_LOCK_OWNER);
        }
    }

    // ------------------------------------------------------------------ 分级

    /**
     * 逐条分级并取最严：分类 → EXPLAIN 估算 → 风险引擎。
     *
     * <p>{@code snapshotEnabled} 恒为 {@code true}（临时执行没有"关闭快照"选项，快照是它唯一的结构安全网）；
     * {@code oldColumnTypesOrNull} 恒为 {@code null}（没有提交时的列类型可比，收窄判定保守按 L3）。
     *
     * @return 整次执行的等级与命中的 BLOCK 规则码（空表示没有被硬规则禁止的语句）
     */
    private Grading grade(List<String> statements, List<Classification> classifications,
            ExecAccountsResolver.Accounts accounts) {
        boolean withinWindow = riskProperties.toWindow().isWithin(clock.instant());
        RiskLevel level = RiskLevel.L0;
        Set<String> blockedRuleCodes = new LinkedHashSet<>();

        for (int i = 0; i < statements.size(); i++) {
            Classification classification = classifications.get(i);
            ExplainEstimate estimate;
            if (classification.ddlAction() != DdlAction.NONE) {
                // DDL 的影响面不由行数决定，EXPLAIN CREATE/ALTER/DROP 也没有意义（与工单执行期一致）。
                // 另外纯 DDL 请求根本不会解析 dml 账号，拿 accounts.dml() 去估算只会拿到 null。
                estimate = new ExplainEstimate(-1);
            } else {
                try {
                    // 写语句的 EXPLAIN 要 dml 账号：只读账号会被 MySQL 以 1142 拒绝 → -1 → 整单误判 L3。
                    estimate = executorClient.explain(
                            classification.writeStatement() ? accounts.dml() : accounts.readonly(),
                            statements.get(i));
                } catch (RuntimeException e) {
                    // 估不出来不是"放弃分级"：保留 -1，引擎自己按最严处理（方向偏严，不会错放行）。
                    log.warn("临时执行无法估算第 {} 条语句的影响行数，按最严处理：{}", i + 1, messageOf(e));
                    estimate = new ExplainEstimate(-1);
                }
                if (estimate == null) {
                    estimate = new ExplainEstimate(-1);
                }
            }

            RiskAssessment assessment = riskEngine.assess(new RiskEngine.RiskRequest(
                    statements.get(i), classification, estimate, withinWindow,
                    // 本切片快照不可关；临时执行更没有"关闭快照"这个选项。
                    true,
                    // 没有提交时的列类型可比：收窄判定保守按 L3（与工单执行期同口径）。
                    null));
            for (RiskHit hit : assessment.hits()) {
                if (hit.discipline() == Discipline.BLOCK) {
                    blockedRuleCodes.add(hit.ruleCode());
                }
            }
            level = RiskLevel.stricter(level, assessment.level());
        }
        return new Grading(level, List.copyOf(blockedRuleCodes));
    }

    // ------------------------------------------------------------------ 小工具

    /** 去掉空白项与空串；{@code null} 列表返回空列表（"没有要执行的语句"由调用方给出 400）。 */
    private static List<String> cleanStatements(List<String> statements) {
        if (statements == null) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(statements.size());
        for (String statement : statements) {
            if (statement != null && !statement.isBlank()) {
                cleaned.add(statement.strip());
            }
        }
        return cleaned;
    }

    /** 本单涉及的表（按首次出现顺序去重，已归一的裸表名）——快照按这组表抓。 */
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

    /**
     * 审计里的执行身份：本次实际用过的数据库账号名。
     *
     * <p>混合请求（先 DML 后 DDL）会用到两个账号，按使用顺序以逗号连接——合并成一个"平均身份"会让
     * "哪条语句用的是哪个账号"在审计里消失。
     */
    private static String execIdentity(Set<String> identities, ExecAccountsResolver.Accounts accounts) {
        String joined = identities.isEmpty()
                ? accounts.execution().username()
                : String.join(",", identities);
        return truncate(joined, EXEC_IDENTITY_MAX_LENGTH);
    }

    /** 失败语句的错误描述：只说事实，不断言"没生效"。 */
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

    private static String messageOf(Throwable e) {
        return e == null || e.getMessage() == null ? "未知错误" : e.getMessage();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("临时执行审计序列化失败", e);
        }
    }

    /** 逐条分级的汇总：整次执行的等级 + 命中的 BLOCK 规则码。 */
    private record Grading(RiskLevel level, List<String> blockedRuleCodes) {
    }

    /**
     * 200 正文（直执行）。
     *
     * @param executed     恒为 {@code true}：请求被直接执行了（不是转成了工单）
     * @param riskLevel    整次执行的风险等级（逐条取最严），形如 {@code L1}
     * @param selfApproved 是否为"组长自提的 L3 直执行"——审计里同样记这个值
     * @param results      逐条结果，按执行顺序；失败即停，未下发的语句不出现在这里
     */
    public record ExecutionResult(
            boolean executed,
            String riskLevel,
            boolean selfApproved,
            List<StatementResult> results) {
    }

    /**
     * 单条语句的执行结果。
     *
     * @param seq           语句序号（从 1 开始，与请求里的顺序一致）
     * @param success       是否执行成功
     * @param affectedRows  影响行数；失败时为 0（失败语句的真实影响行数未知，但这里只做回显，不落库）
     * @param elapsedMs     该条语句的耗时
     * @param errorMessage  失败原因；成功时为 {@code null}
     */
    public record StatementResult(
            int seq,
            boolean success,
            long affectedRows,
            long elapsedMs,
            String errorMessage) {
    }
}
