package com.badc.sql.control.grant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.security.CurrentUser;

/**
 * 授权申请与审批接口（§4.4②③）。
 *
 * <p>前端只需两个动作："我要这个模块的权限"（研发）与"批 / 驳"（系统运维）。
 * 所有业务判断在 {@link GrantRequestService}——控制器只取当前用户、转发参数。
 *
 * <p><b>请求体不接收 {@code userId}</b>：身份只来自 JWT。申请人若能从请求体指定"替谁申请"，
 * 就等于任何人都能给他人提权——这正是本切片要防的自我提权。
 *
 * <p><b>{@code scope} 是一个显式枚举而不是"自动合并"</b>：{@code mine} 与 {@code pending} 的可见范围
 * 完全不同（自己的申请 vs. 自己系统的待办），合成一个列表会让"我提的单也出现在待办里"这种误解有土壤。
 */
@RestController
@RequestMapping("/api/v1/grant-requests")
public class GrantRequestController {

    private final GrantRequestService service;

    public GrantRequestController(GrantRequestService service) {
        this.service = service;
    }

    /**
     * 提交一条授权申请。
     *
     * @return 201 + {@code {id, status:"PENDING"}}
     */
    @PostMapping
    public ResponseEntity<GrantRequestService.SubmitOutcome> submit(
            @RequestBody(required = false) SubmitRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请求体不能为空");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(CurrentUser.id(),
                new GrantRequestCommand(request.moduleId(), request.canExport(), request.reason())));
    }

    /**
     * 审批通过：写入授权并迁移状态。业务判定（状态、自审、管理员、归属、乐观并发）全在服务层。
     *
     * @return 200 + {@code {id, status:"APPROVED", canExport}}
     */
    @PostMapping("/{id}/approve")
    public GrantRequestService.ReviewOutcome approve(@PathVariable long id,
            @RequestBody(required = false) ReviewRequest request) {
        return service.approve(CurrentUser.id(), id, request == null ? null : request.comment());
    }

    /**
     * 驳回：{@code comment} 必填，为空由服务层给出 400。
     *
     * @return 200 + {@code {id, status:"REJECTED", canExport}}
     */
    @PostMapping("/{id}/reject")
    public GrantRequestService.ReviewOutcome reject(@PathVariable long id,
            @RequestBody(required = false) ReviewRequest request) {
        return service.reject(CurrentUser.id(), id, request == null ? null : request.comment());
    }

    /**
     * 申请列表。
     *
     * @param scope {@code mine}（默认，我提交的）或 {@code pending}（待我审批的）
     * @param page  页码，默认 1
     * @param size  页大小，默认 20，上限 100
     */
    @GetMapping
    public GrantRequestService.GrantPage list(
            @RequestParam(defaultValue = "mine") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long userId = CurrentUser.id();
        return switch (scope) {
            case "mine" -> service.mine(userId, page, size);
            case "pending" -> service.pending(userId, page, size);
            default -> throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "scope 只能是 mine 或 pending：" + scope);
        };
    }

    /**
     * 提交请求体。
     *
     * @param moduleId  申请的模块（必填）
     * @param canExport 是否申请导出权；缺省 {@code false}
     * @param reason    申请理由（必填，≤500 字）
     */
    public record SubmitRequest(Long moduleId, boolean canExport, String reason) {
    }

    /** 审批请求体：只有意见，没有身份与结论（结论由调用的端点决定：approve / reject）。 */
    public record ReviewRequest(String comment) {
    }
}
