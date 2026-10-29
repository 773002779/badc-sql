package com.badc.sql.control.risk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.sql.SqlText;

/**
 * 风险分级引擎：把任务 3 的分类、任务 4 的规范校验、EXPLAIN 行数估算与执行窗口合成一个等级。
 *
 * <p><b>本类是纯函数</b>：不调 {@code ExecutorClient}、不连库、不读配置中心。行数估算由调用方（任务 7）
 * 先取好放进 {@link RiskRequest}，"是否在窗口内"也由调用方用 {@link RiskProperties#toWindow()} 算好传进来。
 * 这样引擎可以用纯单测把每条分支跑满，而"估算从哪来、窗口按什么时区算"这类会连库/读配置的判断只有一处。
 *
 * <h2>合并顺序不可调换</h2>
 * <ol>
 *   <li>{@link PolicyValidator#validate} 出命中列表。</li>
 *   <li><b>先看 BLOCK，再看等级</b>：BLOCK 命中的 {@code level} 是 {@code null}（见 {@link RiskHit}）。
 *       若先把命中的 level 合并成等级（{@code null} 按 L0 算），{@code drop database} 会算出
 *       <b>L0（只读、无需审批）</b>——一条被硬禁止的语句拿到"无需审批"的评级。有 BLOCK 就直接 {@code L3}
 *       并 {@code blocked()==true}，后面几步都不走：它本来就进不到审批。</li>
 *   <li>基础等级（见 {@link #baseLevel}）：只读 {@code L0}；结构性破坏 {@code L3}；其余按语句与估算分档；
 *       跨库写至少 {@code L3}；无法归档的写语句默认 {@code L3}。</li>
 *   <li>{@code PIN_LEVEL} 命中把等级<b>抬到</b>其 level，只能抬不能降——配置阈值再宽也压不下结构性破坏。</li>
 *   <li>非窗口期：{@code L1→L2}、{@code L2→L3}；已在 {@code L3} 时不再叠加，只追加一条
 *       {@code OUT_OF_WINDOW_ACK}（{@link Discipline#INFO}），供终审时要求"同意在业务高峰执行"；
 *       {@code L0} 没有可升的审批等级，也不追加。</li>
 *   <li>{@code snapshotEnabled == false}：抬到 {@code L3} 并追加 {@code SNAPSHOT_DISABLED}
 *       （{@link Discipline#PIN_LEVEL}）。本切片快照不可关，这条判定是给切片 3 的关闭理由预留的。</li>
 * </ol>
 *
 * <h2>行数分档（只影响 insert/update/delete）</h2>
 * <ul>
 *   <li>{@code insert}（含 {@code replace}）：估算 ≤ {@code l1MaxRows} → L1；≤ {@code l2MaxRows} → L2；
 *       否则 L3。</li>
 *   <li>{@code update}/{@code delete}：同一套分档。没有 where 的写语句已被校验器钉 L3，走不到这里。</li>
 *   <li><b>估算 {@code -1} 一律 L3</b>，insert 也不例外：{@code -1} 是"估不出来"而不是"0 行"，把它当最小档
 *       等于让一条无法估算的语句免掉一级审批。估算 {@code 0} 是一次真实的"几乎不影响行"，按 ≤ 阈值处理。</li>
 * </ul>
 *
 * <h2>DDL 分档</h2>
 * <p>{@code create table} / {@code create index} / {@code alter add column} → L1；
 * {@code alter add unique index} / {@code alter modify}（<b>证不出收窄</b>）→ L2；
 * 其余写 DDL（{@code create/drop view}、{@code create database}、{@code alter ... engine=} 等）→ L3。
 * 结构性破坏与收窄不在这里判：它们由 {@link Classification#structuralDamage()} 与校验器的
 * {@code PIN_LEVEL L3} 命中表达，引擎只负责"只能抬不能降"。
 *
 * <h2>快照哈希</h2>
 * <p>{@code sha256(level.name() + "|" + 去重排序后的 ruleCode 列表 + "|" + sha256(sql))}。
 * <b>必须含 SQL 哈希</b>：研发把语句改了一个字而等级恰好不变时，哈希必须变，否则任务 9 的"风险未变"校验
 * 会放行一条被改过的语句。全部命中（含 WARN/INFO）都计入 ruleCode 列表：同一等级下"风险构成"变了也要能看出来。
 *
 * <h2>已知局限</h2>
 * <ul>
 *   <li>{@code alter add column} 的"可空/非空"分不出来：{@link Classification} 不带这个信息，一律 L1。
 *       非空且无默认为默认值的加列在大表上会真的锁表，需靠审核人读语句。</li>
 *   <li>跨库判定依赖 {@link Classification#crossDatabase()}，反引号限定名（{@code `db2`.`t`}）检测不到，
 *       见 {@link StatementClassifier} 的已知局限——这是"尽力而为"，不是硬保证。</li>
 *   <li>估算来自优化器统计信息，不是真实影响行数；统计信息陈旧时可能低估。这里只做分档，不做保证。</li>
 * </ul>
 */
