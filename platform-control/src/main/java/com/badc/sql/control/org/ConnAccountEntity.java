package com.badc.sql.control.org;

/**
 * {@code conn_account} 的只读投影，仅供服务端内部使用。
 *
 * <p>{@code passwordCipher} 是 AES-256-GCM 密文；{@link #toString()} 对其脱敏，避免对象被顺手打进日志。
 */
public record ConnAccountEntity(
        Long id,
        Long datasourceId,
        String accountType,
        String username,
        String passwordCipher) {

    @Override
    public String toString() {
        return "ConnAccountEntity[id=" + id
                + ", datasourceId=" + datasourceId
                + ", accountType=" + accountType
                + ", username=" + username
                + ", passwordCipher=***]";
    }
}
