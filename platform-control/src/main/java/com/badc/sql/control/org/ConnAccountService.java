package com.badc.sql.control.org;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.crypto.CredentialCipher;
import com.badc.sql.control.query.DatasourceCapabilityService;

/**
 * 数据源连接账号（{@code conn_account}）管理。
 *
 * <p>密码只在写接口的入参里以明文出现一次，随后立即用 {@link CredentialCipher} 加密，库里只有
 * {@code password_cipher}；任何 HTTP 响应都不返回账号或密码。
 *
 * <p>改账号 / 删账号之后要丢弃能力缓存：能力探测正是"用这个只读账号去连目标库"，凭证一变，
 * 之前探测到的版本与能力就不再可信。
 */
@Service
public class ConnAccountService {

    private final ConnAccountMapper connAccountMapper;
    private final DataSourceMapper dataSourceMapper;
    private final CredentialCipher credentialCipher;
    private final DatasourceCapabilityService capabilityService;

    public ConnAccountService(
            ConnAccountMapper connAccountMapper,
            DataSourceMapper dataSourceMapper,
            CredentialCipher credentialCipher,
            // @Lazy 是为了打破构造器循环：能力服务依赖本服务（它要用只读账号去探测），
            // 而本服务只在写账号之后才需要能力服务的 evict()。注入代理即可，不参与启动期构造顺序。
            @Lazy DatasourceCapabilityService capabilityService) {
        this.connAccountMapper = connAccountMapper;
        this.dataSourceMapper = dataSourceMapper;
        this.credentialCipher = credentialCipher;
        this.capabilityService = capabilityService;
    }

    /**
     * 读取数据源的只读账号并解密，供控制面内部调用（查询执行使用）。
     *
     * <p><b>仅供服务端内部调用</b>，绝不可作为任何 {@code /api/**} 的返回值。
     */
    public ConnConfig readonlyConnConfig(long datasourceId) {
        return connConfig(datasourceId, OrgRules.READONLY_ACCOUNT, "数据源 %d 未配置只读账号");
    }

    /**
     * 读取数据源的 dml 账号并解密，供控制面内部调用（写语句的 EXPLAIN 估算与执行）。
     *
     * <p><b>写语句为什么不能借只读账号估算</b>：MySQL 的 {@code EXPLAIN UPDATE/INSERT/DELETE} 要求对应写
     * 权限，只授 SELECT 的账号会拿到 {@code ERROR 1142 (command denied)}；执行器把它吞成
     * {@code ExplainEstimate(-1)}，风险引擎就会把每条 DML 判成 L3——L1/L2 分档在真实环境里彻底失效。
     *
     * <p><b>仅供服务端内部调用</b>，绝不可作为任何 {@code /api/**} 的返回值。
     */
    public ConnConfig dmlConnConfig(long datasourceId) {
        return connConfig(datasourceId, OrgRules.DML_ACCOUNT, "数据源 %d 未配置 dml 账号");
    }

    /**
     * 读取数据源的 ddl 账号并解密，供控制面内部调用（结构变更语句的执行）。
     *
     * <p><b>为什么 DDL 不能借 dml 账号</b>：{@code alter/create/drop} 需要建表、改表权限，dml 账号通常只授
     * {@code SELECT/INSERT/UPDATE/DELETE}。用错账号的结果是每条 DDL 都在执行时被 MySQL 以 1142 拒绝，
     * 工单从"结构变更"退化成"每条都失败"。
     *
     * <p>与 {@link #dmlConnConfig} 同构：账号缺失是 404（配置问题），不是执行结果。
     *
     * <p><b>仅供服务端内部调用</b>，绝不可作为任何 {@code /api/**} 的返回值。
     */
    public ConnConfig ddlConnConfig(long datasourceId) {
        return connConfig(datasourceId, OrgRules.DDL_ACCOUNT, "数据源 %d 未配置 ddl 账号");
    }

    private ConnConfig connConfig(long datasourceId, String accountType, String missingMessageFormat) {
        DataSourceEntity datasource = dataSourceMapper.findById(datasourceId)
                .orElseThrow(() -> OrgRules.notFound("数据源", datasourceId));
        ConnAccountEntity account = connAccountMapper
                .findByDatasourceAndType(datasourceId, accountType)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                        missingMessageFormat.formatted(datasourceId)));
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
        // 凭证变了，之前探测到的能力不再可信（探测就是拿账号去连库）。
        capabilityService.evict(datasourceId);
    }

    public void deleteAccount(long datasourceId, String accountType) {
        String normalizedType = OrgRules.normalizeAccountType(accountType);
        dataSourceMapper.findById(datasourceId).orElseThrow(() -> OrgRules.notFound("数据源", datasourceId));
        connAccountMapper.delete(datasourceId, normalizedType);
        // 账号没了，"能不能连、连到哪个版本"就成了未知；缓存必须一起丢掉。
        capabilityService.evict(datasourceId);
    }
}
