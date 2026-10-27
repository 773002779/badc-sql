package com.badc.sql.control.query;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.ValidationResult;

/**
 * 调用执行器的出向接口。
 *
 * <p>抽成接口是为了让控制面的测试能替换成桩——控制面要验证的是鉴权、可见性、只读白名单与
 * LIMIT 注入，不是 SQL 执行本身。
 *
 * <p>版本能力与方言校验也在执行器侧（只有执行器能连到目标库，也只有它持有方言实现），控制面只负责
 * 编排：探测一次、缓存、每次查询前校验。这三件事都经由本接口，控制面不复制方言规则。
 */
public interface ExecutorClient {

    RawResult query(ConnConfig conn, String sql, int maxRows);

    /**
     * 探测数据源能力（版本、{@code EXPLAIN ANALYZE} / INSTANT DDL 支持、binlog、在线 DDL）。
     *
     * @param conn             只读账号的连接配置
     * @param onlineDdlOrNull  在线 DDL 专用账号；数据源未配置时传 {@code null}
     * @throws com.badc.sql.common.error.BizException 探测失败（库不可达、权限不足、执行器不可用）
     */
    Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull);

    /**
     * 按目标库版本校验 SQL 是否用了该版本不支持的语法。
     *
     * @param conn    连接配置；当前执行器只按版本判定，保留该参数给后续按连接特性（如 {@code sql_mode}）判定
     * @param sql     已注入行数上限的最终 SQL——用户实际要执行的就是它，校验必须针对它
     * @param version 探测到的版本；调用方不应在版本未知时调用本方法
     * @throws com.badc.sql.common.error.BizException 执行器不可用（不是"SQL 不合法"，后者由返回值表达）
     */
    ValidationResult validate(ConnConfig conn, String sql, DbVersion version);
}
