package com.badc.sql.control.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.badc.sql.common.sql.SqlText;

/**
 * 规范校验（spec §FR-10）：把一条语句落成一组<b>带纪律</b>的命中。
 *
 * <p>本类是纯计算：不连库、不查权限、不读配置，所有输入都来自参数，可在单测里跑满。它只做"这条语句违反了
 * 哪条规范、该按什么口径处理"，等级合并与阈值判断在 {@code RiskEngine}（任务 6）。
 *
 * <h2>两种口径不能混</h2>
 * <ul>
 *   <li>{@link Discipline#BLOCK}：提交阶段直接拒绝，进不到审批（{@code drop database/schema}、多语句拼接）。
 *       <b>命中后本方法立即返回</b>——被拒绝的语句根本不会走到审批，继续算 L3 或风格提示只会把唯一的拒绝理由
 *       埋进噪音里。</li>
 *   <li>{@link Discipline#PIN_LEVEL}：可提交，但等级被钉死在 {@link RiskHit#level()}（恒为 {@link RiskLevel#L3}），
 *       只能抬升、不可下调；配置阈值再宽也不降。</li>
 *   <li>{@link Discipline#WARN} / {@link Discipline#INFO}：只看不改等级。</li>
 * </ul>
 *
 * <p>{@link RiskHit#level()} 只在 {@code PIN_LEVEL} 时有值，{@code BLOCK}/{@code WARN}/{@code INFO} 一律为
 * {@code null}（见 {@link RiskHit} 的类注释）。
 *
 * <h2>规则表</h2>
 * <p>{@code BLOCK}：{@code DROP_DATABASE}、{@code MULTI_STATEMENT}。
 * <p>{@code PIN_LEVEL L3}：{@code DROP_TABLE}、{@code TRUNCATE}、{@code RENAME_TABLE}、{@code ALTER_DROP_COLUMN}、
 * {@code ALTER_DROP_INDEX}、{@code ALTER_MODIFY_NARROWED}、{@code ALTER_CONVERT_CHARSET}、
 * {@code DROP_PARTITION}、{@code WRITE_WITHOUT_WHERE}。
 * <p>{@code WARN}：{@code SELECT_STAR}、{@code SELECT_WITHOUT_LIMIT}、{@code LEADING_WILDCARD_LIKE}、
 * {@code IMPLICIT_CAST}、{@code ORDER_BY_RAND}。
 * <p>{@code INFO}：{@code KEYWORD_NOT_UPPERCASE}、{@code INDENTATION_MESSY}。
 *
 * <h2>为什么 {@code VERSION_MISMATCH_HINT} 不在这里</h2>
 * <p>"5.7 语法用在 8.0 库 / 反之"需要<b>目标库版本</b>，而版本只有执行器连上目标库才知道；这条判断已由执行器侧的
 * 方言校验器承担（切片 1 的 {@code MySqlDialect} 与 {@code ExecutorClient.validate}，任务 6/7 负责接线上）。
 * 在这里写一条拿不到版本、永远不会命中的规则，只会让规则表看起来"齐了"而没人知道它是空的。
 *
 * <h2>收窄判定：必须前后对比</h2>
 * <p>一句 {@code alter table t modify column c varchar(64)} 里<b>没有旧类型</b>，所以收窄不是单看语句能判的：
 * <ul>
 *   <li><b>给了旧类型</b>（{@code oldColumnTypesOrNull} 由任务 7 从结构快照填入）：从原文取
 *       {@code modify|change [column] 列名 类型} 与旧类型比对——整数族按位宽
 *       （{@code bigint > int > mediumint > smallint > tinyint}，覆盖 spec 明列的
 *       {@code bigint → int|smallint|tinyint}）、{@code varchar}/{@code char} 的 {@code n → m} 且
 *       {@code m < n}、{@code decimal/numeric} 的 {@code p' < p} 为收窄；放宽、同宽、换类型族都证不出收窄，
 *       一律不命中。</li>
 *   <li><b>没给旧类型（{@code null}）或快照里查不到这一列</b>：任何 {@code modify}/{@code change} 一律
 *       {@code PIN_LEVEL L3}，消息含"无法判定是否收窄"。在任务 7 填上旧类型之前，系统对所有 {@code modify}
 *       都按 L3 处理——这是刻意的保守，不是遗漏。</li>
 * </ul>
 * <p>两条路径都要有用例（{@code PolicyValidatorTest}），否则"前后对比"会静悄悄退化成"一律 L3"而无人察觉。
 *
 * <h2>近似规则</h2>
 * <p>{@code IMPLICIT_CAST} 拿不到列类型，只能按文本模式识别：<b>带引号的纯数字/日期字面量与标识符比较</b>
 * 即命中。{@code where code = '123'} 命中，{@code where code = 'abc'} 不命中——后者是字符串与字符串比较，
 * 没有可判定的隐式转换。
 *
 * <h2>判定来源与大小写</h2>
 * <p>"是不是写语句、动了哪张表、属于哪个 DDL 动作"一律读 {@link Classification}（任务 3），本类<b>不重新
 * 解析 DDL 动作</b>。所有关键字比较都先小写再比：SQL 关键字大小写等价，{@code DROP TABLE} 与 {@code drop table}
 * 必须同判（任务 3 曾因大写 {@code ANALYZE} 把写语句判成只读）。原文只用于取"列名 + 新类型"与取字面量内容。
 *
 * <h2>已知局限</h2>
 * <ul>
 *   <li>{@link Classification#ddlAction()} 只给出<b>最严重</b>的一个动作：{@code alter table t modify column c
 *       varchar(64), drop column d} 只报 {@code ALTER_DROP_COLUMN}，具体子句的收窄不再单独出码。整单仍被钉在
 *       L3，只是少了一条明细——这是任务 3 的接口决定的，不在本类重新解析 DDL 来补。</li>
 *   <li>表名按"精确 → 忽略大小写 → 去掉库名前缀"的顺序在快照里找（MySQL 表名在 Linux 上区分大小写，
 *       但快照与 SQL 的大小写不保证一致）。</li>
 *   <li>{@code SELECT_*} 与 {@code SELECT_WITHOUT_LIMIT} 只看语句自身的顶层查询（{@code with ... select} 也算），
 *       子查询里的 {@code select *} 不单独出码。</li>
 * </ul>
 */
