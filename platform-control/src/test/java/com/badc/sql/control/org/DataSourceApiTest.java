package com.badc.sql.control.org;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.badc.sql.control.grant.VisibilityService;
import com.badc.sql.control.query.DatasourceCapabilityService;
import com.badc.sql.control.security.JwtService;
import com.badc.sql.control.security.SecurityConfig;
import com.badc.sql.control.support.JwtTestSupport;
import com.badc.sql.control.user.UserMapper;
import com.badc.sql.control.user.UserRoleService;

/**
 * 数据源接口的快速 Web 层测试：真实 {@link SecurityConfig}、真实 JWT 过滤器、真实
 * {@link OperatorGuard} 与 {@code GlobalExceptionHandler}，只有 service、{@link UserRoleService}
 * 与 {@link UserMapper} 被 mock（{@code JdbcTemplate} 也 mock 掉，用于模拟系统归属查询）。
 *
 * <p>真实 MySQL、真实凭证加密与真实 HTTP 的深覆盖见 {@link DataSourceApiIT}。
 */
@WebMvcTest({SystemController.class, DataSourceController.class})
@Import({SecurityConfig.class, JwtService.class, OperatorGuard.class})
@TestPropertySource(properties = "badcsql.jwt.secret=${BADCSQL_JWT_SECRET:badc-sql-local-dev-jwt-secret-change-me-in-production}")
class DataSourceApiTest {

    private static final String DATA_SOURCES = "/api/v1/datasources";
    private static final String DEV_AUTH = "Bearer " + JwtTestSupport.devToken(1L);
    private static final String OPS_AUTH = "Bearer " + JwtTestSupport.token(2L, "ops01");
    private static final String ADMIN_AUTH = "Bearer " + JwtTestSupport.token(3L, "admin01");

    @Autowired
    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private UserRoleService userRoleService;

    @MockBean
    private VisibilityService visibilityService;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SystemService systemService;

    @MockBean
    private DataSourceService dataSourceService;

    @MockBean
    private ConnAccountService connAccountService;

    /** 详情接口依赖能力探测；本类只测 Web 层与守卫，能力由 {@code QueryControllerIT} 端到端覆盖。 */
    @MockBean
    private DatasourceCapabilityService capabilityService;

    @MockBean
    private UserMapper userMapper;

