package com.badc.sql.control.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * 凭证加密的纯单元测试（真实 AES-256-GCM，不 mock 加密实现）。
 *
 * <p>密文格式固定为 {@code base64(iv || ciphertext || tag)}：IV 12 字节，GCM tag 16 字节。
 */
class CredentialCipherTest {

    /** 32 字节测试密钥的 base64，与 {@code src/test/resources/application.properties} 保持一致。 */
    static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void encrypt_then_decrypt_returns_the_original_plaintext() {
        CredentialCipher cipher = new CredentialCipher(TEST_KEY);

        assertThat(cipher.decrypt(cipher.encrypt("p@ss"))).isEqualTo("p@ss");
    }

    @Test
    void roundtrip_preserves_unicode_and_long_values() {
        CredentialCipher cipher = new CredentialCipher(TEST_KEY);
        String plain = "密码-🔐-" + "x".repeat(1024);

        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void ciphertext_is_base64_iv_ciphertext_tag_and_never_contains_the_plaintext() {
        CredentialCipher cipher = new CredentialCipher(TEST_KEY);
        String plain = "p@ss";

        String encrypted = cipher.encrypt(plain);
        byte[] raw = Base64.getDecoder().decode(encrypted);

        assertThat(encrypted).doesNotContain(plain);
        // 12 字节 IV + 与明文等长的密文 + 16 字节 GCM tag。
        assertThat(raw).hasSize(12 + plain.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void same_plaintext_encrypts_to_different_ciphertexts_because_the_iv_is_random() {
        CredentialCipher cipher = new CredentialCipher(TEST_KEY);

        assertThat(cipher.encrypt("p@ss")).isNotEqualTo(cipher.encrypt("p@ss"));
    }

    @Test
    void another_cipher_with_the_same_key_can_decrypt() {
        String encrypted = new CredentialCipher(TEST_KEY).encrypt("p@ss");

        assertThat(new CredentialCipher(TEST_KEY).decrypt(encrypted)).isEqualTo("p@ss");
    }

    @Test
    void decrypt_rejects_a_tampered_ciphertext_instead_of_returning_garbage() {
        CredentialCipher cipher = new CredentialCipher(TEST_KEY);
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("p@ss"));
        raw[raw.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_with_another_key_fails_authentication() {
        String encrypted = new CredentialCipher(TEST_KEY).encrypt("p@ss");
        CredentialCipher other = new CredentialCipher(
                Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missing_key_fails_fast_at_construction_with_a_clear_message() {
        assertThatThrownBy(() -> new CredentialCipher(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BADCSQL_CRED_KEY");
        assertThatThrownBy(() -> new CredentialCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BADCSQL_CRED_KEY");
        assertThatThrownBy(() -> new CredentialCipher("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BADCSQL_CRED_KEY");
    }

    @Test
    void invalid_base64_key_fails_fast_at_construction() {
        assertThatThrownBy(() -> new CredentialCipher("not-base64!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void key_that_does_not_decode_to_32_bytes_fails_fast_at_construction() {
        String sixteenBytes = Base64.getEncoder()
                .encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new CredentialCipher(sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
