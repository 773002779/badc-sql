package com.badc.sql.control.risk;

/**
 * 一条（或一组被 {@code ;} 分隔的）语句的静态分类结果，只做<b>识别</b>，不含等级判定。
 *
 * @param multipleStatements 是否含多条语句（{@code SqlText.splitStatements} 切出多于一段）。为 {@code true}
 *                           时其余字段只描述第一条语句：它马上会被 BLOCK，后面的内容没有判定价值。
 * @param firstKeyword       第一条语句的首关键字，统一小写（{@code select} / {@code update} / …）；空输入为
 *                           {@code null}。{@code with} 开头的 CTE 仍报 {@code with}，其真实性质由
 *                           {@link #writeStatement()} 表达（{@code with ... delete} 是写语句）。
 * @param ddlAction          识别出的、最严重的一个 DDL 动作；普通 DML 与只读语句为 {@link DdlAction#NONE}。
 *                           {@link DdlAction} 的声明顺序即严重度顺序，结构性动作都排在非结构性动作之前。
 * @param structuralDamage   是否命中结构性破坏硬规则：语句里<b>任一</b>识别出的动作属于结构性破坏即为
 *                           {@code true}。重排后 {@code ddlAction()} 已经会返回结构性动作，这里按整组计算
 *                           是第二道保险（将来若往非结构性区间插入新动作，也不会掩掉已识别的结构性动作）。
 *                           {@link DdlAction#ALTER_MODIFY} 单独出现时为 {@code false}（收窄由任务 4 判）。
 * @param writeStatement     是否写语句：首关键字属于 {@code insert/update/delete/replace} 或 DDL
 *                           （{@code create/alter/drop/truncate/rename}）；{@code with} 开头的 CTE 按其
 *                           顶层动词判（{@code with ... insert/update/delete/replace} 是写）；
 *                           {@code explain analyze <dml>} 是写（会真的执行），{@code explain <dml>} 不是。
 * @param hasWhere           顶层（括号外、代码里）是否有 {@code where}。子查询里的 {@code where} 不算。
 * @param hasLimit           顶层（括号外、代码里）是否有 {@code limit}。子查询里的 {@code limit} 不算。
 * @param crossDatabase      是否有跨库表引用：只在<b>表位置</b>（{@code from/join/into/straight_join/using/
 *                           table} 之后，以及不属于 {@code on duplicate key update} 的 {@code update} 之后）
 *                           认 {@code db2.t_order} 形式；{@code where t_order.id = 1} 这类列限定不算。
 *                           别名与逗号分隔的多表列表会被继续检查。
 *                           反引号限定名检测不到，见 {@link StatementClassifier} 的已知局限。
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
