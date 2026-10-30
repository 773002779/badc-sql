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
 * <h2>回滚 SQL 的表名必须命中工单语句</h2>
 * <p>一条 {@code update t_other ...} 的回滚 SQL 挂在一张只改 {@code t_order} 的工单上，执行时会把另一张表
 * 改回去。这里把两边表名按"去反引号 → 取最后一段"归一后比较：{@code `order_db`.`t_order`} 与
 * {@code t_order} 是同一张表；{@code db2.t_order} 也归一成 {@code t_order}（跨库写在风险上是 L3，
 * 这里的归一只是让常见限定名不误报回滚 SQL）。
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

    private TicketSubmitValidator() {
    }

    /**
     * 校验一张待提交的工单。
     *
     * @throws BizException {@link ErrorCode#INVALID_ARGUMENT}：任意一条规则不满足
     */
    public static ValidatedSubmission validate(SubmitCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请求体不能为空");
        }
        requireText(command.title(), "工单标题", 200);
        requireText(command.changeReason(), "变更原因", 0);
        if (command.expectedExecuteAt() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "期望执行时间不能为空");
        }

        List<String> statements = command.statements();
        if (statements.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "工单至少需要一条语句");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "单工单语句上限 " + MAX_STATEMENTS + " 条，当前 " + statements.size() + " 条");
        }

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
        if (!isBlank(command.rollbackSql())) {
            validateRollbackSql(command.rollbackSql(), targetTables);
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
         * {@code ticket_statement.statement_type}：DDL 用动作短名（{@code DROP_TABLE}、{@code ALTER_TABLE}
         * 这类），DML 用首关键字大写。{@code with}/{@code explain} 开头时剥出真实顶层动词——把一条 CTE
         * {@code delete} 记成 {@code WITH} 会让详情页看不出它删了数据。
         */
        public String statementType() {
            String type;
            if (classification.ddlAction() != DdlAction.NONE) {
                type = classification.ddlAction().name();
            } else {
                String first = classification.firstKeyword();
                if (first == null) {
                    type = "UNKNOWN";
                } else if ("with".equals(first) || "explain".equals(first)) {
                    String verb = SqlText.firstTopLevelVerb(SqlText.stripLiteralsAndComments(sql));
                    type = (verb == null ? first : verb).toUpperCase(Locale.ROOT);
                } else {
                    type = first.toUpperCase(Locale.ROOT);
                }
            }
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
