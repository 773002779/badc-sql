package com.badc.sql.executor.schema;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code schema.json} 的读模型：与 {@link SchemaSnapshotter} 的写模型逐字段对应。
 *
 * <p>为什么单独定义一套读记录、而不把写侧那几个 {@code private record} 改成 public：写侧是"抓取"的
 * 内部结构，读侧是"比对"的输入契约。共用一套类型会让一次抓取字段的调整悄悄改变差异接口的语义。
 * 两份记录之间的偏差由 {@code SnapshotDifferTest} 用真实抓取产物守住。
 *
 * <p>{@link JsonIgnoreProperties} 让<b>多出来的字段被忽略</b>：将来抓取侧加字段（例如把分区定义也记进去）
 * 不会让"看差异"这个只读功能跟着 500——那会把一次无害的扩展变成老快照打不开。
 *
 * @param requestedTables 请求抓取的表（含不存在的表：那是"前置状态为空"）
 * @param tables          实际存在的表的快照
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SnapshotDocument(List<String> requestedTables, List<TableSnapshot> tables) {

    public SnapshotDocument {
        requestedTables = requestedTables == null ? List.of() : List.copyOf(requestedTables);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /** 一张表的快照。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableSnapshot(
            String table,
            String createSql,
            List<ColumnSnapshot> columns,
            List<IndexSnapshot> indexes,
            List<TriggerSnapshot> triggers,
            long rowCountEstimate) {
    }

    /** 一列。{@code default} 是 Java 关键字，JSON 字段名用注解钉住，与写侧一致。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnSnapshot(
            String name,
            String type,
            boolean nullable,
            @JsonProperty("default") String defaultValue,
            String comment) {
    }

    /** 一个索引。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexSnapshot(String name, boolean unique, List<String> columns) {
    }

    /** 一个触发器。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TriggerSnapshot(String name, String statement) {
    }
}
