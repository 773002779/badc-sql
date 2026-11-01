package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.risk.RiskLevel;
import com.badc.sql.control.ticket.TicketAssignmentService.Assignment;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedStatement;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedSubmission;
import com.badc.sql.control.web.RequestMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工单落库段：<b>一个事务</b>写 ticket / ticket_statement / schema_snapshot / ticket_event /
 * risk_acknowledgement 与审计。
 *
 * <p><b>为什么它单独存在、且外层 {@link TicketService} 不标 {@code @Transactional}</b>：提交要先做两次
 * 外部调用（结构指纹、EXPLAIN 估算），可能耗时数秒；把它们包在事务里会一直占着行锁/连接。正确顺序是
 * "事务外取外部结果 → 事务内一次写完"。
 *
 * <p><b>为什么在事务里重跑角色/可见性/归属与静态校验</b>：外部调用期间提交人可能被撤权、数据源可能被移出
 * 模块。重跑一遍纯计算与几次索引查询很便宜，却把"用提交前的旧状态落一张已不该存在的工单"的窗口关掉。
 * 重跑后用的是<b>本次</b>推导出的 system/module，而不是事务外那次的结果——否则模块歧义的选择可能与落库
 * 时的真实归属不一致。
 */
@Service
public class TicketStore {

    private static final String ROLLBACK_TICKET_TYPE = "ROLLBACK";

    /**
     * 回滚单的 {@code origin_ticket_id} / {@code rollback_reason} 在插入后补齐。
     *
     * <p>{@link TicketMapper#insert} 的列清单是提交路径的（不含这两列），而本任务不改既有 mapper；
     * 同一个事务里补一次 UPDATE 即可，代价是一条主键更新，换来的是一份写入逻辑。
     */
    private static final String SET_ROLLBACK_ORIGIN =
            "update ticket set origin_ticket_id = ?, rollback_reason = ? where id = ?";

    /** 事务内复查归属：外部调用（指纹/EXPLAIN）期间发起人可能已被移出该系统。 */
    private static final String OPERATES_SYSTEM =
            "select count(*) from sys_system_operator where system_id = ? and user_id = ?";

