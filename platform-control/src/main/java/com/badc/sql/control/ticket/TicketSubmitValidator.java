package com.badc.sql.control.ticket;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;
import com.badc.sql.common.sql.SqlText;
import com.badc.sql.control.risk.Classification;
import com.badc.sql.control.risk.DdlAction;
import com.badc.sql.control.risk.Discipline;
import com.badc.sql.control.risk.PolicyValidator;
import com.badc.sql.control.risk.RiskHit;
import com.badc.sql.control.risk.StatementClassifier;

/**
 * 提交期的静态校验：语句数、BLOCK、必填项、回滚方案/回滚 SQL。
 *
 * <p><b>纯计算</b>：不连库、不调执行器、不读时钟，所以可以在事务外先跑一遍（尽早失败），再在
 * {@link TicketStore} 的事务里<b>重跑一遍</b>关掉"外部调用期间状态已变"的窗口——两次跑的是同一个
 * 方法，不会出现两条口径。
 *
 * <h2>DML 与 DDL 的回滚要求不同</h2>
 * <ul>
 *   <li><b>DML</b>（{@code writeStatement && ddlAction == NONE}）：必须给 {@code rollbackSql}——数据改动
 *       只能靠反向 SQL 回滚，系统不生成。</li>
 *   <li><b>DDL</b>（{@code ddlAction != NONE}）：必须给 {@code rollbackPlan}（文本方案）。DDL 没有通用的
 *       反向 SQL，强求 {@code rollbackSql} 只会逼用户填一段假 SQL；任务 12 的回滚流程也明确"DDL 原单只有
 *       文本方案，回滚语句必须由发起人补写"。</li>
 *   <li>{@code drop table} 额外要求方案里出现固定确认语 {@value #DROP_TABLE_CONFIRMATION}：这一条把
 *       "我知道这表只能按快照重建"变成可审计的文本，而不是靠审核人猜。</li>
 * </ul>
 *
 * <p>以上三条要求可按工单类型关闭（{@link #validate(SubmitCommand, boolean)} 的 {@code requireRollback}）：
 * 运维临时执行转出的 {@code TEMP_EXEC} 工单没有回滚方案，它的安全网是结构快照与审计（§4.3-3）。
 *
 * <h2>回滚 SQL 的表名必须命中工单语句</h2>
 * <p>一条 {@code update t_other ...} 的回滚 SQL 挂在一张只改 {@code t_order} 的工单上，执行时会把另一张表
 * 改回去。这里把两边表名按"去反引号 → 取最后一段"归一后比较：{@code `order_db`.`t_order`} 与
 * {@code t_order} 是同一张表。
 *
 * <p>但归一<b>不等于</b>允许跨库：{@code other_db.t_order} 归一成 {@code t_order} 之后，取指纹与漂移比对
 * 用的都是当前库的同名表，等于核对错了对象。所以跨库限定名由
 * {@link #rejectForeignDatabases(List, String, String) 单独一道闸}拒掉——归一只是为了让同一张表的
 * 不同写法能对上。
 */
public final class TicketSubmitValidator {

    /** {@code drop table} 的回滚方案必须包含的固定确认语。 */
    public static final String DROP_TABLE_CONFIRMATION = "将从快照重建表结构";

    /** 单工单语句上限（全局约束）。 */
    public static final int MAX_STATEMENTS = 5000;

    /** 分类器与校验器都是纯函数、无状态，可安全复用（与 {@code RiskEngine} 的写法一致）。 */
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();
    private static final PolicyValidator VALIDATOR = new PolicyValidator();

    private static final int STATEMENT_TYPE_MAX_LENGTH = 32;

    /** 与 V3 的列宽/列类型对齐：title VARCHAR(200)、change_reason TEXT、rollback_plan TEXT。 */
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int CHANGE_REASON_MAX_LENGTH = 2000;
    private static final int ROLLBACK_PLAN_MAX_LENGTH = 20000;

    private TicketSubmitValidator() {
    }

