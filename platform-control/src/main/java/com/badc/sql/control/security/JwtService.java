package com.badc.sql.control.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与解析（HS256）。
 *
 * <p>密钥取 {@code badcsql.jwt.secret}（环境变量 {@code BADCSQL_JWT_SECRET} 覆盖），有效期固定 8 小时；
 * claims 至少包含 {@code sub}（用户 ID）与 {@code loginName}。解析失败（签名不符、过期、格式错误）一律
 * 抛 {@link ErrorCode#UNAUTHORIZED}，不向调用方区分原因。
 *
 * <p>若密钥仍等于 {@link #DEFAULT_SECRET}（即没有用 {@code BADCSQL_JWT_SECRET} 覆盖），启动时打一条
 * WARN：本地开发照常可用，但生产漏配会把「源码里公开的密钥」当成签名密钥。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** token 有效期：8 小时。 */
    static final Duration TTL = Duration.ofHours(8);

    /** HS256 要求密钥至少 256 位。 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 与 {@code application.yml} 中 {@code badcsql.jwt.secret} 的本地默认值保持一致。 */
    static final String DEFAULT_SECRET = "badc-sql-local-dev-jwt-secret-change-me-in-production";

    private static final String LOGIN_NAME_CLAIM = "loginName";

    private final SecretKey key;
    private final Duration ttl;

    @Autowired
    public JwtService(@Value("${badcsql.jwt.secret}") String secret) {
        this(secret, TTL);
    }

    JwtService(String secret, Duration ttl) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "badcsql.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节（HS256），当前为 "
                            + secretBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.ttl = ttl;
        if (DEFAULT_SECRET.equals(secret)) {
            // 只告警、不阻断：本地开发必须能直接跑起来。
            log.warn("badcsql.jwt.secret 仍在用代码库中的默认值（未设置环境变量 BADCSQL_JWT_SECRET）："
                    + "签名密钥是公开的，任何拿到源码的人都能伪造出合法的登录 token；"
                    + "生产部署必须通过 BADCSQL_JWT_SECRET 覆盖。");
        }
    }

    /** 签发 token。 */
    public String issue(Long userId, String loginName) {
        Instant issuedAt = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(LOGIN_NAME_CLAIM, loginName)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 校验签名与有效期，返回 token 中的用户 ID。 */
    public Long parse(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Long.valueOf(subject);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage(), e);
        }
    }
}
