package com.badc.sql.control.org;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code conn_account} 的读写。表里只保存密文；明文仅在 {@code CredentialCipher} 解密后短暂存在于内存。
 */
@Repository
public class ConnAccountMapper {

    private static final String SELECT_BY_DATASOURCE_AND_TYPE = """
            select id, datasource_id, account_type, username, password_cipher
              from conn_account
             where datasource_id = ? and account_type = ?
            """;
    private static final String UPSERT = """
            insert into conn_account (datasource_id, account_type, username, password_cipher)
            values (?, ?, ?, ?)
            on duplicate key update username = values(username), password_cipher = values(password_cipher)
            """;
    private static final String DELETE =
            "delete from conn_account where datasource_id = ? and account_type = ?";
    private static final String DELETE_BY_DATASOURCE = "delete from conn_account where datasource_id = ?";

    private static final RowMapper<ConnAccountEntity> ROW_MAPPER = (rs, rowNum) -> new ConnAccountEntity(
            rs.getLong("id"),
            rs.getLong("datasource_id"),
            rs.getString("account_type"),
            rs.getString("username"),
            rs.getString("password_cipher"));

    private final JdbcTemplate jdbcTemplate;

    public ConnAccountMapper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ConnAccountEntity> findByDatasourceAndType(long datasourceId, String accountType) {
        return jdbcTemplate.query(SELECT_BY_DATASOURCE_AND_TYPE, ROW_MAPPER, datasourceId, accountType)
                .stream().findFirst();
    }

    public int upsert(long datasourceId, String accountType, String username, String passwordCipher) {
        return jdbcTemplate.update(UPSERT, datasourceId, accountType, username, passwordCipher);
    }

    public int delete(long datasourceId, String accountType) {
        return jdbcTemplate.update(DELETE, datasourceId, accountType);
    }

    public int deleteByDatasource(long datasourceId) {
        return jdbcTemplate.update(DELETE_BY_DATASOURCE, datasourceId);
    }
}
