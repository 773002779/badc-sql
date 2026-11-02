package com.badc.sql.control.notify;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 收件人联系方式来源：从 {@code sys_user} 一次读出多个用户的 {@code email} / {@code wecom_userid}。
 *
 * <p><b>这是本任务唯一读 {@code sys_user} 的地方</b>（R1 裁决：两列直接挂在 {@code sys_user} 上，
 * 不另建联系方式表）。
 *
 * <p><b>没有联系方式字段的用户必须留在返回结果里</b>（字段为 {@code null}），绝对不能在 SQL 里加
 * {@code and email is not null} 或改成内连接某个联系方式表：那样"没填邮箱"会变成"这个人连站内消息
 * 也收不到"——没有异常、没有失败行，只是从此什么都收不到，是最难发现的漏通知。判空是各渠道 sender
 * 自己的事：邮件 sender 跳过没邮箱的人，企微 sender 跳过没 userid 的人。
 *
 * <p>因为联系方式就是 {@code sys_user} 同一行的可空列，这条查询天然是"左连接语义"：主表是
 * {@code sys_user}，没有第二条联系方式表可 join，也就不存在"内连接把主表行丢掉"的可能。
 *
 * <p>返回顺序按 {@code user_id} 升序（与 {@link NotificationRecipients#resolve} 一致），
 * 下游逐条投递/落库时稳定可复现；入参为空时直接返回空列表，不拼 {@code in ()} 这种非法 SQL。
 */
@Component
public class NotificationTargetStore {

    private static final String SELECT_SQL = """
            select id, login_name, email, wecom_userid
              from sys_user
             where id in (%s)
             order by id
            """;

    private final JdbcTemplate jdbc;

    public NotificationTargetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按用户 id 批量读取收件人联系方式。
     *
     * @param userIds 用户 id 列表，不能为 {@code null}（空列表合法，返回空列表）
     * @return 按 {@code user_id} 升序的收件人；查不到的用户不出现，联系方式缺失的用户照常出现（字段为 null）
     */
    public List<NotificationTarget> load(List<Long> userIds) {
        if (userIds == null) {
            throw new IllegalArgumentException("用户 id 列表不能为空");
        }
        if (userIds.isEmpty()) {
            return List.of();
        }
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbc.query(SELECT_SQL.formatted(placeholders),
                (rs, rowNum) -> new NotificationTarget(
                        rs.getLong("id"),
                        rs.getString("login_name"),
                        rs.getString("email"),
                        rs.getString("wecom_userid")),
                userIds.toArray());
    }
}