public final class PolicyValidator {

    private static final String RULE_DROP_DATABASE = "DROP_DATABASE";
    private static final String RULE_MULTI_STATEMENT = "MULTI_STATEMENT";
    private static final String RULE_DROP_TABLE = "DROP_TABLE";
    private static final String RULE_TRUNCATE = "TRUNCATE";
    private static final String RULE_RENAME_TABLE = "RENAME_TABLE";
    private static final String RULE_ALTER_DROP_COLUMN = "ALTER_DROP_COLUMN";
    private static final String RULE_ALTER_DROP_INDEX = "ALTER_DROP_INDEX";
    private static final String RULE_ALTER_MODIFY_NARROWED = "ALTER_MODIFY_NARROWED";
    private static final String RULE_ALTER_CONVERT_CHARSET = "ALTER_CONVERT_CHARSET";
    private static final String RULE_DROP_PARTITION = "DROP_PARTITION";
    private static final String RULE_WRITE_WITHOUT_WHERE = "WRITE_WITHOUT_WHERE";
    private static final String RULE_SELECT_STAR = "SELECT_STAR";
    private static final String RULE_SELECT_WITHOUT_LIMIT = "SELECT_WITHOUT_LIMIT";
    private static final String RULE_LEADING_WILDCARD_LIKE = "LEADING_WILDCARD_LIKE";
    private static final String RULE_IMPLICIT_CAST = "IMPLICIT_CAST";
    private static final String RULE_ORDER_BY_RAND = "ORDER_BY_RAND";
    private static final String RULE_KEYWORD_NOT_UPPERCASE = "KEYWORD_NOT_UPPERCASE";
    private static final String RULE_INDENTATION_MESSY = "INDENTATION_MESSY";

    /** 决定"是否写语句、是哪种写"的 DML 动词。 */
    private static final Set<String> DML_VERBS = Set.of("select", "insert", "update", "delete", "replace");

    /** 只有 update/delete 的"没有 where"才是危险写法：insert 本来就没有 where。 */
    private static final Set<String> WHERE_SENSITIVE_VERBS = Set.of("update", "delete");

    /** 整数族的位宽顺序；同族内部比大小，跨族（如 int → varchar）不判收窄。 */
    private static final Map<String, Integer> INTEGER_WIDTH = Map.of(
            "tinyint", 1,
            "smallint", 2,
            "mediumint", 3,
            "int", 4,
            "bigint", 5);

    /** {@code select} 与投影列表之间的修饰词：{@code select distinct *} 里的星号仍是 {@code select *}。 */
    private static final Set<String> SELECT_MODIFIERS = Set.of(
            "distinct", "all", "high_priority", "straight_join", "sql_small_result", "sql_big_result",
            "sql_buffer_result", "sql_cache", "sql_no_cache", "sql_calc_found_rows");

    /** 聚合函数：出现它们时"没有 limit"不再是问题（结果行数天然有限）。 */
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of(
            "count", "sum", "avg", "min", "max", "group_concat", "std", "stddev", "stddev_pop",
            "stddev_samp", "variance", "var_pop", "var_samp", "bit_and", "bit_or", "bit_xor",
            "json_arrayagg", "json_objectagg");

    /** {@code order by} 子句的终止词：越过它们就不再是这个 order by 了。 */
    private static final Set<String> ORDER_CLAUSE_END = Set.of(
            "limit", "offset", "for", "union", "procedure", "into", "lock");

    /** 用于风格提示（INFO）的关键字集合。刻意不收类型名：{@code date}/{@code text} 这类常被当列名。 */
    private static final Set<String> KEYWORDS = Set.of(
            "select", "from", "where", "group", "by", "order", "having", "limit", "offset",
            "insert", "into", "values", "value", "update", "set", "delete", "replace",
            "create", "alter", "drop", "truncate", "rename", "table", "column", "add", "modify", "change",
            "index", "key", "to", "as", "on", "using", "join", "inner", "left", "right", "full", "outer",
            "cross", "natural", "straight_join", "union", "all", "distinct", "and", "or", "not", "null",
            "is", "in", "exists", "like", "between", "case", "when", "then", "else", "end",
            "primary", "foreign", "references", "unique", "default", "constraint", "auto_increment",
            "comment", "engine", "charset", "character", "collate", "convert", "temporary", "if",
            "partition", "show", "describe", "desc", "explain", "analyze", "database", "schema",
            "grant", "revoke", "lock", "unlock", "begin", "commit", "rollback", "with", "recursive",
            "window", "over");

