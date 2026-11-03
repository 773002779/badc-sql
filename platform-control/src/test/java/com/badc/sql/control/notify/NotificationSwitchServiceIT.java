package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * 事件开关的端到端测试：真实 MySQL（{@code notification_switch} 来自 V4），真实 {@code AuditService}。
 *
 * <p>语义契约（顺序即契约）：
 * <ol>
 *   <li>该系统有行 → 用该系统的值；</li>
 *   <li>没有 → 用全局行（{@code system_id IS NULL}）的值；</li>
 *   <li>都没有 → <b>默认 {@code true}</b>（默认开）。</li>
 * </ol>
 *
 * <p>{@link #repeatedGlobalUpdatesKeepExactlyOneRowAndTheLastValueWins()} 守的是 MySQL 的
 * 一个坑：{@code uk_notification_switch (event_type, system_id)} 允许多个 {@code NULL}，
 * 所以"全局行至多一条"只能由服务层保证，数据库不保证。若 {@code setGlobal} 用
 * {@code insert ... on duplicate key update} 写全局行，两次调用就会留下两行（第二次的 insert
 * 不撞唯一键），而 {@code isEnabled} 读到的可能是旧的那一行。
 *
 * <p><b>顺序无关</b>：每个用例前清空 {@code notification_switch}，所以开关行互不影响；
 * {@code audit_event} 只追加不清空（哈希链不该被测试删除），因此审计用例<b>不</b>依赖"类内只有这两行"——
 * 它先取 {@code max(seq)} 作基线，只查基线之后、且事件类型属于开关的两条。这样 JUnit 无论按什么顺序
 * 执行用例（默认按方法名哈希，不是声明顺序），断言都成立。
 *
 * <p><b>驱动的隐性依赖</b>：{@code useAffectedRows=true} 时 MySQL 对"同值 UPDATE"返回 0 行受影响，
 * 而默认（{@code false}）返回匹配到的行数。实现若把"0 行"直接当成"没有全局行"，
 * 同值重复 {@code setGlobal} 就会插出第二条 NULL 行。{@link #a_same_value_global_write_stays_single_row_when_the_driver_counts_only_changed_rows()}
 * 用一条独立连接把这条依赖钉死。
 */
@SpringBootTest
class NotificationSwitchServiceIT extends AbstractMySqlIT {

    private static final long SYSTEM = 2L;
    private static final long OTHER_SYSTEM = 3L;
    private static final long ACTOR = 1L;
    private static final NotificationEventType EVENT = NotificationEventType.TICKET_APPROVED;

    @Autowired
    private NotificationSwitchService switches;

    @Autowired
    private NotificationSwitchMapper mapper;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = ensureSchema();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", AbstractMySqlIT::dbUser);
        registry.add("spring.datasource.password", AbstractMySqlIT::dbPass);
    }

    @BeforeEach
    void clearSwitchRows() {
        jdbc.update("delete from notification_switch");
    }

    @Test
    void an_event_without_any_row_is_enabled_by_default() {
        assertThat(switches.isEnabled(EVENT, SYSTEM))
                .as("没有任何开关行时必须默认开：漏发比困扰运维更不可接受")
                .isTrue();
    }

    @Test
    void a_global_switch_off_disables_the_event_for_every_system() {
        assertThat(mapper.hasGlobal(EVENT)).as("还没写过，全局行不该存在").isFalse();

        switches.setGlobal(EVENT, false, ACTOR);

        assertThat(mapper.hasGlobal(EVENT)).as("setGlobal 必须落下一条 system_id IS NULL 的行").isTrue();
        assertThat(switches.isEnabled(EVENT, SYSTEM))
                .as("全局关闭必须对每个系统都生效")
                .isFalse();
        assertThat(switches.isEnabled(EVENT, OTHER_SYSTEM))
                .as("全局行不属于某一个系统，任何系统都该读到它")
                .isFalse();
        assertThat(switches.isEnabled(NotificationEventType.TICKET_SUBMITTED, SYSTEM))
                .as("开关按事件区分：关掉审批通知不能顺带关掉提交通知")
                .isTrue();
    }

    @Test
    void a_system_level_switch_overrides_the_global_switch() {
        switches.setGlobal(EVENT, false, ACTOR);
        switches.setForSystem(EVENT, SYSTEM, true, ACTOR);

        assertThat(switches.isEnabled(EVENT, SYSTEM))
                .as("承重断言：系统级是覆盖，不是与全局取与——全局关掉后单个系统仍可开")
                .isTrue();
        assertThat(switches.isEnabled(EVENT, OTHER_SYSTEM))
                .as("覆盖只对目标系统生效，别的系统仍跟随全局")
                .isFalse();
    }

    @Test
    void repeated_global_updates_keep_exactly_one_row_and_the_last_value_wins() {
        switches.setGlobal(EVENT, true, ACTOR);
        switches.setGlobal(EVENT, false, ACTOR);
        // 同值重复写：不能因为"值没变、影响 0 行"就再插一行。
        switches.setGlobal(EVENT, false, ACTOR);

        assertThat(globalRowCount())
                .as("MySQL 唯一索引允许多个 NULL：全局行必须由服务层保证只有一行，否则 isEnabled 可能读到旧行")
                .isEqualTo(1);
        assertThat(switches.isEnabled(EVENT, SYSTEM))
                .as("最后一次写入的 false 必须生效")
                .isFalse();
    }

    @Test
    void a_duplicate_global_row_is_read_from_the_newest_write() {
        // 直接绕过服务层制造脏数据：两条 NULL 行（生产上并发首写可能留下这种状态）。
        jdbc.update("insert into notification_switch (event_type, system_id, enabled) values (?, null, ?)",
                EVENT.name(), false);
        jdbc.update("insert into notification_switch (event_type, system_id, enabled) values (?, null, ?)",
                EVENT.name(), true);

        assertThat(switches.isEnabled(EVENT, SYSTEM))
                .as("脏数据下必须读最新写入的那一行：按 id 升序取到的是最旧值，等于把最近一次开关操作吞掉")
                .isTrue();
    }

    @Test
    void a_same_value_global_write_stays_single_row_when_the_driver_counts_only_changed_rows() {
        DriverManagerDataSource current = (DriverManagerDataSource) jdbc.getDataSource();
        JdbcTemplate affectedJdbc = new JdbcTemplate(new DriverManagerDataSource(
                current.getUrl() + "&useAffectedRows=true", dbUser(), dbPass()));
        NotificationSwitchService affectedRowsService = new NotificationSwitchService(
                new NotificationSwitchMapper(affectedJdbc), new AuditService(affectedJdbc, Clock.systemUTC()));

        affectedRowsService.setGlobal(EVENT, false, ACTOR);
        affectedRowsService.setGlobal(EVENT, false, ACTOR);

        assertThat(globalRowCount())
                .as("useAffectedRows=true 时同值 UPDATE 返回 0 行；把 0 当作「没有全局行」就会插出第二条 NULL 行")
                .isEqualTo(1);
        assertThat(switches.isEnabled(EVENT, SYSTEM)).isFalse();
    }

    @Test
    void a_global_change_and_a_system_change_are_each_audited() {
        // 先制造一条"前序用例留下的开关审计"：本类每个用例都会调 setter，JUnit 的执行顺序未定义，
        // 所以审计断言必须能无视基线之前的同类事件。没有这一步，测试在"审计用例恰好最后跑"时
        // 会绿得没有说服力（顺序一变就红）。
        switches.setGlobal(EVENT, true, ACTOR);

        long before = auditRowCount();
        long baselineSeq = maxAuditSeq();

        switches.setGlobal(EVENT, false, ACTOR);
        switches.setForSystem(EVENT, SYSTEM, true, ACTOR);

        assertThat(auditRowCount())
                .as("两次开关变更各写一条审计")
                .isEqualTo(before + 2);

        // 只取本次写入的两条：用 seq 基线定位，不依赖"类内只有这两条开关审计"（用例顺序无关）。
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select event_type, operator_id, action, object_id, payload_json
                  from audit_event
                 where seq > ?
                   and event_type like 'NOTIFICATION_SWITCH%'
                 order by seq
                """, baselineSeq);
        assertThat(rows).hasSize(2);

        Map<String, Object> global = rows.get(0);
        assertThat(global.get("event_type"))
                .as("事件类型必须能区分全局与按系统")
                .isEqualTo("NOTIFICATION_SWITCH_GLOBAL_CHANGED");
        assertThat(((Number) global.get("operator_id")).longValue()).isEqualTo(ACTOR);
        assertThat(global.get("action")).isEqualTo("notification_switch_changed");
        assertThat(global.get("object_id")).isEqualTo("TICKET_APPROVED:GLOBAL");
        assertThat((String) global.get("payload_json"))
                .as("payload 要能回答「谁在什么时候把哪个事件改成了什么」")
                .contains("\"type\":\"TICKET_APPROVED\"")
                .contains("\"systemId\":null")
                .contains("\"enabled\":false")
                .contains("\"actor\":1");

        Map<String, Object> perSystem = rows.get(1);
        assertThat(perSystem.get("event_type")).isEqualTo("NOTIFICATION_SWITCH_SYSTEM_CHANGED");
        assertThat(perSystem.get("object_id")).isEqualTo("TICKET_APPROVED:2");
        assertThat((String) perSystem.get("payload_json"))
                .contains("\"systemId\":2")
                .contains("\"enabled\":true")
                .contains("\"actor\":1");
    }

    private int globalRowCount() {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification_switch where event_type = ? and system_id is null",
                Integer.class, EVENT.name());
        return count == null ? 0 : count;
    }

    private long auditRowCount() {
        Long count = jdbc.queryForObject("select count(*) from audit_event", Long.class);
        return count == null ? 0L : count;
    }

    private long maxAuditSeq() {
        Long seq = jdbc.queryForObject("select coalesce(max(seq), 0) from audit_event", Long.class);
        return seq == null ? 0L : seq;
    }
}