public final class RiskEngine {

    /** 非窗口期提交的"同意在业务高峰执行"确认；{@link Discipline#INFO}，不参与等级合并。 */
    private static final String RULE_OUT_OF_WINDOW_ACK = "OUT_OF_WINDOW_ACK";

    /** 快照关闭：没有可回退的前置结构；{@link Discipline#PIN_LEVEL} L3（本切片不可关，留给切片 3）。 */
    private static final String RULE_SNAPSHOT_DISABLED = "SNAPSHOT_DISABLED";

    /** 走行数分档的 DML 动词（{@code replace} 是 insert 语义，同样按行数分）。 */
    private static final Set<String> DML_VERBS = Set.of("insert", "update", "delete", "replace");

    /** 只给 {@link RiskRequest} 的兜底分类用：分类器无状态，可安全复用。 */
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();

    private final PolicyValidator validator;
    private final RiskProperties properties;

    public RiskEngine(PolicyValidator validator, RiskProperties properties) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 评估一条语句。
     *
     * @param request 语句原文、分类、估算、窗口与快照开关；{@code oldColumnTypesOrNull} 原样透传给
     *                {@link PolicyValidator}（{@code null} 时收窄判定走"无法判定 → L3"）
     * @return 等级 + 命中列表 + 风险快照哈希；被 BLOCK 时等级为 L3、{@code blocked()} 为 {@code true}
     */
    public RiskAssessment assess(RiskRequest request) {
        Objects.requireNonNull(request, "request");
        List<RiskHit> hits = new ArrayList<>(
                validator.validate(request.sql(), request.classification(), request.oldColumnTypesOrNull()));

        // 1. BLOCK 先于等级：BLOCK 命中的 level 是 null，先合并等级会把它当 L0（免审批）。
        if (blocked(hits)) {
            return assessment(RiskLevel.L3, hits, request.sql());
        }

        // 2. 基础等级。
        RiskLevel level = baseLevel(request);

        // 3. PIN_LEVEL 只能抬不能降（RiskLevel.stricter 对 null 按 L0 处理，契约被违反也不会反而降级）。
        for (RiskHit hit : hits) {
            if (hit.discipline() == Discipline.PIN_LEVEL) {
                level = RiskLevel.stricter(level, hit.level());
            }
        }

        // 4. 非窗口期：L1→L2、L2→L3；已在 L3 只加一条终审确认。
        if (!request.withinWindow()) {
            if (level == RiskLevel.L1) {
                level = RiskLevel.L2;
            } else if (level == RiskLevel.L2) {
                level = RiskLevel.L3;
            } else if (level == RiskLevel.L3) {
                hits.add(outOfWindowAck());
            }
            // L0（只读）没有可升的审批等级，也不追加确认。
        }

        // 5. 快照关闭 → L3：没有可回退的前置结构，失败即无法恢复。
        if (!request.snapshotEnabled()) {
            level = RiskLevel.L3;
            hits.add(snapshotDisabled());
        }

        return assessment(level, hits, request.sql());
    }

