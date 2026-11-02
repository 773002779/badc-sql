package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 收件人联系方式的读取口径（R1 裁决）：真实 MySQL + {@code seed/test-fixtures.sql}。
 *
 * <p>本类的承重断言是"<b>缺 email 的用户仍在返回列表里</b>"。如果 {@link NotificationTargetStore}
 * 在 SQL 里加了 {@code and email is not null}（或改成内连接），这个用户会从结果里消失——对 Task 5
 * 来说，"没填邮箱"就变成了"连站内消息也收不到"，而这是最难被发现的漏通知：没有异常、没有失败行，
 * 只是这个人从此什么都收不到。所以字段为 {@code null} 的用户<b>必须</b>原样返回。
 *
 * <p>夹具里的 1/2/3 号用户初始都没有邮箱与企微 userid；需要联系方式的用例自己在用例里 update，
 * {@link #clearContactDetails()} 每个用例后清回 null，避免方法顺序造成互相污染。
 */
@SpringBootTest
class NotificationTargetStoreIT extends AbstractMySqlIT {

    @Autowired
    private NotificationTargetStore store;

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
    void clearContactDetails() {
        jdbc.update("update sys_user set email = null, wecom_userid = null where id in (1, 2, 3)");
    }

    @Test
    void a_user_without_contact_details_is_still_returned_with_nulls() {
        List<NotificationTarget> targets = store.load(List.of(3L, 1L, 2L));

        assertThat(targets)
                .as("顺序按 user_id 升序；一个都不能少——缺联系方式不是过滤条件")
                .extracting(NotificationTarget::userId)
                .containsExactly(1L, 2L, 3L);
        NotificationTarget dev01 = targets.get(0);
        assertThat(dev01.loginName()).isEqualTo("dev01");
        assertThat(dev01.email()).as("没填邮箱必须原样返回 null，交给 sender 判空跳过").isNull();
        assertThat(dev01.wecomUserId()).isNull();
    }

    @Test
    void contact_details_are_read_from_sys_user() {
        jdbc.update("update sys_user set email = ?, wecom_userid = ? where id = ?",
                "ops01@badc.test", "ops01-wecom", 2L);

        assertThat(store.load(List.of(2L)))
                .containsExactly(new NotificationTarget(2L, "ops01", "ops01@badc.test", "ops01-wecom"));
    }

    @Test
    void an_empty_request_returns_no_targets_instead_of_failing() {
        assertThat(store.load(List.of())).isEmpty();
    }
}
