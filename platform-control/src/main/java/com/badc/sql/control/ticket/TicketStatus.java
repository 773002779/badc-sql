package com.badc.sql.control.ticket;

/**
 * 工单状态。
 *
 * <p>生命周期：草稿 → 审核 → 审批通过 → 执行 → 终态。声明顺序即生命周期顺序，便于日志与
 * 文档对齐；**顺序本身不承载迁移规则**，允许的迁移全部由 {@link TicketStateMachine} 的静态表定义。
 *
 * <p>数据库里存的是枚举名原文（{@code ticket.status VARCHAR(32)}），V3 迁移的 CHECK 约束
 * 与这里的 13 个值一一对应：改枚举必须同时改迁移，否则写入会被数据库拒绝。
 */
public enum TicketStatus {

    /** 草稿：尚未提交审核。 */
    DRAFT,
    /** 一级审核中（L1/L2 单的一级审，也是 L3 单的运维一级审）。 */
    REVIEWING_L1,
    /** 二级审核中（仅 L3 单的组长终审）。 */
    REVIEWING_L2,
    /** 审批通过，等待执行。 */
    APPROVED,
    /** 执行中。 */
    EXECUTING,
    /** 全部语句执行成功。 */
    SUCCEEDED,
    /** 部分语句失败（已有副作用，需人工决定是否回滚）。 */
    PARTIAL_FAILED,
    /** 执行失败（失败点之前可能已有副作用，需人工确认）。 */
    FAILED,
    /** 需人工确认：执行器失联/超时等无法确定结果的情况。 */
    NEEDS_MANUAL_CONFIRM,
    /** 审核驳回。 */
    REJECTED,
    /** 审批通过后超时未执行而失效。 */
    EXPIRED,
    /** 提交人撤销（仅执行前可撤销）。 */
    CANCELLED,
    /** 已回滚（原单或回滚单执行成功后到达）。 */
    ROLLED_BACK
}
