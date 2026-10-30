package com.badc.sql.control.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前 HTTP 请求的来源信息（IP / User-Agent），供审计与风险确认留痕。
 *
 * <p>从 {@link RequestContextHolder} 取而不是把 {@link HttpServletRequest} 传进 Service：服务层不必依赖
 * Servlet API，非 web 调用方（定时任务、测试里的直接调用）拿到的就是 {@code null}——两列本就允许为空，
 * "没有请求上下文"不该让一次合法的落库失败。
 *
 * <p>值在构造时截断到目标列宽（{@code ip VARCHAR(64)}、{@code user_agent VARCHAR(512)}）：超长 UA 是
 * 常态（浏览器串 + 插件串轻松过千），不截断会让一次成功的提交在落库时炸成 500。
 */
public final class RequestMetadata {

    private static final int IP_MAX_LENGTH = 64;
    private static final int USER_AGENT_MAX_LENGTH = 512;

    private RequestMetadata() {
    }

    /** 当前请求的来源；不在 web 请求线程里时两值为 {@code null}。 */
    public static Client current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequest) {
            HttpServletRequest request = servletRequest.getRequest();
            return new Client(request.getRemoteAddr(), request.getHeader("User-Agent"));
        }
        return new Client(null, null);
    }

    /**
     * 来源信息。
     *
     * @param ip        客户端地址（{@link HttpServletRequest#getRemoteAddr()}），最多 64 字符
     * @param userAgent {@code User-Agent} 头原文，最多 512 字符
     */
    public record Client(String ip, String userAgent) {

        public Client {
            ip = truncate(ip, IP_MAX_LENGTH);
            userAgent = truncate(userAgent, USER_AGENT_MAX_LENGTH);
        }

        private static String truncate(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) {
                return value;
            }
            return value.substring(0, maxLength);
        }
    }
}
