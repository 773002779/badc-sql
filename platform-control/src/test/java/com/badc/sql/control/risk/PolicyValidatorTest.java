package com.badc.sql.control.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 规范校验：把 spec §FR-10 的规则集落成"带纪律"的命中。
 *
 * <p>每个 WARN / INFO 规则都有"该命中"与"不该命中"两面：只有正面用例时，一条宽到匹配所有 SQL 的
 * 规则也能全绿，那等于没有规则。BLOCK 与 PIN_LEVEL 两种口径也各有用例——前者拒绝提交，后者只钉等级。
 *
 * <p>收窄判定必须两条路径都测：给了旧类型（放宽 / 收窄 / 同宽）与没给旧类型（保守分支）。只测后者会让
 * "前后对比"静悄悄退化成"一律 L3"。
 */
class PolicyValidatorTest {

    private final StatementClassifier classifier = new StatementClassifier();
    private final PolicyValidator validator = new PolicyValidator();

    private List<RiskHit> hits(String sql) {
        return validator.validate(sql, classifier.classify(sql), null);
    }

    private List<RiskHit> hits(String sql, Map<String, Map<String, String>> oldColumnTypes) {
        return validator.validate(sql, classifier.classify(sql), oldColumnTypes);
    }

    private static List<RiskHit> withCode(List<RiskHit> hits, String ruleCode) {
        return hits.stream().filter(hit -> hit.ruleCode().equals(ruleCode)).toList();
    }

    // ------------------------------------------------------------------ BLOCK

