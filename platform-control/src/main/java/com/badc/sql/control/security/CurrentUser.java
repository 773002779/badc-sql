package com.badc.sql.control.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 当前请求的登录用户。
 *
 * <p>身份由 {@link JwtAuthFilter} 从 JWT 解析后写入 {@code SecurityContext}；未认证时访问本类属于编程错误，
 * 抛 {@link ErrorCode#UNAUTHORIZED}（正常请求在过滤器链就会被 401 拦下）。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前登录用户 ID。 */
    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }
}
