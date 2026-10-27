package com.badc.sql.executor.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;

/**
 * 查询执行的真实库测试：类型映射是这里最容易出问题的地方。
 *
 * <p>风险点（Review Focus 第 2 条）：SQL {@code NULL} 必须变成 JSON {@code null} 而不是字符串
 * "null"；BLOB 不能把二进制直接塞进 JSON；utf8mb4 的 emoji 不能被截成乱码。
 */
class QueryRunnerIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String SCHEMA = "badc_test_queryrunner";

    private final QueryRunner runner = new QueryRunner();

    private static ConnConfig conn(String dbName) {
        return new ConnConfig(HOST, PORT, dbName, USER, PASS, "utf8mb4", "+00:00");
    }

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
            s.execute("create database " + SCHEMA + " default character set utf8mb4");
            s.execute("use " + SCHEMA);
            s.execute("""
                    create table t_types (
                        id int primary key,
                        nullable_col varchar(32) null,
                        blob_col blob null,
                        big_text longtext null,
                        emoji_col varchar(32) null,
                        created_at datetime(3) null
                    ) engine=InnoDB default charset=utf8mb4
                    """);
            s.execute("insert into t_types values (1, null, x'00ff', repeat('a', 70000), '订单🚀', '2026-10-26 12:34:56.789')");
            s.execute("insert into t_types values (2, 'v2', null, 'small', '🎉', null)");
            for (int i = 3; i <= 30; i++) {
                s.execute("insert into t_types values (" + i + ", 'v" + i + "', null, null, null, null)");
            }
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
        }
    }

    @Test
    void sql_null_becomes_json_null_not_the_string_null() {
        RawResult result = runner.run(conn(SCHEMA), "select nullable_col from t_types where id = 1", 100);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).get(0)).isNull();
    }

    @Test
    void blob_is_replaced_by_its_length_not_raw_bytes() {
        RawResult result = runner.run(conn(SCHEMA), "select blob_col from t_types where id = 1", 100);

        Object cell = result.rows().get(0).get(0);
        assertThat(cell).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = (Map<String, Object>) cell;
        assertThat(asMap.get("hexLen")).isEqualTo(2);
    }

    @Test
    void oversized_text_is_truncated_with_a_visible_marker() {
        RawResult result = runner.run(conn(SCHEMA), "select big_text from t_types where id = 1", 100);

        String cell = (String) result.rows().get(0).get(0);
        assertThat(cell.length()).isLessThan(70000);
        assertThat(cell).endsWith("[truncated]");
    }

    @Test
    void emoji_survives_round_trip() {
        RawResult result = runner.run(conn(SCHEMA), "select emoji_col from t_types where id = 1", 100);

        assertThat(result.rows().get(0).get(0)).isEqualTo("订单🚀");
    }

    @Test
    void datetime_is_rendered_as_utc_instant_string() {
        RawResult result = runner.run(conn(SCHEMA), "select created_at from t_types where id = 1", 100);

        assertThat((String) result.rows().get(0).get(0)).isEqualTo("2026-10-26T12:34:56.789Z");
    }

    @Test
    void rows_are_capped_and_flagged_as_truncated() {
        RawResult result = runner.run(conn(SCHEMA), "select id from t_types", 10);

        assertThat(result.rows()).hasSize(10);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void not_truncated_when_result_fits() {
        RawResult result = runner.run(conn(SCHEMA), "select id from t_types where id < 5", 10);

        assertThat(result.rows()).hasSize(4);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void column_meta_carries_name_and_type() {
        RawResult result = runner.run(conn(SCHEMA), "select id, nullable_col from t_types where id = 2", 10);

        assertThat(result.columns()).extracting(c -> c.name()).containsExactly("id", "nullable_col");
        List<String> types = result.columns().stream().map(c -> c.type()).toList();
        assertThat(types).allMatch(t -> t != null && !t.isBlank());
    }
}
