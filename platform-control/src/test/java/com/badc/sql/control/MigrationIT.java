package com.badc.sql.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.badc.sql.control.support.AbstractMySqlIT;

class MigrationIT extends AbstractMySqlIT {

    @Test
    void creates_all_baseline_tables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = database()",
                String.class);

        assertThat(tables).contains(
                "sys_user",
                "sys_system",
                "sys_module",
                "datasource",
                "conn_account",
                "module_datasource",
                "user_module_grant",
                "query_history",
                "audit_event");
    }

    @Test
    void seed_fixtures_grant_dev01_the_module_holding_datasources_101_and_102() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("seed/test-fixtures.sql"));
        DatabasePopulatorUtils.execute(populator, jdbc.getDataSource());

        assertThat(jdbc.queryForList("select login_name from sys_user order by id", String.class))
                .containsExactly("dev01", "ops01");
        assertThat(jdbc.queryForObject(
                "select role from sys_user where login_name = 'dev01'", String.class))
                .isEqualTo("DEVELOPER");
        assertThat(jdbc.queryForList("""
                select ds.id
                  from datasource ds
                  join module_datasource md on md.datasource_id = ds.id
                  join user_module_grant g on g.module_id = md.module_id
                  join sys_user u on u.id = g.user_id
                 where u.login_name = 'dev01'
                 order by ds.id
                """, Long.class)).containsExactly(101L, 102L);
        assertThat(jdbc.queryForObject(
                "select count(*) from module_datasource where datasource_id = 999", Integer.class))
                .isZero();
    }
}
