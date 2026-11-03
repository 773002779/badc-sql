package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;
import com.badc.sql.control.ticket.exec.TicketExecutionService;
import com.badc.sql.control.web.RequestMetadata;

/**
 * 变更工单接口。
 *
 * <p>提交（任务 7a）、详情/列表（任务 7b）、审批链（任务 8）、执行前风险确认（任务 9）、执行申请（任务 11）
 * 与回滚发起（任务 12）挂在同一个控制器上：它们共享 {@code /api/v1/tickets} 前缀与 JWT 身份来源。业务判断
 * 全部在服务层——控制器只负责取当前用户、转发参数。
 *
 * <p><b>请求体不接收 {@code systemId}/{@code moduleId}</b>：归属由服务端从数据源与授权链推导（见
 * {@link TicketAssignmentService}），前端传得越多错得越多。同样不接收 {@code submitterId}——身份只来自
 * JWT，永不信请求体。
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketQueryService queryService;
    private final TicketReviewService reviewService;
    private final RiskAckService riskAckService;
    private final TicketExecutionService executionService;
    private final TicketRollbackService rollbackService;
    private final SchemaDiffService schemaDiffService;

    public TicketController(TicketService ticketService, TicketQueryService queryService,
            TicketReviewService reviewService, RiskAckService riskAckService,
            TicketExecutionService executionService, TicketRollbackService rollbackService,
            SchemaDiffService schemaDiffService) {
        this.ticketService = ticketService;
        this.queryService = queryService;
        this.reviewService = reviewService;
        this.riskAckService = riskAckService;
        this.executionService = executionService;
        this.rollbackService = rollbackService;
        this.schemaDiffService = schemaDiffService;
    }

    /**
     * 当前用户可见的工单，按创建时间倒序。
     *
     * @param page 页码，默认 1
     * @param size 页大小，默认 20，上限 100
     */
    @GetMapping
    public TicketQueryService.TicketPage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.list(CurrentUser.id(), page, size);
    }

    /**
     * 工单详情：存在但无权 403、不存在 404，两者严格区分。
     */
    @GetMapping("/{id}")
    public TicketQueryService.TicketDetail detail(@PathVariable long id) {
        return queryService.detail(CurrentUser.id(), id);
    }

    /**
     * 变更前后的结构对比（FR-02 验收 1）：`change_seq=1`（执行前）与 `2`（执行后）的差异。
     *
     * <p>返回逐表、逐字段的 `before/after`，例如「列 remark：新增」。还没执行过的工单没有可比对的两份快照，
     * 返回 409 并说明原因——见 `SchemaDiffService`。
     */
    @GetMapping("/{id}/schema-diff")
    public SchemaDiff schemaDiff(@PathVariable long id) {
        return schemaDiffService.diff(CurrentUser.id(), id);
    }

    /**
     * 提交一张研发变更工单。
     *
     * @return 201 + {@code {id, status, riskLevel, statements:[{seq, statementType, riskLevel}]}}
     */
    @PostMapping
    public ResponseEntity<TicketService.SubmitResult> submit(@RequestBody SubmitRequest request) {
        if (request == null || request.datasourceId() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少数据源 ID");
        }
        TicketService.SubmitResult result =
                ticketService.submit(CurrentUser.id(), request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * 审批通过。业务判定（状态、自审、归属、角色分级、乐观并发）全部在 {@link TicketReviewService}。
     *
     * @return 200 + {@code {id, status, riskLevel}}，{@code status} 是更新后的状态
     */
    @PostMapping("/{id}/approve")
    public TicketReviewService.ReviewOutcome approve(
            @PathVariable long id,
            @RequestBody(required = false) ReviewRequest request) {
        return reviewService.approve(CurrentUser.id(), id, request == null ? null : request.comment());
    }

    /**
     * 驳回。{@code comment} 必填（≥1 字），为空由服务层给出 400。
     *
     * @return 200 + {@code {id, status, riskLevel}}，{@code status} 恒为 {@code REJECTED}
     */
    @PostMapping("/{id}/reject")
    public TicketReviewService.ReviewOutcome reject(
            @PathVariable long id,
            @RequestBody(required = false) ReviewRequest request) {
        return reviewService.reject(CurrentUser.id(), id, request == null ? null : request.comment());
    }

    /** 审核请求体：只有审核意见，没有身份与级别（级别由服务端按状态与整单等级判定）。 */
    public record ReviewRequest(String comment) {
    }

    /**
     * 发起一张回滚工单（任务 12）。{@code {id}} 是<b>原单</b>的 ID。
     *
     * <p>回滚不是"撤销按钮"，而是基于原单新建一张 {@code ROLLBACK} 工单：它继承原单的归属与数据源、
     * 预填原单的回滚 SQL（运维可在请求体里补写/覆盖，DDL 原单只能这样给出语句）、重新走静态校验与风险定级，
     * 然后同样进审批——原单的成功与否只决定"能不能回滚"，不决定"回滚要不要审批"。
     *
     * <p>回滚原因必填（≥10 字）：审计与详情页要能回答"为什么回滚"，一个字的"回滚"回答不了。
     *
     * @return 201 + {@code {id, status, riskLevel, ticketType:"ROLLBACK", originTicketId}}
     */
    @PostMapping("/{id}/rollback")
    public ResponseEntity<TicketRollbackService.RollbackResult> rollback(
            @PathVariable long id,
            @RequestBody(required = false) RollbackRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请求体不能为空");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                rollbackService.rollback(CurrentUser.id(), id, request.reason(), request.rollbackSql()));
    }

    /**
     * 回滚请求体：只有原因与运维补写的 SQL，没有身份、归属或等级。
     *
     * <p>{@code rollbackSql} 可空：为空时回落到原单提交时填的 {@code rollback_sql}；两者都空由服务层给 400。
     */
    public record RollbackRequest(String reason, String rollbackSql) {
    }

    /**
     * 执行前的风险确认。
     *
     * <p><b>请求体为空</b>：阶段固定为 {@code EXECUTE}，哈希由服务端取工单当前值。任何"客户端传哈希/阶段"
     * 的设计都等于让客户端决定自己确认了什么，见 {@link RiskAckService} 的类注释。
     *
     * <p>{@code ip}/{@code user_agent} 与提交、审批一样从当前请求取（{@link RequestMetadata}，内部读
     * {@code HttpServletRequest} 的 {@code getRemoteAddr()} 与 {@code User-Agent} 并截断到列宽）。
     *
     * @return 200 + {@code {ticketId, stage, riskSnapshotHash, acknowledgedAt}}
     */
    @PostMapping("/{id}/risk-ack")
    public RiskAckService.AckOutcome acknowledgeRisk(@PathVariable long id) {
        RequestMetadata.Client client = RequestMetadata.current();
        return riskAckService.acknowledgeExecution(CurrentUser.id(), id, client.ip(), client.userAgent());
    }

    /**
     * 申请执行一张已审批的工单。
     *
     * <p><b>同步部分必须短</b>：这里只做权限、取锁与状态迁移，真正的执行由
     * {@link com.badc.sql.control.ticket.exec.TicketExecutionService} 在线程池里跑，所以返回 202 而不是
     * 等执行完再回 200——一次 DDL 可能跑一小时。
     *
     * @return 202 + {@code {id, status:"EXECUTING"}}；执行结果异步落到工单状态与语句状态
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<TicketExecutionService.ExecutionAccepted> execute(@PathVariable long id) {
        return ResponseEntity.accepted().body(executionService.requestExecution(CurrentUser.id(), id));
    }

    /**
     * 提交请求体。
     *
     * <p>{@code expectedExecuteAt} 是 ISO-8601 时刻（带 {@code Z} 或偏移），Spring Boot 的 Jackson 已注册
     * JavaTime 模块；缺省为 {@code null}，由服务层给出 400。
     */
    public record SubmitRequest(
            Long datasourceId,
            String title,
            String changeReason,
            String rollbackPlan,
            String rollbackSql,
            List<String> statements,
            Instant expectedExecuteAt) {

        SubmitCommand toCommand() {
            return new SubmitCommand(
                    datasourceId,
                    title,
                    changeReason,
                    rollbackPlan,
                    rollbackSql,
                    statements,
                    expectedExecuteAt);
        }
    }
}
