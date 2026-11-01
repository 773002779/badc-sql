package com.badc.sql.control.ticket;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.common.sql.SqlText;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.Discipline;
import com.badc.sql.control.risk.PolicyValidator;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskEngine;
import com.badc.sql.control.risk.RiskHit;
import com.badc.sql.control.risk.RiskLevel;
import com.badc.sql.control.risk.RiskProperties;
import com.badc.sql.control.risk.StatementClassifier;
import com.badc.sql.control.ticket.TicketStore.PreparedTicket;
import com.badc.sql.control.ticket.TicketStore.RollbackSubmission;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedStatement;
import com.badc.sql.control.ticket.exec.StatementFingerprint;
import com.badc.sql.control.user.UserRoleService;
import com.badc.sql.control.web.RequestMetadata;

/**
 * 回滚工单的发起编排（任务 12）：回滚不是"撤销按钮"，而是<b>一张基于原单的新工单</b>。
 *
 * <p>为什么不直接复用 {@link TicketService#submit}：那条路径按研发的授权链推导 system/module、要求研发角色，
 * 并要求提交人自己写 {@code rollbackSql}。回滚恰恰相反——发起人是运维/组长，归属与"要回滚什么"都继承自原单。
 * 于是这里只复用它的<b>判定口径</b>（静态校验、BLOCK、表名命中、EXPLAIN 定级、整单取最严）与落库段
 * （{@link TicketStore#createRollback}），不复制任何写库逻辑。
 *
 * <h2>判定顺序不可调换</h2>
 * <ol>
 *   <li>读原单，不存在 → <b>404</b>；</li>
 *   <li><b>ADMIN 先于归属</b> → 403：管理员只配置与授权，即使被误配成某个系统的运维也不参与执行链
 *       （与任务 9/11 同一条口径）；</li>
 *   <li>角色必须是运维/组长 → 403（研发无权发起回滚）；</li>
 *   <li>{@code sys_system_operator} 必须有所属 → 403（跨系统不行）；</li>
 *   <li>原单类型不能是 {@code ROLLBACK} → 409：不允许对回滚单再发回滚（否则"回滚的回滚"会绕开原变更）；</li>
 *   <li>原单状态必须是 {@code SUCCEEDED}/{@code PARTIAL_FAILED} → 409（"还没跑过"就没有可回滚的东西）；</li>
 *   <li>回滚原因 ≥ 10 字 → 400；</li>
 *   <li>回滚语句来源：请求体优先，其次原单的 {@code rollback_sql}；两者都空 → 400；</li>
 *   <li>逐条 BLOCK 校验与表名命中校验 → 400；</li>
 *   <li>逐条 EXPLAIN + {@link RiskEngine} 定级，整单取最严，再与原单等级 {@link RiskLevel#stricter} 取更严；</li>
 *   <li>整单 L3 时该系统必须仍有组长 → 400；</li>
 *   <li>算快照/指纹，交给 {@link TicketStore#createRollback} 在<b>一个事务</b>里落库。</li>
 * </ol>
 *
 * <h2>事务边界</h2>
 * <p>与提交路径相同：本类<b>不标</b> {@code @Transactional}。结构指纹与 EXPLAIN 是外部调用，包在事务里会一直
 * 占着连接；落库段在 {@code TicketStore} 上，并在事务内复查归属与组长可用性。
 *
 * <h2>为什么风险要"继承 + 重算"取更严</h2>
 * <p>原单若是 L3，说明它当初被判定为需要组长终审的结构性/高风险变更；回滚一条 L3 变更同样可能重建表结构，
 * 只按回滚语句重算会把它降成 L1——那等于用"反向操作看起来更小"绕过了终审。所以整单等级取两者更严者。
 */
@Service
public class TicketRollbackService {

    /** 与 {@code TicketReviewService} 一致：管理员不参与执行链。 */
    private static final String ADMIN = "ADMIN";

    /** 能发起回滚的角色：该系统的主运维、备运维、组长。研发不在其中。 */
    private static final Set<String> OPERATOR_ROLES = Set.of("PRIMARY_OPS", "BACKUP_OPS", "OPS_LEAD");

    /** 只有真正跑过（含部分成功）的原单才有可回滚的变更。 */
    private static final Set<String> ROLLBACKABLE_STATUSES = Set.of(
            TicketStatus.SUCCEEDED.name(), TicketStatus.PARTIAL_FAILED.name());

