package com.badc.sql.control.ticket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.control.org.ConnAccountService;
import com.badc.sql.control.query.ExecutorClient;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.Discipline;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskHit;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedStatement;

/**
 * 提交路径（{@link TicketService}）与回滚路径（{@link TicketRollbackService}）<b>共用</b>的定级前置步骤。
 *
 * <p><b>为什么必须共用而不是各写一份</b>：整单风险快照哈希同时覆盖"每条语句的等级/命中"与"语句原文"，
 * 而每条语句的等级又取决于这里的三件事——用哪个账号 EXPLAIN、DDL 要不要估算、结构指纹怎么补齐。
 * 两条路径只要有一处口径不同（例如一边用只读账号估算 DML、另一边用 dml 账号），同一组语句会算出不同哈希：
 * <ul>
 *   <li>提交期与执行期不一致 → 执行前风险确认永远对不上（{@code RISK_ACK_MISSING}）；</li>
 *   <li>提交路径与回滚路径不一致 → 回滚单能审批却永远执行不了（任务 12 的 C1 就是这样暴露的）。</li>
 * </ul>
 *
 * <p>所以这些步骤只保留<b>一份实现</b>：本类是包级可见的内部件，不对外暴露，也不持有状态。
 *
 * <p>事务边界与本类无关：这里的方法都会发起外部调用（EXPLAIN / 结构指纹），调用方必须在事务<b>外</b>调用。
 */
final class TicketPipelineSupport {

    private TicketPipelineSupport() {
    }

    /** 数据写入（DML 且非 DDL）——决定回滚 SQL 是否必填、以及估算必须走 dml 账号。 */
    static boolean isDmlWrite(Classification classification) {
        return classification.writeStatement() && classification.ddlAction() == DdlAction.NONE;
    }

    /**
     * 按语句类型选估算账号，DDL 干脆不估。
     *
     * <ul>
     *   <li><b>DDL</b>（{@code ddlAction != NONE}）→ 直接 {@code -1}，<b>不发 explain</b>：DDL 的影响面不由
     *       行数决定，{@code EXPLAIN CREATE/ALTER/DROP} 本身没有意义；结构性动作恒 L3，非结构性动作按
     *       {@link DdlAction} 分档，都不需要估算，省一次网络往返。</li>
     *   <li><b>DML</b>（{@code writeStatement && ddlAction == NONE}）→ <b>dml 账号</b>。只读账号对
     *       {@code EXPLAIN UPDATE/INSERT/DELETE} 会得到 MySQL 1142，执行器吞成 {@code -1}，
     *       整张工单就被误判成 L3——L1/L2 分档在真实环境彻底失效。</li>
     *   <li><b>只读语句</b> → 只读账号。</li>
     * </ul>
     */
    static ExplainEstimate estimateFor(ExecutorClient executorClient, ValidatedStatement statement,
            ConnConfig readonly, ConnConfig dml) {
        if (statement.classification().ddlAction() != DdlAction.NONE) {
            return new ExplainEstimate(-1);
        }
        ConnConfig conn = statement.classification().writeStatement() ? dml : readonly;
        ExplainEstimate estimate = executorClient.explain(conn, statement.sql());
        return estimate == null ? new ExplainEstimate(-1) : estimate;
    }

    /**
     * 取结构指纹：<b>只为实际存在的表返回值，缺失的表补空串</b>。
     *
     * <p>缺失表补 {@code ""} 与"执行器抛异常"必须严格分开：前者是"这张表还不存在，前置状态为空"，提交继续；
     * 后者是"库不可达 / 权限不足"，必须让异常上抛中止。把异常吞成空 map 会让一次网络故障被记成
     * "所有表都是新建的"，之后执行前的漂移检测就会空转通过。
     */
    static Map<String, String> tableFingerprints(ExecutorClient executorClient, ConnConfig conn,
            List<String> tables) {
        if (tables.isEmpty()) {
            return Map.of();
        }
        Map<String, String> returned = executorClient.fingerprint(conn, tables);
        if (returned == null) {
            // 契约要求非空 map / 异常二选一；null 属于执行器违约，按失败处理而不是"没有表"。
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行器未返回结构指纹");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String table : tables) {
            String fingerprint = find(returned, table);
            resolved.put(table, fingerprint == null ? "" : fingerprint);
        }
        return resolved;
    }

    /** 执行器回传的键可能与请求的大小写/限定名不完全一致，按"精确 → 忽略大小写 → 裸表名"依次找。 */
    private static String find(Map<String, String> returned, String table) {
        String value = returned.get(table);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : returned.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(table)) {
                return entry.getValue();
            }
        }
        String bare = TicketSubmitValidator.normalizeTable(table);
        for (Map.Entry<String, String> entry : returned.entrySet()) {
            if (bare != null && bare.equalsIgnoreCase(TicketSubmitValidator.normalizeTable(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 取 dml 账号；缺失时给 400 而不是让它退化成 L3。
     *
     * <p>没有 dml 账号的数据源本来也执行不了写，提前失败比返回一个"因为估不出来所以 L3"的假等级更好：
     * 后者会让用户以为这条语句风险很高，而真正的问题是数据源没配好。
     */
    static ConnConfig dmlConnOrRefuse(ConnAccountService connAccountService, long datasourceId) {
        try {
            return connAccountService.dmlConnConfig(datasourceId);
        } catch (BizException e) {
            if (e.code() == ErrorCode.NOT_FOUND) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "数据源 " + datasourceId + " 未配置 dml 账号，无法估算影响行数，也就无法定级");
            }
            throw e;
        }
    }

    /** BLOCK 命中的说明文案；被拦下的语句没有等级，只有命中自带的理由可给用户看。 */
    static String blockMessage(RiskAssessment assessment) {
        for (RiskHit hit : assessment.hits()) {
            if (hit.discipline() == Discipline.BLOCK) {
                return hit.message();
            }
        }
        return "语句被禁止提交";
    }
}