    @Test
    void drop_database_is_blocked_at_submit() {
        assertThat(hits("drop database order_db"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.BLOCK);
                    assertThat(hit.ruleCode()).isEqualTo("DROP_DATABASE");
                    assertThat(hit.level()).isNull();
                    assertThat(hit.message()).isNotBlank();
                });
    }

    @Test
    void drop_schema_is_blocked_like_drop_database() {
        assertThat(withCode(hits("drop schema order_db"), "DROP_DATABASE"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.discipline()).isEqualTo(Discipline.BLOCK));
    }

    @Test
    void multiple_statements_in_one_string_are_blocked() {
        assertThat(hits("update t set a=1; delete from t"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.ruleCode()).isEqualTo("MULTI_STATEMENT");
                    assertThat(hit.discipline()).isEqualTo(Discipline.BLOCK);
                    assertThat(hit.level()).isNull();
                });
    }

    /** 被 BLOCK 的语句进不到审批：只报阻断理由，不把 L3 钉死或风格提示混进来。 */
    @Test
    void a_blocked_statement_reports_only_blocking_hits() {
        List<RiskHit> hits = hits("drop database order_db; update t set a = 1");
        assertThat(hits).isNotEmpty().allSatisfy(hit -> assertThat(hit.discipline()).isEqualTo(Discipline.BLOCK));
        assertThat(withCode(hits, "MULTI_STATEMENT")).hasSize(1);
        assertThat(withCode(hits, "DROP_DATABASE")).hasSize(1);
    }

    /** 字符串字面量里的分号不是语句分隔符：不能凭它把一条正常 insert 判成多语句。 */
    @Test
    void a_semicolon_inside_a_string_literal_is_not_a_second_statement() {
        List<RiskHit> hits = hits("insert into t_order (note) values ('a;b')");
        assertThat(withCode(hits, "MULTI_STATEMENT")).isEmpty();
        assertThat(hits).noneMatch(hit -> hit.discipline() == Discipline.BLOCK);
    }

    @Test
    void a_drop_database_inside_a_string_literal_is_not_blocked() {
        assertThat(hits("update t_order set note = 'drop database order_db' where id = 1"))
                .noneMatch(hit -> hit.discipline() == Discipline.BLOCK);
    }

    // ------------------------------------------------------------------ PIN_LEVEL：写语句

    @Test
    void an_update_without_where_pins_l3_but_does_not_block() {
        List<RiskHit> hits = hits("update t_order set a = 1");
        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.ruleCode()).isEqualTo("WRITE_WITHOUT_WHERE");
            assertThat(hit.discipline()).isEqualTo(Discipline.PIN_LEVEL);
            assertThat(hit.level()).isEqualTo(RiskLevel.L3);
        });
        assertThat(hits).noneMatch(hit -> hit.discipline() == Discipline.BLOCK);
    }

    @Test
    void a_delete_without_where_pins_l3() {
        assertThat(withCode(hits("delete from t_order"), "WRITE_WITHOUT_WHERE"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.level()).isEqualTo(RiskLevel.L3));
    }

    @Test
    void an_update_with_a_where_does_not_pin_a_level() {
        assertThat(hits("update t_order set a = 1 where id = 1"))
                .noneMatch(hit -> hit.discipline() == Discipline.PIN_LEVEL);
    }

    @Test
    void an_insert_is_not_a_write_without_where() {
        assertThat(withCode(hits("insert into t_order (id) values (1)"), "WRITE_WITHOUT_WHERE")).isEmpty();
    }

    /** 关键字大小写不能影响判定：大写 UPDATE 同样没有 where。 */
    @Test
    void an_uppercase_update_without_where_still_pins_l3() {
        assertThat(withCode(hits("UPDATE T_ORDER SET A = 1"), "WRITE_WITHOUT_WHERE"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.level()).isEqualTo(RiskLevel.L3));
    }

    /** CTE 里的 delete 没有 where：首关键字是 with，但顶层动词是 delete，仍然要钉 L3。 */
    @Test
    void a_cte_delete_without_where_pins_l3() {
        assertThat(withCode(hits("with c as (select id from t_order where id = 1) delete from t_order"),
                "WRITE_WITHOUT_WHERE")).singleElement();
    }

    // ------------------------------------------------------------------ PIN_LEVEL：七个结构性动作

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "drop table t_order | DROP_TABLE",
            "DROP TABLE IF EXISTS t_order | DROP_TABLE",
            "truncate table t_order | TRUNCATE",
            "TRUNCATE t_order | TRUNCATE",
            "rename table t_order to t_order_bak | RENAME_TABLE",
            "alter table t_order drop column a | ALTER_DROP_COLUMN",
            "alter table t_order drop index idx_a | ALTER_DROP_INDEX",
            "alter table t_order convert to character set utf8mb4 | ALTER_CONVERT_CHARSET",
            "alter table t_order drop partition p202601 | DROP_PARTITION",
    })
    void a_structural_action_pins_l3(String sql, String ruleCode) {
        List<RiskHit> hits = hits(sql);
        assertThat(withCode(hits, ruleCode))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.PIN_LEVEL);
                    assertThat(hit.level()).isEqualTo(RiskLevel.L3);
                    assertThat(hit.message()).isNotBlank();
                });
        assertThat(hits).noneMatch(hit -> hit.discipline() == Discipline.BLOCK);
    }

    @Test
    void a_non_structural_ddl_does_not_pin_a_level() {
        assertThat(withCode(hits("create table t_order (id bigint primary key)"), "DROP_TABLE")).isEmpty();
        assertThat(hits("create table t_order (id bigint primary key)"))
                .noneMatch(hit -> hit.discipline() == Discipline.PIN_LEVEL);
        assertThat(hits("alter table t_order add column b int null"))
                .noneMatch(hit -> hit.discipline() == Discipline.PIN_LEVEL);
    }

    // ------------------------------------------------------------------ PIN_LEVEL：收窄（三条 brief 指定用例）

    /** 有旧类型且是放宽：不算收窄，不得命中 ALTER_MODIFY_NARROWED。 */
    @Test
    void widening_a_column_type_is_not_narrowing_when_the_old_type_is_known() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(64)"));
        assertThat(hits("alter table t modify column c varchar(255) not null", old))
                .noneSatisfy(hit -> assertThat(hit.ruleCode()).isEqualTo("ALTER_MODIFY_NARROWED"));
    }

    /** 有旧类型且是收窄：命中 L3。 */
    @Test
    void narrowing_a_column_type_pins_l3_when_the_old_type_is_known() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(255)"));
        assertThat(hits("alter table t modify column c varchar(64) not null", old))
                .anySatisfy(hit -> {
                    assertThat(hit.ruleCode()).isEqualTo("ALTER_MODIFY_NARROWED");
                    assertThat(hit.level()).isEqualTo(RiskLevel.L3);
                });
    }

    /** 没有旧类型可比：一律按最严，并说明理由——这条用例是"保守分支"的唯一证据，不能省。 */
    @Test
    void an_unknown_old_type_falls_back_to_l3_with_a_reason() {
        assertThat(hits("alter table t modify column c varchar(255) not null"))
                .anySatisfy(hit -> {
                    assertThat(hit.ruleCode()).isEqualTo("ALTER_MODIFY_NARROWED");
                    assertThat(hit.message()).contains("无法判定");
                });
    }

    // ------------------------------------------------------------------ 收窄：其余两条路径的边界

    @Test
    void narrowing_a_column_type_pins_l3_with_a_known_old_type_message() {
        Map<String, Map<String, String>> old = Map.of("t_order", Map.of("c", "varchar(255)"));
        assertThat(withCode(hits("alter table t_order modify c varchar(64) not null", old),
                "ALTER_MODIFY_NARROWED"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.PIN_LEVEL);
                    assertThat(hit.message()).contains("varchar(255)").contains("varchar(64)");
                });
    }

    @Test
    void the_same_column_width_is_not_narrowing() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(64)"));
        assertThat(withCode(hits("alter table t modify column c varchar(64) not null", old),
                "ALTER_MODIFY_NARROWED")).isEmpty();
    }

    @Test
    void narrowing_bigint_to_int_pins_l3() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("amount", "bigint"));
        assertThat(withCode(hits("alter table t modify column amount int not null", old),
                "ALTER_MODIFY_NARROWED"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.level()).isEqualTo(RiskLevel.L3);
                    assertThat(hit.message()).contains("bigint").contains("int");
                });
    }

    @Test
    void narrowing_decimal_precision_pins_l3() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("amount", "decimal(10,2)"));
        assertThat(withCode(hits("alter table t modify column amount decimal(8,2) not null", old),
                "ALTER_MODIFY_NARROWED")).singleElement();
    }

    @Test
    void narrowing_char_length_pins_l3() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("code", "char(10)"));
        assertThat(withCode(hits("alter table t modify column code char(4) not null", old),
                "ALTER_MODIFY_NARROWED")).singleElement();
    }

    /** 整数放宽：int → bigint 是加宽，不能命中。 */
    @Test
    void widening_an_integer_is_not_narrowing() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("amount", "int"));
        assertThat(withCode(hits("alter table t modify column amount bigint not null", old),
                "ALTER_MODIFY_NARROWED")).isEmpty();
    }

    /** 换类型但证不出收窄（int → varchar(64)）：按 spec 不命中。 */
    @Test
    void changing_the_type_family_without_proof_is_not_narrowing() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("amount", "int"));
        assertThat(withCode(hits("alter table t modify column amount varchar(64) not null", old),
                "ALTER_MODIFY_NARROWED")).isEmpty();
    }

    /** change [column] 也带类型，收窄风险与 modify 相同。 */
    @Test
    void change_column_narrowing_pins_l3() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(255)"));
        assertThat(withCode(hits("alter table t change column c c_new varchar(64) not null", old),
                "ALTER_MODIFY_NARROWED")).singleElement();
    }

    /** 给了旧类型表、但快照里没有这一列：仍然无法判定，走保守分支并说明理由。 */
    @Test
    void a_column_missing_from_the_snapshot_falls_back_to_l3() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("other", "varchar(255)"));
        assertThat(withCode(hits("alter table t modify column c varchar(64) not null", old),
                "ALTER_MODIFY_NARROWED"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.message()).contains("无法判定是否收窄"));
    }

    /** 空快照与会为 null 的快照等价：不得抛异常，且走保守分支。 */
    @Test
    void an_empty_snapshot_map_falls_back_to_l3() {
        assertThat(withCode(hits("alter table t modify column c varchar(64) not null", Map.of()),
                "ALTER_MODIFY_NARROWED"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.message()).contains("无法判定是否收窄"));
    }

    /** 跨库限定名（db2.t）的表名要在快照里找得到（按最后一段、忽略大小写）。 */
    @Test
    void a_qualified_table_name_still_finds_the_old_type() {
        Map<String, Map<String, String>> old = Map.of("T_ORDER", Map.of("C", "varchar(255)"));
        assertThat(withCode(hits("alter table db2.t_order modify column c varchar(64) not null", old),
                "ALTER_MODIFY_NARROWED")).singleElement();
    }

    @Test
    void an_alter_without_modify_never_reports_a_narrowing() {
        Map<String, Map<String, String>> old = Map.of("t", Map.of("c", "varchar(255)"));
        assertThat(withCode(hits("alter table t add column d int null", old), "ALTER_MODIFY_NARROWED")).isEmpty();
    }

    // ------------------------------------------------------------------ WARN

    @Test
    void select_star_is_only_a_warning() {
        assertThat(withCode(hits("select * from t_order"), "SELECT_STAR"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.WARN);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void an_explicit_column_list_is_not_select_star() {
        assertThat(withCode(hits("select id, name from t_order"), "SELECT_STAR")).isEmpty();
    }

    /** count(*) 里的星号在括号里，不是"取全部列"。 */
    @Test
    void count_star_is_not_select_star() {
        assertThat(withCode(hits("select count(*) from t_order"), "SELECT_STAR")).isEmpty();
    }

    @Test
    void a_qualified_star_is_still_select_star() {
        assertThat(withCode(hits("select t.* from t_order t"), "SELECT_STAR")).singleElement();
    }

    @Test
    void a_select_without_limit_warns() {
        assertThat(withCode(hits("select id from t_order"), "SELECT_WITHOUT_LIMIT"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.WARN);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void a_limit_suppresses_the_missing_limit_warning() {
        assertThat(withCode(hits("select id from t_order limit 10"), "SELECT_WITHOUT_LIMIT")).isEmpty();
    }

    @Test
    void an_aggregate_query_does_not_warn_about_a_missing_limit() {
        assertThat(withCode(hits("select count(*) from t_order"), "SELECT_WITHOUT_LIMIT")).isEmpty();
    }

    @Test
    void a_grouped_query_does_not_warn_about_a_missing_limit() {
        assertThat(withCode(hits("select status, count(*) from t_order group by status"),
                "SELECT_WITHOUT_LIMIT")).isEmpty();
    }

    @Test
    void a_leading_wildcard_like_warns() {
        assertThat(withCode(hits("select id from t_order where name like '%x%' limit 10"),
                "LEADING_WILDCARD_LIKE"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.WARN);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void a_suffix_wildcard_like_does_not_warn() {
        assertThat(withCode(hits("select id from t_order where name like 'x%' limit 10"),
                "LEADING_WILDCARD_LIKE")).isEmpty();
    }

    @Test
    void a_literal_like_that_is_not_a_wildcard_prefix_does_not_warn() {
        assertThat(withCode(hits("select id from t_order where name like 'x%y' limit 10"),
                "LEADING_WILDCARD_LIKE")).isEmpty();
    }

    @Test
    void comparing_a_column_with_a_quoted_number_warns_about_implicit_cast() {
        assertThat(withCode(hits("select id from t_order where code = '123' limit 10"), "IMPLICIT_CAST"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.WARN);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void comparing_a_column_with_a_quoted_word_does_not_warn() {
        assertThat(withCode(hits("select id from t_order where code = 'abc' limit 10"), "IMPLICIT_CAST")).isEmpty();
    }

    @Test
    void comparing_a_column_with_a_quoted_date_warns_about_implicit_cast() {
        assertThat(withCode(hits("select id from t_order where created_at = '2026-01-01 00:00:00' limit 10"),
                "IMPLICIT_CAST")).singleElement();
    }

    @Test
    void comparing_a_column_with_an_unquoted_number_does_not_warn() {
        assertThat(withCode(hits("select id from t_order where code = 123 limit 10"), "IMPLICIT_CAST")).isEmpty();
    }

    /** 双字符比较运算符（{@code <>}/{@code !=}/{@code <=}/{@code >=}）同样是"列与字面量比较"。 */
    @ParameterizedTest(name = "code {0} ''123''")
    @CsvSource(value = {"<>", "!=", "<=", ">="})
    void every_comparison_operator_detects_the_implicit_cast(String operator) {
        assertThat(withCode(hits("select id from t_order where code " + operator + " '123' limit 10"),
                "IMPLICIT_CAST")).singleElement();
    }

    @Test
    void ordering_by_rand_warns() {
        assertThat(withCode(hits("select id from t_order order by rand() limit 10"), "ORDER_BY_RAND"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.WARN);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void ordering_by_a_column_does_not_warn() {
        assertThat(withCode(hits("select id from t_order order by id limit 10"), "ORDER_BY_RAND")).isEmpty();
    }

    /** 关键字比较一律先小写：大写 SQL 的 WARN 规则同样要命中，风格 INFO 则不应误报。 */
    @Test
    void warn_rules_match_keywords_case_insensitively() {
        List<RiskHit> hits = hits("SELECT id FROM t_order WHERE code = '123' AND name LIKE '%x%' "
                + "ORDER BY RAND() LIMIT 10");
        assertThat(hits).extracting(RiskHit::ruleCode)
                .contains("IMPLICIT_CAST", "LEADING_WILDCARD_LIKE", "ORDER_BY_RAND");
        assertThat(withCode(hits, "KEYWORD_NOT_UPPERCASE")).isEmpty();
    }

    // ------------------------------------------------------------------ INFO

    @Test
    void lowercase_keywords_are_only_an_info() {
        assertThat(withCode(hits("select id from t_order limit 10"), "KEYWORD_NOT_UPPERCASE"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.INFO);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void fully_uppercase_keywords_are_not_reported() {
        assertThat(withCode(hits("SELECT id FROM t_order LIMIT 10"), "KEYWORD_NOT_UPPERCASE")).isEmpty();
    }

    @Test
    void mixed_case_keywords_are_reported() {
        assertThat(withCode(hits("Select id From t_order Limit 10"), "KEYWORD_NOT_UPPERCASE")).singleElement();
    }

    /** 反引号里是标识符，不是关键字：`` `select` `` 这种列名不该被当成未大写关键字。 */
    @Test
    void a_backticked_identifier_is_not_a_keyword() {
        assertThat(withCode(hits("SELECT `select` FROM t_order LIMIT 10"), "KEYWORD_NOT_UPPERCASE")).isEmpty();
    }

    @Test
    void indentation_mixing_spaces_and_tabs_is_reported() {
        assertThat(withCode(hits("select id\n \tfrom t_order limit 10"), "INDENTATION_MESSY"))
                .singleElement()
                .satisfies(hit -> {
                    assertThat(hit.discipline()).isEqualTo(Discipline.INFO);
                    assertThat(hit.level()).isNull();
                });
    }

    @Test
    void consistent_indentation_is_not_reported() {
        assertThat(withCode(hits("select id\n    from t_order limit 10"), "INDENTATION_MESSY")).isEmpty();
        assertThat(withCode(hits("select id\n\tfrom t_order limit 10"), "INDENTATION_MESSY")).isEmpty();
        assertThat(withCode(hits("select id from t_order limit 10"), "INDENTATION_MESSY")).isEmpty();
    }

    // ------------------------------------------------------------------ 跨规则与边界

    /** level 只在 PIN_LEVEL 时有值：其余纪律一律 null，不用哨兵值。 */
    @Test
    void only_pin_level_hits_carry_a_level() {
        List<RiskHit> hits = hits("update t_order set a = 1");
        assertThat(hits).isNotEmpty().allSatisfy(hit -> {
            if (hit.discipline() == Discipline.PIN_LEVEL) {
                assertThat(hit.level()).isNotNull();
            } else {
                assertThat(hit.level()).isNull();
            }
        });
    }

    /** 版本兼容提示不在本类产出：目标库版本由执行器的方言校验器负责，写一条永远不命中的规则没有意义。 */
    @Test
    void a_version_mismatch_hint_is_never_produced() {
        assertThat(withCode(hits("select id from t_order limit 10"), "VERSION_MISMATCH_HINT")).isEmpty();
    }

    @Test
    void null_and_blank_sql_produce_no_hits() {
        assertThat(validator.validate(null, classifier.classify(null), null)).isEmpty();
        assertThat(validator.validate("   ", classifier.classify("   "), null)).isEmpty();
    }

    @Test
    void every_hit_carries_a_non_blank_message() {
        assertThat(hits("update t_order set a = 1"))
                .isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.message()).isNotBlank());
    }

    // ------------------------------------------------------------------ RiskLevel

    @Test
    void stricter_takes_the_higher_level() {
        assertThat(RiskLevel.stricter(RiskLevel.L1, RiskLevel.L3)).isEqualTo(RiskLevel.L3);
        assertThat(RiskLevel.stricter(RiskLevel.L3, RiskLevel.L1)).isEqualTo(RiskLevel.L3);
        assertThat(RiskLevel.stricter(RiskLevel.L2, RiskLevel.L2)).isEqualTo(RiskLevel.L2);
        assertThat(RiskLevel.stricter(RiskLevel.L0, RiskLevel.L1)).isEqualTo(RiskLevel.L1);
    }

    @Test
    void stricter_treats_null_as_l0() {
        assertThat(RiskLevel.stricter(null, RiskLevel.L2)).isEqualTo(RiskLevel.L2);
        assertThat(RiskLevel.stricter(RiskLevel.L1, null)).isEqualTo(RiskLevel.L1);
        assertThat(RiskLevel.stricter(null, null)).isEqualTo(RiskLevel.L0);
    }
}
