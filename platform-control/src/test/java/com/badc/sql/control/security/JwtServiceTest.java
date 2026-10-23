package com.badc.sql.control.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Date;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与解析的单元测试：固定 8 小时有效期、HS256 验签、非法/过期 token 一律
 * {@link ErrorCode#UNAUTHORIZED}。
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-unit-test-jwt-secret-0123456789";

    private final JwtService jwtService = new JwtService(SECRET);

    @Test
    void issued_token_carries_user_id_and_login_name_and_expires_in_eight_hours() {
        String token = jwtService.issue(1L, "dev01");

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("loginName", String.class)).isEqualTo("dev01");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(Duration.ofHours(8).toMillis());
        assertThat(claims.getIssuedAt()).isCloseTo(new Date(), 60_000L);
    }

    @Test
    void parse_returns_the_user_id_encoded_in_sub() {
        String token = jwtService.issue(42L, "dev01");

        assertThat(jwtService.parse(token)).isEqualTo(42L);
    }

    @Test
    void token_signed_with_another_secret_is_rejected() {
        String forged = new JwtService("another-unit-test-secret-another-unit-test-0123456789")
                .issue(1L, "dev01");

        assertUnauthorized(() -> jwtService.parse(forged));
    }

    @Test
    void expired_token_is_rejected() {
        String expired = new JwtService(SECRET, Duration.ofHours(-1)).issue(1L, "dev01");

        assertUnauthorized(() -> jwtService.parse(expired));
    }

    @Test
    void malformed_or_empty_token_is_rejected() {
        assertUnauthorized(() -> jwtService.parse("not-a-jwt"));
        assertUnauthorized(() -> jwtService.parse(""));
        assertUnauthorized(() -> jwtService.parse(null));
    }

    @Test
    void secret_shorter_than_32_bytes_is_rejected_at_startup() {
        assertThatThrownBy(() -> new JwtService("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    private static void assertUnauthorized(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