    private static final String ROLLBACK_TICKET_TYPE = "ROLLBACK";

    /** {@code ticket.rollback_reason} 的下限（与简报 §C 一致）。 */
    private static final int MIN_REASON_LENGTH = 10;

    /** {@code ticket.title} 的列宽（VARCHAR(200)）。 */
    private static final int TITLE_MAX_LENGTH = 200;

    /** 分类器 / 校验器都是纯函数、无状态，可安全复用（与 {@code TicketSubmitValidator} 的写法一致）。 */
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();
    private static final PolicyValidator VALIDATOR = new PolicyValidator();

    private final TicketMapper ticketMapper;
    private final TicketStatementMapper statementMapper;
    private final TicketReviewMapper reviewMapper;
    private final UserRoleService userRoleService;
    private final TicketStore ticketStore;
    private final ExecutorClient executorClient;
    private final ConnAccountService connAccountService;
    private final RiskEngine riskEngine;
    private final RiskProperties riskProperties;
    private final Clock clock;

    public TicketRollbackService(
            TicketMapper ticketMapper,
            TicketStatementMapper statementMapper,
            TicketReviewMapper reviewMapper,
            UserRoleService userRoleService,
            TicketStore ticketStore,
            ExecutorClient executorClient,
            ConnAccountService connAccountService,
            RiskEngine riskEngine,
            RiskProperties riskProperties,
            Clock clock) {
        this.ticketMapper = ticketMapper;
        this.statementMapper = statementMapper;
        this.reviewMapper = reviewMapper;
        this.userRoleService = userRoleService;
        this.ticketStore = ticketStore;
        this.executorClient = executorClient;
        this.connAccountService = connAccountService;
        this.riskEngine = riskEngine;
        this.riskProperties = riskProperties;
        this.clock = clock;
    }

    /**
     * 发起一张回滚工单。
     *
     * @param operatorId             发起人（必须是原单所属系统的运维或组长）
     * @param originTicketId         被回滚的原工单
     * @param reason                 回滚原因（≥10 字）
     * @param suppliedRollbackSqlOrNull 运维补写/编辑的回滚 SQL；为空时回落到原单的 {@code rollback_sql}
     * @return 新工单的 ID、状态、等级、类型与原单 ID（直接组装 201 响应）
     * @throws BizException 404 / 403 / 409 / 400，语义见类注释
     */
    public RollbackResult rollback(long operatorId, long originTicketId, String reason,
            String suppliedRollbackSqlOrNull) {
        TicketEntity origin = ticketMapper.findById(originTicketId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "工单不存在：" + originTicketId));

