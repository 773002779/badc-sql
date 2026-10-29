package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;
import com.badc.sql.executor.exec.ExplainRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 真实 MySQL 上的结构快照与指纹。
 *
 * <p>沿用执行器既有风格（见 {@code QueryRunnerIT}）：纯 JUnit、不起 Spring 上下文、固定 schema、
 * 裸 JDBC。要钉住的不是"能跑通"，而是三件容易做错的事：
 * <ul>
 *   <li>抓取只读元数据，<b>不扫表</b>、不执行待估算的语句；</li>
 *   <li>表不存在不是失败（新建表），空指纹不能和"抓取失败"混为一谈；</li>
 *   <li>加列必须改变指纹，否则漂移检测形同虚设。</li>
 * </ul>
 */
class SchemaSnapshotterIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "root";
    private static final String SCHEMA = "badc_test_snapshot";

    @TempDir
    static Path tempRoot;

    private static ConnConfig conn;
    private static SchemaSnapshotter snapshotter;

    private static ConnConfig conn(String dbName) {
        return new ConnConfig(HOST, PORT, dbName, USER, PASS, "utf8mb4", "+00:00");
    }

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
            s.execute("create database " + SCHEMA + " default character set utf8mb4");
        }
        conn = conn(SCHEMA);
        snapshotter = new SchemaSnapshotter(tempRoot.toString());
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn("")), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + SCHEMA);
        }
    }

    @Test
    void capturing_a_snapshot_writes_both_files_and_a_stable_fingerprint() throws Exception {
        execute("create table t_order (id bigint primary key, remark varchar(64) null)");

        SnapshotResult first = snapshotter.capture(conn, List.of("t_order"), "1/101/7/1");

        assertThat(first.tableFingerprints()).containsKey("t_order");
        assertThat(first.ref()).isEqualTo("1/101/7/1");
        assertThat(tempRoot.resolve("1/101/7/1/schema.sql")).exists();
        assertThat(tempRoot.resolve("1/101/7/1/schema.json")).exists();
        assertThat(first.byteSize()).isLessThan(100 * 1024);
        assertThat(snapshotter.fingerprint(conn, List.of("t_order")).get("t_order"))
                .as("同一结构两次取指纹必须一致").isEqualTo(first.tableFingerprints().get("t_order"));

        String schemaSql = Files.readString(tempRoot.resolve("1/101/7/1/schema.sql"));
        assertThat(schemaSql).contains("-- t_order").contains("CREATE TABLE");
    }

    @Test
    void adding_a_column_changes_the_fingerprint() throws Exception {
        execute("create table t2 (id bigint primary key)");
        String before = snapshotter.fingerprint(conn, List.of("t2")).get("t2");
        execute("alter table t2 add column extra int null");
        assertThat(snapshotter.fingerprint(conn, List.of("t2")).get("t2"))
                .as("加列之后指纹必须变，否则漂移检测形同虚设").isNotEqualTo(before);
    }

    /** 新建表 / drop 之后的表：不存在不是失败，返回空指纹，由调用方记录"前置状态为空"。 */
    @Test
    void a_missing_table_yields_an_empty_fingerprint_instead_of_failing() {
        assertThat(snapshotter.fingerprint(conn, List.of("does_not_exist"))).isEmpty();
    }

    /** 抓快照时表不存在同理：文件照写（空快照），不抛异常。 */
    @Test
    void capturing_a_missing_table_writes_empty_snapshot_files_instead_of_failing() throws Exception {
        SnapshotResult result = snapshotter.capture(conn, List.of("does_not_exist"), "9/9/9/9");

        assertThat(result.tableFingerprints()).isEmpty();
        assertThat(tempRoot.resolve("9/9/9/9/schema.sql")).exists();
        assertThat(tempRoot.resolve("9/9/9/9/schema.json")).exists();
        assertThat(result.byteSize()).isPositive();
        assertThat(Files.readString(tempRoot.resolve("9/9/9/9/schema.sql"))).isEmpty();
    }

    @Test
    void the_schema_json_records_columns_indexes_triggers_and_a_row_estimate() throws Exception {
        execute("""
                create table t_json (
                    id bigint primary key,
                    code varchar(32) not null default 'x' comment '编码',
                    amount decimal(10,2) null,
                    key idx_code (code)
                )
                """);
        execute("create trigger trg_json before insert on t_json for each row set new.amount = 0");

        snapshotter.capture(conn, List.of("t_json"), "2/3/4/5");

        JsonNode tables = new ObjectMapper()
                .readTree(Files.readString(tempRoot.resolve("2/3/4/5/schema.json")))
                .path("tables");
        assertThat(tables).hasSize(1);

        JsonNode table = tables.get(0);
        assertThat(table.path("table").asText()).isEqualTo("t_json");
        assertThat(table.path("createSql").asText()).contains("CREATE TABLE");
        assertThat(table.path("rowCountEstimate").asLong()).isGreaterThanOrEqualTo(0L);

        List<String> columnNames = new ArrayList<>();
        table.path("columns").forEach(column -> columnNames.add(column.path("name").asText()));
        assertThat(columnNames).containsExactly("id", "code", "amount");

        JsonNode code = table.path("columns").get(1);
        assertThat(code.path("type").asText()).isEqualTo("varchar(32)");
        assertThat(code.path("nullable").asBoolean()).isFalse();
        assertThat(code.path("default").asText()).isEqualTo("x");
        assertThat(code.path("comment").asText()).isEqualTo("编码");

        Map<String, JsonNode> indexes = new LinkedHashMap<>();
        table.path("indexes").forEach(index -> indexes.put(index.path("name").asText(), index));
        assertThat(indexes).containsKeys("PRIMARY", "idx_code");
        assertThat(indexes.get("PRIMARY").path("unique").asBoolean()).isTrue();
        assertThat(indexes.get("idx_code").path("unique").asBoolean()).isFalse();
        assertThat(indexes.get("idx_code").path("columns").get(0).asText()).isEqualTo("code");

        JsonNode triggers = table.path("triggers");
        assertThat(triggers).hasSize(1);
        assertThat(triggers.get(0).path("name").asText()).isEqualTo("trg_json");
        assertThat(triggers.get(0).path("statement").asText()).containsIgnoringCase("amount");
    }

    /** 表名带反引号：拼进 {@code SHOW CREATE TABLE} 时必须翻倍转义，否则语句会被截断。 */
    @Test
    void a_table_name_with_a_backtick_is_escaped() throws Exception {
        execute("create table `t``x` (id bigint primary key)");

        SnapshotResult result = snapshotter.capture(conn, List.of("t`x"), "3/3/3/3");

        assertThat(result.tableFingerprints()).containsKey("t`x");
        assertThat(Files.readString(tempRoot.resolve("3/3/3/3/schema.json"))).contains("t`x");
    }

    @Test
    void an_explain_estimate_reports_the_rows_of_a_looked_up_statement() throws Exception {
        execute("create table t3 (id bigint primary key)");
        assertThat(new ExplainRunner().estimate(conn, "update t3 set id = 1 where id = 1").estimatedRows())
                .isGreaterThanOrEqualTo(0L);
        assertThat(new ExplainRunner().estimate(conn, "update no_such_table set id = 1").estimatedRows())
                .as("语句本身不合法时给 -1，由调用方按最严处理").isEqualTo(-1L);
    }

    /** 待估算的语句是 DDL 时 EXPLAIN 会报错：给 -1，并且绝不能把表建出来。 */
    @Test
    void an_explain_never_executes_the_statement_itself() throws Exception {
        assertThat(new ExplainRunner().estimate(conn, "create table t_should_not_exist (id int)")
                .estimatedRows()).isEqualTo(-1L);
        assertThat(tableExists("t_should_not_exist")).isFalse();
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static boolean tableExists(String table) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             PreparedStatement s = c.prepareStatement(
                     "select count(*) from information_schema.tables"
                             + " where table_schema = ? and table_name = ?")) {
            s.setString(1, SCHEMA);
            s.setString(2, table);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
