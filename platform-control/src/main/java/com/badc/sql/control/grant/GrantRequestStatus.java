package com.badc.sql.control.grant;

/**
 * 授权申请的状态。落库值是枚举名（{@code grant_request.status}，与 V5 的 CHECK 一致）。
 *
 * <p>状态机是单向的：{@link #PENDING} 只能去 {@link #APPROVED} / {@link #REJECTED} / {@link #CANCELLED}，
 * 三个终态都不再变化。所有迁移都带 {@code where status = ?}（乐观并发），影响行数 0 表示"已被别人处理过"，
 * 调用方必须报 409 —— "看起来成功了"比失败更危险（与工单审批同一条纪律）。
 *
 * <p>{@link #CANCELLED} 目前没有接口写入：申请人撤回属于后续切片。保留枚举值是为了让 V5 的 CHECK 一次定好，
 * 避免为了加一个值再做一次迁移。
 */
public enum GrantRequestStatus {

    /** 待审批。 */
    PENDING,

    /** 已通过，授权已写入 {@code user_module_grant}。 */
    APPROVED,

    /** 被驳回，不写任何授权。 */
    REJECTED,

    /** 申请人撤回（本切片无写入路径，见类注释）。 */
    CANCELLED
}
