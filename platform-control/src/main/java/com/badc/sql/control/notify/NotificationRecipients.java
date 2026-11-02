package com.badc.sql.control.notify;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 事件接收人解析：某个系统上"谁该收到这条通知"。
 *
 * <p>规则（FR-13）：<b>基线 = 主运维 + 备运维</b>（{@code operator_type in ('PRIMARY','BACKUP')}）；
 * 只有 L3 待终审（{@link NotificationEventType#TICKET_L3_PENDING_LEAD}）才并入组长（{@code LEAD}）。
 * 基线<b>不能</b>写成"取该系统全部归属行"：那样组长会收到所有普通事件的通知，而"L3 再并入 LEAD"
 * 也就成了空操作——计划原文的这处自相矛盾已按 FR-13 裁决（R10）。
 *
 * <p>判据是 {@code sys_system_operator} 里的归属<b>事实</b>，不是 {@code sys_user.role}：
 * {@code ADMIN} 没有任何系统归属，就不该收到任何系统的通知。口径与 {@code TicketMapper.countLeads}
 * 同源（同一张表），审批人与收件人不会各说各话。
 *
 * <p>系统不存在、或系统还没配运维时返回<b>空列表</b>而不是抛异常：通知是业务动作的副作用，
 * "没人可发"是正常状态，不该让它看起来像失败。
 *
 * <p>结果去重并按 {@code user_id} 升序：下游逐条投递/落库，稳定顺序让失败重试与测试断言都可复现。
 */
@Component
public class NotificationRecipients {

    private static final String OPERATORS_SQL = """
            select distinct user_id
              from sys_system_operator
             where system_id = ?
               and operator_type in ('PRIMARY', 'BACKUP')
             order by user_id
            """;

    private static final String OPERATORS_WITH_LEAD_SQL = """
            select distinct user_id
              from sys_system_operator
             where system_id = ?
               and operator_type in ('PRIMARY', 'BACKUP', 'LEAD')
             order by user_id
            """;

    private final JdbcTemplate jdbc;

    public NotificationRecipients(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 解析接收人。
     *
     * @param systemId 事件所属系统
     * @param type     事件类型；为 {@code null} 视为调用方 bug（无法判定是否该通知组长）
     * @return 去重、按 {@code user_id} 升序的接收人 id；可能为空，绝不为 {@code null}
     */
    public List<Long> resolve(long systemId, NotificationEventType type) {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        String sql = type == NotificationEventType.TICKET_L3_PENDING_LEAD
                ? OPERATORS_WITH_LEAD_SQL
                : OPERATORS_SQL;
        return jdbc.queryForList(sql, Long.class, systemId);
    }
}