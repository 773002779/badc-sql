package com.badc.sql.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.sql.SqlText;

/**
 * 顶层语句切分：只认代码位置的分号。
 *
 * <p>字符串与注释里的 {@code ;} 是正文内容，不是分隔符——这正是不能用 {@code split(";")} 的原因。
 */
class SqlTextSplitTest {

    @Test
    void a_semicolon_inside_a_string_literal_does_not_split() {
        assertThat(SqlText.splitStatements("insert into t values ('a;b')")).hasSize(1);
    }

    @Test
    void a_semicolon_inside_a_comment_does_not_split() {
        assertThat(SqlText.splitStatements("update t set a=1 -- x;y\n")).hasSize(1);
    }

    @Test
    void two_real_statements_split_into_two() {
        assertThat(SqlText.splitStatements("insert into t values (1); insert into t values (2);"))
                .containsExactly("insert into t values (1)", "insert into t values (2)");
    }

    /** 反引号标识符与字符串一样被掩码，里面的分号同样不切。 */
    @Test
    void a_semicolon_inside_a_backticked_identifier_does_not_split() {
        assertThat(SqlText.splitStatements("insert into `a;b` values (1)")).hasSize(1);
    }

    /** 切出来的是原文，不做任何改写：大小写、注释、空白都保留。 */
    @Test
    void each_piece_is_the_original_text_stripped_at_the_edges() {
        assertThat(SqlText.splitStatements("  UPDATE T SET A=1 /* c */ ;\n  delete from t  "))
                .containsExactly("UPDATE T SET A=1 /* c */", "delete from t");
    }

    @Test
    void empty_pieces_around_separators_are_dropped() {
        assertThat(SqlText.splitStatements(";; insert into t values (1);;"))
                .containsExactly("insert into t values (1)");
    }

    @Test
    void null_or_blank_input_yields_no_statements_rather_than_throwing() {
        assertThat(SqlText.splitStatements(null)).isEmpty();
        assertThat(SqlText.splitStatements("")).isEmpty();
        assertThat(SqlText.splitStatements("  \n\t ")).isEmpty();
        assertThat(SqlText.splitStatements(";")).isEmpty();
    }
}
