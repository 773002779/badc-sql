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
                "audit_event",
                // V3：工单写路径
                "ticket",
                "ticket_statement",
                "ticket_review",
                "ticket_event",
                "schema_snapshot",
                "risk_acknowledgement",
                "datasource_exec_lock",
                // V4：通知
                "notification",
                "notification_switch",
                // V5：授权申请
                "grant_request");
    }

    @Test
    void ticket_type_check_allows_temp_exec() {
        // 运维临时执行的 L3 变更要落成一张 ticket_type = 'TEMP_EXEC' 的工单（V6）。
        // 只断言约束定义、不插一行数据：ticket 有十几个 NOT NULL 列，为了一条断言去凑齐它们，
        // 等于在迁移测试里编造一条假的业务写路径（列一改就得跟着改）。约束定义才是唯一事实来源。
        // 另：CHECK 自 MySQL 8.0.16 起才真正生效，更低版本会静默忽略——那时 TEMP_EXEC 能写进去，
        // 但'临时执行'以外任意拼写也一样能写进去，所以这里必须直接查 check_clause。
        assertThat(jdbc.queryForObject("""
                select check_clause
                  from information_schema.check_constraints
                 where constraint_schema = database()
                   and constraint_name = 'ck_ticket_ticket_type'
                """, String.class)).contains("NORMAL").contains("ROLLBACK").contains("TEMP_EXEC");
    }

    @Test
    void grant_request_declares_the_columns_and_status_check_the_mappers_expect() {
        // 列宽是"理由/意见写入被拒"的边界：varchar 太短会让一次正常申请在严格模式下 500。
        assertThat(columnType("grant_request", "can_export")).isEqualTo("tinyint(1)");
        assertThat(columnType("grant_request", "reason")).isEqualTo("varchar(500)");
        assertThat(columnType("grant_request", "status")).isEqualTo("varchar(16)");
        assertThat(columnType("grant_request", "review_comment")).isEqualTo("varchar(500)");
        assertThat(columnType("grant_request", "created_at")).isEqualTo("datetime(3)");
        assertThat(columnType("grant_request", "reviewed_at")).isEqualTo("datetime(3)");
        assertThat(dataType("grant_request", "user_id")).isEqualTo("bigint");
        assertThat(dataType("grant_request", "reviewer_id")).isEqualTo("bigint");

        // CHECK 必须真的存在：MySQL 8.0.16 起才生效，低于它会被静默忽略——那时非法状态能写进去，
        // 状态机就成了唯一防线。
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from information_schema.table_constraints
                 where table_schema = database()
                   and table_name = 'grant_request'
                   and constraint_type = 'CHECK'
                """, Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("""
                select check_clause
                  from information_schema.check_constraints
                 where constraint_schema = database()
                   and constraint_name = 'ck_grant_request_status'
                """, String.class)).contains("PENDING").contains("APPROVED")
                .contains("REJECTED").contains("CANCELLED");
    }

    @Test
    void notification_tables_declare_the_column_types_the_mappers_expect() {
        // 列类型是后续 mapper 与枚举的契约：varchar 太短会静默截断标题/正文，
        // 时间列若退化成 TIMESTAMP 则在会话时区上二次换算，破坏"时间列一律存 UTC"的约定。
        assertThat(columnType("notification", "event_type")).isEqualTo("varchar(48)");
        assertThat(columnType("notification", "channel")).isEqualTo("varchar(16)");
        assertThat(columnType("notification", "status")).isEqualTo("varchar(16)");
        assertThat(columnType("notification", "title")).isEqualTo("varchar(200)");
        assertThat(columnType("notification", "body")).isEqualTo("varchar(2000)");
        assertThat(columnType("notification", "last_error")).isEqualTo("varchar(500)");
        assertThat(columnType("notification", "created_at")).isEqualTo("datetime(3)");
        assertThat(columnType("notification", "sent_at")).isEqualTo("datetime(3)");
        assertThat(columnType("notification_switch", "event_type")).isEqualTo("varchar(48)");
        assertThat(columnType("notification_switch", "enabled")).isEqualTo("tinyint(1)");
        assertThat(columnType("notification_switch", "updated_at")).isEqualTo("datetime(3)");

        // 整数列用 data_type 断言：8.0.16 的 column_type 仍带显示宽度（bigint(20)），
        // 8.0.17 起去掉了宽度，用 column_type 会让测试卡在特定小版本上。
        assertThat(dataType("notification", "recipient_id")).isEqualTo("bigint");
        assertThat(dataType("notification", "attempts")).isEqualTo("int");
        assertThat(dataType("notification_switch", "system_id")).isEqualTo("bigint");
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                select column_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = ?
                   and column_name = ?
                """, String.class, table, column);
    }

    private String dataType(String table, String column) {
        return jdbc.queryForObject("""
                select data_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = ?
                   and column_name = ?
                """, String.class, table, column);
    }

    @Test
    void database_session_is_utc_so_current_timestamp_defaults_are_utc() {
        // 列默认值 CURRENT_TIMESTAMP(3) 取会话时区；会话必须是 UTC，否则写入的是服务器本地时间。
        assertThat(jdbc.queryForObject("select @@session.time_zone", String.class))
                .isEqualTo("+00:00");
        assertThat(jdbc.queryForObject(
                "select timestampdiff(second, utc_timestamp(), now())", Long.class))
                .isZero();
    }

    @Test
    void seed_fixtures_grant_dev01_the_module_holding_datasources_101_and_102() {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("seed/test-fixtures.sql"));
        DatabasePopulatorUtils.execute(populator, jdbc.getDataSource());

        assertThat(jdbc.queryForList("select login_name from sys_user order by id", String.class))
                .containsExactly("dev01", "ops01", "admin01");
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
