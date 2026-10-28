package com.badc.sql.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行器 {@code /internal/**} 的令牌校验。
 *
 * <p>这些接口的请求体里带着<b>解密后的数据库密码</b>，认证缺失就是"知道端口即可拖库"。
 * 因此这里钉住的是 fail-closed：没配令牌时执行器拒绝一切，而不是退化成裸接口。
 */
class InternalAuthFilterTest {

    private static final String TOKEN = "s3cret";
    private static final String TOKEN_HEADER = "X-Badc-Internal-Token";
    private static final String VALIDATE_PATH = "/internal/v1/validate";
    private static final String REQUEST_BODY = "{\"sql\":\"select 1\"}";

    @Test
    void a_request_without_the_token_is_rejected() throws Exception {
        mockMvc(TOKEN).perform(post(VALIDATE_PATH)
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("内部令牌校验失败"));
    }

    @Test
    void a_request_with_a_wrong_token_is_rejected() throws Exception {
        mockMvc(TOKEN).perform(post(VALIDATE_PATH).header(TOKEN_HEADER, "wrong")
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void a_request_with_the_right_token_passes() throws Exception {
        mockMvc(TOKEN).perform(post(VALIDATE_PATH).header(TOKEN_HEADER, TOKEN)
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /** fail-closed：运维忘了配令牌，执行器不能变成"谁都能连"的裸接口。 */
    @Test
    void an_unconfigured_token_rejects_everything() throws Exception {
        mockMvc("").perform(post(VALIDATE_PATH).header(TOKEN_HEADER, "")
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc("").perform(post(VALIDATE_PATH)
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    /** 拒绝体必须是控制面能解析的 JSON，且声明 UTF-8（否则中文提示会变成乱码）。 */
    @Test
    void the_rejection_body_is_utf8_json() throws Exception {
        mockMvc(TOKEN).perform(post(VALIDATE_PATH)
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"内部令牌校验失败\"}"));
    }

    /** 只拦 {@code /internal/**}：健康检查之类的路径不能被令牌挡住。 */
    @Test
    void only_internal_paths_are_gated() {
        InternalAuthFilter filter = new InternalAuthFilter(TOKEN);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", VALIDATE_PATH))).isFalse();
    }

    private static MockMvc mockMvc(String configuredToken) {
        return MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(new InternalAuthFilter(configuredToken))
                .build();
    }

    /** 只用来让过滤器之后的链路走通，不代表真实的校验接口。 */
    @RestController
    static class StubController {

        @PostMapping(VALIDATE_PATH)
        Map<String, Object> validate() {
            return Map.of("ok", true);
        }
    }
}