    private final TicketAssignmentService assignmentService;
    private final TicketMapper ticketMapper;
    private final TicketStatementMapper statementMapper;
    private final TicketEventMapper eventMapper;
    private final SchemaSnapshotMapper snapshotMapper;
    private final RiskAcknowledgementMapper acknowledgementMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public TicketStore(
            TicketAssignmentService assignmentService,
            TicketMapper ticketMapper,
            TicketStatementMapper statementMapper,
            TicketEventMapper eventMapper,
            SchemaSnapshotMapper snapshotMapper,
            RiskAcknowledgementMapper acknowledgementMapper,
            AuditService auditService,
            ObjectMapper objectMapper,
            JdbcTemplate jdbc) {
        this.assignmentService = assignmentService;
        this.ticketMapper = ticketMapper;
        this.statementMapper = statementMapper;
        this.eventMapper = eventMapper;
        this.snapshotMapper = snapshotMapper;
        this.acknowledgementMapper = acknowledgementMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    /**
     * 落一张待审工单。
     *
     * <p>前置条件：{@code prepared} 里的风险等级/快照/指纹是事务外按 {@code command.statements()} 算好的；
     * 本方法重跑静态校验并重新推导归属，保证落库时归属仍然成立。
     *
     * @return 新工单 ID
     * @throws BizException 重跑校验失败（403/400）或 L3 无组长（400）
     */
    @Transactional
    public long create(long submitterId, SubmitCommand command, PreparedTicket prepared,
            RequestMetadata.Client client) {
        Assignment assignment = assignmentService.assign(submitterId, command.datasourceId());
        ValidatedSubmission validated = TicketSubmitValidator.validate(command);
        if (prepared.ticketLevel() == RiskLevel.L3) {
            requireLead(assignment.systemId());
        }

        long ticketId = ticketMapper.insert(new TicketEntity(
                null,
                assignment.systemId(),
                assignment.moduleId(),
                command.datasourceId(),
                "NORMAL",
                TicketStatus.REVIEWING_L1.name(),
                prepared.ticketLevel().name(),
                prepared.riskSnapshotJson(),
                prepared.riskSnapshotHash(),
                command.title().strip(),
                command.changeReason().strip(),
                command.rollbackPlan(),
                command.rollbackSql(),
                prepared.statementFingerprint(),
                true,
                command.expectedExecuteAt(),
                submitterId,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        List<ValidatedStatement> statements = validated.statements();
        for (int i = 0; i < statements.size(); i++) {
            ValidatedStatement statement = statements.get(i);
            statementMapper.insert(ticketId, statement.seq(), statement.sql(), statement.statementType(),
                    prepared.statementLevels().get(i).name(), "PENDING");
        }

        // ticketId 拿到之后才能拼 snapshot_ref：{systemId}/{datasourceId}/{ticketId}/{changeSeq}。
        snapshotMapper.insert(ticketId, 0, command.datasourceId(),
                assignment.systemId() + "/" + command.datasourceId() + "/" + ticketId + "/0",
                json(prepared.tableFingerprints()));

        Map<String, Object> submittedDetail = new LinkedHashMap<>();
        submittedDetail.put("riskLevel", prepared.ticketLevel().name());
        submittedDetail.put("statementCount", statements.size());
        submittedDetail.put("datasourceId", command.datasourceId());
        submittedDetail.put("systemId", assignment.systemId());
        submittedDetail.put("moduleId", assignment.moduleId());
        eventMapper.append(ticketId, "SUBMITTED", submitterId, json(submittedDetail));

        if (assignment.ambiguous()) {
            Map<String, Object> ambiguousDetail = new LinkedHashMap<>();
            ambiguousDetail.put("chosenModuleId", assignment.moduleId());
            ambiguousDetail.put("moduleCount", assignment.candidateModuleIds().size());
            ambiguousDetail.put("candidateModuleIds", assignment.candidateModuleIds());
            eventMapper.append(ticketId, "MODULE_AMBIGUOUS", submitterId, json(ambiguousDetail));
        }

        acknowledgementMapper.insert(ticketId, "SUBMIT", submitterId,
                prepared.riskSnapshotHash(), client == null ? null : client.ip(),
                client == null ? null : client.userAgent());

        // 审计只记工单事实：等级、语句数、数据源与归属。绝不写连接串/账号/密码（研发永远看不到凭证）。
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("riskLevel", prepared.ticketLevel().name());
        auditPayload.put("statementCount", statements.size());
        auditPayload.put("datasourceId", command.datasourceId());
        auditPayload.put("systemId", assignment.systemId());
        auditPayload.put("moduleId", assignment.moduleId());
        auditService.record(new AuditCommand(
                "TICKET_SUBMITTED",
                submitterId,
                null,
                command.datasourceId(),
                "SUBMIT",
                String.valueOf(ticketId),
                json(auditPayload)));

        return ticketId;
    }

    /**
     * 落一张回滚工单（任务 12）。
     *
     * <p>与 {@link #create} 的差别只有三处，其余写入步骤（语句、结构指纹、事件、风险确认、审计）完全同构：
     * <ul>
     *   <li><b>不做归属推导</b>：{@code system_id}/{@code module_id}/{@code datasource_id} 继承原单——回滚必须打在
     *       原单那张库上，重新按发起人的授权链推导反而可能算出另一个模块；</li>
     *   <li><b>写 {@code ticket_type='ROLLBACK'} 与 {@code origin_ticket_id}/{@code rollback_reason}</b>：前者走
     *       {@link TicketMapper#insert}，后两列由本方法在同一事务里补齐（既有 mapper 的插入列清单属于提交路径，
     *       不在本任务修改范围内）；</li>
     *   <li><b>审计事件是 {@code TICKET_ROLLBACK_SUBMITTED}</b>，载荷含原单 ID；事件明细含
     *       {@code rolledBackFrom}，详情页据此把两张单串起来。</li>
     * </ul>
     *
     * <p>事务内仍然重跑两道廉价判定：发起人是否仍是该系统的运维/组长、L3 单是否还有组长可终审。外部调用
     * （结构指纹、EXPLAIN）在事务外，期间权限或人员配置都可能在别处被改。
     *
     * @param submission 回滚单的业务内容（继承自原单）
     * @param prepared   事务外算好的等级、逐条等级、风险快照、语句指纹与表指纹
     * @param client     来源 IP / User-Agent，可空
     * @return 新工单 ID
     * @throws BizException 403（发起人已不是该系统运维）或 400（L3 无组长）
     */
    @Transactional
    public long createRollback(RollbackSubmission submission, PreparedTicket prepared,
            RequestMetadata.Client client) {
        Integer operating = jdbc.queryForObject(
                OPERATES_SYSTEM, Integer.class, submission.systemId(), submission.submitterId());
        if (operating == null || operating == 0) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只能回滚自己负责的系统的工单（systemId=" + submission.systemId() + "）");
        }
        if (prepared.ticketLevel() == RiskLevel.L3) {
            requireLead(submission.systemId());
        }

        long ticketId = ticketMapper.insert(new TicketEntity(
                null,
                submission.systemId(),
                submission.moduleId(),
                submission.datasourceId(),
                ROLLBACK_TICKET_TYPE,
                TicketStatus.REVIEWING_L1.name(),
                prepared.ticketLevel().name(),
                prepared.riskSnapshotJson(),
                prepared.riskSnapshotHash(),
                submission.title(),
                submission.changeReason(),
                submission.rollbackPlan(),
                // 回滚单自己的"反向 SQL"并不存在（再回滚一次原变更不是业务动作），语句原文只落 ticket_statement。
                null,
                prepared.statementFingerprint(),
                true,
                submission.expectedExecuteAt(),
                submission.submitterId(),
                // 剩余列由数据库默认值/本方法的 UPDATE 补齐：insert 的列清单不含 origin/rollback_reason，
                // 时间戳列走 DEFAULT CURRENT_TIMESTAMP(3)。
                null,
                null,
                null,
                null,
                null,
                null,
                null));
        jdbc.update(SET_ROLLBACK_ORIGIN, submission.originTicketId(), submission.rollbackReason(), ticketId);

        List<ValidatedStatement> statements = submission.statements();
        for (int i = 0; i < statements.size(); i++) {
            ValidatedStatement statement = statements.get(i);
            statementMapper.insert(ticketId, statement.seq(), statement.sql(), statement.statementType(),
                    prepared.statementLevels().get(i).name(), "PENDING");
        }

        // 回滚的基线是"现在"：抓原单这些表的当前指纹，执行前的漂移比对才不会把原单自己造成的结构变化
        // 当成漂移。
        snapshotMapper.insert(ticketId, 0, submission.datasourceId(),
                submission.systemId() + "/" + submission.datasourceId() + "/" + ticketId + "/0",
                json(prepared.tableFingerprints()));

        Map<String, Object> submittedDetail = new LinkedHashMap<>();
        submittedDetail.put("riskLevel", prepared.ticketLevel().name());
        submittedDetail.put("statementCount", statements.size());
        submittedDetail.put("datasourceId", submission.datasourceId());
        submittedDetail.put("systemId", submission.systemId());
        submittedDetail.put("moduleId", submission.moduleId());
        submittedDetail.put("rolledBackFrom", submission.originTicketId());
        eventMapper.append(ticketId, "SUBMITTED", submission.submitterId(), json(submittedDetail));

        acknowledgementMapper.insert(ticketId, "SUBMIT", submission.submitterId(),
                prepared.riskSnapshotHash(), client == null ? null : client.ip(),
                client == null ? null : client.userAgent());

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("originTicketId", submission.originTicketId());
        auditPayload.put("riskLevel", prepared.ticketLevel().name());
        auditPayload.put("statementCount", statements.size());
        auditPayload.put("datasourceId", submission.datasourceId());
        auditPayload.put("systemId", submission.systemId());
        auditPayload.put("moduleId", submission.moduleId());
        auditService.record(new AuditCommand(
                "TICKET_ROLLBACK_SUBMITTED",
                submission.submitterId(),
                null,
                submission.datasourceId(),
                // 动作与提交区分开：审计里"回滚单被发起"与"普通工单被提交"必须是两类事件。
                "ROLLBACK",
                String.valueOf(ticketId),
                json(auditPayload)));

        return ticketId;
    }

    /** §4.7-6：L3 工单若该系统没有组长，提交时就拦下，而不是让它卡在 REVIEWING_L2 无人可审。 */
    public void requireLead(long systemId) {
        if (ticketMapper.countLeads(systemId) == 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "该系统未配置运维组长，L3 工单无人终审");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件/快照序列化失败", e);
        }
    }

    /**
     * 一张回滚工单的业务内容（事务外由 {@code TicketRollbackService} 组装）。
     *
     * <p>{@code systemId}/{@code moduleId}/{@code datasourceId} 一律继承原单：回滚必须落在原变更的那张库上。
     * {@code statements} 是按 {@link ValidatedStatement} 校验过的回滚语句（seq 从 1 开始），与
     * {@link PreparedTicket#statementLevels()} 同序。
     *
     * @param originTicketId  被回滚的原工单
     * @param submitterId     发起回滚的运维/组长
     * @param systemId        原单归属系统
     * @param moduleId        原单发起模块
     * @param datasourceId    原单目标数据源
     * @param title           新工单标题
     * @param changeReason    变更原因（回滚原因原文）
     * @param rollbackPlan    原单的回滚方案文本（留档）
     * @param rollbackReason  回滚原因（写入 {@code ticket.rollback_reason}）
     * @param expectedExecuteAt 期望执行时间（回滚应当尽快执行，通常取"现在"）
     * @param statements      回滚语句（已通过 BLOCK 与表名命中校验）
     */
    public record RollbackSubmission(
            long originTicketId,
            long submitterId,
            long systemId,
            long moduleId,
            long datasourceId,
            String title,
            String changeReason,
            String rollbackPlan,
            String rollbackReason,
            Instant expectedExecuteAt,
            List<ValidatedStatement> statements) {

        public RollbackSubmission {
            statements = List.copyOf(statements);
        }
    }

    /**
     * 事务外算好、事务内直接落库的派生结果。
     *
     * @param ticketLevel         整单等级
     * @param statementLevels     每条语句的等级（与命令里的语句同序）
     * @param riskSnapshotJson    整单风险快照规范化 JSON
     * @param riskSnapshotHash    整单风险快照哈希
     * @param statementFingerprint 语句指纹
     * @param tableFingerprints   表名 → 结构指纹（缺失表为空串）
     */
    public record PreparedTicket(
            RiskLevel ticketLevel,
            List<RiskLevel> statementLevels,
            String riskSnapshotJson,
            String riskSnapshotHash,
            String statementFingerprint,
            Map<String, String> tableFingerprints) {

        public PreparedTicket {
            statementLevels = List.copyOf(statementLevels);
            // 不能 Map.copyOf：它的迭代顺序不保证，而 table_fingerprints_json 的字节序要参与哈希比对。
            tableFingerprints = tableFingerprints == null || tableFingerprints.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(tableFingerprints));
        }
    }
}
