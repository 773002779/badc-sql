package com.badc.sql.control.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 只读白名单的真实判定。
 *
 * <p>只看首个关键字是不够的：MySQL 允许 {@code WITH cte AS (...) UPDATE t SET ...}，也允许
 * {@code EXPLAIN ANALYZE <DML>}（后者会真实执行数据修改，正是 FR-11 明令禁止的）。
 */
class ReadonlyStatementTest {

    @Test
    void plain_select_is_readonly() {
        assertThat(QueryService.isReadonlyStatement("select * from t")).isTrue();
    }

    @Test
    void cte_over_select_is_readonly() {
        assertThat(QueryService.isReadonlyStatement("with c as (select 1) select * from c")).isTrue();
    }

    @Test
    void cte_over_update_is_not_readonly() {
        assertThat(QueryService.isReadonlyStatement("with c as (select 1) update t set x = 1")).isFalse();
    }

    @Test
    void cte_over_delete_is_not_readonly() {
        assertThat(QueryService.isReadonlyStatement("with c as (select 1) delete from t")).isFalse();
    }

    @Test
    void explain_analyze_over_dml_is_not_readonly() {
        assertThat(QueryService.isReadonlyStatement("explain analyze update t set x = 1")).isFalse();
        assertThat(QueryService.isReadonlyStatement("explain analyze delete from t")).isFalse();
    }

    @Test
    void explain_over_select_is_readonly() {
        assertThat(QueryService.isReadonlyStatement("explain select * from t")).isTrue();
        assertThat(QueryService.isReadonlyStatement("explain analyze select * from t")).isTrue();
    }

    @Test
    void leading_comment_does_not_hide_the_real_verb() {
        assertThat(QueryService.isReadonlyStatement("/* note */ update t set x = 1")).isFalse();
    }

    @Test
    void dml_is_rejected() {
        assertThat(QueryService.isReadonlyStatement("update t set x = 1")).isFalse();
        assertThat(QueryService.isReadonlyStatement("insert into t values (1)")).isFalse();
        assertThat(QueryService.isReadonlyStatement("drop table t")).isFalse();
    }

    @Test
    void metadata_commands_are_readonly() {
        assertThat(QueryService.isReadonlyStatement("show tables")).isTrue();
        assertThat(QueryService.isReadonlyStatement("desc t")).isTrue();
    }
}
