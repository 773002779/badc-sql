package com.badc.sql.common;

/**
 * 受支持的数据库大版本。
 *
 * <p>版本差异必须在运行时探测后判定，不允许在业务代码里写死实例版本。
 */
public enum DbVersion {
    MYSQL_5_7,
    MYSQL_8_0
}
