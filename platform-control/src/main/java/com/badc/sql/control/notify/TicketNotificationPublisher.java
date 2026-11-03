package com.badc.sql.control.notify;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.badc.sql.control.ticket.TicketEntity;
import com.badc.sql.control.ticket.TicketMapper;
import com.badc.sql.control.ticket.TicketStatus;

/**
 * 工单生命周期 → 通知事件的唯一翻译层：组装白名单事实、判定兜底路径、发布
 * {@link NotificationEvent}。
 *
 * <h2>业务类只调这里，不碰投递器</h2>
 * <p>工单服务（提交 / 审批 / 执行）只表达"发生了什么"，不关心渠道、重试、开关、脱敏——那些都在
 * {@link NotificationDispatcher} 里。这样"通知发不出去不能影响业务"是<b>结构</b>保证：业务代码里没有一条
 * 通往渠道的调用路径，唯一的出口是 {@code publishEvent}，而事件在提交后由异步线程消费。
 *
 * <h2>这里也绝不抛异常</h2>
 * <p>每个公开方法整个包在 try/catch 里：查名字的 SQL 失败、模板渲染失败、事件监听器注册缺失……都只是
 * "这次通知没发成"，不能让一张已经落库的工单因为通知而返回 500。异常只记 warn（消息经
 * {@link NotificationErrorText} 脱敏）。
 *
 * <h2>facts 只放白名单键（R31）</h2>
 * <p>按事件严格分开：<b>提交类</b>只放 system/module/datasource/statementType/riskLevel/submitter/
 * ticketNo/link；<b>审批类</b>只放 system/submitter/ticketNo/link/reason（reason 只放审核意见/驳回理由）；
 * <b>执行类</b>只放 system/ticketNo/link——<b>不放任何错误文本</b>，因为执行错误可能含 SQL 片段、主机、
 * 账号等凭证形态，要看原因请点短链进工单详情（那里有权限校验）。三个键集互不"借用"（见 {@link #facts}）。
 *
 * <h2>两个事实为什么缺席</h2>
 * <ul>
 *   <li>{@code estimatedRows}：本切片的行数预估只在提交/执行时用于分级，没有落库列（{@code ticket}
 *       与 {@code risk_snapshot_json} 都没有），这里不臆造一个数；</li>
 *   <li>{@code module} / {@code datasource} 的名字查不到（被删）时整项跳过，不写占位符。</li>
 * </ul>
 *
 * <h2>收件人：归属 + 显式两条通道</h2>
 * <p>"系统主/备运维"回答不了"提交人在等结果"这件事：审批通过/驳回若只按归属发，通知会发给刚点完按钮的
 * 运维，而提完单的研发一条都收不到。所以审批结果显式发给<b>提交人 + 一级审核人</b>，执行结果显式发给
 * <b>提交人 + 系统运维</b>（都走 {@link NotificationCommand#explicitRecipients()}）；其余事件不带显式收件人，
 * 仍由 {@link NotificationRecipients#resolve} 按归属解析——两条通道的边界写在
 * {@link #explicitRecipients(TicketEntity, NotificationEventType)} 里。
 *
 * <h2>R16 兜底</h2>
 * <p>提交事件发布前先解析一次基线收件人（{@link NotificationRecipients#resolve(long,
 * NotificationEventType)}，事件用 {@link NotificationEventType#TICKET_SUBMITTED}）。为空说明该系统
 * <b>没有主/备运维归属</b>，此时改发组长代为审批：事件类型换成
 * {@link NotificationEventType#TICKET_LEAD_FALLBACK}（文案点明"缺位"），收件人解析交给投递器按新类型做
 * ——{@code NotificationRecipients} 对这两个类型都并入 {@code LEAD}。若解析本身失败，按普通
 * {@code TICKET_SUBMITTED} 发：宁可事件类型不精确，也不要漏发。
 */
