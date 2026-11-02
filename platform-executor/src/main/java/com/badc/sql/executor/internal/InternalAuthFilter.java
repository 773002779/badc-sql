package com.badc.sql.executor.internal;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;

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
 * <p><b>判定路径与分发器同源。</b>Spring MVC（Boot 3.2，{@code PathPatternParser}）用剥掉 {@code ;}
 * 矩阵参数后的 lookup path 路由，而 {@code getRequestURI()} 原样保留矩阵参数；只看原始 URI 会让
 * {@code /internal;x=1/v1/fingerprint} 这类请求跳过鉴权、仍被分发器送进控制器。执行器没有
 * spring-security，也就没有 {@code StrictHttpFirewall} 兜底。因此这里同时看原始 URI 与
 * {@link ServletRequestPathUtils} 解析出的 lookup path（<b>任一</b>命中即校验），并且<b>一律拒绝</b>
 * 含路径参数的请求——不猜它想打哪个端点，也就不留"路径参数 + 正确令牌"的口子。
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

    /** 矩阵参数分隔符：带了它就不再做任何路径推断，直接 401。 */
    private static final char PATH_PARAMETER_SEPARATOR = ';';

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

    /**
     * 只有<b>确定不是</b>内部请求时才跳过校验。
     *
     * <p>原始 URI 与分发器同源的 lookup path <b>任一</b>指向 {@code /internal/} 都必须过闸：前者挡住
     * 常规请求，后者挡住"原始 URI 被 {@code ;} 参数或 context path 改写、分发器却仍按内部路径路由"的请求。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !targetsInternal(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (hasPathParameters(request)) {
            // 路径参数不做例外：既不猜它想打哪个端点，也不接受"带对令牌"的路径参数请求。
            log.warn("拒绝带路径参数的内部请求：{}", request.getRequestURI());
            reject(response);
            return;
        }
        if (!InternalToken.matches(expectedToken, request.getHeader(TOKEN_HEADER))) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 原始 URI 或 lookup path 任一命中内部前缀，就按内部请求处理。 */
    private static boolean targetsInternal(HttpServletRequest request) {
        if (request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX)) {
            return true;
        }
        try {
            return lookupPath(request).startsWith(INTERNAL_PATH_PREFIX);
        } catch (RuntimeException e) {
            // 归一化路径取不出来时不能猜：按内部请求处理，让它照常过令牌校验（fail-closed）。
            log.warn("无法解析请求的 lookup path，按内部请求处理：{}", request.getRequestURI(), e);
            return true;
        }
    }

    /**
     * 与 {@code DispatcherServlet} 完全同源的路径。
     *
     * <p>两步都与 {@code AbstractHandlerMapping#initLookupPath} 一致：先取分发器解析出的
     * {@code RequestPath#pathWithinApplication()}（已剥掉 context path），再用同一个
     * {@link UrlPathHelper} 剥掉 {@code ;} 矩阵参数。自己拼字符串迟早会与分发器的规则漂移
     * （不止矩阵参数，还有编码、重复斜杠等）。
     */
    private static String lookupPath(HttpServletRequest request) {
        String withinApplication =
                ServletRequestPathUtils.parseAndCache(request).pathWithinApplication().value();
        return UrlPathHelper.defaultInstance.removeSemicolonContent(withinApplication);
    }

    private static boolean hasPathParameters(HttpServletRequest request) {
        return request.getRequestURI().indexOf(PATH_PARAMETER_SEPARATOR) >= 0;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(JSON_UTF_8);
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
