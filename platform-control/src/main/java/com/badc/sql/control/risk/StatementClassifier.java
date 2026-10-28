package com.badc.sql.control.risk;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.badc.sql.common.sql.SqlText;

/**
 * 语句分类：把一条 SQL 拆成"是不是写、动了哪张表、有没有 where/limit、是不是跨库、属于哪种 DDL 动作"。
 *
 * <p><b>所有判定都在 {@link SqlText#stripLiteralsAndComments(String)} 的骨架上做</b>，只有表名从原文取。
 * 关键字藏在注释、字符串字面量或反引号标识符里时不能算数——{@code update t set note = 'drop table x'}
 * 不是 DDL，{@code -- drop table t} 也不是，{@code alter table `drop table` add column a int} 里
 * {@code `drop table`} 是一个表名而不是一条 drop 语句。这三类假阳性比漏判更伤：用户写对了却被判 L3，
 * 或者反过来把真正的破坏性 DDL 当成普通写放过去。
 *
 * <p>本类<b>纯文本</b>：无 Spring 依赖、不连库、不读配置，可单元测试跑满。等级判定不在这里——结构性破坏
 * 的七个动作恒为 L3，而 {@code alter modify} 是否收窄、跨库要不要抬级、多语句要不要拦，都由任务 4/6 决定。
 *
 * <h2>已知局限</h2>
 * <ul>
 *   <li><b>反引号限定名检测不到跨库</b>：{@code `db2`.`t_order`} 的反引号内容与字符串字面量走同一分支被
 *       掩码，骨架里只剩空格，所以 {@code crossDatabase()} 为 {@code false}。这是尽力而为的判定；任务 6 对
 *       跨库另有兜底（至少 L3），不能只依赖本字段。</li>
 *   <li><b>别名限定列会被当成跨库</b>：{@code t_order.id} 形状与 {@code db2.t_order} 相同，左侧
 *       {@code t_order} 不是关键字，因此判为 {@code true}。这是过判——等级只会更高，方向是安全的。</li>
 *   <li><b>首关键字是 {@code with} 的 CTE 不判为写语句</b>：规则只认首关键字，而
 *       {@code with c as (...) delete from t} 的首关键字是 {@code with}。判成"不是写语句"的方向是安全的
 *       （不会被放行去执行），但它属于本任务范围外的写法——真要支持时，应在这里按
 *       {@link com.badc.sql.control.query.QueryService#isReadonlyStatement(String)} 同类思路补判定。</li>
 * </ul>
 */
public final class StatementClassifier {

