package com.badc.sql.control.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 连接凭证的 AES-256-GCM 加解密。
 *
 * <p>密钥来自 {@code badcsql.crypto.credential-key}（环境变量 {@code BADCSQL_CRED_KEY}，32 字节
 * 标准 base64）。密文格式固定为 {@code base64(iv || ciphertext || tag)}：12 字节随机 IV、
 * 与明文等长的密文、16 字节 GCM tag。每次加密都换随机 IV，因此同一明文不会得到相同密文。
 *
 * <p><b>构造即校验</b>：密钥缺失、不是合法 base64、或解码后不是 32 字节，直接抛
 * {@link IllegalStateException}，让应用在启动阶段失败，而不是等到第一次查询时才发现凭证不可用。
 *
 * <p>密码明文只存在于本类方法的入参与返回值中；本类不提供 {@code toString()}，不记录任何日志。
 */
@Component
public class CredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String PROPERTY = "badcsql.crypto.credential-key";
    private static final String ENV_VARIABLE = "BADCSQL_CRED_KEY";

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int MIN_CIPHERTEXT_BYTES = IV_BYTES + TAG_BYTES;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${badcsql.crypto.credential-key:}") String base64Key) {
        this.key = parseKey(base64Key);
    }

    /** 加密明文密码，返回 {@code base64(iv || ciphertext || tag)}。 */
    public String encrypt(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("待加密的凭证不能为 null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, payload, IV_BYTES, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    /** 解密 {@code base64(iv || ciphertext || tag)}；密文被篡改或密钥不匹配时抛异常。 */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            throw new IllegalArgumentException("待解密的凭证密文不能为空");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(cipherText.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("凭证密文不是合法的 base64", e);
        }
        if (payload.length < MIN_CIPHERTEXT_BYTES) {
            throw new IllegalStateException(
                    "凭证密文长度不合法：至少需要 " + MIN_CIPHERTEXT_BYTES + " 字节，实际为 " + payload.length + " 字节");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("凭证解密失败：密文被篡改或密钥不匹配", e);
        }
    }

    private static SecretKey parseKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "缺少连接凭证加密密钥：请通过环境变量 " + ENV_VARIABLE + " 提供 32 字节 base64 密钥（配置项 "
                            + PROPERTY + "）");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "连接凭证加密密钥不是合法的 base64（" + ENV_VARIABLE + "）：请提供 32 字节标准 base64", e);
        }
        if (keyBytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "连接凭证加密密钥长度必须为 " + KEY_BYTES + " 字节（AES-256），当前为 " + keyBytes.length
                            + " 字节；请检查 " + ENV_VARIABLE);
        }
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}
