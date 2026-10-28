package com.badc.sql.control.risk;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一条语句里被识别出的 DDL 动作。
 *
 * <p><b>声明顺序即严重度顺序</b>：{@link #DROP_DATABASE} 最高，{@link #NONE} 最低。为了这条契约真的成立，
 * 七个结构性破坏动作全部排在非结构性动作之前——否则 {@code modify a int, convert to character set utf8mb4}
 * 会按声明序返回 {@link #ALTER_MODIFY}，而它并不是这条 alter 里最严重的动作。{@link EnumSet} 的自然顺序
 * 因此就是严重度顺序，{@code alter table} 一次带多个子句时取其中最靠前的那个
 * （{@code add column b int, drop column a} 必须报 {@link #ALTER_DROP_COLUMN}，否则最严重的子句会被漏掉）。
 *
 * <p><b>结构性破坏</b>是其中七个动作（{@link #DROP_TABLE}、{@link #TRUNCATE}、{@link #RENAME_TABLE}、
 * {@link #ALTER_DROP_COLUMN}、{@link #ALTER_DROP_INDEX}、{@link #ALTER_CONVERT_CHARSET}、
 * {@link #DROP_PARTITION}），恒为 L3，无下调入口。{@link #ALTER_MODIFY} 不在其列：改列类型是否"收窄"要看
 * 列类型文本，由任务 4 的校验器判定，分类器只负责认出"这条 alter 改了列定义"。
 *
 * <p>三个动作同时覆盖多种写法：{@link #ALTER_DROP_INDEX} 既覆盖 alter 形式
 * （{@code alter table t drop index i}）也覆盖顶层形式（{@code drop index i on t}，二者等价）；
 * {@link #ALTER_MODIFY} 既覆盖 {@code modify}，也覆盖 {@code change [column]}（改名 + 改类型，收窄风险相同）；
 * {@link #RENAME_TABLE} 除顶层 {@code rename table a to b} 外，也覆盖 {@code alter table a rename [to|as] b}，
 * {@link #DROP_PARTITION} 除 {@code alter table ... drop partition} 外也覆盖 {@code truncate partition}
 * （两者都是整分区数据丢失）。
 *
 * <p>{@link #DROP_DATABASE} 也不走等级：它在提交阶段直接 BLOCK，进不到审批，所以不属于上面那套 L3 集合
 * （因此 {@code structuralDamage()} 对它返回 {@code false}，这是<b>故意</b>的不对称）。
 */
public enum DdlAction {

    // ---- 结构性破坏：恒 L3 ----

    DROP_DATABASE,
    DROP_TABLE,
    TRUNCATE,
    RENAME_TABLE,
    ALTER_DROP_COLUMN,
    ALTER_DROP_INDEX,
    ALTER_CONVERT_CHARSET,
    DROP_PARTITION,

    // ---- 非结构性 DDL ----

    ALTER_MODIFY,
    ALTER_ADD_UNIQUE_INDEX,
    ALTER_ADD_COLUMN,
    CREATE_INDEX,
    CREATE_TABLE,
    OTHER_DDL,
    NONE;

    private static final Set<DdlAction> STRUCTURAL_DAMAGE = EnumSet.of(
            DROP_TABLE,
            TRUNCATE,
            RENAME_TABLE,
            ALTER_DROP_COLUMN,
            ALTER_DROP_INDEX,
            ALTER_CONVERT_CHARSET,
            DROP_PARTITION);

    /** 是否属于"结构性破坏"（恒 L3）。{@link #ALTER_MODIFY} 要等任务 4 判过收窄才算。 */
    public boolean isStructuralDamage() {
        return STRUCTURAL_DAMAGE.contains(this);
    }

    /**
     * 一组动作里最严重的那个；空集合（没有识别出任何动作）返回 {@link #NONE}。
     */
    public static DdlAction mostSevere(Collection<DdlAction> actions) {
        DdlAction mostSevere = null;
        for (DdlAction action : actions) {
            if (mostSevere == null || action.ordinal() < mostSevere.ordinal()) {
                mostSevere = action;
            }
        }
        return mostSevere == null ? NONE : mostSevere;
    }
}
