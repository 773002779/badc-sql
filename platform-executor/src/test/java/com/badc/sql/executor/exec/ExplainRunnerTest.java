package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ExplainEstimate;

/**
 * {@code explain} 的前置守卫（纯函数，不连库）。
 *
 * <p>安全属性在这里：{@code EXPLAIN ANALYZE} 会<b>真正执行</b>语句。若把 {@code analyze delete ...}
 * 直接拼成 {@code explain analyze delete ...}，等于绕过"绝不执行原语句"的承诺。因此首个可执行 token 是
 * {@code analyze} 时必须直接放弃估算。
 */
class ExplainRunnerTest {

    @Test
    void a_statement_starting_with_analyze_is_refused() {
        assertThat(ExplainRunner.startsWithAnalyze("analyze delete from t_order")).isTrue();
        assertThat(ExplainRunner.startsWithAnalyze("  \t\nANALYZE select 1")).isTrue();
    }

    /** 注释垫在前面绕不过守卫：拼进 EXPLAIN 之后 MySQL 仍然认 ANALYZE。 */
    @Test
    void comments_before_analyze_do_not_bypass_the_guard() {
        assertThat(ExplainRunner.startsWithAnalyze("/* hint */ analyze update t set v = v + 1")).isTrue();
        assertThat(ExplainRunner.startsWithAnalyze("-- 注释\nanalyze delete from t")).isTrue();
        assertThat(ExplainRunner.startsWithAnalyze("# 注释\nanalyze delete from t")).isTrue();
        assertThat(ExplainRunner.startsWithAnalyze("/* a\n b */\nanalyze select 1")).isTrue();
    }

    @Test
    void ordinary_statements_are_not_mistaken_for_analyze() {
        assertThat(ExplainRunner.startsWithAnalyze("select analyze from t")).isFalse();
        assertThat(ExplainRunner.startsWithAnalyze("analyze_table_backup")).isFalse();
        assertThat(ExplainRunner.startsWithAnalyze("update t_order set v = 1 where id = 1")).isFalse();
        assertThat(ExplainRunner.startsWithAnalyze("/* unclosed comment analyze")).isFalse();
    }

    /** 守卫必须在建立连接之前生效：conn 为 null 也不该抛（说明没走到连库那一步）。 */
    @Test
    void an_analyze_statement_yields_minus_one_without_touching_the_database() {
        assertThat(new ExplainRunner().estimate(null, "analyze delete from t_order"))
                .isEqualTo(new ExplainEstimate(-1L));
    }
}