@Component
public class TicketNotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(TicketNotificationPublisher.class);

    private static final String SYSTEM_NAME_SQL = "select name from sys_system where id = ?";
    private static final String MODULE_NAME_SQL = "select name from sys_module where id = ?";
    private static final String DATASOURCE_NAME_SQL = "select name from datasource where id = ?";
    private static final String SUBMITTER_NAME_SQL = "select display_name from sys_user where id = ?";
    private static final String STATEMENT_TYPES_SQL =
            "select statement_type from ticket_statement where ticket_id = ? order by seq_no";

    /** 工单详情路径；基地址来自 {@code badcsql.notify.link-base}（R36），基地址为空时不生成短链。 */
    private static final String TICKET_DETAIL_PATH = "/tickets/";

    /** 一级审核人：审批结果的另一个收件人（提交人之外）。 */
    private static final String FIRST_LEVEL_REVIEWER_SQL =
            "select reviewer_id from ticket_review where ticket_id = ? and review_level = 1";

    private final ApplicationEventPublisher events;
    private final NotificationRecipients recipients;
    private final TicketMapper ticketMapper;
    private final NotificationProperties properties;
    private final JdbcTemplate jdbc;

    public TicketNotificationPublisher(ApplicationEventPublisher events, NotificationRecipients recipients,
            TicketMapper ticketMapper, NotificationProperties properties, JdbcTemplate jdbc) {
        this.events = events;
        this.recipients = recipients;
        this.ticketMapper = ticketMapper;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /**
     * 工单提交成功：{@link NotificationEventType#TICKET_SUBMITTED}，主备运维缺位时改发
     * {@link NotificationEventType#TICKET_LEAD_FALLBACK}（R16）。
     *
     * <p><b>这里不发 {@code TICKET_L3_PENDING_LEAD}</b>：L3 工单提交时只通知运维去一审，组长要等一审通过、
     * 工单真的转入 {@code REVIEWING_L2} 才被通知（R15）。
     *
     * @param ticketId 新工单 ID
     */
    public void submitted(long ticketId) {
        try {
            TicketEntity ticket = find(ticketId);
            if (ticket == null) {
                return;
            }
            publish(ticket, submittedType(ticket), null);
        } catch (RuntimeException e) {
            log.warn("工单提交通知发布失败（工单 {}）：{}", ticketId, NotificationErrorText.sanitize(e));
        }
    }

    /**
     * L3 工单一级审核通过、转入 {@code REVIEWING_L2}：通知组长终审
     * （{@link NotificationEventType#TICKET_L3_PENDING_LEAD}）。
     *
     * @param ticketId 工单 ID
     */
    public void l3PendingLead(long ticketId) {
        try {
            TicketEntity ticket = find(ticketId);
            if (ticket == null) {
                return;
            }
            publish(ticket, NotificationEventType.TICKET_L3_PENDING_LEAD, null);
        } catch (RuntimeException e) {
            log.warn("L3 待终审通知发布失败（工单 {}）：{}", ticketId, NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 工单审批通过（终局）：{@link NotificationEventType#TICKET_APPROVED}。
     *
     * @param ticketId 工单 ID
     * @param comment  审核意见，可空（空则消息里没有"原因"一行）
     */
    public void approved(long ticketId, String comment) {
        try {
            TicketEntity ticket = find(ticketId);
            if (ticket == null) {
                return;
            }
            publish(ticket, NotificationEventType.TICKET_APPROVED, comment);
        } catch (RuntimeException e) {
            log.warn("工单通过通知发布失败（工单 {}）：{}", ticketId, NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 工单被驳回：{@link NotificationEventType#TICKET_REJECTED}。
     *
     * @param ticketId 工单 ID
     * @param reason   驳回理由（服务层已保证非空）
     */
    public void rejected(long ticketId, String reason) {
        try {
            TicketEntity ticket = find(ticketId);
            if (ticket == null) {
                return;
            }
            publish(ticket, NotificationEventType.TICKET_REJECTED, reason);
        } catch (RuntimeException e) {
            log.warn("工单驳回通知发布失败（工单 {}）：{}", ticketId, NotificationErrorText.sanitize(e));
        }
    }

    /**
     * 执行进入终态：成功 → {@link NotificationEventType#TICKET_EXECUTED}；失败类终态
     * （{@code FAILED} / {@code PARTIAL_FAILED} / {@code NEEDS_MANUAL_CONFIRM}）→
     * {@link NotificationEventType#TICKET_EXEC_FAILED}。
     *
     * <p>其它状态不产生通知：它们不是"值得打扰运维"的终态（例如中途的 {@code EXECUTING}）。
     * 这里只传状态不传错误文本——错误可能含 SQL 片段与凭证形态，要查原因请点短链进详情页。
     *
     * @param ticketId    工单 ID
     * @param finalStatus 落库后的终态
     */
    public void executionFinished(long ticketId, TicketStatus finalStatus) {
        if (finalStatus == null) {
            return;
        }
        NotificationEventType type = switch (finalStatus) {
            case SUCCEEDED -> NotificationEventType.TICKET_EXECUTED;
            case FAILED, PARTIAL_FAILED, NEEDS_MANUAL_CONFIRM -> NotificationEventType.TICKET_EXEC_FAILED;
            default -> null;
        };
        if (type == null) {
            return;
        }
        try {
            TicketEntity ticket = find(ticketId);
            if (ticket == null) {
                return;
            }
            publish(ticket, type, null);
        } catch (RuntimeException e) {
            log.warn("工单执行结果通知发布失败（工单 {}，状态 {}）：{}", ticketId, finalStatus,
                    NotificationErrorText.sanitize(e));
        }
    }

    // ------------------------------------------------------------------ 事件构造

    /**
     * 提交事件的类型判定（R16）。
     *
     * <p>只用"基线收件人是否为空"这一个判据，<b>不引入新的归属查询</b>：判据与投递器解析收件人用的是同一条
     * 规则（同一张 {@code sys_system_operator} 表），不会出现"发布侧认为没人、投递侧却有人"的分叉。
     * 只有 LEAD 行时 {@code resolve(system, TICKET_LEAD_FALLBACK)} 返回的就是那些组长。
     */
    private NotificationEventType submittedType(TicketEntity ticket) {
        try {
            List<Long> baseline = recipients.resolve(ticket.systemId(),
                    NotificationEventType.TICKET_SUBMITTED);
            return baseline == null || baseline.isEmpty()
                    ? NotificationEventType.TICKET_LEAD_FALLBACK
                    : NotificationEventType.TICKET_SUBMITTED;
        } catch (RuntimeException e) {
            // 查不出归属不能当成"没人"：那会把一条普通通知错误地改发给组长。按普通提交发。
            log.warn("提交通知的收件人判定失败（系统 {}），按普通提交事件继续：{}",
                    ticket.systemId(), NotificationErrorText.sanitize(e));
            return NotificationEventType.TICKET_SUBMITTED;
        }
    }

    private void publish(TicketEntity ticket, NotificationEventType type, String reason) {
        Map<String, String> facts = facts(ticket, type, reason);
        List<Long> explicitRecipients = explicitRecipients(ticket, type);
        events.publishEvent(new NotificationEvent(new NotificationCommand(
                type, ticket.systemId(), ticket.id(), null, facts, explicitRecipients)));
    }

    /**
     * 哪些事件需要显式收件人。
     *
     * <p>只有两类：<b>审批结果</b>（提交人在等，而他不在系统归属里）与<b>执行结果</b>（提交人 + 系统运维）。
     * 其余事件一律返回 {@code null}，交给 {@link NotificationRecipients#resolve} 按归属解析——
     * 能由归属回答的事不要另开一套口径。
     *
     * @return 非空列表表示"就用这些人"；{@code null} 表示走归属解析
     */
    private List<Long> explicitRecipients(TicketEntity ticket, NotificationEventType type) {
        return switch (type) {
            case TICKET_APPROVED, TICKET_REJECTED -> reviewOutcomeRecipients(ticket);
            case TICKET_EXECUTED, TICKET_EXEC_FAILED -> executionRecipients(ticket);
            default -> null;
        };
    }

    /**
     * 审批结果：<b>提交人 + 一级审核人</b>（brief 的口径）。
     *
     * <p>只给运维是错的——提完单在等结果的是研发，他收不到就等于没通知。一级审核人也要在列表里：
     * 一张 L3 单的终审结果（组长批/驳）对一级运维同样重要。两者用 {@link LinkedHashSet} 去重保序
     * （提交人恰好是审核人时只有一行）。一级审核人可能不存在（例如数据被清理），那就只发提交人。
     */
    private List<Long> reviewOutcomeRecipients(TicketEntity ticket) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (ticket.submitterId() != null) {
            ids.add(ticket.submitterId());
        }
        Long firstLevelReviewer = firstLevelReviewer(ticket.id());
        if (firstLevelReviewer != null) {
            ids.add(firstLevelReviewer);
        }
        return List.copyOf(ids);
    }

    /**
     * 执行结果：<b>提交人 + 该系统的运维</b>（brief 的口径）。
     *
     * <p>归属解析失败时不能让整条通知消失：至少把提交人发出去（他点了"执行"，最需要知道结果）。
     * 两者都为空时返回 {@code null}，退回投递器按事件类型解析——那是最后的兜底，不是正常路径。
     */
    private List<Long> executionRecipients(TicketEntity ticket) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        try {
            List<Long> operators = recipients.resolve(ticket.systemId(), NotificationEventType.TICKET_SUBMITTED);
            if (operators != null) {
                ids.addAll(operators);
            }
        } catch (RuntimeException e) {
            log.warn("执行通知的运维收件人解析失败（系统 {}），只发给提交人：{}",
                    ticket.systemId(), NotificationErrorText.sanitize(e));
        }
        if (ticket.submitterId() != null) {
            ids.add(ticket.submitterId());
        }
        return ids.isEmpty() ? null : List.copyOf(ids);
    }

    /** 一级审核人；没有该级审核记录时返回 {@code null}。 */
    private Long firstLevelReviewer(long ticketId) {
        List<Long> ids = jdbc.queryForList(FIRST_LEVEL_REVIEWER_SQL, Long.class, ticketId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * 短链（R36）：{@code {link-base}/tickets/{id}}，例如 {@code https://console.example.com/tickets/42}。
     *
     * <p><b>基地址为空时返回 {@code null}（不填 link 键）</b>：相对路径在企微里既点不开也不是给人读的地址，
     * 与其发一个打不开的链接，不如不发这一行。基地址只接受显式配置——猜一个域名等于批量制造死链。
     */
    private String link(long ticketId) {
        String base = properties.linkBase();
        if (base == null || base.isBlank()) {
            return null;
        }
        String normalized = base.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized + TICKET_DETAIL_PATH + ticketId;
    }

    /**
     * 按事件组装 facts（R31）。
     *
     * <p>三个事件族各有<b>自己的</b>键集，不是"全量的子集"——R31 的清单是上限，不是建议：
     * <ul>
     *   <li>提交类（{@code TICKET_SUBMITTED} / {@code TICKET_L3_PENDING_LEAD} /
     *       {@code TICKET_LEAD_FALLBACK}）：system、module、datasource、statementType、riskLevel、
     *       submitter、ticketNo、link（{@code estimatedRows} 没有落库来源，见类注释）；</li>
     *   <li>审批类（{@code TICKET_APPROVED} / {@code TICKET_REJECTED}）：system、submitter、ticketNo、
     *       link、reason——<b>不含</b> module/datasource/statementType/riskLevel：审批通知只需要说清
     *       "哪张单、谁提的、为什么"，多带一份风险描述既超出 R31，也让消息更容易被截断；</li>
     *   <li>执行类（{@code TICKET_EXECUTED} / {@code TICKET_EXEC_FAILED}）：system、ticketNo、link
     *       ——<b>不放任何错误文本</b>（错误可能含 SQL 片段与凭证形态，看原因请点短链进详情页）。</li>
     * </ul>
     *
     * <p>键名只能取自 {@link NotificationCommand#ALLOWED_FACT_KEYS}，多一个键会在构造
     * {@link NotificationCommand} 时抛——所以这里不"顺手"加任何东西（尤其不加语句文本）。
     */
    private Map<String, String> facts(TicketEntity ticket, NotificationEventType type, String reason) {
        Map<String, String> facts = new LinkedHashMap<>();
        put(facts, "system", name(SYSTEM_NAME_SQL, ticket.systemId()));
        put(facts, "ticketNo", String.valueOf(ticket.id()));
        put(facts, "link", link(ticket.id()));

        if (type == NotificationEventType.TICKET_EXECUTED
                || type == NotificationEventType.TICKET_EXEC_FAILED) {
            // 执行类到此为止：不携带任何错误文本、语句类型或风险描述。
            return facts;
        }

        if (type == NotificationEventType.TICKET_APPROVED
                || type == NotificationEventType.TICKET_REJECTED) {
            // 审批类：提交人 + 审核意见/驳回理由，仅此而已。
            put(facts, "submitter", name(SUBMITTER_NAME_SQL, ticket.submitterId()));
            put(facts, "reason", reason);
            return facts;
        }

        // 提交类（含 L3 待终审与主备缺位兜底）：一句"这是什么单、要审什么"。
        put(facts, "module", name(MODULE_NAME_SQL, ticket.moduleId()));
        put(facts, "datasource", name(DATASOURCE_NAME_SQL, ticket.datasourceId()));
        put(facts, "statementType", statementTypes(ticket.id()));
        put(facts, "riskLevel", ticket.riskLevel());
        put(facts, "submitter", name(SUBMITTER_NAME_SQL, ticket.submitterId()));
        return facts;
    }

    /** 语句类型（去重、保持工单内顺序）；多语句工单在消息里显示成 {@code UPDATE/DROP}。 */
    private String statementTypes(long ticketId) {
        List<String> types = jdbc.queryForList(STATEMENT_TYPES_SQL, String.class, ticketId);
        LinkedHashSet<String> distinct = new LinkedHashSet<>(types);
        return String.join("/", distinct);
    }

    /** 按 ID 取一个名字；ID 为空或行不存在都返回 {@code null}（渲染器会整行跳过）。 */
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

    private TicketEntity find(long ticketId) {
        return ticketMapper.findById(ticketId).orElse(null);
    }
}
