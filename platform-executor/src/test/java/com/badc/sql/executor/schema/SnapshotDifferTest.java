package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.dto.SchemaDiff.Change;
import com.badc.sql.common.dto.SchemaDiff.FieldChange;
import com.badc.sql.common.dto.SchemaDiff.TableChange;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 结构差异算法：加列 / 删列 / 改列 / 建表 / 删表 / 未变。
 *
 * <p>输入是两份手写的 {@code schema.json}（与 {@link SchemaSnapshotter} 写出的形态一致），
 * 因此既覆盖了比对规则，也覆盖了"读侧记录与写侧记录能对上"这件事——两份记录若字段名漂移，
 * 这里会直接读成 {@code null} 并让断言失败。
 */
class SnapshotDifferTest {

    @TempDir
    Path root;

    /** 两次快照的相对引用：{systemId}/{datasourceId}/{ticketId}/{changeSeq}。 */
    private static final String BEFORE = "1/101/7/1";
    private static final String AFTER = "1/101/7/2";

    @Test
    void a_new_column_is_reported_as_added_with_no_before_value() throws IOException {
        writeSnapshot("1", snapshot(columns(column("id", "int", false, null, null))));
        writeSnapshot("2", snapshot(columns(
                column("id", "int", false, null, null),
                column("remark", "varchar(32)", true, null, "备注"))));

        SchemaDiff diff = new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER);

