package com.badc.sql.control.support;

import com.badc.sql.control.security.JwtService;

/**
 * 测试用 JWT 工厂：为集成测试签发 {@code dev01} 身份（userId=1）的合法 token。
 *
 * <p>密钥与 {@code application.yml} 中 {@code badcsql.jwt.secret} 的本地默认值保持一致；任何环境都可用
 * {@code BADCSQL_JWT_SECRET} 覆盖（与运行中的应用取同一个值）。{@code AuthControllerIT} 用真实接口对
 * 该 token 做端到端校验，密钥若漂移会立刻失败。
 */
public final class JwtTestSupport {

    /** 必须与 application.yml 中 badcsql.jwt.secret 的默认值一致。 */
    static final String DEFAULT_SECRET = "badc-sql-local-dev-jwt-secret-change-me-in-production";

    private JwtTestSupport() {
    }

    /** dev01 的 token。 */
    public static String devToken(long userId) {
        return token(userId, "dev01");
    }

    /** 指定登录名的 token。 */
    public static String token(long userId, String loginName) {
        return new JwtService(secret()).issue(userId, loginName);
    }

    private static String secret() {
        return System.getenv().getOrDefault("BADCSQL_JWT_SECRET", DEFAULT_SECRET);
    }
}
