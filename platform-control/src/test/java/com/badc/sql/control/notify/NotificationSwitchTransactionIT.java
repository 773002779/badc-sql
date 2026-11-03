package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.control.audit.AuditCommand;
import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * "开关写入与审计在同一事务"的回归测试（真实 MySQL）。
 *
 * <p>为什么需要单独一个类：串行执行时，每条 SQL 各自 autocommit，去掉 {@code @Transactional} 后
 * "开关行写了两条、审计一条都没写"这个中间态<b>不会</b>被其它用例观察到——写入成功、计数也正常，
 * 只有"审计失败"才能把事务边界暴露出来。本类用 {@link MockBean} 让 {@code AuditService.record}
 * 抛异常，断言：
 * <ol>
 *   <li>{@code setGlobal} / {@code setForSystem} 把异常抛给调用方（不是吞掉）；</li>
 *   <li>{@code notification_switch} 里<b>没有</b>留下这次变更的行——回滚生效。</li>
 * </ol>
 *
 * <p>不动生产 API：{@code AuditService} 是既有 bean，测试只替换它的实现。
 * 这也顺带证明了"审计失败 = 变更失败"的契约：不会出现"开关改了但没人知道是谁改的"。
 */
@SpringBootTest
class NotificationSwitchTransactionIT extends AbstractMySqlIT {

    private static final long SYSTEM = 2L;
    private static final long ACTOR = 1L;
    private static final NotificationEventType EVENT = NotificationEventType.TICKET_APPROVED;

    @Autowired
    private NotificationSwitchService switches;

    @MockBean
    private AuditService auditService;

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
    void a_failing_audit_write_rolls_back_a_global_change() {
        givenAuditFails();

        assertThatThrownBy(() -> switches.setGlobal(EVENT, false, ACTOR))
                .as("审计写失败必须让整笔变更失败，而不是悄悄改掉开关")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit down");

        assertThat(countGlobal())
                .as("@Transactional：setGlobal 的开关写入必须随审计失败一起回滚，不能留下「改了但没人知道是谁改的」的行")
                .isZero();
    }

    @Test
    void a_failing_audit_write_rolls_back_a_per_system_change() {
        givenAuditFails();

        assertThatThrownBy(() -> switches.setForSystem(EVENT, SYSTEM, false, ACTOR))
                .as("按系统变更同样必须失败")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit down");

        assertThat(countForSystem())
                .as("@Transactional：setForSystem 的开关写入也必须回滚")
                .isZero();
    }

    private void givenAuditFails() {
        when(auditService.record(any(AuditCommand.class)))
                .thenThrow(new IllegalStateException("audit down"));
    }

    private int countGlobal() {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification_switch where event_type = ? and system_id is null",
                Integer.class, EVENT.name());
        return count == null ? 0 : count;
    }

    private int countForSystem() {
        Integer count = jdbc.queryForObject(
                "select count(*) from notification_switch where event_type = ? and system_id = ?",
                Integer.class, EVENT.name(), SYSTEM);
        return count == null ? 0 : count;
    }
}