    /**
     * 校验一张待提交的工单（按研发工单的口径：DML 必填回滚 SQL、DDL 必填回滚方案）。
     *
     * @throws BizException {@link ErrorCode#INVALID_ARGUMENT}：任意一条规则不满足
     */
    public static ValidatedSubmission validate(SubmitCommand command) {
        return validate(command, true);
    }

    /**
     * 校验一张待提交的工单，可选择是否要求回滚信息。
     *
     * <p><b>为什么允许关掉回滚要求</b>：运维临时执行不使用四眼原则，它的安全网是执行前结构快照与审计
     * （§4.3-3）。把"DML 必须填回滚 SQL"照搬到临时执行转出的工单上，只会逼提交人编一段不会有人执行的假 SQL
     * ——假数据比空值更坏，因为它看上去像一份可用的回滚方案。
     *
     * <p>关掉时<b>只跳过</b>三条：DML 必填 {@code rollbackSql}、DDL 必填 {@code rollbackPlan}、
     * {@code drop table} 的固定确认语。其余校验一条不少，尤其是：<b>回滚 SQL 一旦给了仍然要校验</b>
     * （{@link #validateRollbackSql}），不能因为类型特殊就放过一条指向别的表的回滚语句。
     *
     * @param requireRollback DML 是否必须给 {@code rollbackSql}、DDL 是否必须给 {@code rollbackPlan}
     *                        （{@code TEMP_EXEC} 传 {@code false}）
     * @throws BizException {@link ErrorCode#INVALID_ARGUMENT}：任意一条规则不满足
     */
    public static ValidatedSubmission validate(SubmitCommand command, boolean requireRollback) {
        if (command == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请求体不能为空");
        }

        List<String> statements = command.statements();
        if (statements.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "工单至少需要一条语句");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "单工单语句上限 " + MAX_STATEMENTS + " 条，当前 " + statements.size() + " 条");
        }

        // BLOCK 前置：一条被硬禁止的语句（drop database / 一条语句多个分号）先于任何必填项被拒绝，
        // 否则用户会先看到"标题不能为空"，改完标题再被拒一次。
        List<ValidatedStatement> validated = new ArrayList<>(statements.size());
        Set<String> targetTables = new LinkedHashSet<>();
        boolean hasDmlWrite = false;
        boolean hasDdl = false;
        boolean hasDropTable = false;
        int seq = 1;
        for (String sql : statements) {
            if (isBlank(sql)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "第 " + seq + " 条语句不能为空");
            }
            Classification classification = CLASSIFIER.classify(sql);
            throwOnBlock(sql, classification);
            validated.add(new ValidatedStatement(seq, sql, classification));

            String table = normalizeTable(classification.table());
            if (table != null) {
                targetTables.add(table);
            }
            DdlAction action = classification.ddlAction();
            hasDdl |= action != DdlAction.NONE;
            hasDmlWrite |= classification.writeStatement() && action == DdlAction.NONE;
            hasDropTable |= action == DdlAction.DROP_TABLE;
            seq++;
        }

        if (requireRollback) {
            if (hasDmlWrite && isBlank(command.rollbackSql())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "变更数据的工单必须填写回滚 SQL");
            }
            if (hasDdl && isBlank(command.rollbackPlan())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "DDL 变更必须填写回滚方案");
            }
            if (hasDropTable && !containsDropTableConfirmation(command.rollbackPlan())) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "drop table 的回滚方案必须包含确认语「" + DROP_TABLE_CONFIRMATION + "」");
            }
        }
        // 回滚 SQL 是"可选但一旦给了就校验"：跳过必填不等于放过一条指向别的表的回滚语句。
        if (!isBlank(command.rollbackSql())) {
            validateRollbackSql(command.rollbackSql(), targetTables);
        }

        // 必填与长度放最后：BLOCK / 回滚 SQL 的硬错误先返回；长度上限仍要在落库前拦住，
        // 否则 >64KB 的文本会在 INSERT 时炸成 500（V3 里 change_reason / rollback_plan 是 TEXT）。
        requireText(command.title(), "工单标题", TITLE_MAX_LENGTH);
        requireText(command.changeReason(), "变更原因", CHANGE_REASON_MAX_LENGTH);
        requireOptionalText(command.rollbackPlan(), "回滚方案", ROLLBACK_PLAN_MAX_LENGTH);
        if (command.expectedExecuteAt() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "期望执行时间不能为空");
        }

        return new ValidatedSubmission(List.copyOf(validated), List.copyOf(targetTables),
                hasDmlWrite, hasDdl, hasDropTable);
    }

    /** 任一 {@link Discipline#BLOCK} 命中都让提交失败；消息用命中自带的说明（含具体原因）。 */
    private static void throwOnBlock(String sql, Classification classification) {
        for (RiskHit hit : VALIDATOR.validate(sql, classification, null)) {
            if (hit.discipline() == Discipline.BLOCK) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, hit.message());
            }
        }
    }

    /**
     * 回滚 SQL 的静态检查：逐条不得 BLOCK，且每条作用表都必须命中工单语句里出现过的表。
     *
     * <p>用 {@link SqlText#splitStatements(String)} 切开而不是整段 {@code classify}：整段会被判成
     * {@code MULTI_STATEMENT} 直接 BLOCK，而"一串 insert"恰恰是 DML 回滚的正常形态。
     */
    private static void validateRollbackSql(String rollbackSql, Set<String> targetTables) {
        List<String> rollbackStatements = SqlText.splitStatements(rollbackSql);
        if (rollbackStatements.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "回滚 SQL 无法解析为有效语句");
        }
        for (String statement : rollbackStatements) {
            Classification classification = CLASSIFIER.classify(statement);
            throwOnBlock(statement, classification);
            String table = normalizeTable(classification.table());
            if (table == null || !targetTables.contains(table)) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT,
                        "回滚 SQL 未命中目标表：工单语句涉及 " + targetTables
                                + "，回滚语句为 " + (table == null ? "（无法识别表名）" : table));
            }
        }
    }

    /**
     * 拒绝"指向另一个库"的表引用（工单语句与回滚 SQL 都查）。
     *
     * <p><b>为什么这不是可选的额外校验</b>：本类会把限定名归一成裸表名（{@link #normalizeTable}），之后取
     * 指纹、比对漂移用的都是<b>当前库</b>里的同名表。于是一条 {@code update other_db.t_order ...} 会变成：
     * <ul>
     *   <li>当前库没有 {@code t_order} → 被记成"表不存在"，漂移闸把"没有前置结构"当成合法状态
     *       <b>静默放行</b>；</li>
     *   <li>当前库恰好有同名表 → 指纹落到<b>那张表</b>上，核对的是另一张表，而界面一切正常。</li>
     * </ul>
     * 两种都不能接受，所以跨库写在平台上一律拒绝，要求拆成独立工单在目标库上提交。
     *
     * <p>执行器侧还有一道同义的闸（{@code SchemaSnapshotter#resolveTable}），判据是连接<b>实际</b>指向的库，
     * 更权威；这里提前失败是为了给用户一句能读懂的 400，而不是绕一圈看到"结构快照抓取失败"。
     *
     * @param dbName 数据源指向的库名；为空表示配置缺失，此时任何限定名都拒绝（fail-closed）
     */
    public static void rejectForeignDatabases(
            List<ValidatedStatement> statements, String rollbackSql, String dbName) {
        for (ValidatedStatement statement : statements) {
            requireSameDatabase(statement.classification().table(), dbName,
                    "第 " + statement.seq() + " 条语句");
        }
        if (isBlank(rollbackSql)) {
            return;
        }
        for (String sql : SqlText.splitStatements(rollbackSql)) {
            requireSameDatabase(CLASSIFIER.classify(sql).table(), dbName, "回滚 SQL");
        }
    }

    private static void requireSameDatabase(String table, String dbName, String what) {
        String qualifier = qualifierOf(table);
        if (qualifier == null) {
            return; // 没写库名前缀：就是当前库
        }
        if (dbName == null || dbName.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    what + "用库名限定了表 " + table + "，但该数据源未配置库名，无法确认它指的就是这个库；"
                            + "请去掉库名前缀后重试。");
        }
        if (!qualifier.equalsIgnoreCase(dbName)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    what + "引用了另一个库的表 " + table + "（当前数据源库名 " + dbName + "）。"
                            + "平台只能对当前库的表做结构快照与漂移比对；跨库写请拆成独立工单在目标库上提交。");
        }
    }

    /** 表名里的库限定符；没有限定返回 {@code null}。 */
    private static String qualifierOf(String table) {
        if (table == null) {
            return null;
        }
        String cleaned = table.replace("`", "").strip();
        int dot = cleaned.lastIndexOf('.');
        return dot <= 0 || dot == cleaned.length() - 1 ? null : cleaned.substring(0, dot);
    }

    /**
     * 表名归一：去掉所有反引号，取最后一个 {@code .} 之后的部分。
     *
     * <p>{@code `order_db`.`t_order`} → {@code t_order}、{@code order_db.t_order} → {@code t_order}、
     * {@code `t_order`} → {@code t_order}。取不到返回 {@code null}。
     */
    static String normalizeTable(String table) {
        if (table == null) {
            return null;
        }
        String withoutBackticks = table.replace("`", "").strip();
        int dot = withoutBackticks.lastIndexOf('.');
        String name = dot >= 0 ? withoutBackticks.substring(dot + 1) : withoutBackticks;
        return name.isBlank() ? null : name;
    }

    private static boolean containsDropTableConfirmation(String rollbackPlan) {
        return rollbackPlan != null && rollbackPlan.contains(DROP_TABLE_CONFIRMATION);
    }

    private static void requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        if (maxLength > 0 && trimmed.length() > maxLength) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
    }

    private static void requireOptionalText(String value, String field, int maxLength) {
        if (value == null) {
            return;
        }
        if (value.strip().length() > maxLength) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    field + "长度不能超过 " + maxLength + " 个字符");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 一条已通过静态校验的语句。
     *
     * @param seq            工单内序号（从 1 开始）
     * @param sql            原文（未改写）
     * @param classification 分类结果，避免落库时再分类一次
     */
    public record ValidatedStatement(int seq, String sql, Classification classification) {

        /**
         * {@code ticket_statement.statement_type}：与 V3 列注释一致，存语句的短动词
         * （{@code SELECT|INSERT|UPDATE|DELETE|ALTER|CREATE|DROP|TRUNCATE}），而不是更细的
         * {@link DdlAction} 名。{@code with}/{@code explain} 开头时剥出真实顶层动词——把一条 CTE
         * {@code delete} 记成 {@code WITH} 会让详情页看不出它删了数据。
         */
        public String statementType() {
            String type = classification.firstKeyword();
            if (type == null) {
                return "UNKNOWN";
            }
            if ("with".equals(type) || "explain".equals(type)) {
                String verb = SqlText.firstTopLevelVerb(SqlText.stripLiteralsAndComments(sql));
                if (verb != null) {
                    type = verb;
                }
            }
            type = type.toUpperCase(Locale.ROOT);
            return type.length() <= STATEMENT_TYPE_MAX_LENGTH
                    ? type
                    : type.substring(0, STATEMENT_TYPE_MAX_LENGTH);
        }
    }

    /**
     * 一次校验的产物。
     *
     * @param statements  按序的语句
     * @param targetTables 语句涉及的表名（已归一：去反引号、去库前缀），按首次出现顺序去重；
     *                     既用于回滚 SQL 命中判定，也用于向执行器取结构指纹
     * @param hasDmlWrite 是否含数据写入（决定回滚 SQL 是否必填）
     * @param hasDdl      是否含 DDL（决定回滚方案是否必填）
     * @param hasDropTable 是否含 {@code drop table}
     */
    public record ValidatedSubmission(
            List<ValidatedStatement> statements,
            List<String> targetTables,
            boolean hasDmlWrite,
            boolean hasDdl,
            boolean hasDropTable) {

        public ValidatedSubmission {
            statements = List.copyOf(statements);
            targetTables = List.copyOf(targetTables);
        }
    }
}