    private static final Pattern NUMBER_LITERAL = Pattern.compile("[+-]?\\d+(\\.\\d+)?");
    private static final Pattern DATE_LITERAL =
            Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");

    /**
     * 只在调用方漏传分类时兜底用：返回空列表会 fail-open（漏掉结构性破坏的 L3），比多解析一次危险得多。
     * 分类器无状态，可安全复用。
     */
    private final StatementClassifier fallbackClassifier = new StatementClassifier();

    /**
     * 校验一条语句。
     *
     * @param sql                  用户提交的原文；{@code null} 视为空串，不抛异常
     * @param c                    任务 3 的分类结果；为 {@code null} 时本类自己分类一次（兜底，正常路径不会走到）
     * @param oldColumnTypesOrNull 结构快照里的旧列类型 {@code {表名: {列名: 类型文本}}}；{@code null} 表示
     *                             "没有旧类型可比"，收窄判定走最严分支（不抛异常）
     * @return 命中列表，按 BLOCK → PIN_LEVEL → WARN → INFO 的顺序；被 BLOCK 时只含 BLOCK 命中
     */
    public List<RiskHit> validate(String sql, Classification c,
            Map<String, Map<String, String>> oldColumnTypesOrNull) {
        String original = sql == null ? "" : sql;
        Classification classification = c == null ? fallbackClassifier.classify(original) : c;

        List<RiskHit> hits = new ArrayList<>(4);
        blockingHits(classification, hits);
        if (!hits.isEmpty()) {
            return List.copyOf(hits);
        }

        List<Tok> tokens = tokenize(original);
        structuralPins(classification, hits);
        writeWithoutWherePin(original, classification, hits);
        narrowedColumnPin(classification, tokens, oldColumnTypesOrNull, hits);
        warnings(original, classification, tokens, hits);
        infos(original, tokens, hits);
        return List.copyOf(hits);
    }

    // ------------------------------------------------------------------ BLOCK

    /**
     * 阻断类命中。返回后调用方会立即短路：被拒绝的语句进不到审批，别的命中没有意义。
     */
    private static void blockingHits(Classification c, List<RiskHit> hits) {
        if (c.multipleStatements()) {
            hits.add(new RiskHit(RULE_MULTI_STATEMENT, Discipline.BLOCK, null,
                    "一条语句里含多个分号：必须拆成多条语句逐条提交，多语句拼接会被整条拒绝"));
        }
        if (c.ddlAction() == DdlAction.DROP_DATABASE) {
            hits.add(new RiskHit(RULE_DROP_DATABASE, Discipline.BLOCK, null,
                    "drop database/schema 在提交阶段直接拒绝：整库删除不可恢复，管理员也没有豁免"));
        }
    }

    // ------------------------------------------------------------------ PIN_LEVEL：结构性破坏

    /**
     * 七个结构性破坏动作各出固定的一条 L3 命中，等级不来自任何配置。
     *
     * <p>动作只读 {@link Classification#ddlAction()}：它是这条语句里最严重的动作，{@link DdlAction} 的声明顺序
     * 已经保证结构性动作排在非结构性动作之前。
     */
    private static void structuralPins(Classification c, List<RiskHit> hits) {
        DdlAction action = c.ddlAction();
        switch (action) {
            case DROP_TABLE -> pin(hits, RULE_DROP_TABLE,
                    "drop table 属结构性破坏：表结构与数据不可逆（恢复=按快照重建），等级恒为 L3");
            case TRUNCATE -> pin(hits, RULE_TRUNCATE,
                    "truncate 属结构性破坏：数据不可恢复，等级恒为 L3");
            case RENAME_TABLE -> pin(hits, RULE_RENAME_TABLE,
                    "rename table 属结构性破坏：影响面隐蔽（外键/视图/代码），等级恒为 L3");
            case ALTER_DROP_COLUMN -> pin(hits, RULE_ALTER_DROP_COLUMN,
                    "alter table ... drop column 属结构性破坏：列数据永久丢失，等级恒为 L3");
            case ALTER_DROP_INDEX -> pin(hits, RULE_ALTER_DROP_INDEX,
                    "alter table ... drop index 属结构性破坏：可能引发性能雪崩，等级恒为 L3");
            case ALTER_CONVERT_CHARSET -> pin(hits, RULE_ALTER_CONVERT_CHARSET,
                    "alter table ... convert to charset 属结构性破坏：会重建表并锁表，等级恒为 L3");
            case DROP_PARTITION -> pin(hits, RULE_DROP_PARTITION,
                    "drop partition 属结构性破坏：整分区数据丢失，等级恒为 L3");
            default -> {
                // ALTER_MODIFY 是否收窄要看旧类型（见 narrowedColumnPin），其余动作不是结构性破坏。
                // 兜底：将来往 DdlAction 里加了结构性动作却忘了在这里编码时，仍然钉 L3，不会静默放行。
                if (action.isStructuralDamage()) {
                    pin(hits, action.name(), "识别到结构性破坏动作 " + action.name() + "：等级恒为 L3");
                }
            }
        }
    }

