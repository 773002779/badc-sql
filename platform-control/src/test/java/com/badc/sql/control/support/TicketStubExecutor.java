package com.badc.sql.control.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.control.query.ExecutorClient;

/**
 * 工单类集成测试共用的执行器桩。
 *
 * <p>工单要对的是<b>提交流程本身</b>——鉴权、可见性、状态机、漂移检测——而不是 SQL 执行，所以查询与探测
 * 一律不支持（真被调用说明控制面走错了路，直接炸出来比返回假数据更容易定位）。
 *
 * <p><b>默认值必须让正常路径通过</b>：提交时取一次指纹、执行时再取一次，默认两次返回同一组值
 * （{@code t_order → fp-1}），漂移检测才不会误报。要造"结构漂移"的用例改 {@link #fingerprints} 即可，
 * 不必新写一个桩。
 *
 * <p>后续任务会往这里加方法（快照、EXPLAIN 估算等），保持"记录入参 + 可设定的返回值"这一风格。
 */
public class TicketStubExecutor implements ExecutorClient {

    /** 指纹入口的返回值；默认含一张表，提交与执行两次取值相同，正常路径无漂移。 */
    public Map<String, String> fingerprints = new LinkedHashMap<>(Map.of("t_order", "fp-1"));

    /** 最近一次取指纹的入参表名，供用例断言控制面传了什么。 */
    public List<String> lastFingerprintTables = List.of();

    @Override
    public RawResult query(ConnConfig conn, String sql, int maxRows) {
        throw new UnsupportedOperationException("工单测试不走这个入口");
    }

    @Override
    public Capability probe(ConnConfig conn, ConnConfig onlineDdlOrNull) {
        throw new UnsupportedOperationException("工单测试不走这个入口");
    }

    @Override
    public ValidationResult validate(ConnConfig conn, String sql, DbVersion version) {
        return ValidationResult.success();
    }

    @Override
    public Map<String, String> fingerprint(ConnConfig conn, List<String> tables) {
        this.lastFingerprintTables = tables;
        return fingerprints;
    }
}
