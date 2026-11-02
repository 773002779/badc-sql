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

    /**
     * 矩阵参数（{@code ;x=1}）不能把请求从闸门外送进控制器。
     *
     * <p>Spring MVC（Boot 3.2，{@code PathPatternParser}）按<b>剥掉 {@code ;} 参数后的 lookup path</b> 路由，
     * 而 {@code getRequestURI()} 原样带着 {@code ;x=1}。只看原始 URI 的闸门会放行一个分发器仍认作
     * {@code /internal/v1/validate} 的请求——等于知道端口就能无令牌调用内部端点。
     */
    @Test
    void a_matrix_parameter_cannot_smuggle_a_request_past_the_gate() throws Exception {
        mockMvc(TOKEN).perform(post("/internal;x=1/v1/validate")
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 含 {@code ;} 的请求一律拒：不猜它想打哪个端点，也就不给"路径参数 + 正确令牌"留开口。 */
    @Test
    void a_request_with_a_path_parameter_is_rejected_even_with_the_right_token() throws Exception {
        mockMvc(TOKEN).perform(post("/internal;x=1/v1/validate").header(TOKEN_HEADER, TOKEN)
                        .contentType(APPLICATION_JSON).content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 只拦 {@code /internal/**}：健康检查之类的路径不能被令牌挡住。 */
    @Test
    void only_internal_paths_are_gated() {
        InternalAuthFilter filter = new InternalAuthFilter(TOKEN);

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", VALIDATE_PATH))).isFalse();
    }

    /**
     * 设了 {@code server.servlet.context-path} 时 {@code getRequestURI()} 整体带前缀，
     * 只看原始 URI 的闸门会对<b>所有</b>内部接口 fail-open。
     */
    @Test
    void a_context_path_does_not_open_the_gate() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/exec/internal/v1/validate");
        request.setContextPath("/exec");

        assertThat(new InternalAuthFilter(TOKEN).shouldNotFilter(request)).isFalse();
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
