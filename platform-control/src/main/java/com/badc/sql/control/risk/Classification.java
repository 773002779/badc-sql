package com.badc.sql.control.risk;

/**
 * 一条（或一组被 {@code ;} 分隔的）语句的静态分类结果，只做<b>识别</b>，不含等级判定。
 *
 * @param multipleStatements 是否含多条语句（{@code SqlText.splitStatements} 切出多于一段）。为 {@code true}
 *                           时其余字段只描述第一条语句：它马上会被 BLOCK，后面的内容没有判定价值。
 * @param firstKeyword       第一条语句的首关键字，统一小写（{@code select} / {@code update} / …）；空输入为
 *                           {@code null}。
 * @param ddlAction          识别出的、最严重的一个 DDL 动作；普通 DML 与只读语句为 {@link DdlAction#NONE}。
 * @param structuralDamage   是否命中结构性破坏硬规则。来自 {@link DdlAction#isStructuralDamage()}，因此
 *                           {@link DdlAction#ALTER_MODIFY} 单独出现时为 {@code false}（收窄由任务 4 判）。
 * @param writeStatement     是否写语句：首关键字属于 {@code insert/update/delete/replace} 或 DDL
 *                           （{@code create/alter/drop/truncate/rename}）。
 * @param hasWhere           顶层（括号外、代码里）是否有 {@code where}。子查询里的 {@code where} 不算。
 * @param hasLimit           顶层（括号外、代码里）是否有 {@code limit}。子查询里的 {@code limit} 不算。
 * @param crossDatabase      是否疑似跨库引用（{@code db2.t_order} 形式）。尽力而为，见
 *                           {@link StatementClassifier} 的已知局限。
 * @param table              语句作用的目标表，取自<b>原文</b>（剥离后的骨架里表名是空格），反引号已去掉；
 *                           取不到为 {@code null}。
 */
public record Classification(
        boolean multipleStatements,
        String firstKeyword,
        DdlAction ddlAction,
        boolean structuralDamage,
        boolean writeStatement,
        boolean hasWhere,
        boolean hasLimit,
        boolean crossDatabase,
        String table) {
}