        TableChange table = only(diff);
        assertThat(table.table()).isEqualTo("t_order");
        assertThat(table.status()).isEqualTo(Change.CHANGED);
        FieldChange remark = field(table.columns(), "remark");
        assertThat(remark.status()).isEqualTo(Change.ADDED);
        assertThat(remark.before()).isNull();
        assertThat(remark.after()).isEqualTo("varchar(32) NULL COMMENT '备注'");
        assertThat(field(table.columns(), "id").status()).isEqualTo(Change.UNCHANGED);
    }

    @Test
    void a_dropped_column_is_reported_as_removed() throws IOException {
        writeSnapshot("1", snapshot(columns(
                column("id", "int", false, null, null),
                column("legacy", "int", true, null, null))));
        writeSnapshot("2", snapshot(columns(column("id", "int", false, null, null))));

        FieldChange legacy = field(only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER)).columns(), "legacy");

        assertThat(legacy.status()).isEqualTo(Change.REMOVED);
        assertThat(legacy.before()).isEqualTo("int NULL");
        assertThat(legacy.after()).isNull();
    }

    @Test
    void a_widened_or_retyped_column_is_reported_as_changed_with_both_sides() throws IOException {
        // 收窄类型（varchar(64) → varchar(32)）正是审核人必须看到的那类变更。
        writeSnapshot("1", snapshot(columns(column("name", "varchar(64)", true, null, null))));
        writeSnapshot("2", snapshot(columns(column("name", "varchar(32)", false, "'x'", null))));

        FieldChange name = field(only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER)).columns(), "name");

        assertThat(name.status()).isEqualTo(Change.CHANGED);
        assertThat(name.before()).isEqualTo("varchar(64) NULL");
        assertThat(name.after()).isEqualTo("varchar(32) NOT NULL DEFAULT 'x'");
    }

    @Test
    void an_index_that_was_dropped_is_reported_under_indexes() throws IOException {
        writeSnapshot("1", snapshot(columns(column("id", "int", false, null, null)),
                "[{\"name\":\"uk_id\",\"unique\":true,\"columns\":[\"id\"]}]"));
        writeSnapshot("2", snapshot(columns(column("id", "int", false, null, null)), "[]"));

        TableChange table = only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER));

        FieldChange index = field(table.indexes(), "uk_id");
        assertThat(index.status()).isEqualTo(Change.REMOVED);
        assertThat(index.before()).isEqualTo("UNIQUE (id)");
    }

    @Test
    void a_table_created_by_the_change_is_added_as_a_whole() throws IOException {
        // 建表工单：执行前那份快照里"请求过但这张表不存在"，因此只有 requestedTables。
        writeSnapshot("1", "{\"requestedTables\":[\"t_order\"],\"tables\":[]}");
        writeSnapshot("2", snapshot(columns(column("id", "int", false, null, null))));

        TableChange table = only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER));

        assertThat(table.status()).isEqualTo(Change.ADDED);
        assertThat(field(table.columns(), "id").status()).isEqualTo(Change.ADDED);
    }

    @Test
    void a_dropped_table_is_reported_as_removed() throws IOException {
        writeSnapshot("1", snapshot(columns(column("id", "int", false, null, null))));
        writeSnapshot("2", "{\"requestedTables\":[\"t_order\"],\"tables\":[]}");

        assertThat(only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER)).status())
                .isEqualTo(Change.REMOVED);
    }

    @Test
    void an_untouched_table_is_explicitly_reported_as_unchanged() throws IOException {
        String same = snapshot(columns(column("id", "int", false, null, null)));
        writeSnapshot("1", same);
        writeSnapshot("2", same);

        assertThat(only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER)).status())
                .isEqualTo(Change.UNCHANGED);
    }

    @Test
    void a_missing_before_snapshot_is_treated_as_empty_not_as_an_error() throws IOException {
        writeSnapshot("2", snapshot(columns(column("id", "int", false, null, null))));

        assertThat(only(new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER)).status())
                .isEqualTo(Change.ADDED);
    }

    @Test
    void a_missing_after_snapshot_is_an_explicit_error() throws IOException {
        writeSnapshot("1", snapshot(columns(column("id", "int", false, null, null))));

        // 明确报错，而不是返回"没有差异"：后者会被读成"这次变更什么都没改"。
        assertThatThrownBy(() -> new SnapshotDiffer(root.toString()).diff(BEFORE, AFTER))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("执行后没有抓到快照")
                .extracting(e -> ((BizException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void a_snapshot_reference_escaping_the_root_is_refused() {
        assertThatThrownBy(() -> new SnapshotDiffer(root.toString()).diff(BEFORE, "../../etc"))
                .isInstanceOf(BizException.class);
    }

    // ------------------------------------------------------------------ fixtures

    private static TableChange only(SchemaDiff diff) {
        assertThat(diff.tables()).hasSize(1);
        return diff.tables().get(0);
    }

    private static FieldChange field(java.util.List<FieldChange> fields, String name) {
        return fields.stream().filter(field -> field.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("差异里没有字段 " + name + "：" + fields));
    }

    private void writeSnapshot(String changeSeq, String document) throws IOException {
        Path directory = root.resolve("1/101/7").resolve(changeSeq);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(SchemaSnapshotter.SCHEMA_JSON), document, StandardCharsets.UTF_8);
    }

    /** 单表快照：表名固定 {@code t_order}，列与索引由参数给。 */
    private static String snapshot(String columnsJson, String indexesJson) {
        return "{\"requestedTables\":[\"t_order\"],\"tables\":[{\"table\":\"t_order\","
                + "\"createSql\":\"create table ...\",\"columns\":" + columnsJson + ","
                + "\"indexes\":" + (indexesJson == null ? "[]" : indexesJson) + ","
                + "\"triggers\":[],\"rowCountEstimate\":0}]}";
    }

    private static String snapshot(String columnsJson) {
        return snapshot(columnsJson, null);
    }

    private static String columns(String... columns) {
        return "[" + String.join(",", columns) + "]";
    }

    /** 一列的 JSON；{@code default} 用引号钉住字段名（与写侧 {@code @JsonProperty("default")} 一致）。 */
    private static String column(String name, String type, boolean nullable, String defaultValue, String comment) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"nullable\":" + nullable
                + ",\"default\":" + (defaultValue == null ? "null" : "\"" + defaultValue + "\"")
                + ",\"comment\":" + (comment == null ? "null" : "\"" + comment + "\"") + "}";
    }
}
