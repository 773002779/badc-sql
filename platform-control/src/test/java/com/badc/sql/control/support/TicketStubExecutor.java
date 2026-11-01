package com.badc.sql.control.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.Capability;
import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExecAccountType;
import com.badc.sql.common.dto.ExecuteResult;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.dto.ValidationResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.query.ExecutorClient;

/**
 * 工单类集成测试共用的执行器桩。
 *
 * <p>工单要对的是<b>编排流程本身</b>——鉴权、状态机、漂移检测、逐条执行与汇总——而不是 SQL 执行，所以查询与
 * 探测一律不支持（真被调用说明控制面走错了路，直接炸出来比返回假数据更容易定位）。
 *
 * <p><b>默认值必须让正常路径通过</b>：提交时取一次指纹、执行时再取一次，默认两次返回同一组值
 * （{@code t_order → fp-1}），漂移检测才不会误报；{@code execute} 默认成功且 0 行。要造"结构漂移"
 * 的用例改 {@link #fingerprints}，要造"部分失败"改 {@link #failOnStatementIndex} 或 {@link #executeResults}，
 * 不必新写一个桩。
 *
 * <p>后续任务会往这里加方法，保持"记录入参 + 可设定的返回值"这一风格。
 */
public class TicketStubExecutor implements ExecutorClient {

    /** 指纹入口的返回值；默认含一张表，提交与执行两次取值相同，正常路径无漂移。 */
    public Map<String, String> fingerprints = new LinkedHashMap<>(Map.of("t_order", "fp-1"));

    /** 最近一次取指纹的入参表名，供用例断言控制面传了什么。 */
    public List<String> lastFingerprintTables = List.of();

    /** 让 fingerprint 抛 {@code BizException}，模拟库不可达 / 权限不足（区别于"表不存在"的空 map）。 */
    public boolean fingerprintThrows;

    /**
     * EXPLAIN 的行数估算；默认 1，让 {@code update ... where id = 1} 这类典型语句落在 L1——工单测试要的是
     * 编排流程本身，不该被一个虚构的大行数顶到 L3 去走两级审批。
     *
     * <p>要造"风险随估算升级"的用例改这个字段即可；{@code -1}（无法估算）也要能设。
     */
    public ExplainEstimate explainEstimate = new ExplainEstimate(1);

    /** 最近一次估算的入参语句，供用例断言控制面把哪条语句拿去估算了。 */
    public String lastExplainedSql;

    /** explain 的调用次数：DDL 不该被估算，应为 0。 */
    public int explainCalls;

    /**
     * explain 使用的连接账号名，按调用顺序。
     *
     * <p>用来钉住"按语句类型选账号"：DML 必须走 dml 账号（只读账号 EXPLAIN 写语句会被 MySQL 以 1142
     * 拒绝 → {@code -1} → 整单误判 L3），只读语句走 readonly 账号。
     */
    public List<String> explainedUsernames = new ArrayList<>();

    /** 快照入口返回的逐表指纹；与 {@link #fingerprints} 独立（快照结果不参与漂移比对）。 */
    public Map<String, String> snapshotFingerprints = new LinkedHashMap<>(Map.of("t_order", "fp-1"));

    /** 让 snapshot 抛异常，模拟"抓不到执行前快照"——必须一条语句都不执行。 */
    public boolean snapshotThrows;

    /** snapshot 的入参相对引用，按调用顺序；用来钉住 {@code {systemId}/{datasourceId}/{ticketId}/1} 的形状。 */
    public List<String> snapshotRefs = new ArrayList<>();

    /** snapshot 的入参表名，按调用顺序。 */
    public List<String> snapshotTables = new ArrayList<>();

    /**
     * 依次返回的执行结果；用完则返回"成功且 0 行"。
     *
     * <p>要造"第一条成功 2 行"这类断言时用它——{@code affected_rows} 直接来自这里。
     */
    public List<ExecuteResult> executeResults = new ArrayList<>();

    /** 这些下标（从 0 开始）的语句执行失败；优先于 {@link #executeResults}。 */
    public Set<Integer> failOnStatementIndex = new LinkedHashSet<>();

    /** 执行过的语句原文，按调用顺序。 */
    public List<String> executedSql = new ArrayList<>();

    /** 每条语句用的账号类型，按调用顺序（DDL/DML 分开断言）。 */
    public List<ExecAccountType> executedAccountTypes = new ArrayList<>();

    /** execute 的调用次数：闸门拦下时为 0。 */
    public int executeCalls;

    /**
     * 恢复默认状态。字段是 public 的，用例改完不还原就会把状态漏给下一个用例；统一在这里复位，
     * 免得每个测试类各写一遍且漏掉某个字段。
     */
    public void reset() {
        fingerprints = new LinkedHashMap<>(Map.of("t_order", "fp-1"));
        lastFingerprintTables = List.of();
        fingerprintThrows = false;
        explainEstimate = new ExplainEstimate(1);
        lastExplainedSql = null;
        explainCalls = 0;
        explainedUsernames = new ArrayList<>();
        snapshotFingerprints = new LinkedHashMap<>(Map.of("t_order", "fp-1"));
        snapshotThrows = false;
        snapshotRefs = new ArrayList<>();
        snapshotTables = new ArrayList<>();
        executeResults = new ArrayList<>();
        failOnStatementIndex = new LinkedHashSet<>();
        executedSql = new ArrayList<>();
        executedAccountTypes = new ArrayList<>();
        executeCalls = 0;
    }

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
        // 复制入参：持有调用方的列表会让"调用后列表被改"变成桩里的假历史。
        this.lastFingerprintTables = List.copyOf(tables);
        if (fingerprintThrows) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器不可用，请稍后重试");
        }
        return fingerprints;
    }

    @Override
    public ExplainEstimate explain(ConnConfig conn, String sql) {
        this.explainCalls++;
        this.lastExplainedSql = sql;
        this.explainedUsernames.add(conn == null ? null : conn.username());
        return explainEstimate;
    }

    @Override
    public SnapshotResult snapshot(ConnConfig conn, List<String> tables, String relativeRef) {
        this.snapshotRefs.add(relativeRef);
        this.snapshotTables.add(String.join(",", tables));
        if (snapshotThrows) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "结构快照抓取失败：数据源不可达");
        }
        return new SnapshotResult(relativeRef, snapshotFingerprints, 128L);
    }

    @Override
    public ExecuteResult execute(ConnConfig conn, String sql, ExecAccountType type) {
        int index = this.executeCalls++;
        this.executedSql.add(sql);
        this.executedAccountTypes.add(type);
        if (failOnStatementIndex.contains(index)) {
            return new ExecuteResult(false, 0, 5L, "42000", "模拟的语句执行失败（表不存在）");
        }
        if (index < executeResults.size()) {
            return executeResults.get(index);
        }
        // 没铺结果的成功语句：默认 0 行，与 DDL 的真实返回值一致。
        return new ExecuteResult(true, 0, 5L, null, null);
    }
}
