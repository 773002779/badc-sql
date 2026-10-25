package com.badc.sql.executor.adapter;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ValidationResult;

/**
 * 数据源适配器 SPI：新增数据库类型只需实现本接口并注册，不改工单 / 审计 / 权限模块。
 *
 * <p>执行器<b>不持久化</b> {@link ConnConfig}：它随每次请求下发，用完即弃。
 */
public interface DataSourceAdapter {

    /**
     * 探测能力：版本、是否支持 {@code EXPLAIN ANALYZE}、是否支持 INSTANT DDL、binlog 是否开启、
     * 在线 DDL 是否可用。
     *
     * @param conn            主连接配置（只读或写入账号）
     * @param onlineDdlConfig 在线 DDL 专用账号配置；未配置传 {@code null}
     */
    Capability probe(ConnConfig conn, ConnConfig onlineDdlConfig);

    /** 按目标库版本校验 SQL 是否用了该版本不支持的语法。 */
    ValidationResult validate(String sql, DbVersion version);
}