    // ------------------------------------------------------------------ PIN_LEVEL：没有 where 的写语句

    /**
     * {@code update}/{@code delete} 没有顶层 {@code where} → 钉 L3，但不阻断（spec：不阻断，强制升 L3 + 二次确认）。
     *
     * <p>动词取自 {@link Classification#firstKeyword()}，{@code with}/{@code explain} 开头时改用
     * {@link SqlText#firstTopLevelVerb(String)}（与分类器、只读白名单共用同一判定）：{@code with ... delete}
     * 的动词是 {@code delete}，只看首关键字会漏掉"没有 where 的 CTE 删除"。
     */
    private static void writeWithoutWherePin(String sql, Classification c, List<RiskHit> hits) {
        String verb = statementVerb(sql, c);
        if (verb == null || !WHERE_SENSITIVE_VERBS.contains(verb) || c.hasWhere()) {
            return;
        }
        pin(hits, RULE_WRITE_WITHOUT_WHERE,
                verb + " 没有 where 条件：会命中整表，等级强制 L3 并需二次确认（不阻断提交）");
    }

    private static String statementVerb(String sql, Classification c) {
        String first = c.firstKeyword();
        if (first == null) {
            return null;
        }
        if (DML_VERBS.contains(first)) {
            return first;
        }
        if ("with".equals(first) || "explain".equals(first)) {
            return SqlText.firstTopLevelVerb(SqlText.stripLiteralsAndComments(sql));
        }
        return first;
    }

    // ------------------------------------------------------------------ PIN_LEVEL：类型收窄

    /**
     * 判 {@code modify}/{@code change} 是否把列类型收窄。
     *
     * <p>只在 {@link Classification#ddlAction()} 是 {@link DdlAction#ALTER_MODIFY} 时进入：分类器已经确认这条
     * alter 改了列定义，本方法只负责"和旧类型比一比"——不重新识别 DDL 动作。
     *
     * <p>旧类型缺失（参数为 {@code null}、快照里没有这张表、或没有这一列）时一律保守钉 L3 并在消息里说明
     * "无法判定是否收窄"；有旧类型时只有<b>证得出收窄</b>才命中，放宽/同宽不命中。
     */
    private static void narrowedColumnPin(Classification c, List<Tok> tokens,
            Map<String, Map<String, String>> oldColumnTypesOrNull, List<RiskHit> hits) {
        if (c.ddlAction() != DdlAction.ALTER_MODIFY) {
            return;
        }
        List<ColumnChange> changes = columnChanges(tokens);
        if (changes.isEmpty()) {
            // 分类器认为改了列定义，但原文里取不到可解析的列名 + 类型：无法判定，按最严处理。
            pin(hits, RULE_ALTER_MODIFY_NARROWED,
                    "alter table ... modify/change：原文里取不到列定义，无法判定是否收窄，按最严处理，等级恒为 L3");
            return;
        }
        for (ColumnChange change : changes) {
            String oldType = oldTypeOf(oldColumnTypesOrNull, c.table(), change.column());
            if (oldType == null) {
                pin(hits, RULE_ALTER_MODIFY_NARROWED, change.keyword() + " 列 " + change.column()
                        + "：结构快照里没有该列的旧类型，无法判定是否收窄，按最严处理，等级恒为 L3");
            } else if (isNarrowing(oldType, change.newType())) {
                pin(hits, RULE_ALTER_MODIFY_NARROWED, change.keyword() + " 列 " + change.column()
                        + "：类型由 " + oldType + " 收窄为 " + change.newType().text()
                        + "，可能静默截断数据，等级恒为 L3");
            }
            // 放宽、同宽或换了类型族却证不出收窄：不命中（spec：无法证明收窄就不算结构性破坏）。
        }
    }

    /**
     * 从原文取 {@code modify|change [column] 列名 类型}（{@code change} 还有改名的第二个名字）。
     *
     * <p>只认顶层（括号深度 0）的关键字，并且跳过字符串/注释/反引号里的同名内容：这些都是 token 化时已经
     * 处理过的。取不到完整列定义（没有名字或没有类型）的子句会被跳过。
     */
    private static List<ColumnChange> columnChanges(List<Tok> tokens) {
        List<ColumnChange> changes = new ArrayList<>(1);
        for (int i = 0; i < tokens.size(); i++) {
            Tok token = tokens.get(i);
            if (token.depth() != 0 || token.kind() != Kind.WORD) {
                continue;
            }
            boolean modify = "modify".equals(token.lower());
            boolean change = "change".equals(token.lower());
            if (!modify && !change) {
                continue;
            }
            int j = i + 1;
            if (isWordAt(tokens, j, "column")) {
                j++;
            }
            Tok column = tokenAt(tokens, j);
            if (!isColumnName(column)) {
                continue;
            }
            j++;
            if (change) {
                // change old_name new_name <type>：旧类型的归属是第一个名字。
                Tok newName = tokenAt(tokens, j);
                if (!isColumnName(newName)) {
                    continue;
                }
                j++;
            }
            TypeSpec type = readType(tokens, j);
            if (type == null) {
                continue;
            }
            changes.add(new ColumnChange(modify ? "modify" : "change", column.text(), type));
        }
        return changes;
    }

