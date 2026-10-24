package com.badc.sql.control.org;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.badc.sql.control.security.JwtService;
import com.badc.sql.control.security.SecurityConfig;
import com.badc.sql.control.support.JwtTestSupport;
import com.badc.sql.control.user.UserMapper;

/**
 * 数据源接口的快速 Web 层测试：真实 {@link SecurityConfig}、真实 JWT 过滤器、真实
 * {@link OperatorGuard} 与 {@code GlobalExceptionHandler}，只有 service 与 {@link UserMapper} 被 mock。
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

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SystemService systemService;

    @MockBean
    private DataSourceService dataSourceService;

    @MockBean
    private ConnAccountService connAccountService;

    @MockBean
    private UserMapper userMapper;

    @Test
    void datasource_list_never_leaks_connection_info() throws Exception {
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

    @Test
    void anonymous_request_is_rejected_with_401() throws Exception {
        mockMvc.perform(get(DATA_SOURCES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void developer_cannot_create_a_system_and_gets_403() throws Exception {
        given(userMapper.findRoleById(1L)).willReturn(Optional.of("DEVELOPER"));

        mockMvc.perform(post("/api/v1/systems")
                        .header("Authorization", DEV_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"研发不该建的系统\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(systemService);
    }

    @Test
    void primary_ops_can_create_a_system() throws Exception {
        given(userMapper.findRoleById(2L)).willReturn(Optional.of("PRIMARY_OPS"));
        given(systemService.createSystem("支付中心")).willReturn(new SystemEntity(3L, "支付中心"));

        mockMvc.perform(post("/api/v1/systems")
                        .header("Authorization", OPS_AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"支付中心\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("支付中心"));
    }

    @Test
    void developer_cannot_change_connection_or_credentials() throws Exception {
        given(userMapper.findRoleById(1L)).willReturn(Optional.of("DEVELOPER"));

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
}
