package com.badc.sql.control.temp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.security.CurrentUser;

/**
 * 运维临时执行接口：{@code POST /api/v1/temp-executions}。
 *
 * <p><b>两条出口，两种状态码</b>：
 * <ul>
 *   <li>当场执行（L1/L2 与"组长自提的 L3"）→ <b>200</b> + 逐条执行结果。没有"稍后回来查状态"的地方：
 *       直执行不产生工单，结果只在响应与审计里；</li>
 *   <li>L3 且提交人不是组长 → <b>202</b> + {@code {executed:false, ticketId, riskLevel, status}}：
 *       请求变成了一张等组长审批的工单，一条语句都没下发。202 与工单执行的语义一致（"已受理、尚未生效"）。</li>
 * </ul>
 *
 * <p>控制器只做三件事：取当前用户（身份只来自 JWT，请求体里没有任何身份字段）、把请求交给
 * {@link TempExecutionService}、按结果类型选状态码。全部业务判断（角色、归属、分级、自确认、取锁、快照、
 * 执行、审计、转单）都在服务层，与 {@code TicketController} 同一风格。
 */
@RestController
@RequestMapping("/api/v1/temp-executions")
public class TempExecutionController {

    private final TempExecutionService tempExecutionService;

    public TempExecutionController(TempExecutionService tempExecutionService) {
        this.tempExecutionService = tempExecutionService;
    }

    /**
     * 执行一次临时请求，或把它转成一张待审批的工单。
     *
     * @param command 目标数据源、语句与自确认标记；{@code null} 请求体按"缺少数据源 ID"处理（400）
     * @return 直执行：200 + {@code {executed:true, riskLevel, selfApproved, results:[{seq, success,
     *         affectedRows, elapsedMs, errorMessage}]}}；
     *         L3 非组长：202 + {@code {executed:false, ticketId, riskLevel, status}}
     */
    @PostMapping
    public ResponseEntity<Object> execute(@RequestBody(required = false) TempExecutionCommand command) {
        TempExecutionService.Outcome outcome = tempExecutionService.execute(CurrentUser.id(), command);
        if (outcome instanceof TempExecutionService.TicketPending pending) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(pending);
        }
        return ResponseEntity.ok(outcome);
    }
}
