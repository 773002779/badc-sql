package com.badc.sql.control.ticket;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;

/**
 * 变更工单接口。
 *
 * <p>本切片（任务 7a）只有提交 {@code POST /api/v1/tickets}；详情与列表由任务 7b 接在同一控制器上。
 *
 * <p><b>请求体不接收 {@code systemId}/{@code moduleId}</b>：归属由服务端从数据源与授权链推导（见
 * {@link TicketAssignmentService}），前端传得越多错得越多。同样不接收 {@code submitterId}——身份只来自
 * JWT，永不信请求体。
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
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
