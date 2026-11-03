package com.badc.sql.executor.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.badc.sql.common.dto.SchemaDiff;
import com.badc.sql.common.dto.SchemaDiff.Change;
import com.badc.sql.common.dto.SchemaDiff.FieldChange;
import com.badc.sql.common.dto.SchemaDiff.TableChange;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.schema.SnapshotDocument.ColumnSnapshot;
import com.badc.sql.executor.schema.SnapshotDocument.IndexSnapshot;
import com.badc.sql.executor.schema.SnapshotDocument.TableSnapshot;
import com.badc.sql.executor.schema.SnapshotDocument.TriggerSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 比对两次结构快照，算出「哪里变了」（FR-02 验收 1）。
 *
 * <p><b>为什么比对落盘的 {@code schema.json} 而不是数据库里的指纹</b>：指纹是
 * {@code sha256(SHOW CREATE TABLE)}，只能回答"变没变"，回答不了"改了什么"。而差异的用途正是后者
 * ——审核人要判断这次 ALTER 的影响面。结构化快照本来就已经落盘（{@code schema.json} 里有列、索引、
 * 触发器），这里只是把它读回来比对，不需要新的抓取。
 *
 * <p><b>两次快照的语义</b>：{@code changeSeq=1} 是执行前，{@code changeSeq=2} 是执行后。
 * 一次 {@code alter table add column} 工单因此会得到"列 {@code remark} 新增"这样的结果。
 *
 * <p><b>前一次快照缺失按空快照处理，后一次缺失是错误</b>：前者对应"表还不存在"（建表工单），
 * 差异就是"整张表都是新增"；后者说明执行后没抓到快照，此时没有任何东西可比，必须明确报错而不是
 * 返回"没有差异"——"没有差异"会被读成"这次变更什么都没改"。
 */
@Component
public class SnapshotDiffer {

    private final Path root;
    private final ObjectMapper json = new ObjectMapper();

    /** 生产由 Spring 注入 {@code badcsql.snapshot.root}；测试用 {@code new SnapshotDiffer(tempDir)}。 */
    public SnapshotDiffer(@Value("${badcsql.snapshot.root:/data/snapshot}") String root) {
        this.root = Path.of(root);
    }

    /**
     * 比对两份快照。
     *
     * @param beforeRef 前一次快照的相对引用（{@code {systemId}/{datasourceId}/{ticketId}/1}）
     * @param afterRef  后一次快照的相对引用（{@code .../2}）
     * @return 逐表差异；两次都没抓到的表不出现在结果里
     * @throws BizException 后一次快照不存在或读不出来（前一次缺失按空快照处理）
     */
    public SchemaDiff diff(String beforeRef, String afterRef) {
        SnapshotDocument before = load(beforeRef, true);
        SnapshotDocument after = load(afterRef, false);

        Map<String, TableSnapshot> beforeTables = byTable(before);
        Map<String, TableSnapshot> afterTables = byTable(after);

        List<TableChange> changes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 后一次快照的顺序在前：新增的表自然出现在结果顶部。
        for (TableSnapshot afterTable : after.tables()) {
            seen.add(afterTable.table());
            changes.add(compare(afterTable.table(), beforeTables.get(afterTable.table()), afterTable));
        }
        // 只在执行前存在的表（被 drop 了）：保持前一次快照里的顺序。
        for (TableSnapshot beforeTable : before.tables()) {
            if (!seen.contains(beforeTable.table())) {
                changes.add(compare(beforeTable.table(), beforeTable, null));
            }
        }
        return new SchemaDiff(changes);
    }

    private static TableChange compare(String table, TableSnapshot before, TableSnapshot after) {
        List<FieldChange> columns = compareFields(
                before == null ? List.of() : before.columns(), after == null ? List.of() : after.columns(),
                ColumnSnapshot::name, SnapshotDiffer::columnSignature);
        List<FieldChange> indexes = compareFields(
                before == null ? List.of() : before.indexes(), after == null ? List.of() : after.indexes(),
                IndexSnapshot::name, SnapshotDiffer::indexSignature);
        List<FieldChange> triggers = compareFields(
                before == null ? List.of() : before.triggers(), after == null ? List.of() : after.triggers(),
                TriggerSnapshot::name, TriggerSnapshot::statement);

        Change status;
        if (before == null) {
            status = Change.ADDED;
        } else if (after == null) {
            status = Change.REMOVED;
        } else if (anyChanged(columns) || anyChanged(indexes) || anyChanged(triggers)) {
            status = Change.CHANGED;
        } else {
            status = Change.UNCHANGED;
        }
        return new TableChange(table, status, columns, indexes, triggers);
    }