    /** 读一个列类型：{@code varchar(64)}、{@code decimal(10,2)}、{@code bigint} 都能取到。 */
    private static TypeSpec readType(List<Tok> tokens, int index) {
        Tok base = tokenAt(tokens, index);
        if (base == null || base.kind() != Kind.WORD || !isTypeName(base.text())) {
            return null;
        }
        String baseName = base.lower();
        Tok open = tokenAt(tokens, index + 1);
        if (open == null || !open.isPunct("(")) {
            return new TypeSpec(baseName, null, null, baseName);
        }
        int depth = open.depth();
        Integer first = null;
        Integer second = null;
        boolean closed = false;
        for (int j = index + 2; j < tokens.size(); j++) {
            Tok token = tokens.get(j);
            if (token.isPunct(")") && token.depth() == depth) {
                closed = true;
                break;
            }
            if (token.kind() != Kind.WORD || token.depth() != depth + 1 || !isDigits(token.text())) {
                continue;
            }
            if (first == null) {
                first = parsePositive(token.text());
            } else if (second == null) {
                second = parsePositive(token.text());
            }
        }
        if (!closed || first == null) {
            return new TypeSpec(baseName, first, second, baseName);
        }
        String text = second == null ? baseName + "(" + first + ")" : baseName + "(" + first + "," + second + ")";
        return new TypeSpec(baseName, first, second, text);
    }

