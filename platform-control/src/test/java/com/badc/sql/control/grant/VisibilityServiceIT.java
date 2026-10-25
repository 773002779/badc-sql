package com.badc.sql.control.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.support.AbstractMySqlIT;

/**
 * 可见性判定的端到端测试：真实 MySQL，判定逻辑就是 SQL 联结本身。
 *
 * <p>夹具（{@code seed/test-fixtures.sql}）：
 * <ul>
 *   <li>dev01 = DEVELOPER，模块 201 已授权，模块下是数据源 101 / 102；</li>
 *   <li>ops01 = PRIMARY_OPS，是系统 2 的主运维，系统 2 下是数据源 999；</li>
 *   <li>admin01 = ADMIN，与任何系统无关；</li>
 *   <li>用户 3 号站点：数据源 12345 不存在。</li>
 * </ul>
 */
@SpringBootTest
class VisibilityServiceIT extends AbstractMySqlIT {

    private static final long DEV = 1L;
    private static final long OPS = 2L;
    private static final long ADMIN = 3L;

    @Autowired
    private VisibilityService visibilityService;

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

    @Test
    void developer_sees_only_datasources_of_granted_modules() {
        assertThat(visibilityService.visibleDatasourceIds(DEV)).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void operator_sees_every_datasource_of_the_systems_they_operate() {
        assertThat(visibilityService.visibleDatasourceIds(OPS)).containsExactlyInAnyOrder(999L);
    }

    @Test
    void admin_sees_every_datasource() {
        assertThat(visibilityService.visibleDatasourceIds(ADMIN)).containsExactlyInAnyOrder(101L, 102L, 999L);
    }

    @Test
    void developer_sees_only_the_granted_module() {
        assertThat(visibilityService.visibleModuleIds(DEV)).containsExactly(201L);
        assertThat(visibilityService.visibleModuleIds(OPS)).isEmpty();
    }

    @Test
    void existing_datasource_without_permission_is_forbidden_not_hidden() {
        assertThatThrownBy(() -> visibilityService.assertVisible(DEV, 999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void missing_datasource_is_not_found_so_no_permission_differs_from_no_such_database() {
        assertThatThrownBy(() -> visibilityService.assertVisible(DEV, 12345L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void visible_datasource_passes_the_check() {
        visibilityService.assertVisible(DEV, 101L);
    }
}