    /** 写语句的首关键字：DML 四种 + DDL 五种。 */
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "insert", "update", "delete", "replace", "create", "alter", "drop", "truncate", "rename");

    /** {@code drop/create table} 之后、表名之前可能夹着的词。 */
    private static final Set<String> TABLE_NAME_FILLERS = Set.of("temporary", "if", "not", "exists");

    /** {@code update}/{@code insert}/{@code replace} 之后、表名之前可能夹着的修饰词。 */
    private static final Set<String> WRITE_MODIFIERS = Set.of("low_priority", "ignore", "quick", "delayed");

    /** {@code create} 与 {@code index} 之间的修饰词。 */
    private static final Set<String> INDEX_MODIFIERS = Set.of("unique", "fulltext", "spatial");

    /**
     * 出现在 {@code a.b} 左侧时不足以说明"a 是库名"的词：SQL 关键字、子句开头、常见函数名。
     *
     * <p>不在这个表里的左侧标识符一律按库名处理（过判，见类注释）。函数名也列进来是因为 MySQL 允许
     * {@code schema.func()} 这种写法，右侧是函数时不该算跨库表引用。
     */
    private static final Set<String> NON_QUALIFIER_WORDS = Set.of(
            "select", "insert", "update", "delete", "replace", "merge", "from", "where", "and", "or", "not",
            "on", "using", "join", "inner", "left", "right", "outer", "cross", "natural", "straight_join",
            "group", "order", "by", "having", "limit", "offset", "union", "all", "distinct", "as", "into",
            "values", "value", "set", "table", "database", "schema", "index", "key", "column", "constraint",
            "primary", "foreign", "unique", "fulltext", "spatial", "check", "default", "partition", "case",
            "when", "then", "else", "end", "is", "null", "like", "in", "exists", "between", "interval",
            "character", "charset", "collate", "convert", "cast", "modify", "change", "add", "drop", "alter",
            "create", "truncate", "rename", "desc", "describe", "explain", "show", "use", "call", "for",
            "current_date", "current_time", "current_timestamp", "now", "count", "sum", "avg", "min", "max",
            "if", "ifnull", "coalesce", "concat", "substr", "substring", "date_format", "json_extract",
            "json_unquote", "length", "lower", "upper", "round", "floor", "ceil", "abs");

    /**
     * 分类一条 SQL。{@code null} 与空白输入返回"什么都没有"的分类，不抛 NPE。
     *
     * <p>多语句时只描述第一条：整条 SQL 马上会被 BLOCK，后面的语句没有判定价值。
     */
    public Classification classify(String sql) {
        String original = sql == null ? "" : sql;
        List<String> statements = SqlText.splitStatements(original);
        boolean multipleStatements = statements.size() > 1;

        // 多语句时只看第一条；单条时直接用原文（保留表名的大小写与位置）。
        String subject = multipleStatements ? statements.get(0) : original;
        List<Token> tokens = scan(subject);

        DdlAction ddlAction = DdlAction.mostSevere(ddlActions(tokens));
        String firstKeyword = firstKeyword(tokens);

        return new Classification(
                multipleStatements,
                firstKeyword,
                ddlAction,
                ddlAction.isStructuralDamage(),
                isWriteStatement(firstKeyword),
                hasTopLevelKeyword(tokens, "where"),
                hasTopLevelKeyword(tokens, "limit"),
                hasCrossDatabaseReference(tokens),
                extractTable(tokens));
    }

    private static String firstKeyword(List<Token> tokens) {
        if (tokens.isEmpty() || tokens.get(0).quoted()) {
            return null;
        }
        return tokens.get(0).lower();
    }

    /** {@code Set.of(...)} 的 {@code contains(null)} 会抛 NPE，首关键字可能为空，先挡一层。 */
    private static boolean isWriteStatement(String firstKeyword) {
        return firstKeyword != null && WRITE_KEYWORDS.contains(firstKeyword);
    }

    // ------------------------------------------------------------------ DDL 动作

    private static EnumSet<DdlAction> ddlActions(List<Token> tokens) {
        EnumSet<DdlAction> actions = EnumSet.noneOf(DdlAction.class);
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth() != 0 || token.quoted()) {
                continue;
            }
            switch (token.lower()) {
                case "drop" -> {
                    int after = isKeywordAt(tokens, i + 1, "temporary") ? i + 2 : i + 1;
                    actions.add(switch (lowerAt(tokens, after)) {
                        case "database", "schema" -> DdlAction.DROP_DATABASE;
                        case "table" -> DdlAction.DROP_TABLE;
                        case "partition" -> DdlAction.DROP_PARTITION;
                        // drop index / view / trigger / procedure：是 DDL，但不是这里定义的结构性动作。
                        default -> DdlAction.OTHER_DDL;
                    });
                }
                case "truncate" -> actions.add(DdlAction.TRUNCATE);
                case "rename" -> actions.add(isKeywordAt(tokens, i + 1, "table")
                        ? DdlAction.RENAME_TABLE
                        : DdlAction.OTHER_DDL);
                case "create" -> actions.add(createAction(tokens, i));
                case "alter" -> {
                    scanAlterClauses(tokens, i + 1, actions);
                    i = tokens.size(); // 一条语句只有一个 alter，子句已在上面一次扫完
                }
                default -> {
                    // 普通 DML 与只读语句
                }
            }
        }
        return actions;
    }

    private static DdlAction createAction(List<Token> tokens, int createIndex) {
        int i = createIndex + 1;
        if (isKeywordAt(tokens, i, "temporary")) {
            i++;
        }
        if (isKeywordAt(tokens, i, "table")) {
            return DdlAction.CREATE_TABLE;
        }
        int j = i;
        while (isAnyKeywordAt(tokens, j, INDEX_MODIFIERS)) {
            j++;
        }
        if (isKeywordAt(tokens, j, "index")) {
            return DdlAction.CREATE_INDEX;
        }
        // create database / view / trigger / procedure / function ...
        return DdlAction.OTHER_DDL;
    }

    /**
     * 扫描 {@code alter} 之后的<b>全部</b>子句动词——一个 alter 可以有多个子句，只认第一个会把
     * {@code add column b int, drop column a} 判成"加列"。
     */
    private static void scanAlterClauses(List<Token> tokens, int from, EnumSet<DdlAction> actions) {
        int before = actions.size();
        for (int i = from; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth() != 0 || token.quoted()) {
                continue;
            }
            switch (token.lower()) {
                case "drop" -> {
                    String next = lowerAt(tokens, i + 1);
                    switch (next) {
                        case "column" -> actions.add(DdlAction.ALTER_DROP_COLUMN);
                        case "index", "key" -> actions.add(DdlAction.ALTER_DROP_INDEX);
                        case "partition" -> actions.add(DdlAction.DROP_PARTITION);
                        default -> {
                            // alter ... drop check/constraint/foreign key：不是这里定义的结构性动作
                        }
                    }
                }
                case "add" -> {
                    if (isKeywordAt(tokens, i + 1, "unique")
                            && (isKeywordAt(tokens, i + 2, "index") || isKeywordAt(tokens, i + 2, "key"))) {
                        actions.add(DdlAction.ALTER_ADD_UNIQUE_INDEX);
                    } else {
                        actions.add(DdlAction.ALTER_ADD_COLUMN);
                    }
                }
                case "modify" -> actions.add(DdlAction.ALTER_MODIFY);
                case "convert" -> {
                    if (isKeywordAt(tokens, i + 1, "to")
                            && (isKeywordAt(tokens, i + 2, "charset")
                                    || (isKeywordAt(tokens, i + 2, "character") && isKeywordAt(tokens, i + 3, "set")))) {
                        actions.add(DdlAction.ALTER_CONVERT_CHARSET);
                    }
                }
                default -> {
                    // add 之外的子句关键字（engine、algorithm、lock 等）不改变判定
                }
            }
        }
        if (actions.size() == before) {
            // alter table t engine=innodb / alter database ...：是 DDL，但没有可识别的结构性子句。
            actions.add(DdlAction.OTHER_DDL);
        }
    }

    // ------------------------------------------------------------------ 表名

    /**
     * 从<b>原文的 token</b> 取表名。反引号已去掉，限定名保留 {@code db.t} 形式。
     *
     * <p>表名不能从剥离后的骨架取：骨架里表名是空格。这也是本类自带扫描器（保留反引号标识符）的原因。
     */
    private static String extractTable(List<Token> tokens) {
        if (tokens.isEmpty() || tokens.get(0).quoted()) {
            return null;
        }
        return switch (tokens.get(0).lower()) {
            case "update" -> nameAt(tokens, skipFillers(tokens, 1, WRITE_MODIFIERS));
            case "insert", "replace" -> {
                int into = indexOf(tokens, "into", 0);
                yield nameAt(tokens, into >= 0 ? into + 1 : skipFillers(tokens, 1, WRITE_MODIFIERS));
            }
            case "delete" -> {
                int from = indexOf(tokens, "from", 0);
                yield from >= 0 ? nameAt(tokens, from + 1) : null;
            }
            case "truncate" -> nameAt(tokens, skipFillers(tokens, 1, Set.of("table")));
            case "alter" -> {
                int table = indexOf(tokens, "table", 0);
                yield table >= 0 ? nameAt(tokens, skipFillers(tokens, table + 1, TABLE_NAME_FILLERS)) : null;
            }
            case "drop" -> extractDroppedObject(tokens);
            case "rename" -> {
                int table = indexOf(tokens, "table", 0);
                yield table >= 0 ? nameAt(tokens, table + 1) : null;
            }
            case "create" -> extractCreatedObject(tokens);
            default -> null;
        };
    }

    private static String extractDroppedObject(List<Token> tokens) {
        int i = 1;
        if (isKeywordAt(tokens, i, "temporary")) {
            i++;
        }
        if (isKeywordAt(tokens, i, "table")) {
            return nameAt(tokens, skipFillers(tokens, i + 1, TABLE_NAME_FILLERS));
        }
        if (isKeywordAt(tokens, i, "database") || isKeywordAt(tokens, i, "schema")) {
            return nameAt(tokens, i + 1);
        }
        if (isKeywordAt(tokens, i, "index")) {
            int on = indexOf(tokens, "on", i + 1);
            return on >= 0 ? nameAt(tokens, on + 1) : null;
        }
        return null;
    }

    private static String extractCreatedObject(List<Token> tokens) {
        int i = 1;
        if (isKeywordAt(tokens, i, "temporary")) {
            i++;
        }
        if (isKeywordAt(tokens, i, "table")) {
            return nameAt(tokens, skipFillers(tokens, i + 1, TABLE_NAME_FILLERS));
        }
        int j = i;
        while (isAnyKeywordAt(tokens, j, INDEX_MODIFIERS)) {
            j++;
        }
        if (isKeywordAt(tokens, j, "index")) {
            // create [unique] index idx on t (...)：表在 on 后面
            int on = indexOf(tokens, "on", j + 1);
            return on >= 0 ? nameAt(tokens, on + 1) : null;
        }
        return null;
    }

    private static int skipFillers(List<Token> tokens, int from, Set<String> fillers) {
        int i = from;
        while (i < tokens.size() && !tokens.get(i).quoted() && fillers.contains(tokens.get(i).lower())) {
            i++;
        }
        return i;
    }

    private static int indexOf(List<Token> tokens, String keyword, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (isKeywordAt(tokens, i, keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static String nameAt(List<Token> tokens, int index) {
        if (index < 0 || index >= tokens.size()) {
            return null;
        }
        Token token = tokens.get(index);
        String name = token.text();
        if (name.isEmpty()) {
            return null;
        }
        String[] parts = name.split("\\.", -1);
        for (int p = 0; p < parts.length; p++) {
            String part = parts[p];
            if (part.isEmpty()) {
                return null;
            }
            if (!token.quoted() && !isBareIdentifier(part)) {
                return null;
            }
            if (p == 0 && !token.quoted() && Character.isDigit(part.charAt(0))) {
                return null; // 数字字面量（1.5）不是表名
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ 顶层关键字与跨库

    private static boolean hasTopLevelKeyword(List<Token> tokens, String keyword) {
        for (Token token : tokens) {
            if (token.depth() == 0 && !token.quoted() && token.lower().equals(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 骨架里是否出现 {@code 标识符.标识符} 且左侧不是关键字/函数名。
     *
     * <p>反引号限定名被掩码，检测不到（已知局限，见类注释）。
     */
    private static boolean hasCrossDatabaseReference(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.quoted()) {
                continue;
            }
            String text = token.text();
            if (text.indexOf('.') <= 0) {
                continue;
            }
            String[] parts = text.split("\\.", -1);
            boolean qualified = parts.length >= 2;
            for (String part : parts) {
                if (!isBareIdentifier(part)) {
                    qualified = false;
                    break;
                }
            }
            if (!qualified) {
                continue;
            }
            String left = parts[0];
            if (Character.isDigit(left.charAt(0))) {
                continue; // 1.5 之类的数字字面量
            }
            if (NON_QUALIFIER_WORDS.contains(left.toLowerCase(Locale.ROOT))) {
                continue;
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ token 化

    /**
     * 把 SQL 切成 token：注释与字符串字面量整体丢弃，反引号标识符作为一个不可再分的 token，
     * 括号深度记在 token 上（顶层关键字判定要用）。
     *
     * <p>与 {@link SqlText#stripLiteralsAndComments(String)} 判定等价（注释、字面量、反引号名都不算关键字），
     * 但保留了位置与反引号标识符，好在原文里取表名。
     */
    private static List<Token> scan(String sql) {
        List<Token> tokens = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        int depth = 0;
        while (i < n) {
            char c = sql.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2))))) {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }

            int start = i;
            StringBuilder text = new StringBuilder();
            int end = readName(sql, i, text);
            if (end < 0) {
                i++; // 运算符等，不是名字
                continue;
            }
            tokens.add(new Token(text.toString(), text.toString().toLowerCase(Locale.ROOT),
                    sql.charAt(start) == '`', depth));
            i = end;
        }
        return tokens;
    }

    /** 读一个（可带 {@code .} 限定的）名字；返回结束下标，不是名字返回 -1。 */
    private static int readName(String sql, int from, StringBuilder out) {
        int n = sql.length();
        int i = from;
        int parts = 0;
        while (true) {
            if (i >= n) {
                break;
            }
            char c = sql.charAt(i);
            if (c == '`') {
                i = readBackticked(sql, i, out);
                parts++;
            } else if (isNameChar(c)) {
                while (i < n && isNameChar(sql.charAt(i))) {
                    out.append(sql.charAt(i));
                    i++;
                }
                parts++;
            } else {
                break;
            }
            // 限定名：`db`.`t` / db.t
            if (i + 1 < n && sql.charAt(i) == '.' && startsName(sql, i + 1)) {
                out.append('.');
                i++;
                continue;
            }
            break;
        }
        return parts == 0 ? -1 : i;
    }

    /** 读反引号标识符的内容（已处理 `` 转义），返回结束下标；未闭合时吞到结尾。 */
    private static int readBackticked(String sql, int from, StringBuilder out) {
        int n = sql.length();
        int i = from + 1;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '`') {
                if (i + 1 < n && sql.charAt(i + 1) == '`') {
                    out.append('`');
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return n;
    }

    private static int skipQuoted(String sql, int from, char quote) {
        int n = sql.length();
        int i = from + 1;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static boolean startsName(String sql, int index) {
        if (index >= sql.length()) {
            return false;
        }
        char c = sql.charAt(index);
        return c == '`' || isNameChar(c);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isBareIdentifier(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isNameChar(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isKeywordAt(List<Token> tokens, int index, String keyword) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return !token.quoted() && token.lower().equals(keyword);
    }

    private static boolean isAnyKeywordAt(List<Token> tokens, int index, Set<String> keywords) {
        if (index < 0 || index >= tokens.size()) {
            return false;
        }
        Token token = tokens.get(index);
        return !token.quoted() && keywords.contains(token.lower());
    }

    private static String lowerAt(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index).lower() : "";
    }

    /** 扫描出的一个 token：{@code text} 保留原文大小写（表名要用），{@code lower} 用于关键字匹配。 */
    private record Token(String text, String lower, boolean quoted, int depth) {
    }
}
