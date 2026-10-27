package com.badc.sql.control.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.badc.sql.control.audit.AuditService;
import com.badc.sql.control.security.JwtService;
import com.badc.sql.control.security.SecurityConfig;

/**
 * 登录的快速 Web 层测试：真实 {@link SecurityConfig} + 真实 {@link JwtService} + 真实
 * {@code BCryptPasswordEncoder}，只有 {@link UserMapper} 被 mock，不连数据库。
 *
 * <p>更完整的真实 HTTP + 真实 MySQL 覆盖见 {@link AuthControllerIT}。
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class})
@TestPropertySource(properties = "badcsql.jwt.secret=unit-test-jwt-secret-unit-test-jwt-secret-0123456789")
class AuthControllerTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    /** seed/test-fixtures.sql 中 dev01 的 BCrypt 哈希，明文 Passw0rd!。 */
    private static final String DEV01_PASSWORD_HASH =
            "$2b$10$TznpaUV2xFOaY3aN9B0lpe0H6sbVMVrDBekF4fxgICSl5BcU9SVGW";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserMapper userMapper;

    /** 审计链在切片里由 T8 引入；Web 层单测不连库，用 mock 顶替。 */
    @MockBean
    private AuditService auditService;

    @Test
    void login_with_valid_password_returns_token() throws Exception {
        givenDev01();

        mockMvc.perform(post(LOGIN_PATH).contentType(APPLICATION_JSON)
                        .content("{\"loginName\":\"dev01\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.loginName").value("dev01"));
    }

    @Test
    void login_with_wrong_password_returns_401() throws Exception {
        givenDev01();

        mockMvc.perform(post(LOGIN_PATH).contentType(APPLICATION_JSON)
                        .content("{\"loginName\":\"dev01\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void login_with_unknown_user_returns_the_same_401_body_as_a_wrong_password() throws Exception {
        givenDev01();
        String wrongPassword = unauthorizedLoginBody("dev01", "wrong");

        given(userMapper.findByLoginName("ghost")).willReturn(Optional.empty());
        String unknownUser = unauthorizedLoginBody("ghost", "Passw0rd!");

        assertThat(unknownUser).isEqualTo(wrongPassword).contains("UNAUTHORIZED");
    }

    @Test
    void api_paths_require_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void paths_outside_api_are_denied_by_default() throws Exception {
        // fail-closed：新增端点若挂错前缀，默认是关闭的，必须显式开放。
        mockMvc.perform(get("/health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void givenDev01() {
        given(userMapper.findByLoginName("dev01")).willReturn(Optional.of(
                new SysUser(1L, "dev01", DEV01_PASSWORD_HASH, "DEVELOPER", "研发一号")));
    }

    private String unauthorizedLoginBody(String loginName, String password) throws Exception {
        return mockMvc.perform(post(LOGIN_PATH).contentType(APPLICATION_JSON)
                        .content("{\"loginName\":\"%s\",\"password\":\"%s\"}".formatted(loginName, password)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
