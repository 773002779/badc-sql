package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;
import com.badc.sql.control.risk.StatementClassifier;
import com.badc.sql.control.ticket.TicketSubmitValidator.ValidatedStatement;

/**
 * 跨库表引用的拒绝。
 *
 * <p>这条闸存在的理由不是"跨库不好看"，而是<b>归一成裸名之后核对的就不是那张表了</b>：
 * {@code update other_db.t_order} 会拿当前库的 {@code t_order} 去取指纹、比漂移。于是要么静默放行
 * （当前库没这张表 → 记成"表不存在"），要么核对错表（当前库恰好同名）。两种都在界面上一片正常。
 *
 * <p>所以这里断言的都不只是"抛异常"，还包括消息里必须说清<b>哪条语句</b>、<b>哪个库</b>——否则运维只能
 * 拿着一句"跨库不支持"去猜是哪条语句。
 */
class TicketSubmitValidatorCrossDatabaseTest {

    private static final String DB = "order_db";
    private static final StatementClassifier CLASSIFIER = new StatementClassifier();

    @Test
    void an_unqualified_table_belongs_to_the_current_database() {
        assertThatCode(() -> check(List.of("update t_order set status = 0 where id = 1"), null, DB))
                .doesNotThrowAnyException();
    }

    @Test
    void a_table_qualified_with_the_current_database_is_allowed() {
        assertThatCode(() -> check(List.of("update order_db.t_order set status = 0 where id = 1"), null, DB))
                .as("显式限定到本库与裸名等价，不该被拦")
                .doesNotThrowAnyException();
        assertThatCode(() -> check(List.of("update `order_db`.`t_order` set status = 0 where id = 1"), null, DB))
                .doesNotThrowAnyException();
    }

    @Test
    void a_table_in_another_database_is_rejected_and_the_message_says_which() {
        assertThatThrownBy(() -> check(List.of("update other_db.t_order set status = 0 where id = 1"), null, DB))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("other_db.t_order")
                .hasMessageContaining(DB)
                .hasMessageContaining("第 1 条语句");
    }

    @Test
    void the_statement_position_is_reported_for_multi_statement_tickets() {
        assertThatThrownBy(() -> check(List.of(
                "update t_order set status = 0 where id = 1",
                "delete from other_db.t_archive where id = 2"), null, DB))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 2 条语句");
    }

    @Test
    void a_qualified_table_is_refused_when_the_datasource_has_no_database_name() {
        // 库名缺失时无法证明"这个限定名就是本库"，只能拒绝：猜错一次就是核对错表。
        assertThatThrownBy(() -> check(List.of("update order_db.t_order set status = 0 where id = 1"), null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未配置库名");
        assertThatThrownBy(() -> check(List.of("update order_db.t_order set status = 0 where id = 1"), null, "  "))
                .isInstanceOf(BizException.class);
    }

    @Test
    void rollback_sql_pointing_at_another_database_is_rejected_too() {
        // 回滚 SQL 不是装饰：它会在回滚单里被真的执行。
        assertThatThrownBy(() -> check(
                List.of("update t_order set status = 0 where id = 1"),
                "update other_db.t_order set status = 1 where id = 1", DB))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("回滚 SQL")
                .hasMessageContaining("other_db.t_order");
    }

    @Test
    void rollback_sql_on_the_current_database_passes() {
        assertThatCode(() -> check(
                List.of("update t_order set status = 0 where id = 1"),
                "update order_db.t_order set status = 1 where id = 1;\nupdate t_order set status = 2 where id = 3;",
                DB))
                .doesNotThrowAnyException();
    }

    @Test
    void a_statement_without_a_table_is_not_touched() {
        assertThatCode(() -> check(List.of("create table t_new (id bigint primary key)"), null, DB))
                .doesNotThrowAnyException();
    }

    private static void check(List<String> statements, String rollbackSql, String dbName) {
        List<ValidatedStatement> validated = new ArrayList<>();
        int seq = 1;
        for (String sql : statements) {
            validated.add(new ValidatedStatement(seq++, sql, CLASSIFIER.classify(sql)));
        }
        TicketSubmitValidator.rejectForeignDatabases(validated, rollbackSql, dbName);
    }
}
