package com.badc.sql.executor.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.SnapshotResult;
import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.executor.adapter.mysql.MySqlAdapter;
import com.badc.sql.executor.util.SqlErrorMessages;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 表结构快照与指纹。
 *
 * <p>只读 {@code SHOW CREATE TABLE} 与 {@code information_schema}，<b>绝不扫表</b>：快照在变更执行前
 * 抓取，扫表既会拖慢工单，也会让一次提交的代价随数据量增长。
 *
 * <p>落盘在快照根目录下的 {@code ref} 子目录，两个文件：
 * <ul>
 *   <li>{@code schema.sql}：各表 {@code SHOW CREATE TABLE} 原文，以 {@code -- <表名>} 分隔；</li>
 *   <li>{@code schema.json}：{@code requestedTables}（请求过的表，去重）与 {@code tables} 数组，每项含
 *       {@code table}、{@code createSql}、{@code columns}（name/type/nullable/default/comment）、
 *       {@code indexes}（name/unique/columns）、{@code triggers}（name/statement）与
 *       {@code rowCountEstimate}。</li>
 * </ul>
 *
 * <p>失败与"表不存在"必须分开：抓取失败（连不上、没权限）抛 {@code BizException}，调用方据此禁止执行；
 * 表不存在只是没抓到前置状态，跳过该表即可，不算失败。
 */
@Component
public class SchemaSnapshotter {

    static final String SCHEMA_SQL = "schema.sql";
    static final String SCHEMA_JSON = "schema.json";

    /** 表不存在：新建表场景下"前置状态为空"，不是抓取失败。 */
    private static final int TABLE_NOT_FOUND = 1146;
    private static final String MISSING_TABLE_SQL_STATE = "42S02";

    /**
     * 表选项尾部的自增计数，形如 {@code ) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=...}。
     *
     * <p>必须<b>锚到表选项尾部</b>（{@code )} + {@code ENGINE=...}），并且靠贪婪前綴只命中<b>最后一处</b>：
     * 列上的 {@code COMMENT '... ENGINE=InnoDB AUTO_INCREMENT=9 ...'} 是用户写的字面文本，
     * 剥它就是"注释里的一段文字改了结构指纹"——既污染哈希，又制造假漂移（真实计数还留在指纹里）。
     */
    private static final Pattern AUTO_INCREMENT_COUNTER =
            Pattern.compile("(?s)(.*\\)\\s*ENGINE=\\S+) AUTO_INCREMENT=\\d+");

    private static final int SCHEMA_QUERY_TIMEOUT_SECONDS = 30;

    private final Path root;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 生产由 Spring 注入 {@code badcsql.snapshot.root}；测试直接用 {@code new SchemaSnapshotter(tempDir)}
     * 指向临时目录（同一个构造器，注解在容器外不生效）。
     */
    public SchemaSnapshotter(@Value("${badcsql.snapshot.root:/data/snapshot}") String root) {
        this.root = Path.of(root);
    }

