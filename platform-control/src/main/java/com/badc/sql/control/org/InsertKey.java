package com.badc.sql.control.org;

import java.sql.PreparedStatement;
import java.sql.Statement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * 执行 INSERT 并取回自增主键。
 *
 * <p>用 {@link Statement#RETURN_GENERATED_KEYS} 而不是再查一次 {@code last_insert_id()}：
 * 后者在连接池下可能落到别的连接上。
 */
final class InsertKey {

    private InsertKey() {
    }

    static long insert(JdbcTemplate jdbcTemplate, String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("insert 未返回自增主键: " + sql);
        }
        return key.longValue();
    }
}
