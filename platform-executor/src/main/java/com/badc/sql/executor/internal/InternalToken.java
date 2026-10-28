package com.badc.sql.executor.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部令牌的常量时间比较。
 *
 * <p>不用 {@code String.equals}：它按字符提前返回，攻击者能靠响应耗时逐位猜出令牌。
 * 任一侧为 {@code null} 或空都返回 {@code false}——空令牌意味着配置缺失，不是"免检"。
 */
public final class InternalToken {

    private InternalToken() {
    }

    /**
     * 比较期望令牌与实际令牌，耗时与两者内容无关。
     *
     * @param expected 配置的令牌；为空表示未配置
     * @param actual   请求头带来的令牌；为空表示没带
     * @return 两者均非空且逐字节相等时为 {@code true}
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || expected.isEmpty() || actual == null || actual.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