    /** 把快照里的类型文本解析成同样的规格；解析不出返回 {@code null}（当作"无法判定"）。 */
    private static TypeSpec parseType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int end = 0;
        while (end < lower.length() && (Character.isLetterOrDigit(lower.charAt(end)) || lower.charAt(end) == '_')) {
            end++;
        }
        if (end == 0 || !Character.isLetter(lower.charAt(0))) {
            return null;
        }
        String base = lower.substring(0, end);
        Integer first = null;
        Integer second = null;
        int open = lower.indexOf('(', end);
        if (open >= 0) {
            int close = lower.indexOf(')', open);
            String inside = close > open ? lower.substring(open + 1, close) : lower.substring(open + 1);
            String[] parts = inside.split(",");
            if (parts.length > 0) {
                first = parsePositive(parts[0].strip());
            }
            if (parts.length > 1) {
                second = parsePositive(parts[1].strip());
            }
        }
        return new TypeSpec(base, first, second, trimmed);
    }

    /**
     * 旧类型 → 新类型是否收窄。
     *
     * <p>只有<b>同族</b>才可能证明收窄：整数族按位宽比、{@code varchar}/{@code char} 比长度、
     * {@code decimal}/{@code numeric} 比精度。换族（{@code int → varchar}）证不出收窄，按 spec 不命中。
     */
    private static boolean isNarrowing(String oldTypeText, TypeSpec newType) {
        TypeSpec oldType = parseType(oldTypeText);
        if (oldType == null || newType == null) {
            return false;
        }
        String oldBase = oldType.base();
        String newBase = newType.base();
        if (oldBase.equals(newBase)) {
            return switch (oldBase) {
                case "varchar", "char" -> newType.first() != null
                        && oldType.first() != null
                        && newType.first() < oldType.first();
                case "decimal", "numeric" -> newType.first() != null
                        && oldType.first() != null
                        && newType.first() < oldType.first();
                default -> false;
            };
        }
        Integer oldWidth = INTEGER_WIDTH.get(oldBase);
        Integer newWidth = INTEGER_WIDTH.get(newBase);
        return oldWidth != null && newWidth != null && newWidth < oldWidth;
    }

    /**
     * 在快照里找旧类型：表名先精确、再忽略大小写、最后去掉库名前缀重试；列名同样忽略大小写。
     * 找不到（或类型文本为空）返回 {@code null}，调用方按"无法判定"处理。
     */
    private static String oldTypeOf(Map<String, Map<String, String>> oldTypes, String table, String column) {
        if (oldTypes == null || oldTypes.isEmpty() || column == null) {
            return null;
        }
        Map<String, String> columns = columnTypesOf(oldTypes, table);
        if (columns == null) {
            return null;
        }
        String type = columns.get(column);
        if (type == null) {
            for (Map.Entry<String, String> entry : columns.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                    type = entry.getValue();
                    break;
                }
            }
        }
        return type == null || type.isBlank() ? null : type;
    }

    private static Map<String, String> columnTypesOf(Map<String, Map<String, String>> oldTypes, String table) {
        if (table == null) {
            return null;
        }
        Map<String, String> columns = tableEntry(oldTypes, table);
        if (columns != null) {
            return columns;
        }
        int dot = table.lastIndexOf('.');
        return dot >= 0 ? tableEntry(oldTypes, table.substring(dot + 1)) : null;
    }

    private static Map<String, String> tableEntry(Map<String, Map<String, String>> oldTypes, String table) {
        Map<String, String> columns = oldTypes.get(table);
        if (columns != null) {
            return columns;
        }
        for (Map.Entry<String, Map<String, String>> entry : oldTypes.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(table)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ WARN

    /**
     * 提示类命中。{@code SELECT_*} 与"没有 limit"只看语句自身的顶层查询（CTE 的 {@code select} 也算）；
     * {@code like} / 隐式转换 / {@code order by rand()} 则是纯文本特征，写语句里同样要提示。
     */
    private static void warnings(String sql, Classification c, List<Tok> tokens, List<RiskHit> hits) {
        boolean selectQuery = "select".equals(statementVerb(sql, c));
        if (selectQuery && hasSelectStar(tokens)) {
            hits.add(warn(RULE_SELECT_STAR,
                    "select * 会取回全部列：建议显式列出需要的列，避免多余 IO 与表结构变更后出错"));
        }
        if (selectQuery && !c.hasLimit() && !hasAggregate(tokens)) {
            hits.add(warn(RULE_SELECT_WITHOUT_LIMIT,
                    "select 既没有 limit 也没有聚合：可能拉回全表，建议加上 limit"));
        }
        if (hasLeadingWildcardLike(tokens)) {
            hits.add(warn(RULE_LEADING_WILDCARD_LIKE,
                    "like 的模式以通配符开头：索引用不上，可能退化成全表扫描"));
        }
        Cast cast = implicitCast(tokens);
        if (cast != null) {
            hits.add(warn(RULE_IMPLICIT_CAST,
                    "列 " + cast.column() + " 与带引号的 '"
                            + cast.literal() + "' 比较：可能触发隐式类型转换而使索引失效"
                            + "（近似规则：只按文本模式识别，拿不到列类型）"));
        }
        if (hasOrderByRand(tokens)) {
            hits.add(warn(RULE_ORDER_BY_RAND,
                    "order by rand()：会为每行生成随机数再排序，大表上代价极高"));
        }
    }

    /**
     * 顶层投影里是否有 {@code *}。
     *
     * <p>{@code from} 之前的顶层 {@code *} 才可能是通配列，且要求它紧跟在 {@code select}/{@code distinct} 这类
     * 投影起点或 {@code ,}/{@code .} 之后、后面紧跟 {@code ,}/{@code from}/结尾——这样 {@code select a * b}
     * 的乘号不会被误判；{@code count(*)} 的星号在括号里（深度 &gt; 0），同样不算。
     */
    private static boolean hasSelectStar(List<Tok> tokens) {
        int from = indexOfTopLevelWord(tokens, "from");
        int end = from < 0 ? tokens.size() : from;
        for (int i = 0; i < end; i++) {
            Tok token = tokens.get(i);
            if (token.depth() != 0 || !token.isPunct("*")) {
                continue;
            }
            int anchorIndex = i - 1;
            while (anchorIndex >= 0) {
                Tok previous = tokens.get(anchorIndex);
                if (previous.kind() == Kind.WORD && SELECT_MODIFIERS.contains(previous.lower())) {
                    anchorIndex--;
                    continue;
                }
                break;
            }
            Tok anchor = tokenAt(tokens, anchorIndex);
            boolean projectionStart = anchor != null
                    && (anchor.isPunct(",") || anchor.isPunct(".")
                            || (anchor.kind() == Kind.WORD && "select".equals(anchor.lower())));
            Tok next = tokenAt(tokens, i + 1);
            boolean projectionEnd = next == null
                    || next.isPunct(",")
                    || (next.kind() == Kind.WORD && "from".equals(next.lower()));
            if (projectionStart && projectionEnd) {
                return true;
            }
        }
        return false;
    }

    /** 有聚合函数调用，或有顶层 {@code group by}：结果行数天然有限，"缺 limit"不再是问题。 */
    private static boolean hasAggregate(List<Tok> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Tok token = tokens.get(i);
            if (token.kind() != Kind.WORD) {
                continue;
            }
            if (AGGREGATE_FUNCTIONS.contains(token.lower()) && isPunctAt(tokens, i + 1, "(")) {
                return true;
            }
            if (token.depth() == 0 && "group".equals(token.lower()) && isWordAt(tokens, i + 1, "by")) {
                return true;
            }
        }
        return false;
    }

    /** {@code like '%x'} / {@code like '%x%'}：模式以 {@code %} 开头，前缀索引失效。 */
    private static boolean hasLeadingWildcardLike(List<Tok> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Tok token = tokens.get(i);
            if (token.kind() != Kind.WORD || !"like".equals(token.lower())) {
                continue;
            }
            int j = i + 1;
            if (isWordAt(tokens, j, "binary")) {
                j++;
            }
            Tok literal = tokenAt(tokens, j);
            if (literal != null && literal.kind() == Kind.LITERAL && literal.text().startsWith("%")) {
                return true;
            }
        }
        return false;
    }

    /** 标识符与带引号的纯数字/日期字面量做比较（任一方向）。 */
    private static Cast implicitCast(List<Tok> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Tok token = tokens.get(i);
            if (token.kind() != Kind.PUNCT) {
                continue;
            }
            String punct = token.text();
            if (!"=".equals(punct) && !"<".equals(punct) && !">".equals(punct) && !"!".equals(punct)) {
                continue;
            }
            // 双字符比较运算符（<>、<=、>=、!=）在 token 里是两段，按整体算，否则 <> 会把右侧读成 '>'。
            int operatorEnd = i;
            String next = textAt(tokens, i + 1);
            boolean twoChar = ("<".equals(punct) && (">".equals(next) || "=".equals(next)))
                    || (">".equals(punct) && "=".equals(next))
                    || ("!".equals(punct) && "=".equals(next));
            if (twoChar) {
                operatorEnd = i + 1;
            } else if ("!".equals(punct)) {
                continue; // 单独的 ! 不是比较运算符
            }
            Tok left = tokenAt(tokens, i - 1);
            Tok right = tokenAt(tokens, operatorEnd + 1);
            Cast cast = castBetween(left, right);
            if (cast == null) {
                cast = castBetween(right, left);
            }
            if (cast != null) {
                return cast;
            }
        }
        return null;
    }

    private static Cast castBetween(Tok column, Tok literal) {
        if (!isColumnReference(column) || !isNumberOrDateLiteral(literal)) {
            return null;
        }
        return new Cast(column.text(), literal.text());
    }

    /** 列引用：标识符，或含字母且不是关键字的裸词（纯数字是字面量）。 */
    private static boolean isColumnReference(Tok token) {
        if (token == null) {
            return false;
        }
        if (token.kind() == Kind.IDENTIFIER) {
            return true;
        }
        if (token.kind() != Kind.WORD || !token.text().chars().anyMatch(Character::isLetter)) {
            return false;
        }
        return !KEYWORDS.contains(token.lower());
    }

    private static boolean isNumberOrDateLiteral(Tok token) {
        if (token == null || token.kind() != Kind.LITERAL) {
            return false;
        }
        String text = token.text().strip();
        return NUMBER_LITERAL.matcher(text).matches() || DATE_LITERAL.matcher(text).matches();
    }

    /** {@code order by rand()}：随机排序，等价于整表排序后随机取。 */
    private static boolean hasOrderByRand(List<Tok> tokens) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Tok token = tokens.get(i);
            if (token.kind() != Kind.WORD || !"order".equals(token.lower()) || !isWordAt(tokens, i + 1, "by")) {
                continue;
            }
            int orderDepth = token.depth();
            for (int j = i + 2; j < tokens.size(); j++) {
                Tok next = tokens.get(j);
                if (next.depth() < orderDepth) {
                    break; // 子查询结束
                }
                if (next.depth() == orderDepth && next.kind() == Kind.WORD
                        && ORDER_CLAUSE_END.contains(next.lower())) {
                    break;
                }
                if (next.depth() == orderDepth && next.kind() == Kind.WORD
                        && "rand".equals(next.lower()) && isPunctAt(tokens, j + 1, "(")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ INFO

    private static void infos(String sql, List<Tok> tokens, List<RiskHit> hits) {
        String lowercaseKeyword = firstLowercaseKeyword(tokens);
        if (lowercaseKeyword != null) {
            hits.add(info(RULE_KEYWORD_NOT_UPPERCASE,
                    "SQL 关键字建议统一大写（例如出现了 " + lowercaseKeyword + "）：仅提示，不影响执行"));
        }
        if (hasMixedIndentation(sql)) {
            hits.add(info(RULE_INDENTATION_MESSY,
                    "行首缩进混用了空格与制表符：仅提示格式不统一"));
        }
    }

    private static String firstLowercaseKeyword(List<Tok> tokens) {
        for (Tok token : tokens) {
            if (token.kind() != Kind.WORD || !KEYWORDS.contains(token.lower())) {
                continue;
            }
            if (!token.text().equals(token.text().toUpperCase(Locale.ROOT))) {
                return token.text();
            }
        }
        return null;
    }

    /**
     * 行首缩进是否混用空格与制表符——本任务只做这一条可判定的风格规则，不做风格评分。
     *
     * <p>纯空格、纯制表符、无缩进都不算；只有同一行的行首同时出现两者才提示。
     */
    private static boolean hasMixedIndentation(String sql) {
        for (String line : sql.split("\n", -1)) {
            boolean spaces = false;
            boolean tabs = false;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == ' ') {
                    spaces = true;
                } else if (ch == '\t') {
                    tabs = true;
                } else {
                    break;
                }
            }
            if (spaces && tabs) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 命中构造

    private static void pin(List<RiskHit> hits, String ruleCode, String message) {
        hits.add(new RiskHit(ruleCode, Discipline.PIN_LEVEL, RiskLevel.L3, message));
    }

    private static RiskHit warn(String ruleCode, String message) {
        return new RiskHit(ruleCode, Discipline.WARN, null, message);
    }

    private static RiskHit info(String ruleCode, String message) {
        return new RiskHit(ruleCode, Discipline.INFO, null, message);
    }

    // ------------------------------------------------------------------ token 化

    /**
     * 把原文切成 token：注释、字符串字面量、反引号标识符各自成一体，不再参与关键字匹配。
     *
     * <p>与 {@link SqlText#stripLiteralsAndComments(String)} 判定等价，但保留了两个骨架丢掉的东西：字面量的
     * <b>内容</b>（{@code like '%x%'}、{@code code = '123'} 要看）与括号深度（顶层判断要用）。
     */
    private static List<Tok> tokenize(String sql) {
        List<Tok> tokens = new ArrayList<>();
        int depth = 0;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '#' || isDashComment(sql, i)) {
                i = skipLineComment(sql, i);
                continue;
            }
            if (ch == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (ch == '\'' || ch == '"') {
                StringBuilder content = new StringBuilder();
                i = readLiteral(sql, i, content);
                tokens.add(new Tok(Kind.LITERAL, content.toString(), content.toString(), depth));
                continue;
            }
            if (ch == '`') {
                StringBuilder content = new StringBuilder();
                i = readBackticked(sql, i, content);
                String text = content.toString();
                tokens.add(new Tok(Kind.IDENTIFIER, text, text.toLowerCase(Locale.ROOT), depth));
                continue;
            }
            if (ch == '(') {
                tokens.add(new Tok(Kind.PUNCT, "(", "(", depth));
                depth++;
                i++;
                continue;
            }
            if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
                tokens.add(new Tok(Kind.PUNCT, ")", ")", depth));
                i++;
                continue;
            }
            if (isNameChar(ch)) {
                int start = i;
                while (i < n && isNameChar(sql.charAt(i))) {
                    i++;
                }
                String text = sql.substring(start, i);
                tokens.add(new Tok(Kind.WORD, text, text.toLowerCase(Locale.ROOT), depth));
                continue;
            }
            tokens.add(new Tok(Kind.PUNCT, String.valueOf(ch), String.valueOf(ch), depth));
            i++;
        }
        return tokens;
    }

    /** MySQL 的行注释 {@code -- }：{@code --} 之后必须是空白，否则是减号。 */
    private static boolean isDashComment(String sql, int index) {
        return sql.charAt(index) == '-'
                && index + 1 < sql.length()
                && sql.charAt(index + 1) == '-'
                && (index + 2 >= sql.length() || Character.isWhitespace(sql.charAt(index + 2)));
    }

    private static int skipLineComment(String sql, int from) {
        int i = from;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int from) {
        int i = from + 2;
        while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
            i++;
        }
        return Math.min(i + 2, sql.length());
    }

    /** 读字符串字面量，返回结束下标；内容解掉反斜杠转义与 {@code ''} 双写。 */
    private static int readLiteral(String sql, int from, StringBuilder out) {
        char quote = sql.charAt(from);
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '\\' && i + 1 < n) {
                out.append(sql.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    out.append(quote);
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            out.append(ch);
            i++;
        }
        return n;
    }

    /** 读反引号标识符（内容已处理 {@code ``} 转义），返回结束下标；未闭合时吞到结尾。 */
    private static int readBackticked(String sql, int from, StringBuilder out) {
        int i = from + 1;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            if (ch == '`') {
                if (i + 1 < n && sql.charAt(i + 1) == '`') {
                    out.append('`');
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            out.append(ch);
            i++;
        }
        return n;
    }

    private static boolean isNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private static boolean isTypeName(String text) {
        if (text.isEmpty() || !Character.isLetter(text.charAt(0))) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isColumnName(Tok token) {
        if (token == null) {
            return false;
        }
        return token.kind() == Kind.IDENTIFIER
                || (token.kind() == Kind.WORD && token.text().chars().anyMatch(Character::isLetter));
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Integer parsePositive(String text) {
        if (text == null || !isDigits(text.strip())) {
            return null;
        }
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null; // 位数夸张到溢出：当作取不到长度，不当成收窄
        }
    }

    private static Tok tokenAt(List<Tok> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }

    private static boolean isWordAt(List<Tok> tokens, int index, String word) {
        Tok token = tokenAt(tokens, index);
        return token != null && token.kind() == Kind.WORD && token.lower().equals(word);
    }

    private static boolean isPunctAt(List<Tok> tokens, int index, String punct) {
        Tok token = tokenAt(tokens, index);
        return token != null && token.isPunct(punct);
    }

    private static String textAt(List<Tok> tokens, int index) {
        Tok token = tokenAt(tokens, index);
        return token == null ? null : token.text();
    }

    private static int indexOfTopLevelWord(List<Tok> tokens, String word) {
        for (int i = 0; i < tokens.size(); i++) {
            Tok token = tokens.get(i);
            if (token.depth() == 0 && token.kind() == Kind.WORD && token.lower().equals(word)) {
                return i;
            }
        }
        return -1;
    }

    private enum Kind {
        WORD,
        IDENTIFIER,
        LITERAL,
        PUNCT
    }

    private record Tok(Kind kind, String text, String lower, int depth) {

        boolean isPunct(String punct) {
            return kind == Kind.PUNCT && text.equals(punct);
        }
    }

    /** 解析出的类型规格：{@code varchar(64)} → base=varchar、first=64。 */
    private record TypeSpec(String base, Integer first, Integer second, String text) {
    }

    /** 一条 {@code modify}/{@code change} 子句：关键字、要查旧类型的列名、新类型。 */
    private record ColumnChange(String keyword, String column, TypeSpec newType) {
    }

    /** 一处隐式转换：列与字面量，只用于拼消息。 */
    private record Cast(String column, String literal) {
    }
}
