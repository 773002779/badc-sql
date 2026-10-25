package com.badc.sql.executor.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.badc.sql.common.DbVersion;
import com.badc.sql.common.dto.ValidationResult;

/**
 * MySQL 版本方言校验。
 *
 * <p>核心风险是<b>假阳性</b>：把字符串字面量或注释里的关键字当成语法特性，导致合法 SQL 被拒。
 * 因此校验前必须先剥掉注释与字面量。
 */
class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    void cte_is_rejected_on_57_with_a_version_aware_message() {
        ValidationResult result = dialect.validate("with t as (select 1) select * from t", DbVersion.MYSQL_5_7);

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("5.7").contains("WITH");
    }

    @Test
    void cte_is_accepted_on_80() {
        assertThat(dialect.validate("with t as (select 1) select * from t", DbVersion.MYSQL_8_0).ok()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select 'WITH x is not a cte'",
            "select \"with\" from dual",
            "select `with` from t",
    })
    void keyword_inside_a_string_literal_is_not_a_feature(String sql) {
        assertThat(dialect.validate(sql, DbVersion.MYSQL_5_7).ok()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-- with t as (select 1) select * from t\nselect 1",
            "# with t as (select 1)\nselect 1",
            "/* with t as (select 1) */ select 1",
    })
    void keyword_inside_a_comment_is_not_a_feature(String sql) {
        assertThat(dialect.validate(sql, DbVersion.MYSQL_5_7).ok()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "select row_number() over (order by id) from t",
            "select json_col ->> '$.a' from t",
            "select * from json_table('[1]', '$[*]' columns (a int path '$')) jt",
    })
    void other_80_only_features_are_rejected_on_57(String sql) {
        assertThat(dialect.validate(sql, DbVersion.MYSQL_5_7).ok()).isFalse();
    }

    @Test
    void plain_sql_passes_on_both_versions() {
        String sql = "select id, name from t_order where id = 1";
        assertThat(dialect.validate(sql, DbVersion.MYSQL_5_7).ok()).isTrue();
        assertThat(dialect.validate(sql, DbVersion.MYSQL_8_0).ok()).isTrue();
    }

    @Test
    void empty_or_blank_sql_is_rejected_as_invalid_argument() {
        assertThat(dialect.validate("   ", DbVersion.MYSQL_8_0).ok()).isFalse();
        assertThat(dialect.validate(null, DbVersion.MYSQL_8_0).ok()).isFalse();
    }

    @Test
    void escapes_inside_string_literals_do_not_leak_the_literal_into_matching() {
        // 单引号内的 \' 转义：'it\'s WITH' 仍是字面量，不该触发 CTE 判定。
        assertThat(dialect.validate("select 'it\\'s WITH x'", DbVersion.MYSQL_5_7).ok()).isTrue();
    }
}