    /**
     * 不看窗口与快照的基础等级。
     *
     * <p>{@code structuralDamage()} 先于 DDL 分档：它按"整组识别出的动作"计算，即使
     * {@link Classification#ddlAction()} 因将来新增动作而排错，也不会让结构性破坏掉进 L1/L2。
     */
    private RiskLevel baseLevel(RiskRequest request) {
        Classification classification = request.classification();
        if (!classification.writeStatement()) {
            return RiskLevel.L0;
        }
        if (classification.structuralDamage()) {
            return RiskLevel.L3;
        }
        RiskLevel level = classification.ddlAction() == DdlAction.NONE
                ? dmlLevel(request, classification)
                : ddlLevel(classification.ddlAction());
        // 跨库写至少 L3：审核人看不到另一个库的影响面，按最严处理。
        return classification.crossDatabase() ? RiskLevel.L3 : level;
    }

    /**
     * 非结构性 DDL 的分档。
     *
     * <p>{@code default} 必须存在且落在 L3：结构性动作与 {@code DROP_DATABASE} 本应在上游就返回
     * （{@code structuralDamage} / BLOCK），真走到这里说明分类与策略不一致——将来往 {@link DdlAction}
     * 加了新动作却忘了在这里编码时，默认分支保证它按最严处理，而不是静默 L0。
     */
    private static RiskLevel ddlLevel(DdlAction action) {
        return switch (action) {
            case CREATE_TABLE, CREATE_INDEX, ALTER_ADD_COLUMN -> RiskLevel.L1;
            case ALTER_ADD_UNIQUE_INDEX, ALTER_MODIFY -> RiskLevel.L2;
            // OTHER_DDL（create/drop view、create database、alter ... engine=…）与兜底：从严。
            case NONE, OTHER_DDL -> RiskLevel.L3;
            default -> RiskLevel.L3;
        };
    }

    /** DML 分档：insert/replace 与 update/delete 用同一套行数阈值。 */
    private RiskLevel dmlLevel(RiskRequest request, Classification classification) {
        String verb = statementVerb(request.sql(), classification);
        return switch (verb == null ? "" : verb) {
            case "insert", "replace", "update", "delete" -> rowLevel(request.estimate().estimatedRows());
            // 写语句但剥不出 DML 动词（with/explain 之外的怪形态）：默认从严。
            default -> RiskLevel.L3;
        };
    }

    /**
     * 行数分档：{@code ≤ l1MaxRows → L1}、{@code ≤ l2MaxRows → L2}、其余 L3。
     *
     * <p>估算为负数（{@code -1} = 无法估算）直接 L3：不区分 insert 与 update——"估不出来"在同一条语句上
     * 不比"估出来很大"更安全。
     */
    private RiskLevel rowLevel(long estimatedRows) {
        if (estimatedRows < 0) {
            return RiskLevel.L3;
        }
        if (estimatedRows <= properties.l1MaxRows()) {
            return RiskLevel.L1;
        }
        if (estimatedRows <= properties.l2MaxRows()) {
            return RiskLevel.L2;
        }
        return RiskLevel.L3;
    }

    /**
     * 语句的 DML 动词。
     *
     * <p>{@code with}/{@code explain} 开头时不用首关键字：{@code with cte as (...) delete from t} 与
     * {@code explain analyze delete from t} 的真实动词要剥出来，否则一条删数据的语句会因为"首关键字是
     * with/explain"而落进默认的 L3——方向安全但分档失真；反过来若默认分支写成 L1，就是免审批的漏洞。
     * 剥离与分类器、只读白名单共用 {@link SqlText#firstTopLevelVerb(String)}，三个入口不会给出相反结论。
     */
    private static String statementVerb(String sql, Classification classification) {
        String first = classification.firstKeyword();
        if (first == null) {
            return null;
        }
        if (DML_VERBS.contains(first)) {
            return first;
        }
        if ("with".equals(first) || "explain".equals(first)) {
            return SqlText.firstTopLevelVerb(SqlText.stripLiteralsAndComments(sql));
        }
        return first;
    }

