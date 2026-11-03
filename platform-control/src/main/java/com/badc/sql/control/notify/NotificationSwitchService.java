package com.badc.sql.control.notify;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 通知事件开关：全局默认 + 按系统覆盖。
 *
 * <h2>{@link #isEnabled} 的取值顺序（顺序即契约）</h2>
 * <ol>
 *   <li>该系统的行（{@code system_id = ?}）——有就用它；</li>
 *   <li>否则全局行（{@code system_id IS NULL}）——有就用它；</li>
 *   <li>两者都没有 → <b>{@code true}</b>（默认开）。</li>
 * </ol>
 * <p>系统级是<b>覆盖</b>而不是与全局取与：全局关掉后，仍可以给某个系统单独打开（维护窗口过去、
 * 该系统还没有问题）。反过来"没有行 = 关"是错的：新装一套系统、事件从未被配置过时，通知必须能用；
 * 要关必须有人显式关，并且留下审计。
 *
 * <h2>为什么默认开</h2>
 * <p>开关是"少发"，失败是"发不出去"。默认关会让一次配置遗漏（漏建行、升级后表是空的）静默吃掉
 * 全部通知；默认开最坏是多发几条，而多发的代价远低于漏发。这也是 {@code NotificationDispatcher}
 * 在开关查询失败时按"开"继续投递的同一口径。
 *
 * <h2>审计</h2>
 * <p>每次变更写一条 {@code audit_event}：事件类型区分作用域
 * （{@value #EVENT_GLOBAL_CHANGED} / {@value #EVENT_SYSTEM_CHANGED}），
 * {@code payload} 带 {@code type/systemId/enabled/actor}。调用口径与仓库既有调用点一致
 * （{@link AuditService#record(AuditCommand)}）。{@code setGlobal} / {@code setForSystem} 都带
 * {@link Transactional}：开关写入与审计要么一起成功、要么一起回滚——"改了但没人知道是谁改的"不可接受。
 *
 * <h2>并发限制（已知）</h2>
 * <p>MySQL 的唯一索引允许多个 {@code NULL}，所以"全局行至多一条"只能靠
 * {@link NotificationSwitchMapper#upsert} 的 update → insert 顺序 + 本类的事务保证<b>串行</b>调用正确。
 * 两个请求在空表上同时首次写同一个事件的全局开关，仍可能各插一行（InnoDB 无唯一性可依赖）。
 * 这种情形下 {@code isEnabled} 读<b>最新插入</b>的那一行（{@code order by id desc limit 1}），
 * 不会报错；要彻底消除需要给全局行一个非空哨兵列或改用独立表，超出本切片范围。
 */
@Service
public class NotificationSwitchService {

    /** 全局开关变更的审计事件类型。 */
    static final String EVENT_GLOBAL_CHANGED = "NOTIFICATION_SWITCH_GLOBAL_CHANGED";

    /** 按系统开关变更的审计事件类型。 */
    static final String EVENT_SYSTEM_CHANGED = "NOTIFICATION_SWITCH_SYSTEM_CHANGED";

    /** 两类变更共用的动作名（与 {@code action} 列 64 字符上限对齐）。 */
    static final String ACTION_CHANGED = "notification_switch_changed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NotificationSwitchMapper mapper;
    private final AuditService auditService;

    public NotificationSwitchService(NotificationSwitchMapper mapper, AuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * 某个系统上某个事件是否开启。
     *
     * @param type     事件类型，非 {@code null}（{@code null} 是调用方 bug，直接抛）
     * @param systemId 系统 ID
     * @return 该系统的行 → 全局行 → 默认 {@code true}
     */
    public boolean isEnabled(NotificationEventType type, long systemId) {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        Optional<Boolean> forSystem = mapper.findForSystem(type, systemId);
        if (forSystem.isPresent()) {
            return forSystem.get();
        }
        return mapper.findGlobal(type).orElse(Boolean.TRUE);
    }

    /**
     * 设置全局默认开关（只影响没有自己那一行的系统）。
     *
     * @param type    事件类型
     * @param enabled 是否开启
     * @param actorId 操作人用户 ID（写进审计的 {@code operator_id}）
     */
    @Transactional
    public void setGlobal(NotificationEventType type, boolean enabled, long actorId) {
        requireType(type);
        mapper.upsert(null, type, enabled);
        audit(EVENT_GLOBAL_CHANGED, type, null, enabled, actorId);
    }

    /**
     * 设置某个系统的开关（覆盖全局）。
     *
     * @param type     事件类型
     * @param systemId 系统 ID
     * @param enabled  是否开启
     * @param actorId  操作人用户 ID（写进审计的 {@code operator_id}）
     */
    @Transactional
    public void setForSystem(NotificationEventType type, long systemId, boolean enabled, long actorId) {
        requireType(type);
        mapper.upsert(systemId, type, enabled);
        audit(EVENT_SYSTEM_CHANGED, type, systemId, enabled, actorId);
    }

    private void audit(String eventType, NotificationEventType type, Long systemId, boolean enabled, long actorId) {
        String scope = systemId == null ? "GLOBAL" : String.valueOf(systemId);
        auditService.record(new AuditCommand(
                eventType, actorId, null, null, ACTION_CHANGED, type.name() + ":" + scope,
                payload(type, systemId, enabled, actorId)));
    }

    /**
     * 审计载荷：{@code {"type":...,"systemId":...,"enabled":...,"actor":...}}。
     *
     * <p>用 Jackson 而不是字符串拼接：枚举名今天只含 {@code A-Z_}，但拼 JSON 的写法会在某次
     * 改名（或未来加入带引号的字段）时变成非法 JSON，而 {@code AuditService.canonicalJson}
     * 对非法 JSON 是"原样保留"——链不会报错，只是再也解析不出来。
     */
    private static String payload(NotificationEventType type, Long systemId, boolean enabled, long actorId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type.name());
        if (systemId == null) {
            node.putNull("systemId");
        } else {
            node.put("systemId", systemId);
        }
        node.put("enabled", enabled);
        node.put("actor", actorId);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // ObjectNode 序列化不会失败；真失败说明 JVM/Jackson 环境坏了，宁可让事务回滚。
            throw new IllegalStateException("通知开关审计载荷序列化失败", e);
        }
    }

    private static void requireType(NotificationEventType type) {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
    }
}
