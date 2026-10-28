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
