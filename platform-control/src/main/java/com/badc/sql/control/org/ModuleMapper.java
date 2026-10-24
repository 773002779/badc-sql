package com.badc.sql.control.org;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code sys_module} 与 {@code module_datasource} 的读写。
 */
@Repository
public class ModuleMapper {

    private static final String SELECT_ALL = "select id, system_id, name from sys_module order by id";
    private static final String SELECT_BY_SYSTEM =
            "select id, system_id, name from sys_module where system_id = ? order by id";
    private static final String SELECT_BY_ID =
            "select id, system_id, name from sys_module where id = ?";
    private static final String INSERT = "insert into sys_module (system_id, name) values (?, ?)";
    private static final String UPDATE = "update sys_module set name = ? where id = ?";
    private static final String DELETE = "delete from sys_module where id = ?";
    private static final String MOUNT = """
            insert into module_datasource (module_id, datasource_id)
            values (?, ?)
            on duplicate key update id = id
            """;
    private static final String UNMOUNT =
            "delete from module_datasource where module_id = ? and datasource_id = ?";
    private static final String DELETE_DATASOURCE_LINKS =
            "delete from module_datasource where module_id = ?";
    private static final String DELETE_GRANTS =
            "delete from user_module_grant where module_id = ?";

    private static final RowMapper<ModuleEntity> ROW_MAPPER = (rs, rowNum) -> new ModuleEntity(
            rs.getLong("id"), rs.getLong("system_id"), rs.getString("name"));

    private final JdbcTemplate jdbcTemplate;

    public ModuleMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ModuleEntity> findAll() {
        return jdbcTemplate.query(SELECT_ALL, ROW_MAPPER);
    }

    public List<ModuleEntity> findAllBySystemId(long systemId) {
        return jdbcTemplate.query(SELECT_BY_SYSTEM, ROW_MAPPER, systemId);
    }

    public Optional<ModuleEntity> findById(long id) {
        return jdbcTemplate.query(SELECT_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    public long insert(long systemId, String name) {
        return InsertKey.insert(jdbcTemplate, INSERT, systemId, name);
    }

    public int update(long id, String name) {
        return jdbcTemplate.update(UPDATE, name, id);
    }

    public int delete(long id) {
        return jdbcTemplate.update(DELETE, id);
    }

    public void mount(long moduleId, long datasourceId) {
        jdbcTemplate.update(MOUNT, moduleId, datasourceId);
    }

    public int unmount(long moduleId, long datasourceId) {
        return jdbcTemplate.update(UNMOUNT, moduleId, datasourceId);
    }

    public int deleteDatasourceLinks(long moduleId) {
        return jdbcTemplate.update(DELETE_DATASOURCE_LINKS, moduleId);
    }

    public int deleteGrants(long moduleId) {
        return jdbcTemplate.update(DELETE_GRANTS, moduleId);
    }
}