    /**
     * 按名字对齐两组对象并逐个比较。
     *
     * <p>顺序取"后一次快照的顺序在前，前一次多出来的按原顺序追加"：删掉的列出现在末尾，
     * 与 {@code ALTER TABLE ... DROP COLUMN} 的心智一致。
     */
    private static <T> List<FieldChange> compareFields(List<T> before, List<T> after,
            Function<T, String> nameOf, Function<T, String> signatureOf) {
        Map<String, T> beforeByName = index(before, nameOf);
        Map<String, T> afterByName = index(after, nameOf);

        List<FieldChange> changes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (T item : after) {
            String name = nameOf.apply(item);
            seen.add(name);
            T old = beforeByName.get(name);
            if (old == null) {
                changes.add(new FieldChange(name, Change.ADDED, null, signatureOf.apply(item)));
            } else if (!Objects.equals(signatureOf.apply(old), signatureOf.apply(item))) {
                changes.add(new FieldChange(name, Change.CHANGED, signatureOf.apply(old), signatureOf.apply(item)));
            } else {
                changes.add(new FieldChange(name, Change.UNCHANGED, signatureOf.apply(old), signatureOf.apply(item)));
            }
        }
        for (T item : before) {
            String name = nameOf.apply(item);
            if (!seen.contains(name)) {
                changes.add(new FieldChange(name, Change.REMOVED, signatureOf.apply(item), null));
            }
        }
        return changes;
    }

    /** 列的可读形态：{@code varchar(32) NOT NULL DEFAULT 'x' COMMENT '备注'}。 */
    static String columnSignature(ColumnSnapshot column) {
        StringBuilder text = new StringBuilder(column.type() == null ? "" : column.type());
        text.append(column.nullable() ? " NULL" : " NOT NULL");
        if (column.defaultValue() != null) {
            text.append(" DEFAULT ").append(column.defaultValue());
        }
        if (column.comment() != null && !column.comment().isBlank()) {
            text.append(" COMMENT '").append(column.comment()).append('\'');
        }
        return text.toString();
    }

    /** 索引的可读形态：{@code UNIQUE (a, b)}。 */
    static String indexSignature(IndexSnapshot index) {
        String columns = index.columns() == null ? "" : String.join(", ", index.columns());
        return (index.unique() ? "UNIQUE" : "INDEX") + " (" + columns + ")";
    }

    private static boolean anyChanged(List<FieldChange> changes) {
        return changes.stream().anyMatch(change -> change.status() != Change.UNCHANGED);
    }

    private static Map<String, TableSnapshot> byTable(SnapshotDocument document) {
        Map<String, TableSnapshot> tables = new LinkedHashMap<>();
        for (TableSnapshot table : document.tables()) {
            if (table.table() != null) {
                tables.put(table.table(), table);
            }
        }
        return tables;
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> nameOf) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T item : items) {
            String name = nameOf.apply(item);
            if (name != null) {
                indexed.put(name, item);
            }
        }
        return indexed;
    }

    /**
     * 读一份快照文档。
     *
     * @param required {@code true} 表示这份快照必须有（后一次快照）；{@code false} 时缺失按空文档处理
     */
    private SnapshotDocument load(String relativeRef, boolean optional) {
        Path directory = SnapshotPaths.resolve(root.toString(), relativeRef);
        Path file = directory.resolve(SchemaSnapshotter.SCHEMA_JSON);
        if (!Files.isRegularFile(file)) {
            if (optional) {
                return new SnapshotDocument(List.of(), List.of());
            }
            throw new BizException(ErrorCode.NOT_FOUND,
                    "结构快照不存在：" + relativeRef + "（执行后没有抓到快照，无法给出结构差异）");
        }
        try {
            return json.readValue(Files.readAllBytes(file), SnapshotDocument.class);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "结构快照读取失败：" + relativeRef, e);
        }
    }
}