        // 角色闸：ADMIN 先于归属（被误配进 sys_system_operator 的管理员也不能回滚）。
        String role = userRoleService.roleOf(operatorId);
        if (ADMIN.equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "管理员只负责配置与授权，不参与回滚");
        }
        if (role == null || !OPERATOR_ROLES.contains(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有该系统的运维或组长可以发起回滚");
        }
        if (!reviewMapper.isOperatorOf(origin.systemId(), operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只能回滚自己负责的系统的工单（systemId=" + origin.systemId() + "）");
        }

        // 类型先于状态：一张已成功的回滚单本身也满足"SUCCEEDED"，但绝不能再被回滚一次。
        if (ROLLBACK_TICKET_TYPE.equals(origin.ticketType())) {
            throw new BizException(ErrorCode.CONFLICT, "回滚工单不能再被回滚：请直接对原变更重新提单");
        }
        if (!ROLLBACKABLE_STATUSES.contains(origin.status())) {
            throw new BizException(ErrorCode.CONFLICT,
                    "原单当前状态为 " + origin.status() + "，只有执行成功或部分成功（SUCCEEDED/PARTIAL_FAILED）的工单可以回滚");
        }

        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.length() < MIN_REASON_LENGTH) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "回滚原因至少 " + MIN_REASON_LENGTH + " 个字，当前 " + normalizedReason.length() + " 个");
        }

        // 回滚语句的唯一两个合法来源：①运维补写 ②原单提交时填的。系统不生成任何 SQL。
        String effectiveSql = isBlank(suppliedRollbackSqlOrNull) ? origin.rollbackSql() : suppliedRollbackSqlOrNull;
        if (isBlank(effectiveSql)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "原单未填写回滚 SQL，请提供回滚语句");
        }

        List<String> rollbackTexts = SqlText.splitStatements(effectiveSql);
        if (rollbackTexts.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "回滚 SQL 无法解析为有效语句");
        }
        if (rollbackTexts.size() > TicketSubmitValidator.MAX_STATEMENTS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "单工单语句上限 " + TicketSubmitValidator.MAX_STATEMENTS + " 条，回滚 SQL 切出 "
                            + rollbackTexts.size() + " 条");
        }

        // 原单涉及的表：回滚语句必须命中其中之一，否则会把另一张表改回去。
        List<String> originTables = originTargetTables(originTicketId);
        List<ValidatedStatement> rollbackStatements = validateRollbackSql(rollbackTexts, originTables);

        // 风险与指纹都在事务外：与任务 7a 完全同一条路径（DML 用 dml 账号估算、DDL 不估）。
        boolean hasDmlWrite = rollbackStatements.stream()
                .anyMatch(statement -> isDmlWrite(statement.classification()));
        ConnConfig dml = hasDmlWrite ? dmlConnOrRefuse(origin.datasourceId()) : null;
        ConnConfig readonly = connAccountService.readonlyConnConfig(origin.datasourceId());
        // 回滚的基线是"现在"：抓原单这些表的当前指纹。
        Map<String, String> tableFingerprints = tableFingerprints(readonly, originTables);

        boolean withinWindow = riskProperties.toWindow().isWithin(clock.instant());
        List<RiskAssessment> assessments = new ArrayList<>(rollbackStatements.size());
        List<RiskLevel> statementLevels = new ArrayList<>(rollbackStatements.size());
        RiskLevel ticketLevel = RiskLevel.L0;
        for (ValidatedStatement statement : rollbackStatements) {
            ExplainEstimate estimate = estimateFor(statement, readonly, dml);
            RiskAssessment assessment = riskEngine.assess(new RiskEngine.RiskRequest(
                    statement.sql(),
                    statement.classification(),
                    estimate,
                    withinWindow,
                    // 本切片快照不可关；回滚单同样必须留结构基线。
                    true,
                    // 与提交期一致：没有旧列类型可比，modify/change 保守按 L3。
                    null));
            if (assessment.blocked()) {
                // 静态校验已拦过 BLOCK；这里兜底，绝不把 BLOCK 语句落成工单。
                throw new BizException(ErrorCode.INVALID_ARGUMENT, blockMessage(assessment));
            }
            assessments.add(assessment);
            statementLevels.add(assessment.level());
            ticketLevel = RiskLevel.stricter(ticketLevel, assessment.level());
        }
        // 原单 L3 → 回滚同样 L3（§FR-15：回滚一次高风险变更也要组长终审）。
        ticketLevel = RiskLevel.stricter(ticketLevel, RiskLevel.valueOf(origin.riskLevel()));

        if (ticketLevel == RiskLevel.L3) {
            ticketStore.requireLead(origin.systemId());
        }

        TicketRiskSnapshot.Snapshot snapshot = TicketRiskSnapshot.of(ticketLevel, assessments);
        String riskSnapshotJson = TicketRiskSnapshot.json(snapshot);
        String riskSnapshotHash = TicketRiskSnapshot.hash(snapshot, rollbackTexts);
        String fingerprint = StatementFingerprint.of(origin.datasourceId(), rollbackTexts);

        PreparedTicket prepared = new PreparedTicket(
                ticketLevel, statementLevels, riskSnapshotJson, riskSnapshotHash,
                fingerprint, tableFingerprints);

        RollbackSubmission submission = new RollbackSubmission(
                origin.id(),
                operatorId,
                origin.systemId(),
                origin.moduleId(),
                origin.datasourceId(),
                rollbackTitle(origin),
                normalizedReason,
                origin.rollbackPlan(),
                normalizedReason,
                // 回滚应当尽快执行：默认"现在"。
                clock.instant(),
                rollbackStatements);

        long ticketId = ticketStore.createRollback(submission, prepared, RequestMetadata.current());
        return new RollbackResult(ticketId, TicketStatus.REVIEWING_L1, ticketLevel,
                ROLLBACK_TICKET_TYPE, origin.id());
    }

    // ------------------------------------------------------------------ 静态校验

    /**
     * 逐条校验回滚语句：不得 BLOCK，且每条的作用表都必须命中原单语句里出现过的表。
     *
     * <p>表名与 {@code TicketSubmitValidator} 用同一套归一规则（{@link TicketSubmitValidator#normalizeTable}），
     * 所以 {@code `order_db`.`t_order`} 与 {@code t_order} 会被认成同一张表。
     */
    private static List<ValidatedStatement> validateRollbackSql(List<String> rollbackTexts, List<String> originTables) {
        Set<String> originTableSet = new LinkedHashSet<>(originTables);
        List<ValidatedStatement> validated = new ArrayList<>(rollbackTexts.size());
        int seq = 1;
        for (String sql : rollbackTexts) {
            Classification classification = CLASSIFIER.classify(sql);
            throwOnBlock(sql, classification);

            String table = TicketSubmitValidator.normalizeTable(classification.table());
            if (table == null || !originTableSet.contains(table)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "回滚 SQL 未命中目标表：原单涉及 " + originTableSet
                                + "，回滚语句为 " + (table == null ? "（无法识别表名）" : table));
            }
            validated.add(new ValidatedStatement(seq++, sql, classification));
        }
        return validated;
    }

    /** 原单各条语句涉及的表（按首次出现顺序去重、已归一）。 */
    private List<String> originTargetTables(long originTicketId) {
        Set<String> tables = new LinkedHashSet<>();
        for (String sql : statementMapper.sqlTexts(originTicketId)) {
            String table = TicketSubmitValidator.normalizeTable(CLASSIFIER.classify(sql).table());
            if (table != null) {
                tables.add(table);
            }
        }
        return List.copyOf(tables);
    }

    /** 任一 {@link Discipline#BLOCK} 命中都让回滚失败；消息用命中自带的说明（含具体原因）。 */
    private static void throwOnBlock(String sql, Classification classification) {
        for (RiskHit hit : VALIDATOR.validate(sql, classification, null)) {
            if (hit.discipline() == Discipline.BLOCK) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, hit.message());
            }
        }
    }

    // ------------------------------------------------------------------ 定级与指纹

    /**
     * 按语句类型选估算账号，DDL 不估——与 {@link TicketService} 提交期的口径完全一致。
     *
     * <p>DML 必须用 dml 账号：只读账号对 {@code EXPLAIN UPDATE/INSERT/DELETE} 会被 MySQL 以 1142 拒绝，
     * 执行器吞成 {@code -1}，回滚单就会被误判成 L3。
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

    /** 与 {@code TicketService} 相同：dml 账号缺失时给出 400，而不是让每条 DML 静默退化成 L3。 */
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
     * 取结构指纹：只为实际存在的表返回值，缺失的表补空串（"前置状态为空"）。
     *
     * <p>与 {@code TicketService} 一样严格区分"表不存在"（空串，继续）与"执行器抛异常"（上抛，中止）：
     * 把故障吞成空 map 会让一次网络故障被记成"所有表都是新建的"，之后执行前的漂移检测就会空转通过。
     */
    private Map<String, String> tableFingerprints(ConnConfig conn, List<String> tables) {
        if (tables.isEmpty()) {
            return Map.of();
        }
        Map<String, String> returned = executorClient.fingerprint(conn, tables);
        if (returned == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器未返回结构指纹");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String table : tables) {
            String fingerprint = find(returned, table);
            resolved.put(table, fingerprint == null ? "" : fingerprint);
        }
        return resolved;
    }

    /** 执行器回传的键可能与请求的大小写/限定名不一致，按"精确 → 忽略大小写 → 裸表名"依次找。 */
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
        return "回滚语句被禁止提交";
    }

    // ------------------------------------------------------------------ 小工具

    /** 回滚单标题：保留原单标题以便详情页一眼看出"回滚的是哪一单"，并截断到列宽。 */
    private static String rollbackTitle(TicketEntity origin) {
        String base = origin.title() == null ? "" : origin.title().strip();
        String title = base.isEmpty() ? "回滚工单 #" + origin.id() : "回滚：" + base;
        return title.length() <= TITLE_MAX_LENGTH ? title : title.substring(0, TITLE_MAX_LENGTH);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 回滚发起结果：直接对应 {@code 201} 正文。
     *
     * @param id             新工单 ID
     * @param status         新工单状态（恒为 {@code REVIEWING_L1}：回滚同样要审批）
     * @param riskLevel      整单等级（回滚语句重算与原单等级取更严）
     * @param ticketType     恒为 {@code ROLLBACK}
     * @param originTicketId 被回滚的原工单
     */
    public record RollbackResult(long id, TicketStatus status, RiskLevel riskLevel,
            String ticketType, long originTicketId) {
    }
}