    /**
     * 抓取结构快照并落盘。
     *
     * <p>落盘<b>不是原子的</b>：两个文件顺序写，第二个写失败会留下单文件的孤儿快照。安全语义不受影响
     * （异常照抛 → 调用方禁止执行），孤儿文件交给 180 天保留期的清理任务。
     *
     * @param conn        目标库连接配置（已解密）
     * @param tables      需要抓取的表；为空表示这一单不涉及已有表
     * @param relativeRef 控制面给的相对引用，最终路径由 {@link SnapshotPaths#resolve} 校验
     * @return 落盘结果：引用、存在的表的指纹、文件字节数
     * @throws BizException 连接失败、权限不足或写盘失败；表不存在不算失败
     */
    public SnapshotResult capture(ConnConfig conn, List<String> tables, String relativeRef) {
        Path directory = SnapshotPaths.resolve(root.toString(), relativeRef);
        List<String> requestedTables = distinctTables(tables);

        List<TableSnapshot> snapshots = new ArrayList<>();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        try (Connection connection = open(conn)) {
            String schemaName = schemaName(connection, conn);
            for (String table : requestedTables) {
                String createSql = showCreateTable(connection, table);
                if (createSql == null) {
                    continue;
                }
                snapshots.add(readTable(connection, schemaName, table, createSql));
                fingerprints.put(table, SnapshotPaths.sha256Hex(normalize(createSql)));
            }
            Files.createDirectories(directory);
            long byteSize = write(directory, snapshots, requestedTables);
            return new SnapshotResult(relativeRef, fingerprints, byteSize);
        } catch (SQLException e) {
            throw failure(e);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "结构快照写入失败：" + e.getMessage(), e);
        }
    }

    /**
     * 取各表的结构指纹。
     *
     * <p>每表一次 {@code SHOW CREATE TABLE}，规范化（统一换行、去行尾空白、剔除自增计数）后取 sha256。
     * 表不存在时该表<b>不进 map</b>也不抛异常；全部不存在返回空 map。
     *
     * @throws BizException 连接失败或权限不足——空 map 只能表示"表不存在"，不能表示"库连不上"
     */
    public Map<String, String> fingerprint(ConnConfig conn, List<String> tables) {
        List<String> requestedTables = distinctTables(tables);
        Map<String, String> fingerprints = new LinkedHashMap<>();
        try (Connection connection = open(conn)) {
            for (String table : requestedTables) {
                String createSql = showCreateTable(connection, table);
                if (createSql == null) {
                    continue;
                }
                fingerprints.put(table, SnapshotPaths.sha256Hex(normalize(createSql)));
            }
        } catch (SQLException e) {
            throw failure(e);
        }
        return Collections.unmodifiableMap(fingerprints);
    }

    /**
     * 指纹的规范化：统一换行为 {@code \n}、每行去行尾空白、去末尾空行，并剔除随数据增长的
     * {@code AUTO_INCREMENT=N} 计数。
     *
     * <p><b>不做大小写折叠</b>：{@code SHOW CREATE TABLE} 由服务端生成、关键字固定大写，同一实例两次抓取
     * 不存在"格式大小写不同"这回事；折叠只会让 {@code DEFAULT 'ABC'} → {@code 'abc'}、仅大小写不同的
     * 表改名或注释改动这类真实变更从指纹里消失（漂移检测失效）。
     *
     * <p>{@code AUTO_INCREMENT=N} 属于数据侧状态而不是结构：正常写入的业务表在"提交 → 审批 → 执行"之间
     * 几乎必然插入过数据，若把它算进指纹，好工单会被误判成"结构已漂移"。这里按
     * <b>表选项尾部</b>的 {@code ) ENGINE=<engine> AUTO_INCREMENT=<n>} 形态剔除（只去数字与它前面的空格），
     * 而不是剥全 DDL 第一处 {@code ENGINE=... AUTO_INCREMENT=...}——后者会先命中列 COMMENT 里的字面文本，
     * 把注释改掉、真计数留在指纹里。
     */
    static String normalize(String createSql) {
        String unified = AUTO_INCREMENT_COUNTER.matcher(
                        createSql.replace("\r\n", "\n").replace('\r', '\n'))
                .replaceFirst("$1");
        StringBuilder normalized = new StringBuilder(unified.length());
        for (String line : unified.split("\n", -1)) {
            normalized.append(stripTrailing(line)).append('\n');
        }
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '\n') {
            end--;
        }
        return normalized.substring(0, end);
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * {@code SHOW CREATE TABLE}；表不存在返回 {@code null}，其余数据库错误原样抛出。
     *
     * <p>表名直接拼进语句，反引号必须按 MySQL 规则转义（{@code `} → {@code ``}），否则
     * {@code t`x} 这类名字会截断语句。
     */
    private static String showCreateTable(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // 元数据也可能被长事务的 MDL 挡住：给个上限，别把工单线程挂死。
            statement.setQueryTimeout(SCHEMA_QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery("show create table " + quote(table))) {
                return rs.next() ? rs.getString(2) : null;
            }
        } catch (SQLException e) {
            if (isMissingTable(e)) {
                return null;
            }
            throw e;
        }
    }

    private static boolean isMissingTable(SQLException e) {
        return e.getErrorCode() == TABLE_NOT_FOUND
                || MISSING_TABLE_SQL_STATE.equals(e.getSQLState());
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static Connection open(ConnConfig conn) throws SQLException {
        if (conn == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少连接配置");
        }
        return DriverManager.getConnection(MySqlAdapter.url(conn), conn.username(), conn.password());
    }

    /** 连接串里可能没写库名：此时以会话当前库为准。 */
    private static String schemaName(Connection connection, ConnConfig conn) throws SQLException {
        if (conn.dbName() != null && !conn.dbName().isBlank()) {
            return conn.dbName();
        }
        String catalog = connection.getCatalog();
        return catalog == null ? "" : catalog;
    }

    private static TableSnapshot readTable(
            Connection connection, String schemaName, String table, String createSql) throws SQLException {
        return new TableSnapshot(
                table,
                createSql,
                columns(connection, schemaName, table),
                indexes(connection, schemaName, table),
                triggers(connection, schemaName, table),
                rowCountEstimate(connection, schemaName, table));
    }

    private static List<ColumnSnapshot> columns(Connection connection, String schemaName, String table)
            throws SQLException {
        List<ColumnSnapshot> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select column_name, column_type, is_nullable, column_default, column_comment
                from information_schema.columns
                where table_schema = ? and table_name = ?
                order by ordinal_position
                """)) {
            statement.setQueryTimeout(SCHEMA_QUERY_TIMEOUT_SECONDS);
            statement.setString(1, schemaName);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(new ColumnSnapshot(
                            rs.getString(1),
                            rs.getString(2),
                            "YES".equalsIgnoreCase(rs.getString(3)),
                            rs.getString(4),
                            rs.getString(5)));
                }
            }
        }
        return columns;
    }

    private static List<IndexSnapshot> indexes(Connection connection, String schemaName, String table)
            throws SQLException {
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select index_name, non_unique, column_name
                from information_schema.statistics
                where table_schema = ? and table_name = ?
                order by index_name, seq_in_index
                """)) {
            statement.setQueryTimeout(SCHEMA_QUERY_TIMEOUT_SECONDS);
            statement.setString(1, schemaName);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    columnsByIndex.computeIfAbsent(name, key -> new ArrayList<>()).add(rs.getString(3));
                    uniqueByIndex.putIfAbsent(name, rs.getInt(2) == 0);
                }
            }
        }
        List<IndexSnapshot> indexes = new ArrayList<>(columnsByIndex.size());
        columnsByIndex.forEach((name, columns) -> indexes.add(
                new IndexSnapshot(name, uniqueByIndex.getOrDefault(name, false), List.copyOf(columns))));
        return indexes;
    }

    private static List<TriggerSnapshot> triggers(Connection connection, String schemaName, String table)
            throws SQLException {
        List<TriggerSnapshot> triggers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select trigger_name, action_statement
                from information_schema.triggers
                where trigger_schema = ? and event_object_table = ?
                order by trigger_name
                """)) {
            statement.setQueryTimeout(SCHEMA_QUERY_TIMEOUT_SECONDS);
            statement.setString(1, schemaName);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    triggers.add(new TriggerSnapshot(rs.getString(1), rs.getString(2)));
                }
            }
        }
        return triggers;
    }

    /** InnoDB 的 {@code table_rows} 是统计估算值，可能为 {@code null}：此时记 {@code -1} 表示未知。 */
    private static long rowCountEstimate(Connection connection, String schemaName, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select table_rows
                from information_schema.tables
                where table_schema = ? and table_name = ?
                """)) {
            statement.setQueryTimeout(SCHEMA_QUERY_TIMEOUT_SECONDS);
            statement.setString(1, schemaName);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || rs.getObject(1) == null) {
                    return -1L;
                }
                return rs.getLong(1);
            }
        }
    }

    private long write(Path directory, List<TableSnapshot> snapshots, List<String> requestedTables)
            throws IOException {
        StringBuilder schemaSql = new StringBuilder();
        for (TableSnapshot snapshot : snapshots) {
            // 表名可能含换行，注释行里必须压平；真实表名仍原样保存在 schema.json 里。
            schemaSql.append("-- ").append(commentSafe(snapshot.table())).append('\n')
                    .append(snapshot.createSql()).append('\n');
        }
        Path sqlFile = directory.resolve(SCHEMA_SQL);
        Files.writeString(sqlFile, schemaSql.toString(), StandardCharsets.UTF_8);

        // 请求了哪些表要显式记下来：任务 11 靠"在请求里但不在 tables 里"判定前置状态为空，
        // 只让它隐含在 tables 数组的缺失里，调用方就得靠猜。
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("requestedTables", requestedTables);
        document.put("tables", snapshots);
        Path jsonFile = directory.resolve(SCHEMA_JSON);
        json.writerWithDefaultPrettyPrinter().writeValue(jsonFile.toFile(), document);

        return Files.size(sqlFile) + Files.size(jsonFile);
    }

    /** 去重、去 null，保持入参顺序：重复表名会让 schema.sql / schema.json 出现重复段落。 */
    private static List<String> distinctTables(List<String> tables) {
        return tables == null
                ? List.of()
                : tables.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String commentSafe(String table) {
        return table.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * 数据库异常翻译。
     *
     * <p>连接类错误（SQLState {@code 08xxx}）不回显原始消息，避免把 host 带进响应；其余错误保留
     * {@code SQLState} 与错误码，让控制面能说清"为什么没抓到快照"（权限、库不存在等），但消息里的
     * {@code '账号'@'主机'} 必须先脱敏——权限类错误（28000）的原文会把两者一起带给研发。
     * 脱敏统一走 {@link SqlErrorMessages#sanitize(String)}，不要在这里另写一份。
     */
    static BizException failure(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("08")) {
            return new BizException(ErrorCode.INTERNAL_ERROR,
                    "数据源不可达（SQLState=" + state + "），请确认网络与白名单配置", e);
        }
        return new BizException(ErrorCode.INTERNAL_ERROR,
                "结构快照抓取失败（SQLState=" + state + ", errorCode=" + e.getErrorCode()
                        + "）：" + SqlErrorMessages.sanitize(e.getMessage()), e);
    }

    /** 单表快照的 JSON 结构。 */
    private record TableSnapshot(
            String table,
            String createSql,
            List<ColumnSnapshot> columns,
            List<IndexSnapshot> indexes,
            List<TriggerSnapshot> triggers,
            long rowCountEstimate) {
    }

    /** {@code default} 是 Java 关键字，JSON 字段名用注解钉住，避免落成 {@code defaultValue}。 */
    private record ColumnSnapshot(
            String name,
            String type,
            boolean nullable,
            @JsonProperty("default") String defaultValue,
            String comment) {
    }

    private record IndexSnapshot(String name, boolean unique, List<String> columns) {
    }

    private record TriggerSnapshot(String name, String statement) {
    }
}
