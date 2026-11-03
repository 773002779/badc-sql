package com.badc.sql.common.dto;

import java.util.List;

/**
 * 两次结构快照之间的差异（FR-02 验收 1：「可查看与上一版的字段差异」）。
 *
 * <p>由执行器计算（只有它持有快照文件），控制面原样透出。设计上的两个取舍：
 * <ul>
 *   <li><b>以表为单位分组</b>：一次变更通常只碰一两张表，按表分组让"哪张表变了"一眼可见；</li>
 *   <li><b>每个字段带 {@code before}/{@code after} 的人类可读文本</b>，而不是结构化字段的并集。
 *       差异是给人看的：{@code varchar(32) NOT NULL COMMENT '备注'} → {@code varchar(64) NULL}
 *       比 {@code {type: {...}} } 更容易判断"这次变更对不对"。结构化版本在前端真有需求时再加。</li>
 * </ul>
 *
 * <p>{@link Change#UNCHANGED} 也会出现在结果里（表在两次快照中都存在且完全相同）：显式说"这张表没变"
 * 比让它从列表里消失更有用——工单常常同时碰到多张表，缺一行会让人以为漏抓了。
 *
 * @param tables 逐表差异；没有可比对的表时为空列表
 */
public record SchemaDiff(List<TableChange> tables) {

    public SchemaDiff {
        tables = List.copyOf(tables);
    }

    /** 一个对象（表、字段、索引、触发器）在两次快照之间的变化方向。 */
    public enum Change {

        /** 只出现在后一次快照里。 */
        ADDED,

        /** 只出现在前一次快照里。 */
        REMOVED,

        /** 两次都有，但内容不同。 */
        CHANGED,

        /** 两次完全一样。 */
        UNCHANGED
    }

    /**
     * 一张表的差异。
     *
     * @param table    表名
     * @param status   表的整体状态（任一字段变化即 {@code CHANGED}）
     * @param columns  列差异（含未变的列，便于前端完整渲染"变更前后"）
     * @param indexes  索引差异
     * @param triggers 触发器差异
     */
    public record TableChange(
            String table,
            Change status,
            List<FieldChange> columns,
            List<FieldChange> indexes,
            List<FieldChange> triggers) {

        public TableChange {
            columns = List.copyOf(columns);
            indexes = List.copyOf(indexes);
            triggers = List.copyOf(triggers);
        }
    }

    /**
     * 一个对象的差异。
     *
     * @param name   对象名（列名 / 索引名 / 触发器名）
     * @param status 变化方向
     * @param before 前一次快照里的样子；{@link Change#ADDED} 时为 {@code null}
     * @param after  后一次快照里的样子；{@link Change#REMOVED} 时为 {@code null}
     */
    public record FieldChange(String name, Change status, String before, String after) {
    }
}
