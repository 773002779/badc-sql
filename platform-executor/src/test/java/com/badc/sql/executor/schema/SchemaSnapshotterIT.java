package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;
import com.badc.sql.executor.exec.ExplainRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 真实 MySQL 上的结构快照与指纹。
 *
 * <p>沿用执行器既有风格（见 {@code QueryRunnerIT}）：纯 JUnit、不起 Spring 上下文、随机 schema、
 * 裸 JDBC。要钉住的不是"能跑通"，而是几件容易做错的事：
 * <ul>
 *   <li>抓取只读元数据，<b>不扫表</b>、不执行待估算的语句；</li>
 *   <li>表不存在不是失败（新建表），空指纹不能和"抓取失败"混为一谈；</li>
 *   <li>结构变了指纹必须变（加列、只改字面量大小写），数据变了指纹不能变（自增计数）。</li>
 * </ul>
 */
class SchemaSnapshotterIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "root";
    /** 受限账号的口令：只在测试库里存在，且用完即删。 */
    private static final String LIMITED_PASS = "Limited-pass-1";
    /** 每类一个随机 schema（同 {@code AbstractMySqlIT} 的 {@code badc_test_<8位随机>}），并行/残留不会撞。 */
    private static final String SCHEMA =
            "badc_test_snapshot_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

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

    /**
     * I1 回归：{@code AUTO_INCREMENT=N} 随插入增长。热表在"提交 → 审批 → 执行"之间几乎必然被插入，
     * 若它进指纹，工单会被误判成"结构已漂移"而中止。
     */
    @Test
    void auto_increment_growth_does_not_change_the_fingerprint() throws Exception {
        execute("create table t_auto (id bigint not null auto_increment primary key, v int null)");
        String before = snapshotter.fingerprint(conn, List.of("t_auto")).get("t_auto");

        execute("insert into t_auto (v) values (1)");

        assertThat(rawCreateTable("t_auto"))
                .as("前提：插入后服务端确实会输出自增计数，否则本用例什么也没证明")
                .contains("AUTO_INCREMENT=");
        assertThat(snapshotter.fingerprint(conn, List.of("t_auto")).get("t_auto"))
                .as("自增计数属于数据侧状态，不属于结构").isEqualTo(before);
    }

    /** 新建表 / drop 之后的表：不存在不是失败，返回空指纹，由调用方记录"前置状态为空"。 */
    @Test
    void a_missing_table_yields_an_empty_fingerprint_instead_of_failing() {
        assertThat(snapshotter.fingerprint(conn, List.of("does_not_exist"))).isEmpty();
    }

    /** 抓快照时表不存在同理：文件照写（空快照），不抛异常；"请求过"必须留在 json 里。 */
    @Test
    void capturing_a_missing_table_writes_empty_snapshot_files_instead_of_failing() throws Exception {
        SnapshotResult result = snapshotter.capture(conn, List.of("does_not_exist"), "9/9/9/9");

        assertThat(result.tableFingerprints()).isEmpty();
        assertThat(tempRoot.resolve("9/9/9/9/schema.sql")).exists();
        assertThat(tempRoot.resolve("9/9/9/9/schema.json")).exists();
        assertThat(result.byteSize()).isPositive();
        assertThat(Files.readString(tempRoot.resolve("9/9/9/9/schema.sql"))).isEmpty();

        JsonNode document = new ObjectMapper()
                .readTree(Files.readString(tempRoot.resolve("9/9/9/9/schema.json")));
        assertThat(document.path("tables")).as("没有前置结构的表不进 tables").isEmpty();
        List<String> requested = new ArrayList<>();
        document.path("requestedTables").forEach(node -> requested.add(node.asText()));
        assertThat(requested).as("任务 11 靠它判定前置状态为空").containsExactly("does_not_exist");
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

        // 重复表名只抓一次：请求清单与 tables 都只应出现一项。
        snapshotter.capture(conn, List.of("t_json", "t_json"), "2/3/4/5");

        JsonNode document = new ObjectMapper()
                .readTree(Files.readString(tempRoot.resolve("2/3/4/5/schema.json")));
        List<String> requested = new ArrayList<>();
        document.path("requestedTables").forEach(node -> requested.add(node.asText()));
        assertThat(requested).containsExactly("t_json");

        JsonNode tables = document.path("tables");
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

    /**
     * "EXPLAIN 不执行原语句"必须用看得见的效果证明：估一次 {@code update ... set v = v + 1}，行值不能变。
     * 只断言行数估算非负证明不了任何事——执行了也照样有估算。
     */
    @Test
    void an_explain_never_executes_the_statement_itself() throws Exception {
        execute("create table t_probe (id bigint primary key, v int not null)");
        execute("insert into t_probe values (1, 10)");

        assertThat(new ExplainRunner().estimate(conn, "update t_probe set v = v + 1 where id = 1")
                .estimatedRows()).isGreaterThanOrEqualTo(0L);
        assertThat(probeValue(1L))
                .as("EXPLAIN 执行了原语句：v 被加了 1").isEqualTo(10);

        // DDL 连 EXPLAIN 都不支持：给 -1，且表不能被建出来。
        assertThat(new ExplainRunner().estimate(conn, "create table t_should_not_exist (id int)")
                .estimatedRows()).isEqualTo(-1L);
        assertThat(tableExists("t_should_not_exist")).isFalse();
    }

    /**
     * 限定到<b>本库</b>的表名等价于裸名：指纹按裸名登记，控制面才能对上它请求的名字。
     */
    @Test
    void a_table_qualified_with_the_current_database_is_accepted_under_its_bare_name() throws Exception {
        execute("create table t_qual (id bigint primary key)");

        Map<String, String> qualified = snapshotter.fingerprint(conn, List.of(SCHEMA + ".t_qual"));

        assertThat(qualified).containsOnlyKeys("t_qual");
        assertThat(qualified.get("t_qual"))
                .isEqualTo(snapshotter.fingerprint(conn, List.of("t_qual")).get("t_qual"));
    }

    /**
     * 限定到<b>别的库</b>的表必须以失败告终，不能退化成"这张表不存在"。
     *
     * <p>退化的后果有两层：轻则漂移闸把"没有前置结构"当成合法状态静默放行；重则如果本库恰好有同名表，
     * 指纹会落到那张表上，界面一切正常而核对的是另一张表。两种都不能接受，所以这里连"快照目录没被创建"
     * 一起断言——失败必须发生在写盘之前，不能留下半个快照。
     */
    @Test
    void a_table_qualified_with_another_database_is_refused_instead_of_looking_absent() throws Exception {
        String otherSchema = SCHEMA + "_other";
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("drop database if exists " + otherSchema);
            s.execute("create database " + otherSchema);
            s.execute("create table " + otherSchema + ".t_away (id bigint primary key)");
        }
        try {
            String foreign = otherSchema + ".t_away";

            assertThatThrownBy(() -> snapshotter.fingerprint(conn, List.of(foreign)))
                    .as("跨库表取不到指纹，必须报错而不是返回空 map")
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("另一个库");

            assertThatThrownBy(() -> snapshotter.capture(conn, List.of(foreign), "6/6/6/6"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("另一个库");
            assertThat(tempRoot.resolve("6/6/6/6")).doesNotExist();
        } finally {
            try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
                 Statement s = c.createStatement()) {
                s.execute("drop database if exists " + otherSchema);
            }
        }
    }

    /**
     * 账号没有 {@code TRIGGER} 权限时必须让快照<b>失败</b>。
     *
     * <p>MySQL 对缺权限的处理是"静默过滤"：{@code information_schema.triggers} 与 {@code SHOW TRIGGERS}
     * 都返回空集而不是报错（本机 8.0.16 实测）。所以不主动问授权的话，快照会写下"这张表没有触发器"，
     * 之后按它重建就会丢触发器——而重建往往发生在事故现场。
     *
     * <p>反向那半段（授权后能抓到触发器）不能省：只断言"报错"证明不了报错是因为权限，也可能是因为
     * 表名写错、连接失败之类的任何原因。
     */
    @Test
    void a_capture_fails_loudly_when_the_account_cannot_see_triggers() throws Exception {
        execute("create table t_trig (id bigint primary key)");
        execute("create trigger trg_bi_t_trig before insert on t_trig for each row set @trg_probe = 1");

        String limitedUser = "badc_no_trg_" + SCHEMA.substring("badc_test_snapshot_".length());
        createLimitedUser(limitedUser, SCHEMA);
        try {
            ConnConfig limited = new ConnConfig(HOST, PORT, SCHEMA, limitedUser, LIMITED_PASS, "utf8mb4", "+00:00");

            assertThatThrownBy(() -> snapshotter.capture(limited, List.of("t_trig"), "5/5/5/1"))
                    .as("看不见触发器时快照必须失败，不能声称这张表没有触发器")
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("TRIGGER");

            grantTrigger(limitedUser, SCHEMA);
            SnapshotResult afterGrant = snapshotter.capture(limited, List.of("t_trig"), "5/5/5/2");

            assertThat(afterGrant.tableFingerprints()).containsKey("t_trig");
            assertThat(Files.readString(tempRoot.resolve("5/5/5/2/schema.json")))
                    .as("有了 TRIGGER 权限才真的读到触发器")
                    .contains("trg_bi_t_trig");
        } finally {
            dropUser(limitedUser);
        }
    }

    /** 只给单库读写权限、<b>不给 TRIGGER</b> 的账号——模拟"ddl 账号按最小权限配置"的常见配置。 */
    private static void createLimitedUser(String user, String schema) throws Exception {
        execute("drop user if exists '" + user + "'@'%'");
        execute("create user '" + user + "'@'%' identified by '" + LIMITED_PASS + "'");
        execute("grant select, insert, update, delete, create, drop, alter, index, references on `"
                + schema + "`.* to '" + user + "'@'%'");
    }

    private static void grantTrigger(String user, String schema) throws Exception {
        execute("grant trigger on `" + schema + "`.* to '" + user + "'@'%'");
    }

    private static void dropUser(String user) throws Exception {
        execute("drop user if exists '" + user + "'@'%'");
    }

    private static int probeValue(long id) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             PreparedStatement s = c.prepareStatement("select v from t_probe where id = ?")) {
            s.setLong(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static String rawCreateTable(String table) throws Exception {
        try (Connection c = DriverManager.getConnection(MySqlAdapter.url(conn), USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("show create table " + table)) {
            return rs.next() ? rs.getString(2) : null;
        }
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
