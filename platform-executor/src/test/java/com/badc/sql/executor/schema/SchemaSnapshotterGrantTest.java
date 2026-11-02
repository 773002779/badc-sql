package com.badc.sql.executor.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@code SHOW GRANTS} 行的解析。
 *
 * <p>为什么要单独测这个纯函数：它决定"快照能不能声称自己看见了全部触发器"。判错的两种方向后果差很多：
 * 误判成"有权限"→ 静默丢触发器（最坏）；误判成"没权限"→ 快照失败、工单打不动（烦人但安全）。
 * 所以这里的用例都取自 MySQL 8.0.16 的真实输出格式（含 {@code ALL PRIVILEGES} 被展开、
 * 角色授权行、{@code USAGE} 这种"只有连接权"的行）。
 */
class SchemaSnapshotterGrantTest {

    @Test
    void a_schema_scoped_trigger_grant_counts() {
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, TRIGGER ON `order_db`.* TO `u`@`%`",
                "order_db")).isTrue();
    }

    @Test
    void a_trigger_grant_on_another_schema_does_not_count() {
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT SELECT, TRIGGER ON `other_db`.* TO `u`@`%`", "order_db")).isFalse();
    }

    @Test
    void a_global_trigger_grant_counts_for_every_schema() {
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT SELECT, TRIGGER ON *.* TO `u`@`%`", "order_db")).isTrue();
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT SELECT, TRIGGER ON *.* TO `u`@`%`", "anything_else")).isTrue();
    }

    @Test
    void all_privileges_counts_even_though_mysql_usually_expands_it() {
        // 真机上 GRANT ALL 会被展开成显式列表，但 SQL 文本里也可能就是 ALL PRIVILEGES。
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT ALL PRIVILEGES ON `order_db`.* TO `u`@`%`", "order_db")).isTrue();
    }

    @Test
    void the_expanded_static_privilege_list_from_mysql_8_is_recognised() {
        // 8.0.16 实测：GRANT ALL ON *.* 会展开成一长串，其中包含 TRIGGER。
        String expanded = "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, RELOAD, SHUTDOWN, PROCESS, FILE, "
                + "REFERENCES, INDEX, ALTER, SHOW DATABASES, SUPER, CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, "
                + "REPLICATION SLAVE, REPLICATION CLIENT, CREATE VIEW, SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, "
                + "CREATE USER, EVENT, TRIGGER, CREATE TABLESPACE, CREATE ROLE, DROP ROLE ON *.* TO `u`@`%`";
        assertThat(SchemaSnapshotter.grantsTriggerOn(expanded, "order_db")).isTrue();
    }

    @Test
    void a_grant_without_trigger_does_not_count() {
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON `order_db`.* TO `u`@`%`", "order_db")).isFalse();
        assertThat(SchemaSnapshotter.grantsTriggerOn("GRANT USAGE ON *.* TO `u`@`%`", "order_db")).isFalse();
    }

    @Test
    void a_role_grant_is_not_a_privilege_grant() {
        // 角色授权行没有 ON 子句；把它当权限会让"经由角色拿到 TRIGGER"被误判成有权限。
        assertThat(SchemaSnapshotter.grantsTriggerOn("GRANT `ops_role`@`%` TO `u`@`%`", "order_db")).isFalse();
    }

    @Test
    void schema_name_comparison_ignores_case_but_not_the_name_itself() {
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT TRIGGER ON `Order_DB`.* TO `u`@`%`", "order_db")).isTrue();
        assertThat(SchemaSnapshotter.grantsTriggerOn(
                "GRANT TRIGGER ON `order_db_x`.* TO `u`@`%`", "order_db")).isFalse();
    }

    @Test
    void a_null_or_malformed_line_is_not_a_grant() {
        assertThat(SchemaSnapshotter.grantsTriggerOn(null, "order_db")).isFalse();
        assertThat(SchemaSnapshotter.grantsTriggerOn("", "order_db")).isFalse();
        assertThat(SchemaSnapshotter.grantsTriggerOn("TRIGGER ON `order_db`.*", "order_db")).isFalse();
    }
}
