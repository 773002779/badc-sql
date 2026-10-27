package com.badc.sql.control.query;

import com.badc.sql.common.dto.ConnConfig;
import com.badc.sql.common.dto.RawResult;

/**
 * 调用执行器的出向接口。
 *
 * <p>抽成接口是为了让控制面的测试能替换成桩——控制面要验证的是鉴权、可见性、只读白名单与
 * LIMIT 注入，不是 SQL 执行本身。
 */
public interface ExecutorClient {

    RawResult query(ConnConfig conn, String sql, int maxRows);
}
