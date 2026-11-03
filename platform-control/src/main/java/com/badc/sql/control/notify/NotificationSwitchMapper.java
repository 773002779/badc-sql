package com.badc.sql.control.notify;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notification_switch} 的读写（列定义见 {@code V4__notification.sql}）。
 *
 * <h2>两种作用域</h2>
 * <ul>
 *   <li>{@code system_id = ?}：只覆盖某一个系统；</li>
 *   <li>{@code system_id IS NULL}：全局默认，任何没有自己那一行的系统都读它。</li>
 * </ul>
 *
 * <h2>{@code system_id IS NULL} 的坑（不要"优化"成唯一键 upsert）</h2>
 * <p>{@code uk_notification_switch (event_type, system_id)} 在 MySQL 里<b>允许多个 {@code NULL}</b>
 * （SQL 标准里 {@code NULL != NULL}）。所以全局行不能靠唯一键保证唯一：如果照抄
 * {@code insert ... on duplicate key update}，第二次 {@code setGlobal} 会插入一行新的而不是更新旧行，
 * 表里就有两条全局行，而 {@link #findGlobal} 读到的可能是旧的那一条——"最后一次生效"变成"看运气"。
 * <p>因此全局写入是 <b>update → 命中就结束；没命中才 insert</b>，由服务层在同一个事务里调用
 * （{@link NotificationSwitchService#setGlobal} 带 {@code @Transactional}）。这样串行调用只会有一行；
 * 并发首写仍可能各插一行（InnoDB 无法对 {@code NULL} 做唯一约束），属已知限制，见服务类注释。
 *
 * <h2>读为什么带 {@code limit 1}，以及为什么是 {@code desc}</h2>
 * <p>正常状态下（按上面的写法）每个作用域至多一行。{@code order by id desc limit 1} 是对历史脏数据的兜底：
 * 万一库里真有两条全局行，读<b>最新插入</b>的那条（{@code id} 最大 = 最近一次写入的那一行），
 * 而不是让 {@code queryForObject} 直接抛 {@code Incorrect result size}——投递路径上的开关查询不该因为脏数据
 * 变成"通知永远发不出去"。
 * <p>方向必须是 {@code desc}：{@code order by id limit 1} 取到的是<b>最旧</b>的一行，等于把最近一次
 * 开关操作吞掉——那正是本节要防的"最后一次生效变成看运气"。改这个方向前先看
 * {@code NotificationSwitchServiceIT.a_duplicate_global_row_is_read_from_the_newest_write()}。
 */
@Repository
public class NotificationSwitchMapper {

    private static final String SELECT_FOR_SYSTEM = """
            select enabled
              from notification_switch
             where event_type = ?
               and system_id = ?
             order by id
             limit 1
            """;

    private static final String SELECT_GLOBAL = """
            select enabled
              from notification_switch
             where event_type = ?
               and system_id is null
             order by id desc
             limit 1
            """;

    private static final String COUNT_GLOBAL =
            "select count(*) from notification_switch where event_type = ? and system_id is null";

    /**
     * 按系统写入：唯一键 {@code (event_type, system_id)} 在这里是可靠的（非空），
     * 可以放心用 {@code on duplicate key update}。
     *
     * <p>用 {@code values(enabled)} 而不是 {@code new.enabled}：后者的行别名语法要 MySQL 8.0.19+，
     * 而 V4 的 CHECK 要 8.0.16+，本仓库的部署下限是 8.0.16。
     *
     * <p><b>不显式写 {@code updated_at}</b>：列上有 {@code ON UPDATE CURRENT_TIMESTAMP(3)}，
     * 值真的变了它会自己跳。显式赋值会把"同值写入"也变成一次行变更——那会让
     * {@code useAffectedRows=true} 的 0 行语义变得不可解释（同一毫秒内是 0，隔一毫秒是 1），
     * 也会让 {@link #upsert} 的"0 行 = 值没变"判断失去确定性。
     */
    private static final String UPSERT_FOR_SYSTEM = """
            insert into notification_switch (event_type, system_id, enabled)
            values (?, ?, ?)
            on duplicate key update enabled = values(enabled)
            """;

    /**
     * 全局行：先更新，命中不了才插入（见类注释）。
     *
     * <p>同样不显式写 {@code updated_at}：同值写入必须是<b>真正的 no-op</b>（0 行变更），
     * 这样 {@code useAffectedRows=true} 下"0 行"才确定地表示"已有全局行、值没变"，
     * {@link #upsert} 的二次确认才有意义。见
     * {@code NotificationSwitchServiceIT.a_same_value_global_write_stays_single_row_when_the_driver_counts_only_changed_rows()}。
     */
    private static final String UPDATE_GLOBAL = """
            update notification_switch
               set enabled = ?
             where event_type = ? and system_id is null
            """;

    private static final String INSERT = """
            insert into notification_switch (event_type, system_id, enabled)
            values (?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public NotificationSwitchMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查某个系统自己的开关行（<b>只查</b> {@code system_id = ?}，不回落全局）。
     *
     * @param type     事件类型，非 {@code null}
     * @param systemId 系统 ID
     * @return 该系统的开关值；没有这一行时 {@link Optional#empty()}（由调用方决定回落到全局还是默认值）
     */
    public Optional<Boolean> findForSystem(NotificationEventType type, long systemId) {
        List<Boolean> found = jdbc.query(SELECT_FOR_SYSTEM,
                (rs, rowNum) -> rs.getBoolean("enabled"), requireType(type).name(), systemId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 查全局开关行（{@code system_id IS NULL}）。
     *
     * @param type 事件类型，非 {@code null}
     * @return 全局开关值；没有这一行时 {@link Optional#empty()}
     */
    public Optional<Boolean> findGlobal(NotificationEventType type) {
        List<Boolean> found = jdbc.query(SELECT_GLOBAL,
                (rs, rowNum) -> rs.getBoolean("enabled"), requireType(type).name());
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** 全局行是否存在（不关心值）。 */
    public boolean hasGlobal(NotificationEventType type) {
        Integer count = jdbc.queryForObject(COUNT_GLOBAL, Integer.class, requireType(type).name());
        return count != null && count > 0;
    }

    /**
     * 写入一个开关值：{@code systemId == null} 写全局行，否则写该系统行。
     *
     * <p>这是<b>写入</b>而不是"纯 upsert"：全局分支内部是 update → insert 两步，必须在调用方的事务里执行，
     * 否则两步之间进程退出会留下"更新没生效、插入也没发生"的空窗（更糟的是并发各插一行）。
     *
     * @param systemId {@code null} 表示全局；非空表示只覆盖该系统
     * @param type     事件类型，非 {@code null}
     * @param enabled  开关值
     * @return 受影响的行数，<b>仅供诊断</b>，调用方不得把它当作"是否写入成功"的判据：
     *         系统分支的 {@code on duplicate key update} 在"值没变"时可能返回 0（连接串开了
     *         {@code useAffectedRows=true}）或 2（默认）；全局分支的"有行但值没变"返回 0。
     *         {@link NotificationSwitchService} 不看这个值。
     */
    public int upsert(Long systemId, NotificationEventType type, boolean enabled) {
        requireType(type);
        if (systemId != null) {
            return jdbc.update(UPSERT_FOR_SYSTEM, type.name(), systemId, enabled);
        }
        int updated = jdbc.update(UPDATE_GLOBAL, enabled, type.name());
        if (updated > 0) {
            return updated;
        }
        // 影响 0 行有两种可能：真的没有全局行，或者有这一行但值没变——MySQL Connector/J 在
        // useAffectedRows=true 时对同值 UPDATE 返回 0（默认 false 才返回"匹配到的行数"）。
        // 少了这次确认，同值重复 setGlobal 会插出第二条全局行；而 NULL 不参与唯一性，插进去就是永久的。
        if (hasGlobal(type)) {
            return 0;
        }
        // 显式 setNull：让"全局"确实是 SQL NULL，而不是依赖驱动的类型推断。
        return jdbc.update(INSERT, ps -> {
            ps.setString(1, type.name());
            ps.setNull(2, Types.BIGINT);
            ps.setBoolean(3, enabled);
        });
    }

    private static NotificationEventType requireType(NotificationEventType type) {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        return type;
    }
}
