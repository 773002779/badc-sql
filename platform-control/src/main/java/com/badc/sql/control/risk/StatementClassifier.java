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
 * <p><b>跨库判定只看表位置</b>：只有 {@code from}/{@code join}/{@code into}/{@code straight_join}/{@code using}/
 * {@code table}（以及首关键字的 {@code update}）之后出现的两段式名称（{@code db2.t_order}）才算跨库；
 * {@code where t_order.id = 1}、{@code select o.id} 里的 {@code x.y} 是列限定，不算。别名不是表列表的结束，
 * 逗号分隔的多表列表会继续检查——{@code update t1 as a, db2.t2 as b set ...} 必须命中。
 *
 * <p><b>{@code with} 开头的 CTE 按顶层动词判性质</b>：{@code with ... select} 是只读，
 * {@code with ... insert/update/delete/replace} 是写语句（{@code ddlAction} 为 {@code NONE}）——
 * 判成只读会让整单基础等级落到 L0（免审批），方向正好错。这里的顶层动词判定与查询接口的只读白名单共用
 * {@link SqlText#firstTopLevelVerb(String)}，两个入口不会对同一条 SQL 给出相反结论。
 *
 * <p><b>{@code explain} 看有没有 {@code analyze}</b>：{@code explain <dml>} 只出执行计划，不算写；
 * {@code explain analyze <dml>} 在 MySQL 里会真的执行那条 DML，算写语句（{@code ddlAction} 为 {@code NONE}）。
 * 这一条与只读白名单的 {@code explain} 分支对齐——否则 {@code explain analyze ... delete} 会被当只读放行。
 *
 * <p><b>DDL 动作只从首关键字起扫</b>：{@code select truncate from t} 是合法的只读查询，不能因为列名叫
 * {@code truncate} 就被判成结构性破坏。
 *
 * <p>{@link DdlAction#ALTER_DROP_INDEX} 同时覆盖 alter 形式（{@code alter table t drop index i}）与
 * 顶层形式（{@code drop index i on t}）；{@link DdlAction#ALTER_MODIFY} 同时覆盖 {@code modify} 与
 * {@code change [column]}（改名 + 改类型，收窄风险相同）；{@link DdlAction#RENAME_TABLE} 同时覆盖
 * {@code rename table a to b} 与 {@code alter table a rename [to|as] b}；{@link DdlAction#DROP_PARTITION}
 * 同时覆盖 {@code drop partition} 与 {@code truncate partition}（都是整分区数据丢失）。
 *
 * <h2>已知局限</h2>
 * <ul>
 *   <li><b>反引号限定名检测不到跨库</b>：{@code `db2`.`t_order`} 的反引号内容与字符串字面量走同一分支被
 *       掩码，所以 {@code crossDatabase()} 为 {@code false}。这是尽力而为的判定；任务 6 对跨库另有兜底
 *       （至少 L3），不能只依赖本字段。</li>
 * </ul>
 */
public final class StatementClassifier {

    /** 写语句的首关键字：DML 四种 + DDL 五种。 */
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "insert", "update", "delete", "replace", "create", "alter", "drop", "truncate", "rename");

    /** 只有首关键字是它们时，这条语句才可能是 DDL；用来挡住 {@code select truncate from t} 这类误判。 */
    private static final Set<String> DDL_KEYWORDS = Set.of("create", "alter", "drop", "truncate", "rename");

    /** {@code EXPLAIN ANALYZE} 会真的执行其后的语句：这些动词意味着真的写库。 */
    private static final Set<String> DML_VERBS = Set.of("insert", "update", "delete", "replace");

    /** {@code drop/create table} 之后、表名之前可能夹着的词。 */
    private static final Set<String> TABLE_NAME_FILLERS = Set.of("temporary", "if", "not", "exists");

    /** {@code update}/{@code insert}/{@code replace} 之后、表名之前可能夹着的修饰词。 */
    private static final Set<String> WRITE_MODIFIERS = Set.of("low_priority", "ignore", "quick", "delayed");

    /** {@code create} 与 {@code index} 之间的修饰词。 */
    private static final Set<String> INDEX_MODIFIERS = Set.of("unique", "fulltext", "spatial");

    /**
     * 只有这些关键字之后的名称才在<b>表位置</b>上：{@code from}/{@code join}/{@code into}/{@code table}/
     * {@code straight_join}/{@code using} 之后出现的两段式名称才是跨库表引用。{@code where}/{@code on}/
     * {@code set}/{@code select} 列表里的 {@code x.y} 是列限定（{@code t_order.id}、{@code o.id}），不是跨库。
     *
     * <p>{@code update} 不在集合里而是单独判：只有它不是 {@code on duplicate key update} 里的那个
     * {@code update} 时才算表位置（见 {@link #hasCrossDatabaseReference(List)}），否则普通 upsert 里
     * {@code update b = c.d} 的 {@code c.d} 会被当成跨库表。
     */
    private static final Set<String> TABLE_POSITION_KEYWORDS = Set.of(
            "from", "join", "into", "table", "straight_join", "using");

    /** 表位置之后遇到这些词说明表列表已结束，后面的 {@code x.y} 是列或表达式，不是表引用。 */
    private static final Set<String> TABLE_LIST_END = Set.of(
            "where", "on", "using", "set", "group", "order", "having", "limit", "offset", "union",
            "values", "value", "select", "for", "columns", "partition", "duplicate",
            "join", "inner", "left", "right", "outer", "cross", "natural", "straight_join",
            "drop", "add", "modify", "change", "convert", "rename", "engine", "algorithm", "lock",
            "comment", "default", "character", "collate", "auto_increment");


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

        EnumSet<DdlAction> actions = ddlActions(tokens);
        DdlAction ddlAction = DdlAction.mostSevere(actions);
        // 结构性判定看整组动作，而不是只看"最严重的那个"：声明顺序里 ALTER_MODIFY 排在改字符集与
        // drop partition 之前，若只看返回值，`modify a int, convert to character set utf8mb4` 会把恒 L3
        // 的改字符集漏掉。
        boolean structuralDamage = actions.stream().anyMatch(DdlAction::isStructuralDamage);
        String firstKeyword = firstKeyword(tokens);

        return new Classification(
                multipleStatements,
                firstKeyword,
                ddlAction,
                structuralDamage,
                isWriteStatement(subject, firstKeyword),
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

    /**
     * 是否写语句。
     *
     * <p>{@code with} 开头的 CTE 不能只看首关键字：{@code with c as (...) delete from t} 的首关键字是
     * {@code with}，但它是写语句。判成只读会让整单基础等级落 L0（免审批），方向正好错，所以按 CTE 之后的
     * 顶层动词判——判定与查询接口的只读白名单共用 {@link SqlText#firstTopLevelVerb(String)}，两个入口
     * 不会给出相反结论。
     *
     * <p>{@code explain} 要看有没有 {@code analyze}：{@code explain <dml>} 只出执行计划、不执行；
     * {@code explain analyze <dml>} 在 MySQL 里<b>会真的执行</b>那条语句，所以后者算写语句。
     *
     * <p>拿不到顶层动词时按写语句处理（保守）：宁可多审一次，也不把一条来历不明的语句放成只读。
     */
    private static boolean isWriteStatement(String subject, String firstKeyword) {
        if (firstKeyword == null) {
            return false;
        }
        if (WRITE_KEYWORDS.contains(firstKeyword)) {
            return true;
        }
        if ("with".equals(firstKeyword)) {
            String verb = SqlText.firstTopLevelVerb(SqlText.stripLiteralsAndComments(subject));
            return verb == null || WRITE_KEYWORDS.contains(verb);
        }
        if ("explain".equals(firstKeyword)) {
            return isWriteUnderExplain(SqlText.stripLiteralsAndComments(subject));
        }
        return false;
    }

    /**
     * {@code explain analyze <dml>} 会真的执行 DML，算写；{@code explain <dml>}（不带 analyze）只出计划，不算。
     *
     * @param code 已剥离注释与字面量的文本，首词必然是 {@code explain}（调用方已确认）
     */
    private static boolean isWriteUnderExplain(String code) {
        String rest = code.stripLeading();
        int endOfExplain = 0;
        while (endOfExplain < rest.length() && Character.isLetter(rest.charAt(endOfExplain))) {
            endOfExplain++;
        }
        String after = rest.substring(Math.min(endOfExplain, rest.length())).stripLeading();
        if (!startsWithWord(after, "analyze")) {
            return false; // explain <dml>：只出计划，不执行
        }
        String verb = SqlText.firstTopLevelVerb(code);
        return verb == null || DML_VERBS.contains(verb);
    }

    private static boolean startsWithWord(String text, String word) {
        if (!text.startsWith(word)) {
            return false;
        }
        int end = word.length();
        return text.length() == end || !isNameChar(text.charAt(end));
    }

    // ------------------------------------------------------------------ DDL 动作

    private static EnumSet<DdlAction> ddlActions(List<Token> tokens) {
        EnumSet<DdlAction> actions = EnumSet.noneOf(DdlAction.class);
        // 只有首 token 是 DDL 关键字时才扫动作：否则 `select truncate from t` 会被判成结构性破坏。
        if (tokens.isEmpty() || tokens.get(0).quoted() || !DDL_KEYWORDS.contains(tokens.get(0).lower())) {
            return actions;
        }
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
                        // 顶层 drop index 与 alter table ... drop index 等价，归同一个动作。
                        case "index", "key" -> DdlAction.ALTER_DROP_INDEX;
                        // drop view / trigger / procedure：是 DDL，但不是这里定义的结构性动作。
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
                // change [column] 同时改名与改类型，收窄风险与 modify 相同，交给任务 4 的收窄判定覆盖。
                case "change" -> actions.add(DdlAction.ALTER_MODIFY);
                case "rename" -> {
                    // alter table a rename [to|as] b 等价于 rename table a to b；rename column/index/key
                    // 只是改名字，不是表级结构破坏（没有可识别动作，最终归 OTHER_DDL）。
                    String next = lowerAt(tokens, i + 1);
                    if (!"column".equals(next) && !"index".equals(next) && !"key".equals(next)) {
                        actions.add(DdlAction.RENAME_TABLE);
                    }
                }
                case "truncate" -> {
                    // truncate partition 与 drop partition 一样是整分区数据丢失。
                    if (isKeywordAt(tokens, i + 1, "partition")) {
                        actions.add(DdlAction.DROP_PARTITION);
                    }
                }
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
     * 是否有跨库表引用：只认<b>表位置</b>上的两段式名称。
     *
     * <p>{@code from}/{@code join}/{@code into}/{@code straight_join}/{@code using}/{@code table} 之后出现的
     * {@code db2.t_order} 是跨库；{@code where t_order.id = 1}、{@code select o.id ...} 里的 {@code x.y}
     * 是列限定，不算。别名（{@code as}）不是表列表的结束，逗号分隔的多表列表会继续被检查，
     * 所以 {@code update t1 as a, db2.t2 as b set ...} 能命中。
     *
     * <p>{@code update} 单独判：只有它不是 {@code on duplicate key update} 里的那个 {@code update} 时才算
     * 表位置——否则普通 upsert 的 {@code update b = c.d} 会把 {@code c.d} 当成跨库表。
     *
     * <p>反引号限定名 {@code `db2`.`t_order`} 被掩码，检测不到（已知局限，见类注释）。
     */
    private static boolean hasCrossDatabaseReference(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token keyword = tokens.get(i);
            if (keyword.quoted() || !isTablePosition(tokens, i)) {
                continue;
            }
            // 表位置的同一层括号内继续找：from t1 as a, db2.t2 与 join db2.t2 都要覆盖。
            for (int j = i + 1; j < tokens.size(); j++) {
                Token candidate = tokens.get(j);
                if (candidate.depth() != keyword.depth()) {
                    break; // 进入子查询或列定义，表列表已结束
                }
                if (candidate.quoted()) {
                    continue;
                }
                if (TABLE_LIST_END.contains(candidate.lower())) {
                    break;
                }
                if (isTwoPartQualifiedName(candidate.text())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 该 token 是否开启一个表列表：表位置关键字，或不是 {@code on duplicate key update} 的 {@code update}。 */
    private static boolean isTablePosition(List<Token> tokens, int index) {
        String word = tokens.get(index).lower();
        if (TABLE_POSITION_KEYWORDS.contains(word)) {
            return true;
        }
        if (!"update".equals(word)) {
            return false;
        }
        return index == 0 || !"key".equals(tokens.get(index - 1).lower());
    }

    /** 两段式限定名（{@code db2.t_order}）；三段（{@code db.t.col}）不算表位置上的引用。 */
    private static boolean isTwoPartQualifiedName(String text) {
        int dot = text.indexOf('.');
        if (dot <= 0 || dot == text.length() - 1 || text.indexOf('.', dot + 1) >= 0) {
            return false;
        }
        String left = text.substring(0, dot);
        String right = text.substring(dot + 1);
        return isBareIdentifier(left) && isBareIdentifier(right) && !Character.isDigit(left.charAt(0));
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
