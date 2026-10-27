package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 研发查询的 {@code LIMIT 1001} 注入规则。
 *
 * <p>这是"研发不能一次拉走全表"的唯一防线，因此规则必须精确：
 * <ul>
 *   <li>没有顶层 LIMIT → 追加；</li>
 *   <li>已有顶层 LIMIT 且 ≤1001 → 原样保留（不重复加，避免改变语义）；</li>
 *   <li>已有顶层 LIMIT 且 &gt;1001 → 包一层外层 LIMIT，因为改内层 LIMIT 会改变聚合语义；</li>
 *   <li><b>子查询里的 LIMIT 不算顶层</b>——{@code where id in (select id from t limit 10)} 仍然要加外层限制。</li>
 * </ul>
 */
class SqlLimitInjectorTest {

    private static final int CAP = 1001;

    private final SqlLimitInjector injector = new SqlLimitInjector(CAP);

    @Test
    void appends_limit_when_there_is_none() {
        assertThat(injector.apply("select * from t_order"))
                .isEqualTo("select * from t_order limit 1001");
    }

    @Test
    void keeps_an_existing_smaller_limit_untouched() {
        assertThat(injector.apply("select * from t_order limit 10"))
                .isEqualTo("select * from t_order limit 10");
    }

    @Test
    void keeps_a_limit_exactly_at_the_cap() {
        assertThat(injector.apply("select * from t_order limit 1001"))
                .isEqualTo("select * from t_order limit 1001");
    }

    @Test
    void wraps_when_the_existing_limit_is_larger_than_the_cap() {
        assertThat(injector.apply("select * from t_order limit 5000"))
                .isEqualTo("select * from (select * from t_order limit 5000) __badc_cap limit 1001");
    }

    @Test
    void limit_inside_a_subquery_does_not_count_as_a_top_level_limit() {
        String sql = "select * from t_order where id in (select id from t_item limit 10)";

        assertThat(injector.apply(sql))
                .isEqualTo(sql + " limit 1001");
    }

    @Test
    void limit_inside_a_string_literal_does_not_count() {
        String sql = "select * from t where name = 'limit 10'";

        assertThat(injector.apply(sql)).isEqualTo(sql + " limit 1001");
    }

    @Test
    void limit_inside_a_comment_does_not_count() {
        String sql = "select * from t /* limit 10 */ where id = 1";

        assertThat(injector.apply(sql)).isEqualTo(sql + " limit 1001");
    }

    @Test
    void supports_limit_offset_syntax() {
        assertThat(injector.apply("select * from t limit 10, 2000"))
                .isEqualTo("select * from (select * from t limit 10, 2000) __badc_cap limit 1001");
    }

    @Test
    void trailing_semicolon_is_preserved_after_the_injected_limit() {
        assertThat(injector.apply("select * from t_order;"))
                .isEqualTo("select * from t_order limit 1001;");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select 1",
            "SELECT * FROM t ORDER BY id DESC LIMIT 2000",
    })
    void never_throws_and_always_bounds_the_result(String sql) {
        assertThat(injector.apply(sql).toLowerCase()).contains("limit");
    }

    @Test
    void non_select_statements_are_left_alone() {
        String sql = "show tables";
        assertThat(injector.apply(sql)).isEqualTo(sql);
    }
}