    private static boolean blocked(List<RiskHit> hits) {
        return hits.stream().anyMatch(hit -> hit.discipline() == Discipline.BLOCK);
    }

    private static RiskHit outOfWindowAck() {
        return new RiskHit(RULE_OUT_OF_WINDOW_ACK, Discipline.INFO, null,
                "提交时间不在业务窗口内（默认 20:00–06:00）：终审需确认「同意在业务高峰执行」，等级不再叠加");
    }

    private static RiskHit snapshotDisabled() {
        return new RiskHit(RULE_SNAPSHOT_DISABLED, Discipline.PIN_LEVEL, RiskLevel.L3,
                "结构快照已关闭：没有可回退的前置结构，失败即无法按快照恢复，等级强制 L3");
    }

    private static RiskAssessment assessment(RiskLevel level, List<RiskHit> hits, String sql) {
        return new RiskAssessment(level, hits, snapshotHash(level, hits, sql));
    }

    /**
     * 风险快照哈希：{@code sha256(level | 排序去重后的规则码 | sha256(sql))}。
     *
     * <p>三个输入缺一不可：等级说明"多严"，规则码说明"严在哪"，SQL 哈希说明"是哪条语句"。少了 SQL 哈希，
     * 改一个字但等级与规则构成都不变的两条语句会得到同一个哈希，执行前的"风险未变"校验就会放行被改过的语句。
     *
     * <p>WARN/INFO 也计入规则码列表：同一等级下风险构成变了（例如多了一条"无 limit"提示）也要能看出来。
     * 代价是纯格式调整（关键字大小写）也会改变哈希——刻意如此：改过就要重新确认一次。
     */
    private static String snapshotHash(RiskLevel level, List<RiskHit> hits, String sql) {
        String ruleCodes = hits.stream()
                .map(RiskHit::ruleCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        return sha256Hex(level.name() + "|" + ruleCodes + "|" + sha256Hex(sql == null ? "" : sql));
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走到这里说明运行环境被裁剪过。
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /**
     * 引擎的输入：一条语句 + 调用方先算好的估算与窗口判定。
     *
     * <p>{@link #withinWindow()} 由调用方用 {@link RiskProperties#toWindow()} 算好传来，引擎自己不碰时钟、
     * 不读配置——同一个请求在单测里可以任意复现。
     *
     * @param sql                  语句原文；{@code null} 视为空串
     * @param classification       任务 3 的分类结果；{@code null} 时本记录兜底分类一次（与校验器的兜底同源）
     * @param estimate             EXPLAIN 行数估算；{@code null} 按"无法估算"（{@code -1}）处理，即最严
     * @param withinWindow         提交时刻是否在业务窗口内；{@code false} 时升一级或追加终审确认
     * @param snapshotEnabled      结构快照是否可用；{@code false} 时强制 L3
     * @param oldColumnTypesOrNull 结构快照里的旧列类型 {@code {表名: {列名: 类型文本}}}，原样透传给
     *                             {@link PolicyValidator}；{@code null} 表示"没有旧类型可比"（收窄判定走最严）
     */
    public record RiskRequest(
            String sql,
            Classification classification,
            ExplainEstimate estimate,
            boolean withinWindow,
            boolean snapshotEnabled,
            Map<String, Map<String, String>> oldColumnTypesOrNull) {

        public RiskRequest {
            sql = sql == null ? "" : sql;
            // 兜底而不是 fail-open：拿不到分类就跑一次分类器（纯文本、便宜），
            // 绝不把"没传分类"当成"没有风险"。
            classification = classification == null ? CLASSIFIER.classify(sql) : classification;
            // 没给估算等于估不出来：按 -1 走最严，不能当成 0 行。
            estimate = estimate == null ? new ExplainEstimate(-1) : estimate;
        }
    }
}
