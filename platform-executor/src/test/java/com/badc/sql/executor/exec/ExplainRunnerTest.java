package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ExplainEstimate;
import com.badc.sql.common.error.BizException;

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

    /**
     * MySQL 可执行注释绕得过"跳过前导注释"的守卫。
     *
     * <p>{@code /*!80018 ANALYZE *}{@code /} 长得像块注释，服务端却会把它展开成可执行 token：拼成
     * {@code explain /*!80018 ANALYZE *}{@code / select ...} 之后就变成 {@code EXPLAIN ANALYZE}，
     * 原语句真的被执行。含 {@code /*!} 一律放弃估算。
     */
    @Test
    void a_mysql_executable_comment_refuses_the_estimate() {
        assertThat(ExplainRunner.hasExecutableComment("/*!80018 ANALYZE */ select 1")).isTrue();
        assertThat(ExplainRunner.hasExecutableComment("select /*!40001 SQL_NO_CACHE */ * from t")).isTrue();
        assertThat(ExplainRunner.hasExecutableComment("select 1")).isFalse();

        // 不走连库：conn 为 null 仍返回 -1，说明守卫在建立连接之前就拦下了。
        assertThat(new ExplainRunner().estimate(null, "/*!80018 ANALYZE */ delete from t_order"))
                .isEqualTo(new ExplainEstimate(-1L));
    }

    /**
     * 权限类错误（1044/1045/1142）是<b>故障</b>：必须抛，不能吞成 {@code -1}。
     *
     * <p>吞成 {@code -1} 的后果是"没权限"与"语句估不出来"再也分不开：控制面对前者必须中止，
     * 对后者按最严分档。
     */
    @Test
    void permission_failures_are_thrown_instead_of_swallowed() {
        assertThatThrownBy(() -> ExplainRunner.throwIfUnavailable(new SQLException(
                "EXPLAIN command denied to user 'ro_user'@'10.1.2.3' for table 't_order'", "42000", 1142)))
                .as("1142：只读账号 EXPLAIN 写语句")
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("1142")
                        .contains("'***'@'***'")
                        .doesNotContain("ro_user")
                        .doesNotContain("10.1.2.3"));

        assertThatThrownBy(() -> ExplainRunner.throwIfUnavailable(new SQLException(
                "Access denied for user 'ro_user'@'10.1.2.3' (using password: YES)", "28000", 1045)))
                .as("1045：账号或密码错误")
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("1045")
                        .doesNotContain("ro_user")
                        .doesNotContain("10.1.2.3"));

        assertThatThrownBy(() -> ExplainRunner.throwIfUnavailable(new SQLException(
                "Access denied for user 'ro_user'@'10.1.2.3' to database 'order_db'", "42000", 1044)))
                .as("1044：库级权限不足")
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("1044"));
    }

    /**
     * 连接类（{@code 08xxx}）同样抛，且<b>不回显原文</b>：原文里带着 host:port。
     */
    @Test
    void connection_failures_are_thrown_without_echoing_the_host() {
        assertThatThrownBy(() -> ExplainRunner.throwIfUnavailable(new SQLException(
                "Communications link failure to 10.1.2.3:3306", "08S01", 0)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("数据源不可达")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("10.1.2.3")
                        .doesNotContain("Communications link failure"));
    }

    /**
     * 语法错、表不存在这些"语句本身估不出来"仍是正常结果（{@code -1}），不能被误当成故障抛出去。
     */
    @Test
    void ordinary_sql_failures_are_still_reported_as_unknown() {
        assertThatCode(() -> ExplainRunner.throwIfUnavailable(new SQLException(
                "You have an error in your SQL syntax", "42000", 1064)))
                .doesNotThrowAnyException();
        assertThatCode(() -> ExplainRunner.throwIfUnavailable(new SQLException(
                "Table 'order_db.no_such_table' doesn't exist", "42S02", 1146)))
                .doesNotThrowAnyException();
        // SQLState 缺失（驱动没给）：不能靠猜，按"估不出来"处理。
        assertThatCode(() -> ExplainRunner.throwIfUnavailable(new SQLException("unknown", null, 0)))
                .doesNotThrowAnyException();
    }
}
