package com.badc.sql.control.temp;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.control.security.CurrentUser;

/**
 * 运维临时执行接口：{@code POST /api/v1/temp-executions}。
 *
 * <p><b>不建工单，同步返回执行结果</b>：L1/L2 与"组长自提的 L3"当场逐条下发，结果直接出现在响应里。
 * 与工单执行（202 + 异步跑）不同，这里没有"稍后回来查状态"的地方——直执行不产生工单，结果只在响应与审计里。
 *
 * <p>控制器只做两件事：取当前用户（身份只来自 JWT，请求体里没有任何身份字段）、把请求交给
 * {@link TempExecutionService}。全部业务判断（角色、归属、分级、自确认、取锁、快照、执行、审计）都在服务层，
 * 与 {@code TicketController} 同一风格。
 */
@RestController
@RequestMapping("/api/v1/temp-executions")
public class TempExecutionController {

    private final TempExecutionService tempExecutionService;

    public TempExecutionController(TempExecutionService tempExecutionService) {
        this.tempExecutionService = tempExecutionService;
    }

    /**
     * 直接执行一次临时请求。
     *
     * @param command 目标数据源、语句与自确认标记；{@code null} 请求体按"缺少数据源 ID"处理（400）
     * @return 200 + {@code {executed:true, riskLevel, selfApproved, results:[{seq, success, affectedRows,
     *         elapsedMs, errorMessage}]}}
     */
    @PostMapping
    public TempExecutionService.ExecutionResult execute(
            @RequestBody(required = false) TempExecutionCommand command) {
        return tempExecutionService.execute(CurrentUser.id(), command);
    }
}
