package com.badc.sql.control.grant;

/**
 * 提交一条授权申请的请求体。
 *
 * <p>只有三样东西：申请哪个模块、要不要导出、为什么。没有 {@code userId}——申请人身份只来自 JWT；
 * 也没有 {@code systemId}——系统由模块推导（{@code sys_module.system_id}），前端传得越多错得越多。
 *
 * @param moduleId  申请的模块 ID（必填，由服务层校验存在性）
 * @param canExport 本次申请是否包含导出权；{@code false} 表示"只要查询 + 提单"
 * @param reason    申请理由（必填，≤500 字）；审批人要据此判断，空理由的申请等于让人猜
 */
public record GrantRequestCommand(Long moduleId, boolean canExport, String reason) {
}
