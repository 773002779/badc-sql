package com.badc.sql.control.risk;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一条语句里被识别出的 DDL 动作。
 *
 * <p><b>声明顺序即严重度顺序</b>：{@link #DROP_DATABASE} 最高，{@link #NONE} 最低。{@link EnumSet} 的自然
 * 顺序因此就是严重度顺序——{@code alter table} 一次带多个子句时，取其中最靠前的那个
 * （{@code add column b int, drop column a} 必须报 {@link #ALTER_DROP_COLUMN}，否则最严重的子句会被漏掉）。
 *
 * <p><b>结构性破坏</b>是其中七个动作（{@link #DROP_TABLE}、{@link #TRUNCATE}、{@link #RENAME_TABLE}、
 * {@link #ALTER_DROP_COLUMN}、{@link #ALTER_DROP_INDEX}、{@link #ALTER_CONVERT_CHARSET}、
 * {@link #DROP_PARTITION}），恒为 L3，无下调入口。{@link #ALTER_MODIFY} 不在其列：改列类型是否"收窄"要看
 * 列类型文本，由任务 4 的校验器判定，分类器只负责认出"这条 alter 改了列定义"。
 *
 * <p>两个动作同时覆盖多种写法：{@link #ALTER_DROP_INDEX} 既覆盖 alter 形式
 * （{@code alter table t drop index i}）也覆盖顶层形式（{@code drop index i on t}，二者等价）；
 * {@link #ALTER_MODIFY} 既覆盖 {@code modify}，也覆盖 {@code change [column]}（改名 + 改类型，收窄风险相同）。
 *
 * <p>注意"最严重动作"与"是否结构性破坏"不是一回事：{@link #ALTER_MODIFY} 按声明顺序排在
 * {@link #ALTER_CONVERT_CHARSET} 与 {@link #DROP_PARTITION} 之前，一条 alter 同时含二者时
 * {@link DdlAction#mostSevere} 会返回 {@link #ALTER_MODIFY}。因此 {@code Classification} 的
 * {@code structuralDamage} 是按整组动作算的（任一为结构性即为 {@code true}），否则改字符集这类恒 L3 的
 * 规则会被同一条 alter 里的 modify 掩掉。
 *
 * <p>{@link #DROP_DATABASE} 也不走等级：它在提交阶段直接 BLOCK，进不到审批，所以不属于上面那套 L3 集合。
 */
public enum DdlAction {

    DROP_DATABASE,
    DROP_TABLE,
    TRUNCATE,
    RENAME_TABLE,
    ALTER_DROP_COLUMN,
    ALTER_DROP_INDEX,
    ALTER_MODIFY,
    ALTER_CONVERT_CHARSET,
    DROP_PARTITION,
    CREATE_TABLE,
    CREATE_INDEX,
    ALTER_ADD_COLUMN,
    ALTER_ADD_UNIQUE_INDEX,
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
