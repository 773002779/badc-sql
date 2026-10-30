package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedSubmission;

/**
 * 提交期静态校验的纯单测：不需要 Spring、不需要数据库。
 *
 * <p>集成测试已经覆盖"接口返回 400"这一层；这里钉的是<b>为什么</b>拒绝（消息、归类、归一规则），
 * 以及"DDL 只要方案、DML 只要回滚 SQL"这条容易被改回去的口径。
 */
class TicketSubmitValidatorTest {

    private static final Instant TOMORROW = Instant.parse("2026-12-31T12:00:00Z");

    @Test
    void a_ddl_ticket_needs_a_rollback_plan_but_not_rollback_sql() {
        ValidatedSubmission validated = TicketSubmitValidator.validate(command(
                List.of("create table t_new (id int)"), "从快照重建", null));

        assertThat(validated.hasDdl()).isTrue();
        assertThat(validated.hasDmlWrite()).isFalse();
        assertThat(validated.statements()).singleElement()
                .satisfies(statement -> assertThat(statement.statementType()).isEqualTo("CREATE"));
    }

    @Test
    void a_dml_ticket_needs_rollback_sql() {
        assertThatThrownBy(() -> TicketSubmitValidator.validate(command(
                List.of("update t_order set a=1 where id=1"), null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("回滚 SQL");
    }

    @Test
    void drop_table_requires_the_fixed_confirmation_phrase() {
        assertThatThrownBy(() -> TicketSubmitValidator.validate(command(
                List.of("drop table t_order"), "重建表", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(TicketSubmitValidator.DROP_TABLE_CONFIRMATION);
    }

    @Test
    void a_rollback_that_targets_another_table_is_refused() {
        assertThatThrownBy(() -> TicketSubmitValidator.validate(command(
                List.of("update t_order set a=1 where id=1"), null,
                "update t_other set a=0 where id=1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("回滚 SQL 未命中目标表");
    }

    /** 限定名与反引号在比较前归一：`order_db`.`t_order` 与 t_order 是同一张表。 */
    @Test
    void a_qualified_rollback_against_the_same_table_is_accepted() {
        ValidatedSubmission validated = TicketSubmitValidator.validate(command(
                List.of("update order_db.t_order set a=1 where id=1"), null,
                "update `order_db`.`t_order` set a=0 where id=1"));

        assertThat(validated.targetTables()).containsExactly("t_order");
    }

    @Test
    void a_statement_that_contains_a_semicolon_is_blocked_as_multiple_statements() {
        assertThatThrownBy(() -> TicketSubmitValidator.validate(command(
                List.of("update t_order set a=1; delete from t_order"), null,
                "update t_order set a=0 where id=1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("多个分号");
    }

    @Test
    void more_than_five_thousand_statements_are_refused() {
        List<String> statements = new ArrayList<>();
        for (int i = 0; i <= TicketSubmitValidator.MAX_STATEMENTS; i++) {
            statements.add("select 1");
        }

        assertThatThrownBy(() -> TicketSubmitValidator.validate(command(statements, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("5000");
    }

    @Test
    void statement_types_use_the_short_verb_from_the_first_keyword() {
        ValidatedSubmission validated = TicketSubmitValidator.validate(command(
                List.of("drop table t_order", "update t_order set a=1 where id=1"),
                TicketSubmitValidator.DROP_TABLE_CONFIRMATION,
                "update t_order set a=0 where id=1"));

        assertThat(validated.statements())
                .extracting(TicketSubmitValidator.ValidatedStatement::statementType)
                .containsExactly("DROP", "UPDATE");
    }

    /** BLOCK 先于必填项：不给标题也该先看到"drop database 被拒绝"，而不是"标题不能为空"。 */
    @Test
    void a_blocked_statement_is_reported_before_missing_metadata() {
        SubmitCommand command = new SubmitCommand(101L, null, null, null, null,
                List.of("drop database order_db"), null);

        assertThatThrownBy(() -> TicketSubmitValidator.validate(command))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("drop database");
    }

    @Test
    void a_change_reason_longer_than_the_limit_is_refused() {
        SubmitCommand command = new SubmitCommand(101L, "标题", "x".repeat(2001), null, null,
                List.of("select 1"), TOMORROW);

        assertThatThrownBy(() -> TicketSubmitValidator.validate(command))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("变更原因长度不能超过 2000");
    }

    @Test
    void a_rollback_plan_longer_than_the_limit_is_refused() {
        SubmitCommand command = new SubmitCommand(101L, "标题", "原因", "p".repeat(20001), null,
                List.of("create table t_new (id int)"), TOMORROW);

        assertThatThrownBy(() -> TicketSubmitValidator.validate(command))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("回滚方案长度不能超过 20000");
    }

    private static SubmitCommand command(List<String> statements, String rollbackPlan, String rollbackSql) {
        return new SubmitCommand(101L, "标题", "原因", rollbackPlan, rollbackSql, statements, TOMORROW);
    }
}
