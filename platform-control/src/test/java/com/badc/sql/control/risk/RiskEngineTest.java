package com.badc.sql.control.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.control.risk.RiskEngine.RiskRequest;

/**
 * 风险分级引擎：把分类、规范校验、行数估算与时间窗合成一个等级。
 *
 * <p>本类只钉<b>合并口径</b>——每条语句本身"是什么"由任务 3 的分类器与任务 4 的校验器决定，这里不重复断言
 * 它们的规则表。合并顺序是本任务唯一容易写错的地方，所以每一个"先看什么、后看什么"的分支都有一条用例：
 *
 * <ul>
 *   <li><b>BLOCK 先于等级</b>：BLOCK 命中的 {@code level} 是 {@code null}，先合并等级会把 {@code drop database}
 *       算成 L0（只读、无需审批）。</li>
 *   <li><b>PIN_LEVEL 只抬不降</b>：结构性破坏与"写语句无 where"的 L3 不因阈值放宽而降低。</li>
 *   <li><b>估算 {@code -1} 不是 0 行</b>：无法估算按最严处理，绝不能落进 L1。</li>
 *   <li><b>哈希含 SQL</b>：改了语句但等级恰好不变时，风险快照哈希必须变，否则任务 9 的"风险未变"校验会放行
 *       一条被改过的语句。</li>
 * </ul>
 *
 * <p>引擎是纯函数：不调 {@code ExecutorClient}、不连库、不读配置中心。行数估算与"是否在窗口内"都由调用方
 * （任务 7）先算好放进 {@link RiskRequest}，所以这里可以用固定属性把每条分支跑满。
 */
class RiskEngineTest {

    private final StatementClassifier classifier = new StatementClassifier();
    private final RiskEngine engine = new RiskEngine(new PolicyValidator(), RiskProperties.defaults());

    private RiskEngine engineWith(RiskProperties p) {
        return new RiskEngine(new PolicyValidator(), p);
    }

    private RiskRequest req(String sql, boolean withinWindow) {
        return new RiskRequest(sql, classifier.classify(sql), new ExplainEstimate(-1), withinWindow, true, null);
    }

    private RiskRequest reqWithEstimate(String sql, long rows, boolean withinWindow) {
        return new RiskRequest(sql, classifier.classify(sql), new ExplainEstimate(rows), withinWindow, true, null);
    }

    private RiskRequest reqWithOldTypes(String sql, Map<String, Map<String, String>> oldColumnTypes) {
        return new RiskRequest(sql, classifier.classify(sql), new ExplainEstimate(-1), true, true, oldColumnTypes);
    }

    private static List<RiskHit> withCode(RiskAssessment assessment, String ruleCode) {
        return assessment.hits().stream().filter(hit -> hit.ruleCode().equals(ruleCode)).toList();
    }

    // ------------------------------------------------------------------ 硬规则不可放宽

