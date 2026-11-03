package com.badc.sql.control.notify;

/**
 * 通知事件类型。
 *
 * <p>两套名字各有用途，<b>不要混用</b>：
 * <ul>
 *   <li>{@link #name()} 是落库值（{@code notification.event_type VARCHAR(48)}），也是
 *       {@code notification_switch.event_type} 的主键组成部分。改枚举名等于改数据库里的历史数据，
 *       V4 的列宽 48 是给它的上限。</li>
 *   <li>{@link #label()} 只出现在给人看的标题里（"【工单已提交】订单中心"）。改文案不影响任何落库值，
 *       也不会让开关匹配不上。</li>
 * </ul>
 *
 * <p>声明顺序按业务生命周期排列：工单类（提交 → 审批 → 终审 → 执行），再是授权类（申请 → 审批）。
 * 顺序不承载任何状态机语义——状态迁移由工单自己的状态机定义，这里只负责"通知叫什么"。
 */
public enum NotificationEventType {

    /** 工单已提交，等待一级审核。 */
    TICKET_SUBMITTED("工单已提交"),
    /**
     * 工单已提交，但该系统没有主/备运维归属，改请组长代为审批（R16 兜底）。
     *
     * <p>它不是"提交"的同义词：收件人是组长而不是主备运维，文案也必须点明"缺位"，
     * 否则组长会以为自己只是普通抄送对象。发布侧在 {@code resolve(system, TICKET_SUBMITTED)}
     * 为空时把 {@link #TICKET_SUBMITTED} 替换成本类型。
     */
    TICKET_LEAD_FALLBACK("主备运维缺位，待组长代为审批"),
    /** 工单审批通过，等待执行。 */
    TICKET_APPROVED("工单已通过"),
    /** 工单被驳回。 */
    TICKET_REJECTED("工单已驳回"),
    /** L3 工单已过一级审，等待组长终审。 */
    TICKET_L3_PENDING_LEAD("工单待终审"),
    /** 工单执行完成。 */
    TICKET_EXECUTED("工单已执行"),
    /** 工单执行失败（失败点之前可能已有副作用）。 */
    TICKET_EXEC_FAILED("工单执行失败"),
    /** 收到授权申请，等待审批。 */
    GRANT_REQUESTED("授权申请"),
    /** 授权申请已通过。 */
    GRANT_APPROVED("授权已通过"),
    /** 授权申请被驳回。 */
    GRANT_REJECTED("授权已驳回");

    private final String label;

    NotificationEventType(String label) {
        this.label = label;
    }

    /** 标题里的事件中文名；只用于渲染，不落库。 */
    public String label() {
        return label;
    }
}
