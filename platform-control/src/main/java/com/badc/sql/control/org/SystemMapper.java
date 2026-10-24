package com.badc.sql.control.org;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code sys_system} 的读写。
 */
@Repository
public class SystemMapper {

    private static final String SELECT_ALL = "select id, name from sys_system order by id";
    private static final String SELECT_BY_ID = "select id, name from sys_system where id = ?";
    private static final String INSERT = "insert into sys_system (name) values (?)";
    private static final String UPDATE = "update sys_system set name = ? where id = ?";
    private static final String DELETE = "delete from sys_system where id = ?";
    private static final String COUNT_MODULES = "select count(*) from sys_module where system_id = ?";
    private static final String COUNT_DATASOURCES = "select count(*) from datasource where system_id = ?";

    private static final RowMapper<SystemEntity> ROW_MAPPER =
            (rs, rowNum) -> new SystemEntity(rs.getLong("id"), rs.getString("name"));

    private final JdbcTemplate jdbcTemplate;

    public SystemMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SystemEntity> findAll() {
        return jdbcTemplate.query(SELECT_ALL, ROW_MAPPER);
    }

    public Optional<SystemEntity> findById(long id) {
        return jdbcTemplate.query(SELECT_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    public long insert(String name) {
        return InsertKey.insert(jdbcTemplate, INSERT, name);
    }

    public int update(long id, String name) {
        return jdbcTemplate.update(UPDATE, name, id);
    }

    public int delete(long id) {
        return jdbcTemplate.update(DELETE, id);
    }

    public int countModules(long systemId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_MODULES, Integer.class, systemId);
        return count == null ? 0 : count;
    }

    public int countDatasources(long systemId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_DATASOURCES, Integer.class, systemId);
        return count == null ? 0 : count;
    }
}
