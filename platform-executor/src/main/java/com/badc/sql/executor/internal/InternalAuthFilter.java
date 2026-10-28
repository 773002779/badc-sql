package com.badc.sql.executor.internal;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 执行器 {@code /internal/**} 的内部令牌校验。
 *
 * <p>这些接口的请求体里带着<b>解密后的数据库密码</b>，所以认证缺失就等于"知道端口即可拖库"。
 * 校验失败一律 401，且<b>默认拒绝</b>：{@code badcsql.internal.token} 未配置时拒绝全部内部请求，
 * 执行器不会因为运维漏配而退化成裸接口。
 *
 * <p>令牌只从配置读取、不设字面默认值——切片 1 的 {@code root/root} 默认值已经把"本机能跑"
 * 变成了"生产能连"。
 */
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    /** 控制面与执行器约定的令牌头。 */
    private static final String TOKEN_HEADER = "X-Badc-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(InternalAuthFilter.class);

    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"内部令牌校验失败\"}";

    private static final String JSON_UTF_8 = "application/json;charset=UTF-8";

    private final String expectedToken;

    public InternalAuthFilter(@Value("${badcsql.internal.token:}") String expectedToken) {
        // 未配置（null）与配成空串是同一件事：没有可用令牌，一律拒绝。
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @PostConstruct
    void warnWhenTokenIsMissing() {
        if (expectedToken.isEmpty()) {
            log.error("badcsql.internal.token 未配置：执行器将拒绝全部内部请求");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!InternalToken.matches(expectedToken, request.getHeader(TOKEN_HEADER))) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(JSON_UTF_8);
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
