package com.badc.sql.control.grant;

import java.time.Instant;

/**
 * {@code grant_request} 一行的只读快照。
 *
 * <p>字段与 V5 的列一一对应。时间统一是 {@link Instant}（UTC）。
 *
 * @param id            申请 ID
 * @param userId        申请人用户 ID
 * @param moduleId      申请的模块 ID
 * @param canExport     本次申请是否包含导出权
 * @param reason        申请理由
 * @param status        状态（落库字符串，非枚举：读路径不需要把所有行都当成已知状态）
 * @param reviewerId    审批人；未处理时 {@code null}
 * @param reviewComment 审批意见 / 驳回理由；未处理时 {@code null}
 * @param createdAt     申请时间（UTC）
 * @param reviewedAt    审批时间（UTC）；未处理时 {@code null}
 */
public record GrantRequestRow(
        long id,
        long userId,
        long moduleId,
        boolean canExport,
        String reason,
        String status,
        Long reviewerId,
        String reviewComment,
        Instant createdAt,
        Instant reviewedAt) {

    /** 是否仍是待审批状态。字符串比较集中在这里，避免各处散落 {@code "PENDING".equals(...)}。 */
    public boolean isPending() {
        return GrantRequestStatus.PENDING.name().equals(status);
    }
}
