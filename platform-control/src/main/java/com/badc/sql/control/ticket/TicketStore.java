package com.badc.sql.control.ticket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final TicketAssignmentService assignmentService;
    private final TicketMapper ticketMapper;
    private final TicketStatementMapper statementMapper;
    private final TicketEventMapper eventMapper;
    private final SchemaSnapshotMapper snapshotMapper;
    private final RiskAcknowledgementMapper acknowledgementMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public TicketStore(
            TicketAssignmentService assignmentService,
            TicketMapper ticketMapper,
            TicketStatementMapper statementMapper,
            TicketEventMapper eventMapper,
            SchemaSnapshotMapper snapshotMapper,
            RiskAcknowledgementMapper acknowledgementMapper,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.assignmentService = assignmentService;
        this.ticketMapper = ticketMapper;
        this.statementMapper = statementMapper;
        this.eventMapper = eventMapper;
        this.snapshotMapper = snapshotMapper;
        this.acknowledgementMapper = acknowledgementMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
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
