package com.badc.sql.control.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 无状态安全配置：JWT 过滤器 + 路径授权。
 *
 * <p>除 {@code /api/v1/auth/login} 外所有 {@code /api/**} 端点都要求认证；未认证一律 401（绝不 403），
 * 错误体与 {@code @RestControllerAdvice} 保持一致。
 *
 * <p>授权默认是 <b>fail-closed</b>：没有明确登记的路径一律 {@code denyAll()}。新增端点若挂错前缀
 * （例如漏了 {@code /api}），会直接关闭，而不是悄悄对外开放；需要公开时必须显式加一条规则。
 * 容器内部的 {@code ERROR} 派发（转发到 {@code /error}）单独放行，否则 4xx/5xx 的错误页本身会被
 * 安全链拦掉，把 404 变成 401/403 或空响应体。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String LOGIN_PATH = "/api/v1/auth/login";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // 错误派发必须先放行：Spring Boot 把未处理异常转发到 /error，若被 denyAll 拦下，
                        // 404/500 会退化成 401/403 或空 body。
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(LOGIN_PATH).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // fail closed：未登记路径默认关闭，不随新增控制器自动开放。
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, ErrorCode.UNAUTHORIZED, objectMapper))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, ErrorCode.FORBIDDEN, objectMapper)))
                // 直接在配置里 new：若把 filter 注册成 bean，Spring Boot 会再把它当普通 servlet filter
                // 注册一次，导致每个请求执行两遍。
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    private static void writeError(HttpServletResponse response, ErrorCode code, ObjectMapper objectMapper)
            throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code.name(), code.defaultMessage()));
    }
}