    @Test
    void a_structural_drop_is_l3_even_when_every_threshold_is_relaxed() {
        assertThat(engineWith(RiskProperties.relaxed()).assess(req("drop table t_order", true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    /** 阈值放宽只影响"按行数分档"，不能把已经钉死的 L3 拉下来。 */
    @Test
    void relaxing_the_thresholds_does_not_lower_a_write_without_where() {
        RiskAssessment a = engineWith(RiskProperties.relaxed()).assess(reqWithEstimate(
                "update t_order set a = 1", 1L, true));
        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(withCode(a, "WRITE_WITHOUT_WHERE")).isNotEmpty();
    }

    /** 估算不出来是"未知"，不是"0 行"：放宽阈值也救不回一条无法估算的语句。 */
    @Test
    void relaxing_the_thresholds_does_not_rescue_an_unknown_estimate() {
        assertThat(engineWith(RiskProperties.relaxed()).assess(reqWithEstimate(
                "update t_order set a = 1 where id = 1", -1L, true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    // ------------------------------------------------------------------ BLOCK

    /**
     * BLOCK 命中的 {@code level} 是 {@code null}。若先把所有命中的 level 合并成等级（null 当 L0），
     * {@code drop database} 会算出 <b>L0（只读、无需审批）</b>——一条被硬禁止的语句拿到"无需审批"的评级。
     * 所以必须先看 discipline、再看等级：有 BLOCK 就直接 L3 并标记不可提交。
     */
    @Test
    void a_blocking_rule_is_reported_and_the_ticket_cannot_be_submitted() {
        RiskAssessment a = engine.assess(req("drop database x", true));

        assertThat(a.blocked()).isTrue();
        // 若先合并等级（BLOCK 命中的 level 是 null，会被当成 L0），这里会是 L0——一条被硬禁止的语句反而"无需审批"。
        assertThat(a.level()).isNotEqualTo(RiskLevel.L0);
        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(withCode(a, "DROP_DATABASE")).singleElement()
                .satisfies(hit -> assertThat(hit.discipline()).isEqualTo(Discipline.BLOCK));
    }

    /** 多语句拼接同样在提交阶段被拒绝，不是"按第一条算个等级再审批"。 */
    @Test
    void a_multi_statement_string_is_blocked_at_l3() {
        RiskAssessment a = engine.assess(req("update t set a = 1 where id = 1; drop table t", true));

        assertThat(a.blocked()).isTrue();
        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(withCode(a, "MULTI_STATEMENT")).isNotEmpty();
    }

    /** 被拒绝的语句不进入审批，也就不该再叠窗口/快照的升档理由。 */
    @Test
    void a_blocked_statement_is_not_escalated_further_outside_the_window() {
        RiskAssessment a = engine.assess(new RiskRequest("drop database x", classifier.classify("drop database x"),
                new ExplainEstimate(-1), false, false, null));

        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(withCode(a, "OUT_OF_WINDOW_ACK")).isEmpty();
        assertThat(withCode(a, "SNAPSHOT_DISABLED")).isEmpty();
    }

    // ------------------------------------------------------------------ 写语句：没有 where

    @Test
    void an_update_without_where_is_l3_but_not_blocked() {
        RiskAssessment a = engine.assess(req("update t_order set a = 1", true));

        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(a.blocked()).isFalse();
    }

    // ------------------------------------------------------------------ 按估算分档

    @Test
    void an_update_with_a_small_estimate_is_l1_inside_the_window() {
        assertThat(engine.assess(reqWithEstimate("update t_order set a = 1 where id = 1", 1L, true)).level())
                .isEqualTo(RiskLevel.L1);
    }

    @Test
    void an_update_between_the_two_thresholds_is_l2() {
        assertThat(engine.assess(reqWithEstimate("update t set a = 1 where b = 2", 500L, true)).level())
                .isEqualTo(RiskLevel.L2);
    }

    @Test
    void an_update_over_a_thousand_rows_is_l3() {
        assertThat(engine.assess(reqWithEstimate("update t set a = 1 where b = 2", 5_000L, true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    /** 估算不出来就不能当 L1：宁可多一级审批。 */
    @Test
    void an_unknown_estimate_grades_an_update_as_l3() {
        assertThat(engine.assess(reqWithEstimate("update t join u on u.id = t.id set t.a = 1", -1L, true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    /**
     * 阈值边界：{@code l1Max=100}、{@code l2Max=1000} 是<b>含</b>端点的。边界写错（&lt; 写成 ≤）会让
     * 恰好在 100/1000 行上的语句少一级审批，而那正是最常出现的规模。
     */
    @ParameterizedTest(name = "{0} 行 → {1}")
    @CsvSource({
            "1, L1",
            "100, L1",
            "101, L2",
            "1000, L2",
            "1001, L3",
    })
    void the_row_thresholds_are_inclusive(long rows, RiskLevel expected) {
        assertThat(engine.assess(reqWithEstimate("delete from t where b = 2", rows, true)).level())
                .isEqualTo(expected);
    }

    /** 估算 0 行是一次"几乎不影响行"的真实估算，与 {@code -1}（无法估算）不是一回事。 */
    @Test
    void an_estimate_of_zero_rows_is_still_l1() {
        assertThat(engine.assess(reqWithEstimate("update t set a = 1 where id = 1", 0L, true)).level())
                .isEqualTo(RiskLevel.L1);
    }

    /**
     * {@code with} 开头不是"无法归档"：CTE 之后的真实动词是 {@code delete}，按估算分档。
     *
     * <p>若引擎只看首关键字，{@code with ... delete} 会落进默认的 L3（方向安全但分档失真，所有 CTE 写语句都被
     * 顶到最高级）；反过来若默认分支写成 L1，就是"删数据免审批"的漏洞。两个方向都靠这条用例钉住。
     */
    @Test
    void a_cte_delete_is_graded_by_its_real_verb() {
        assertThat(engine.assess(reqWithEstimate(
                "with c as (select id from t where id = 1) delete from t where id in (select id from c)", 1L, true))
                .level()).isEqualTo(RiskLevel.L1);
    }

    /** DDL 不看行数估算：建表/加列没有"影响行数"可言，估算再大也不会把 L1 抬成 L3。 */
    @Test
    void a_create_table_is_l1_regardless_of_the_estimate() {
        assertThat(engine.assess(reqWithEstimate("create table t_new (id int)", 5_000_000L, true)).level())
                .isEqualTo(RiskLevel.L1);
    }

    // ------------------------------------------------------------------ insert

    @Test
    void an_insert_with_a_small_estimate_is_l1() {
        assertThat(engine.assess(reqWithEstimate("insert into t_order (a) values (1)", 1L, true)).level())
                .isEqualTo(RiskLevel.L1);
    }

    @Test
    void an_insert_between_the_two_thresholds_is_l2() {
        assertThat(engine.assess(reqWithEstimate("insert into t_order (a) select a from t", 500L, true)).level())
                .isEqualTo(RiskLevel.L2);
    }

    @Test
    void an_insert_over_a_thousand_rows_is_l3() {
        assertThat(engine.assess(reqWithEstimate("insert into t_order (a) select a from t", 5_000L, true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    /** insert 的估算同样不能是"未知即最小"：估不出来就按最严处理。 */
    @Test
    void an_unknown_estimate_grades_an_insert_as_l3() {
        assertThat(engine.assess(reqWithEstimate("insert into t_order (a) select a from t", -1L, true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    // ------------------------------------------------------------------ DDL 分档

    @Test
    void a_create_table_is_l1() {
        assertThat(engine.assess(req("create table t_new (id int)", true)).level()).isEqualTo(RiskLevel.L1);
    }

    @Test
    void a_create_index_is_l1() {
        assertThat(engine.assess(req("create index idx_a on t_order (a)", true)).level()).isEqualTo(RiskLevel.L1);
    }

    @Test
    void an_added_column_is_l1() {
        assertThat(engine.assess(req("alter table t_order add column note varchar(32)", true)).level())
                .isEqualTo(RiskLevel.L1);
    }

    @Test
    void an_added_unique_index_is_l2() {
        assertThat(engine.assess(req("alter table t_order add unique index uk_a (a)", true)).level())
                .isEqualTo(RiskLevel.L2);
    }

    /** 改列类型但**证不出收窄**（varchar(32) → varchar(64)）：L2，不是 L3。 */
    @Test
    void a_non_narrowing_modify_is_l2() {
        RiskAssessment a = engine.assess(reqWithOldTypes(
                "alter table t_order modify column note varchar(64)",
                Map.of("t_order", Map.of("note", "varchar(32)"))));

        assertThat(a.level()).isEqualTo(RiskLevel.L2);
        assertThat(withCode(a, "ALTER_MODIFY_NARROWED")).isEmpty();
    }

    /** 收窄由校验器钉 L3，引擎只负责"只能抬不能降"。 */
    @Test
    void a_narrowing_modify_is_l3() {
        RiskAssessment a = engine.assess(reqWithOldTypes(
                "alter table t_order modify column note varchar(16)",
                Map.of("t_order", Map.of("note", "varchar(32)"))));

        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(withCode(a, "ALTER_MODIFY_NARROWED")).isNotEmpty();
    }

    /** 没有旧类型可比时校验器按最严处理：任务 7 填快照之前，所有 modify 都落到 L3。 */
    @Test
    void a_modify_without_old_types_is_l3() {
        assertThat(engine.assess(req("alter table t_order modify column note varchar(64)", true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    /** 无法归档的写 DDL 默认从严。 */
    @Test
    void an_unclassified_ddl_falls_back_to_l3() {
        assertThat(engine.assess(req("drop view v_order", true)).level()).isEqualTo(RiskLevel.L3);
    }

    /** 跨库写至少 L3：同一个工单里的语句落在多个库上是审核人看不见的影响面。 */
    @Test
    void a_cross_database_write_is_l3() {
        assertThat(engine.assess(reqWithEstimate("update db2.t_order set a = 1 where id = 1", 1L, true)).level())
                .isEqualTo(RiskLevel.L3);
    }

    // ------------------------------------------------------------------ 只读

    @Test
    void a_readonly_statement_is_l0() {
        assertThat(engine.assess(req("select id from t_order where id = 1", true)).level())
                .isEqualTo(RiskLevel.L0);
    }

    /** 只读语句没有"审批等级"可升：非窗口期也不追加确认。 */
    @Test
    void a_readonly_statement_outside_the_window_is_still_l0_without_ack() {
        RiskAssessment a = engine.assess(req("select id from t_order where id = 1", false));

        assertThat(a.level()).isEqualTo(RiskLevel.L0);
        assertThat(withCode(a, "OUT_OF_WINDOW_ACK")).isEmpty();
    }

    // ------------------------------------------------------------------ 时间窗

    @Test
    void outside_the_window_a_small_update_is_escalated_by_one_level() {
        assertThat(engine.assess(reqWithEstimate("update t set a = 1 where id = 1", 1L, false)).level())
                .isEqualTo(RiskLevel.L2);
    }

    @Test
    void outside_the_window_an_l2_update_becomes_l3() {
        assertThat(engine.assess(reqWithEstimate("update t set a = 1 where b = 2", 500L, false)).level())
                .isEqualTo(RiskLevel.L3);
    }

    /** 结构性破坏已在最高级，非窗口期不再叠加，只加一条终审确认。 */
    @Test
    void a_structural_l3_is_not_escalated_again_outside_the_window() {
        RiskAssessment a = engine.assess(req("drop table t_order", false));

        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(a.hits()).anySatisfy(h -> assertThat(h.ruleCode()).isEqualTo("OUT_OF_WINDOW_ACK"));
    }

    /**
     * 非窗口期的 L3 只追加一条 {@code INFO} 级确认（终审时要求"同意在业务高峰执行"），
     * 等级不变、也不是 PIN——它不是风险来源，只是审批话术。
     */
    @Test
    void the_out_of_window_ack_is_an_info_hit_without_a_level() {
        RiskAssessment a = engine.assess(req("drop table t_order", false));

        assertThat(withCode(a, "OUT_OF_WINDOW_ACK")).singleElement().satisfies(hit -> {
            assertThat(hit.discipline()).isEqualTo(Discipline.INFO);
            assertThat(hit.level()).isNull();
            assertThat(hit.message()).contains("高峰");
        });
    }

    // ------------------------------------------------------------------ 快照

    /** 快照关闭升 L3（本切片快照不可关，保留判定以便切片 3 复用）。 */
    @Test
    void a_disabled_snapshot_grades_l3_and_is_recorded_as_a_hit() {
        RiskRequest r = new RiskRequest("update t set a = 1 where id = 1",
                classifier.classify("update t set a = 1 where id = 1"), new ExplainEstimate(1), true, false, null);
        RiskAssessment a = engine.assess(r);

        assertThat(a.level()).isEqualTo(RiskLevel.L3);
        assertThat(a.hits()).anySatisfy(h -> assertThat(h.ruleCode()).isEqualTo("SNAPSHOT_DISABLED"));
        assertThat(withCode(a, "SNAPSHOT_DISABLED")).singleElement().satisfies(hit -> {
            assertThat(hit.discipline()).isEqualTo(Discipline.PIN_LEVEL);
            assertThat(hit.level()).isEqualTo(RiskLevel.L3);
        });
    }

    /** 快照关闭是"没有退路"，不能因为语句本身很小就留在 L1。 */
    @Test
    void a_disabled_snapshot_lifts_even_a_small_read_to_l3() {
        RiskRequest r = new RiskRequest("select id from t where id = 1",
                classifier.classify("select id from t where id = 1"), new ExplainEstimate(1), true, false, null);

        assertThat(engine.assess(r).level()).isEqualTo(RiskLevel.L3);
    }

    // ------------------------------------------------------------------ 快照哈希

    /** 改了一个字但等级没变：哈希必须变，否则"风险未变"校验会放行被改过的语句。 */
    @Test
    void the_snapshot_hash_changes_when_the_sql_changes() {
        assertThat(engine.assess(reqWithEstimate("update t set a = 1 where id = 1", 1L, true)).snapshotHash())
                .isNotEqualTo(engine.assess(reqWithEstimate("update t set a = 2 where id = 1", 1L, true))
                        .snapshotHash());
    }

    @Test
    void the_snapshot_hash_is_stable_for_identical_input() {
        assertThat(engine.assess(req("drop table t", true)).snapshotHash())
                .isEqualTo(engine.assess(req("drop table t", true)).snapshotHash());
    }

    /** 等级相同、SQL 也相同，但落在窗口内外不同：审批要求变了（要多一条高峰确认），快照哈希也必须变。 */
    @Test
    void the_snapshot_hash_changes_when_the_window_verdict_changes() {
        RiskAssessment inside = engine.assess(req("drop table t_order", true));
        RiskAssessment outside = engine.assess(req("drop table t_order", false));

        assertThat(inside.level()).isEqualTo(outside.level());
        assertThat(inside.snapshotHash()).isNotEqualTo(outside.snapshotHash());
    }

    /** 哈希是 SHA-256 十六进制，不是把规则码直接拼出来的可读串。 */
    @Test
    void the_snapshot_hash_is_a_sha256_hex_digest() {
        assertThat(engine.assess(req("drop table t", true)).snapshotHash()).matches("[0-9a-f]{64}");
    }

    // ------------------------------------------------------------------ 返回值的不可变性

    /** 命中列表给出去之后不能再被改：调用方拿到的是等级与审计标签的唯一来源。 */
    @Test
    void the_hit_list_cannot_be_modified_by_the_caller() {
        RiskAssessment a = engine.assess(req("drop table t_order", true));

        assertThatThrownBy(() -> a.hits().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