    @Test
    void datasource_list_never_leaks_connection_info() throws Exception {
        given(visibilityService.visibleDatasourceIds(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> java.util.Set.of(101L, 102L, 999L, 1000L, 1001L, 1002L, 1003L));
        given(dataSourceService.listDatasources()).willReturn(List.of(
                new DataSourceView(101L, "订单库-只读", "order_db", 1L, "MYSQL"),
                new DataSourceView(102L, "订单归档库-只读", "order_archive", 1L, "MYSQL")));

        mockMvc.perform(get(DATA_SOURCES).header("Authorization", DEV_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dbName").value("order_db"))
                .andExpect(jsonPath("$[0].host").doesNotExist())
                .andExpect(jsonPath("$[0].port").doesNotExist())
                .andExpect(jsonPath("$[0].username").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].charset").doesNotExist())
                .andExpect(jsonPath("$[0].timeZone").doesNotExist())
                // 视图只有 id/name/dbName/systemId/type 五个字段。
                .andExpect(jsonPath("$[0].*", hasSize(5)))
                .andExpect(content().string(not(containsString("127.0.0.1"))));
    }

    /**
     * B8：列表按可见性过滤。可见集合是服务端算出的白名单，未授权的高 ID 不能出现在响应里，
     * 即使 service 把全量视图取了回来。
     */
    @Test
    void datasource_list_keeps_only_visible_datasources() throws Exception {
        given(visibilityService.visibleDatasourceIds(1L)).willReturn(Set.of(101L));
        given(dataSourceService.listDatasources()).willReturn(List.of(
                new DataSourceView(101L, "订单库-只读", "order_db", 1L, "MYSQL"),
                new DataSourceView(999L, "支付库-只读", "pay_db", 2L, "MYSQL")));

        mockMvc.perform(get(DATA_SOURCES).header("Authorization", DEV_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(101));
    }

    /**
     * 可见性服务返回 null 时列表必须是空数组，不能变成 500：读路径上宁可少给也不能崩。
     * （{@code Set.of(...).contains(null)} 与 NPE 都是这里要挡住的失败模式。）
     */
    @Test
    void datasource_list_treats_null_visibility_as_empty_instead_of_500() throws Exception {
        given(visibilityService.visibleDatasourceIds(1L)).willReturn(null);
        given(dataSourceService.listDatasources()).willReturn(List.of(
                new DataSourceView(101L, "订单库-只读", "order_db", 1L, "MYSQL")));

        mockMvc.perform(get(DATA_SOURCES).header("Authorization", DEV_AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anonymous_request_is_rejected_with_401() throws Exception {
        mockMvc.perform(get(DATA_SOURCES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void developer_cannot_create_a_system_and_gets_403() throws Exception {
        given(userRoleService.roleOf(1L)).willReturn("DEVELOPER");

        mockMvc.perform(post("/api/v1/systems")
                        .header("Authorization", DEV_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"研发不该建的系统\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(systemService);
    }

    @Test
    void primary_ops_cannot_create_a_system_because_that_is_admin_only() throws Exception {
        given(userRoleService.roleOf(2L)).willReturn("PRIMARY_OPS");

        // §4.4：建档属于管理员职责；运维只维护自己系统内的模块与数据源。
        mockMvc.perform(post("/api/v1/systems")
                        .header("Authorization", OPS_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"支付中心\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(systemService);
    }

    @Test
    void admin_can_create_a_system() throws Exception {
        given(userRoleService.roleOf(3L)).willReturn("ADMIN");
        given(systemService.createSystem("支付中心", 3L)).willReturn(new SystemEntity(4L, "支付中心"));

        mockMvc.perform(post("/api/v1/systems")
                        .header("Authorization", ADMIN_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"支付中心\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.name").value("支付中心"));
    }

    @Test
    void developer_cannot_change_connection_or_credentials() throws Exception {
        given(userRoleService.roleOf(1L)).willReturn("DEVELOPER");

        mockMvc.perform(put("/api/v1/datasources/101/connection")
                        .header("Authorization", DEV_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"10.0.0.9\",\"port\":3307,\"charset\":\"utf8mb4\",\"timeZone\":\"+00:00\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(put("/api/v1/datasources/101/accounts/readonly")
                        .header("Authorization", DEV_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dev-account\",\"password\":\"secret\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(dataSourceService, connAccountService);
    }

    /**
     * C1 的最小复现：ops01 角色够了，但 101 属于系统 1，而归属表里只有 (系统 2, ops01)。
     *
     * <p>守卫必须按数据源反查系统再查归属，否则一次 PUT 就能把别人的连接串指向自己的主机。
     */
    @Test
    void primary_ops_cannot_change_connection_of_a_datasource_in_another_system() throws Exception {
        given(userRoleService.roleOf(2L)).willReturn("PRIMARY_OPS");
        given(jdbcTemplate.queryForList("select system_id from datasource where id = ?", Long.class, 101L))
                .willReturn(List.of(1L));
        given(jdbcTemplate.queryForObject(
                "select count(*) from sys_system_operator where system_id = ? and user_id = ?",
                Integer.class, 1L, 2L)).willReturn(0);

        mockMvc.perform(put("/api/v1/datasources/101/connection")
                        .header("Authorization", OPS_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"attacker.tld\",\"port\":3306,\"charset\":\"utf8mb4\",\"timeZone\":\"+00:00\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(dataSourceService);
    }

    @Test
    void primary_ops_can_change_connection_of_a_datasource_in_their_own_system() throws Exception {
        given(userRoleService.roleOf(2L)).willReturn("PRIMARY_OPS");
        given(jdbcTemplate.queryForList("select system_id from datasource where id = ?", Long.class, 101L))
                .willReturn(List.of(2L));
        given(jdbcTemplate.queryForObject(
                "select count(*) from sys_system_operator where system_id = ? and user_id = ?",
                Integer.class, 2L, 2L)).willReturn(1);

        mockMvc.perform(put("/api/v1/datasources/101/connection")
                        .header("Authorization", OPS_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"10.0.0.9\",\"port\":3307,\"charset\":\"utf8mb4\",\"timeZone\":\"+00:00\"}"))
                .andExpect(status().isNoContent());

        verify(dataSourceService).updateConnection(101L, "10.0.0.9", 3307, "utf8mb4", "+00:00");
    }

    /** 新建数据源缺 systemId：运维拿到 400（参数问题），不能因为守卫拆箱变成 500。 */
    @Test
    void creating_a_datasource_without_system_id_is_400_not_500() throws Exception {
        given(userRoleService.roleOf(2L)).willReturn("PRIMARY_OPS");

        mockMvc.perform(post(DATA_SOURCES)
                        .header("Authorization", OPS_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"没有归属的数据源\",\"dbName\":\"x\",\"host\":\"10.0.0.9\",\"port\":3306}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(dataSourceService);
    }
}
