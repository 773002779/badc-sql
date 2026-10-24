package com.badc.sql.control.org;

import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.crypto.CredentialCipher;

/**
 * 数据源连接账号（{@code conn_account}）管理。
 *
 * <p>密码只在写接口的入参里以明文出现一次，随后立即用 {@link CredentialCipher} 加密，库里只有
 * {@code password_cipher}；任何 HTTP 响应都不返回账号或密码。
 */
@Service
public class ConnAccountService {

    private final ConnAccountMapper connAccountMapper;
    private final DataSourceMapper dataSourceMapper;
    private final CredentialCipher credentialCipher;

    public ConnAccountService(
            ConnAccountMapper connAccountMapper,
            DataSourceMapper dataSourceMapper,
            CredentialCipher credentialCipher) {
        this.connAccountMapper = connAccountMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.credentialCipher = credentialCipher;
    }

    /**
     * 读取数据源的只读账号并解密，供控制面内部调用（查询执行使用）。
     *
     * <p><b>仅供服务端内部调用</b>，绝不可作为任何 {@code /api/**} 的返回值。
     */
    public ConnConfig readonlyConnConfig(long datasourceId) {
        DataSourceEntity datasource = dataSourceMapper.findById(datasourceId)
                .orElseThrow(() -> OrgRules.notFound("数据源", datasourceId));
        ConnAccountEntity account = connAccountMapper
                .findByDatasourceAndType(datasourceId, OrgRules.READONLY_ACCOUNT)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        "数据源 " + datasourceId + " 未配置只读账号"));
        return new ConnConfig(
                datasource.host(),
                datasource.port(),
                datasource.dbName(),
                account.username(),
                credentialCipher.decrypt(account.passwordCipher()),
                datasource.charset(),
                datasource.timeZone());
    }

    /** 新建或轮换账号；同一数据源的同一账号类型只保留一行。 */
    public void saveAccount(long datasourceId, String accountType, String username, String plainPassword) {
        String normalizedType = OrgRules.normalizeAccountType(accountType);
        dataSourceMapper.findById(datasourceId).orElseThrow(() -> OrgRules.notFound("数据源", datasourceId));
        String normalizedUsername = OrgRules.requireText(username, "数据库用户名", 128);
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "数据库密码不能为空");
        }
        connAccountMapper.upsert(datasourceId, normalizedType, normalizedUsername,
                credentialCipher.encrypt(plainPassword));
    }

    public void deleteAccount(long datasourceId, String accountType) {
        String normalizedType = OrgRules.normalizeAccountType(accountType);
        dataSourceMapper.findById(datasourceId).orElseThrow(() -> OrgRules.notFound("数据源", datasourceId));
        connAccountMapper.delete(datasourceId, normalizedType);
    }
}
