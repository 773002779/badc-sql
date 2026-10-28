package com.badc.sql.control.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 语句分类：只做<b>识别</b>，不判等级（等级在任务 4 的 PolicyValidator / 任务 6 的 RiskEngine）。
 *
 * <p>两条主线：关键字必须出现在代码里（注释、字符串、反引号标识符里的不算），且 {@code alter table}
 * 一次多个子句时要报出其中最严重的那个——只认第一个动作会把 L3 判成 L1。
 */
class StatementClassifierTest {

    private final StatementClassifier classifier = new StatementClassifier();

    @Test
    void a_drop_table_inside_a_string_literal_is_not_ddl() {
        Classification c = classifier.classify("update t_order set note = 'drop table x' where id = 1");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.NONE);
        assertThat(c.structuralDamage()).isFalse();
        assertThat(c.writeStatement()).isTrue();
    }

    @Test
    void a_commented_out_drop_table_does_not_make_an_update_structural() {
        assertThat(classifier.classify("-- drop table t\nupdate t set a = 1 where id = 2").structuralDamage()).isFalse();
    }

    @Test
    void a_backticked_table_named_like_a_keyword_is_not_a_drop() {
        assertThat(classifier.classify("alter table `drop table` add column a int null").ddlAction())
                .isEqualTo(DdlAction.ALTER_ADD_COLUMN);
    }

    /** 反引号 + if exists：仍然是一次 drop table。 */
    @Test
    void drop_table_if_exists_still_counts_as_structural_damage() {
        Classification c = classifier.classify("DROP TABLE IF EXISTS `t_order`");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.DROP_TABLE);
        assertThat(c.structuralDamage()).isTrue();
        assertThat(c.table()).isEqualTo("t_order");
    }

    /** 最严重的子句在后面：只看第一个动作就会把 L3 判成 L1。 */
    @Test
    void drop_column_after_add_column_still_makes_the_alter_structural() {
        assertThat(classifier.classify("alter table t_order add column b int null, drop column a").structuralDamage())
                .isTrue();
    }

    @Test
    void multiple_statements_are_flagged() {
        assertThat(classifier.classify("update t set a=1; delete from t").multipleStatements()).isTrue();
    }

    @Test
    void a_cross_database_reference_is_flagged() {
        assertThat(classifier.classify("update db2.t_order set a = 1 where id = 1").crossDatabase()).isTrue();
    }

    // ---- 以上为 brief 逐字指定的用例；以下补齐八种结构性动作与边界 ----

    @Test
    void drop_column_after_add_column_reports_the_most_severe_clause() {
        assertThat(classifier.classify("alter table t_order add column b int null, drop column a").ddlAction())
                .isEqualTo(DdlAction.ALTER_DROP_COLUMN);
    }

    @Test
    void truncate_is_structural() {
        Classification c = classifier.classify("truncate table t_order");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.TRUNCATE);
        assertThat(c.structuralDamage()).isTrue();
        assertThat(c.table()).isEqualTo("t_order");
        assertThat(c.writeStatement()).isTrue();
    }

    @Test
    void rename_table_is_structural() {
        Classification c = classifier.classify("rename table t_order to t_order_bak");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.RENAME_TABLE);
        assertThat(c.structuralDamage()).isTrue();
    }

    @Test
    void drop_partition_is_structural() {
        Classification c = classifier.classify("alter table t_order drop partition p202601");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.DROP_PARTITION);
        assertThat(c.structuralDamage()).isTrue();
    }

    @Test
    void altering_the_charset_is_structural() {
        Classification c = classifier.classify("alter table t_order convert to character set utf8mb4");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.ALTER_CONVERT_CHARSET);
        assertThat(c.structuralDamage()).isTrue();
    }

    @Test
    void dropping_an_index_is_structural() {
        Classification c = classifier.classify("alter table t_order drop index idx_note");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.ALTER_DROP_INDEX);
        assertThat(c.structuralDamage()).isTrue();
    }

    /** 改列类型只报动作；是否收窄要看列类型文本，由任务 4 判。 */
    @Test
    void modify_is_recognised_but_not_structural_on_its_own() {
        Classification c = classifier.classify("alter table t_order modify column note varchar(64) not null");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.ALTER_MODIFY);
        assertThat(c.structuralDamage()).isFalse();
    }

    @Test
    void drop_database_is_recognised_separately_from_drop_table() {
        assertThat(classifier.classify("drop database order_db").ddlAction()).isEqualTo(DdlAction.DROP_DATABASE);
        assertThat(classifier.classify("drop schema order_db").ddlAction()).isEqualTo(DdlAction.DROP_DATABASE);
    }

    @Test
    void adding_a_unique_index_is_recognised() {
        assertThat(classifier.classify("alter table t_order add unique key uk_note (note)").ddlAction())
                .isEqualTo(DdlAction.ALTER_ADD_UNIQUE_INDEX);
    }

    @Test
    void create_table_is_a_write_but_not_structural_damage() {
        Classification c = classifier.classify("create table if not exists `t_new` (id bigint primary key)");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.CREATE_TABLE);
        assertThat(c.structuralDamage()).isFalse();
        assertThat(c.writeStatement()).isTrue();
        assertThat(c.table()).isEqualTo("t_new");
    }

    @Test
    void create_index_is_recognised_and_keeps_its_table() {
        Classification c = classifier.classify("create index idx_note on t_order (note)");
        assertThat(c.ddlAction()).isEqualTo(DdlAction.CREATE_INDEX);
        assertThat(c.structuralDamage()).isFalse();
        assertThat(c.table()).isEqualTo("t_order");
    }

    @Test
    void a_select_is_not_a_write_and_reports_its_where_and_limit() {
        Classification c = classifier.classify("select * from t_order where id = 1 limit 10");
        assertThat(c.firstKeyword()).isEqualTo("select");
        assertThat(c.writeStatement()).isFalse();
        assertThat(c.hasWhere()).isTrue();
        assertThat(c.hasLimit()).isTrue();
        assertThat(c.ddlAction()).isEqualTo(DdlAction.NONE);
    }

    @Test
    void a_delete_without_where_is_still_a_write() {
        Classification c = classifier.classify("delete from t_order");
        assertThat(c.firstKeyword()).isEqualTo("delete");
        assertThat(c.writeStatement()).isTrue();
        assertThat(c.hasWhere()).isFalse();
        assertThat(c.table()).isEqualTo("t_order");
    }

    /** 顶层才有意义：子查询里的 where/limit 不代表这条 update 收窄了行范围。 */
    @Test
    void where_and_limit_inside_parentheses_are_not_top_level() {
        Classification c = classifier.classify("update t_order set total = (select max(x) from other where y = 1)");
        assertThat(c.hasWhere()).isFalse();
        assertThat(c.hasLimit()).isFalse();
    }

    @Test
    void a_top_level_where_next_to_a_subquery_is_still_top_level() {
        Classification c = classifier.classify(
                "update t_order set a = (select max(b) from other where c = 1) where id in (select id from x limit 3)");
        assertThat(c.hasWhere()).isTrue();
        assertThat(c.hasLimit()).isFalse();
    }

    @Test
    void plain_same_database_writes_are_not_cross_database() {
        assertThat(classifier.classify("update t_order set a = 1 where id = 1").crossDatabase()).isFalse();
    }

    /** 多语句时只判第一条——它马上会被 BLOCK，其余字段没有意义。 */
    @Test
    void only_the_first_statement_is_classified_when_there_are_several() {
        Classification c = classifier.classify("select 1; drop table t_order");
        assertThat(c.multipleStatements()).isTrue();
        assertThat(c.writeStatement()).isFalse();
        assertThat(c.ddlAction()).isEqualTo(DdlAction.NONE);
    }

    @Test
    void trailing_semicolons_do_not_count_as_multiple_statements() {
        assertThat(classifier.classify("update t set a=1 where id=1;").multipleStatements()).isFalse();
    }

    @Test
    void null_or_blank_input_is_classified_as_nothing_rather_than_throwing() {
        for (String sql : new String[] {null, "", "   ", ";"}) {
            Classification c = classifier.classify(sql);
            assertThat(c.multipleStatements()).isFalse();
            assertThat(c.firstKeyword()).isNull();
            assertThat(c.ddlAction()).isEqualTo(DdlAction.NONE);
            assertThat(c.structuralDamage()).isFalse();
            assertThat(c.writeStatement()).isFalse();
            assertThat(c.hasWhere()).isFalse();
            assertThat(c.hasLimit()).isFalse();
            assertThat(c.crossDatabase()).isFalse();
            assertThat(c.table()).isNull();
        }
    }

    @Test
    void first_keyword_is_lowercased_so_callers_need_not_normalise() {
        assertThat(classifier.classify("INSERT INTO t VALUES (1)").firstKeyword()).isEqualTo("insert");
    }

    @Test
    void insert_into_a_backticked_table_keeps_the_table_name() {
        assertThat(classifier.classify("insert into `t_order` (a) values (1)").table()).isEqualTo("t_order");
    }

    @Test
    void a_qualified_write_keeps_the_qualified_table_name() {
        Classification c = classifier.classify("update db2.t_order set a = 1 where id = 1");
        assertThat(c.table()).isEqualTo("db2.t_order");
        assertThat(c.writeStatement()).isTrue();
    }
}
