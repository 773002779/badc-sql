package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * 接收人解析的端到端测试：真实 MySQL，口径就是 {@code sys_system_operator} 上的那条 SQL。
 *
 * <p>夹具（{@code seed/test-fixtures.sql}）：ops01 是系统 2（支付中心）唯一的 PRIMARY 运维；
 * admin01 是 ADMIN，但不在归属表里——<b>角色不参与接收人判定</b>，否则 ADMIN 会凭空收到所有系统的通知。
 *
 * <p>本类会往系统 2 追加 LEAD / BACKUP 归属行，{@link #removeSeededRows()} 按 user_id 清掉，
 * 避免同一 schema 下的用例互相影响（顺序未定义）。
 */
@SpringBootTest
class NotificationRecipientsIT extends AbstractMySqlIT {

    private static final long SYSTEM = 2L;
    private static final long PRIMARY_OPS = 2L;
    private static final long ADMIN = 3L;
    private static final long LEAD = 4L;
    private static final long BACKUP_FIRST = 5L;
    private static final long BACKUP_SECOND = 6L;

    private static final String PASSWORD_HASH =
            "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW";

    @Autowired
    private NotificationRecipients recipients;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = ensureSchema();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", AbstractMySqlIT::dbUser);
        registry.add("spring.datasource.password", AbstractMySqlIT::dbPass);
    }

    @BeforeAll
    static void loadFixtures() {
        DatabasePopulatorUtils.execute(
                new ResourceDatabasePopulator(new ClassPathResource("seed/test-fixtures.sql")),
                jdbc.getDataSource());
    }

    @AfterEach
    void removeSeededRows() {
        jdbc.update("delete from sys_system_operator where system_id = ? and user_id in (?, ?, ?)",
                SYSTEM, LEAD, BACKUP_FIRST, BACKUP_SECOND);
        jdbc.update("delete from sys_user where id in (?, ?, ?)", LEAD, BACKUP_FIRST, BACKUP_SECOND);
    }

    @Test
    void an_ordinary_event_reaches_the_primary_operator_and_never_falls_back_to_roles() {
        assertThat(recipients.resolve(SYSTEM, NotificationEventType.TICKET_SUBMITTED))
                .as("主运维必须收到；ADMIN 不在归属表里，不能按角色兜底")
                .contains(PRIMARY_OPS)
                .doesNotContain(ADMIN);
    }

    @Test
    void a_system_without_ownership_has_no_recipients_instead_of_an_exception() {
        assertThat(recipients.resolve(999L, NotificationEventType.TICKET_SUBMITTED)).isEmpty();
    }

    @Test
    void a_lead_is_notified_for_l3_only() {
        insertOperator(LEAD, "lead01", "OPS_LEAD", "组长一号", "LEAD");

        List<Long> baseline = recipients.resolve(SYSTEM, NotificationEventType.TICKET_SUBMITTED);
        assertThat(baseline)
                .as("普通事件不该打扰组长：基线只取 PRIMARY / BACKUP")
                .doesNotContain(LEAD);

        assertThat(recipients.resolve(SYSTEM, NotificationEventType.TICKET_L3_PENDING_LEAD))
                .as("L3 是「追加组长」：主/备运维必须仍在收件人里，只剩组长就是漏通知")
                .contains(PRIMARY_OPS, LEAD)
                .containsAll(baseline);
    }

    /**
     * R16 兜底事件的收件人：系统没有主/备运维时，查询必须返回组长。
     *
     * <p>这一条是"改发组长"能不能落地的<b>关键环节</b>：发布侧把事件类型换成
     * {@link NotificationEventType#TICKET_LEAD_FALLBACK} 之后，投递器会按<b>新类型重新解析</b>收件人。
     * 若这里仍走只取 PRIMARY/BACKUP 的基线 SQL，兜底通知会解析出空列表——"改发组长"变成"谁也不发"，
     * 而提交接口依旧 201，问题只在"组长永远收不到"里体现。
     */
    @Test
    void a_fallback_event_resolves_to_the_lead_when_no_primary_or_backup_exists() {
        insertOperator(LEAD, "lead01", "OPS_LEAD", "组长一号", "LEAD");
        // 先把夹具里的主运维（user 2）摘掉，制造"只有组长"的系统；finally 里恢复。
        jdbc.update("delete from sys_system_operator where system_id = ? and user_id = ?", SYSTEM, PRIMARY_OPS);
        try {
            assertThat(recipients.resolve(SYSTEM, NotificationEventType.TICKET_SUBMITTED))
                    .as("没有主/备运维时基线为空，发布侧据此判定走兜底")
                    .isEmpty();
            assertThat(recipients.resolve(SYSTEM, NotificationEventType.TICKET_LEAD_FALLBACK))
                    .as("兜底事件必须解析得到组长，否则通知谁也发不到")
                    .containsExactly(LEAD);
        } finally {
            jdbc.update("""
                    insert into sys_system_operator (id, system_id, user_id, operator_type)
                    values (1, 2, 2, 'PRIMARY')
                    """);
        }
    }

    @Test
    void recipients_are_unique_and_ordered_by_user_id() {        insertOperator(BACKUP_SECOND, "ops03", "BACKUP_OPS", "备运维三号", "BACKUP");
        insertOperator(BACKUP_FIRST, "ops02", "BACKUP_OPS", "备运维二号", "BACKUP");

        assertThat(recipients.resolve(SYSTEM, NotificationEventType.TICKET_SUBMITTED))
                .as("顺序是 user_id 升序；同一用户只出现一次")
                .containsExactly(PRIMARY_OPS, BACKUP_FIRST, BACKUP_SECOND);
    }

    @Test
    void a_missing_event_type_is_refused() {
        assertThatThrownBy(() -> recipients.resolve(SYSTEM, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件类型");
    }

    private static void insertOperator(long userId, String loginName, String role, String displayName,
            String operatorType) {
        jdbc.update("""
                insert into sys_user (id, login_name, password_hash, role, display_name)
                values (?, ?, ?, ?, ?)
                """, userId, loginName, PASSWORD_HASH, role, displayName);
        jdbc.update("""
                insert into sys_system_operator (system_id, user_id, operator_type)
                values (?, ?, ?)
                """, SYSTEM, userId, operatorType);
    }
}
