package com.badc.sql.common.dto;

/**
 * 执行写语句时使用的账号类型，同时是本条语句超时与 socket 读超时的选择依据。
 *
 * <p>账号本身不在这里：连接凭证由控制面加密保存、执行时解密成 {@link ConnConfig} 传给执行器。
 * 这个枚举只回答一个问题——"这条语句走 DML 账号还是 DDL 账号"，执行器据此取超时：
 * DML 300 秒、DDL 3600 秒（全局约束）。放 {@code platform-common} 是因为控制面（编排）与执行器
 * （执行）都要用它，避免任一侧反向依赖另一侧。
 */
public enum ExecAccountType {

    /** 数据变更语句：{@code insert} / {@code update} / {@code delete}，超时 300 秒。 */
    DML,

    /** 结构变更语句：{@code create} / {@code alter} / {@code drop} 等，超时 3600 秒。 */
    DDL
}
