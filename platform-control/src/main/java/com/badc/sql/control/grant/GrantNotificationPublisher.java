package com.badc.sql.control.grant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.badc.sql.control.notify.NotificationCommand;
import com.badc.sql.control.notify.NotificationErrorText;
import com.badc.sql.control.notify.NotificationEvent;
import com.badc.sql.control.notify.NotificationEventType;
import com.badc.sql.control.notify.NotificationProperties;

/**
 * 授权申请生命周期 → 通知事件的翻译层（与 {@code TicketNotificationPublisher} 同一个形状）。
 *
 * <p>三个事件，收件人两种：
 * <ul>
 *   <li>{@link NotificationEventType#GRANT_REQUESTED}：收件人是<b>该系统的运维</b>（按归属解析，
 *       即 {@code sys_system_operator} 的 PRIMARY/BACKUP）——有事要办的人是他们；</li>
 *   <li>{@link NotificationEventType#GRANT_APPROVED} / {@link NotificationEventType#GRANT_REJECTED}：
 *       收件人是<b>申请人</b>（显式收件人）。他在等结果，而系统归属里根本没有他；按归属发等于通知错人。</li>
 * </ul>
 *
 * <p><b>facts 只用白名单键</b>（{@link NotificationCommand#ALLOWED_FACT_KEYS}）：system、module、
 * submitter（申请人）、reason（申请理由 / 审批意见）、link。授权类事件尤其不能带 SQL——它本来就不涉及语句，
 * 更不该因为"顺手带上模块下有哪些库"而扩大信息面。
 *
 * <p><b>这里绝不抛异常</b>：与工单侧同理，通知是业务动作的副作用，查名字失败或事件注册缺失都只记 warn。
 * 若这里抛出去，一次已经提交的审批会看起来像失败。
 */
@Component
public class GrantNotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(GrantNotificationPublisher.class);

    private static final String SYSTEM_NAME_SQL = "select name from sys_system where id = ?";
    private static final String MODULE_NAME_SQL = "select name from sys_module where id = ?";
    private static final String USER_NAME_SQL = "select display_name from sys_user where id = ?";
    private static final String MODULE_SYSTEM_SQL = "select system_id from sys_module where id = ?";

    /** 授权申请详情路径；基地址来自 {@code badcsql.notify.link-base}（与工单短链同一配置）。 */
    private static final String GRANT_DETAIL_PATH = "/grants/";

    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final NotificationProperties properties;

    public GrantNotificationPublisher(ApplicationEventPublisher events, JdbcTemplate jdbc,
            NotificationProperties properties) {
        this.events = events;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 新的授权申请：通知该系统的运维去审批。
     *
     * @param row 刚落库的申请（{@code PENDING}）
     */
    public void requested(GrantRequestRow row) {
        try {
            publish(NotificationEventType.GRANT_REQUESTED, row, row.reason(), null);
        } catch (RuntimeException e) {
            log.warn("授权申请通知发布失败（申请 {}）：{}", row.id(), NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 申请已通过：通知申请人，带上审批意见（可能是空的"同意"）。
     *
     * @param row     申请单（已迁移为 {@code APPROVED}）
     * @param comment 审批意见，可空
     */
    public void approved(GrantRequestRow row, String comment) {
        try {
            publish(NotificationEventType.GRANT_APPROVED, row, comment, List.of(row.userId()));
        } catch (RuntimeException e) {
            log.warn("授权通过通知发布失败（申请 {}）：{}", row.id(), NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 申请被驳回：通知申请人，带上驳回理由（服务层已保证非空）。
     *
     * @param row     申请单（已迁移为 {@code REJECTED}）
     * @param comment 驳回理由
     */
    public void rejected(GrantRequestRow row, String comment) {
        try {
            publish(NotificationEventType.GRANT_REJECTED, row, comment, List.of(row.userId()));
        } catch (RuntimeException e) {
            log.warn("授权驳回通知发布失败（申请 {}）：{}", row.id(), NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 组装命令并发布。
     *
     * @param explicitRecipients {@code null} 表示按系统归属解析（申请提交事件）；非空表示就发给这些人（审批结果）
     */
    private void publish(NotificationEventType type, GrantRequestRow row, String reason,
            List<Long> explicitRecipients) {
        Long systemId = systemIdOf(row.moduleId());
        if (systemId == null) {
            // 模块已被删除：通知里说不出"哪个系统的什么模块"，链路上也没有可投递的收件人依据。
            // 这是可接受的丢失——申请单本身还在，审计可查。
            log.warn("授权申请 {} 的模块 {} 已不存在，跳过通知", row.id(), row.moduleId());
            return;
        }
        Map<String, String> facts = facts(row, systemId, reason);
        events.publishEvent(new NotificationEvent(new NotificationCommand(
                type, systemId, null, row.id(), facts, explicitRecipients)));
    }

    private Map<String, String> facts(GrantRequestRow row, long systemId, String reason) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "system", name(SYSTEM_NAME_SQL, systemId));
        put(facts, "module", name(MODULE_NAME_SQL, row.moduleId()));
        put(facts, "submitter", name(USER_NAME_SQL, row.userId()));
        // 白名单里没有 canExport 键（R31 的清单是上限，不在这里扩展）：申请人若想确认批的是查询还是导出，
        // 点短链进详情页看。
        put(facts, "reason", reason);
        put(facts, "link", link(row.id()));
        return facts;
    }

    /** 短链：{@code {link-base}/grants/{id}}；基地址为空时不填这一行（相对路径在企微里点不开）。 */
    private String link(long requestId) {
        String base = properties.linkBase();
        if (base == null || base.isBlank()) {
            return null;
        }
        String normalized = base.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized + GRANT_DETAIL_PATH + requestId;
    }

    private Long systemIdOf(long moduleId) {
        List<Long> ids = jdbc.queryForList(MODULE_SYSTEM_SQL, Long.class, moduleId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String name(String sql, Long id) {
        if (id == null) {
            return null;
        }
        List<String> names = jdbc.queryForList(sql, String.class, id);
        return names.isEmpty() ? null : names.get(0);
    }

    private static void put(Map<String, String> facts, String key, String value) {
        if (value != null && !value.isBlank()) {
            facts.put(key, value);
        }
    }
}
