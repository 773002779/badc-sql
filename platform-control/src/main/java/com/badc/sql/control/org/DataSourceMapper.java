package com.badc.sql.control.org;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code datasource} 的读写。查询结果含连接信息，只允许在服务端内部使用。
 */
@Repository
public class DataSourceMapper {

    private static final String COLUMNS =
            "id, system_id, name, db_name, db_type, host, port, charset, time_zone";
    private static final String SELECT_ALL = "select " + COLUMNS + " from datasource order by id";
    private static final String SELECT_BY_ID = "select " + COLUMNS + " from datasource where id = ?";
    private static final String INSERT = """
            insert into datasource (system_id, name, db_name, db_type, host, port, charset, time_zone)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_METADATA =
            "update datasource set name = ?, db_name = ? where id = ?";
    private static final String UPDATE_CONNECTION =
            "update datasource set host = ?, port = ?, charset = ?, time_zone = ? where id = ?";
    private static final String DELETE = "delete from datasource where id = ?";
    private static final String DELETE_MODULE_LINKS =
            "delete from module_datasource where datasource_id = ?";

    private static final RowMapper<DataSourceEntity> ROW_MAPPER = (rs, rowNum) -> new DataSourceEntity(
            rs.getLong("id"),
            rs.getLong("system_id"),
            rs.getString("name"),
            rs.getString("db_name"),
            rs.getString("db_type"),
            rs.getString("host"),
            rs.getInt("port"),
            rs.getString("charset"),
            rs.getString("time_zone"));

    private final JdbcTemplate jdbcTemplate;

    public DataSourceMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DataSourceEntity> findAll() {
        return jdbcTemplate.query(SELECT_ALL, ROW_MAPPER);
    }

    public Optional<DataSourceEntity> findById(long id) {
        return jdbcTemplate.query(SELECT_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    public long insert(DataSourceEntity datasource) {
        return InsertKey.insert(jdbcTemplate, INSERT,
                datasource.systemId(),
                datasource.name(),
                datasource.dbName(),
                datasource.dbType(),
                datasource.host(),
                datasource.port(),
                datasource.charset(),
                datasource.timeZone());
    }

    public int updateMetadata(long id, String name, String dbName) {
        return jdbcTemplate.update(UPDATE_METADATA, name, dbName, id);
    }

    public int updateConnection(long id, String host, int port, String charset, String timeZone) {
        return jdbcTemplate.update(UPDATE_CONNECTION, host, port, charset, timeZone, id);
    }

    public int delete(long id) {
        return jdbcTemplate.update(DELETE, id);
    }

    public int deleteModuleLinks(long datasourceId) {
        return jdbcTemplate.update(DELETE_MODULE_LINKS, datasourceId);
    }
}
