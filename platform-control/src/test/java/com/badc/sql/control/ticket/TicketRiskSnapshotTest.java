package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.badc.sql.control.risk.Discipline;
import com.badc.sql.control.risk.RiskAssessment;
import com.badc.sql.control.risk.RiskHit;
import com.badc.sql.control.risk.RiskLevel;

/**
 * 整单风险快照哈希的纯单测：同一输入必须同哈希，等级/规则/SQL 任何一处变化都必须变。
 *
 * <p>任务 9/11 会拿同一个方法重算"当前风险"再与保存的哈希比对；这里把哈希的敏感度钉死，避免日后有人
 * 为了"看起来一样"把某个分量删掉，导致风险确认在执行前变成走过场。
 */
class TicketRiskSnapshotTest {

    private static RiskAssessment warnAssessment() {
        return new RiskAssessment(RiskLevel.L1,
                List.of(new RiskHit("SELECT_WITHOUT_LIMIT", Discipline.WARN, null, "缺少 limit")),
                "per-statement-hash-is-not-used");
    }

    private static RiskAssessment structuralAssessment() {
        return new RiskAssessment(RiskLevel.L3,
                List.of(new RiskHit("DROP_TABLE", Discipline.PIN_LEVEL, RiskLevel.L3, "结构性破坏")),
                "per-statement-hash-is-not-used");
    }

    @Test
    void the_same_inputs_produce_the_same_json_and_hash() {
        List<RiskAssessment> assessments = List.of(warnAssessment(), structuralAssessment());
        List<String> statements = List.of("update t set a=1", "drop table t");

        TicketRiskSnapshot.Snapshot snapshot = TicketRiskSnapshot.of(RiskLevel.L3, assessments);

        assertThat(TicketRiskSnapshot.json(snapshot)).isEqualTo(TicketRiskSnapshot.json(snapshot));
        assertThat(TicketRiskSnapshot.hash(snapshot, statements))
                .isEqualTo(TicketRiskSnapshot.hash(RiskLevel.L3, assessments, statements));
    }

    @Test
    void changing_one_statement_changes_the_hash() {
        List<RiskAssessment> assessments = List.of(warnAssessment());

        assertThat(TicketRiskSnapshot.hash(RiskLevel.L1, assessments, List.of("update t set a=1")))
                .isNotEqualTo(TicketRiskSnapshot.hash(RiskLevel.L1, assessments, List.of("update t set a=2")));
    }

    /** 整单仍是 L3，但某条语句从 L1 升到 L2：哈希必须变，否则确认会放行一条变重的语句。 */
    @Test
    void a_statement_escalation_changes_the_hash_even_when_the_ticket_level_does_not() {
        List<RiskAssessment> before = List.of(warnAssessment(), structuralAssessment());
        List<RiskAssessment> after = List.of(
                new RiskAssessment(RiskLevel.L2,
                        List.of(new RiskHit("WRITE_WITHOUT_WHERE", Discipline.PIN_LEVEL, RiskLevel.L3, "无 where")),
                        "per-statement-hash-is-not-used"),
                structuralAssessment());

        assertThat(TicketRiskSnapshot.hash(RiskLevel.L3, before, List.of("update t set a=1", "drop table t")))
                .isNotEqualTo(TicketRiskSnapshot.hash(RiskLevel.L3, after, List.of("update t set a=1", "drop table t")));
    }

    @Test
    void the_json_carries_every_statement_rule_with_its_discipline() {
        String json = TicketRiskSnapshot.json(
                TicketRiskSnapshot.of(RiskLevel.L3, List.of(structuralAssessment())));

        assertThat(json)
                .contains("\"level\":\"L3\"")
                .contains("\"seq\":1")
                .contains("DROP_TABLE")
                .contains("PIN_LEVEL")
                .contains("结构性破坏");
    }
}
