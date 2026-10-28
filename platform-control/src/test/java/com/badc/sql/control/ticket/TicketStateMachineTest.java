package com.badc.sql.control.ticket;

import static com.badc.sql.control.ticket.TicketStatus.APPROVED;
import static com.badc.sql.control.ticket.TicketStatus.CANCELLED;
import static com.badc.sql.control.ticket.TicketStatus.DRAFT;
import static com.badc.sql.control.ticket.TicketStatus.EXECUTING;
import static com.badc.sql.control.ticket.TicketStatus.EXPIRED;
import static com.badc.sql.control.ticket.TicketStatus.FAILED;
import static com.badc.sql.control.ticket.TicketStatus.NEEDS_MANUAL_CONFIRM;
import static com.badc.sql.control.ticket.TicketStatus.PARTIAL_FAILED;
import static com.badc.sql.control.ticket.TicketStatus.REJECTED;
import static com.badc.sql.control.ticket.TicketStatus.REVIEWING_L1;
import static com.badc.sql.control.ticket.TicketStatus.REVIEWING_L2;
import static com.badc.sql.control.ticket.TicketStatus.ROLLED_BACK;
import static com.badc.sql.control.ticket.TicketStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.badc.sql.common.error.BizException;
import com.badc.sql.common.error.ErrorCode;

/**
 * 工单状态机只允许计划里列出的迁移。
 *
 * <p>对照表逐行抄自计划（DRAFT / REVIEWING_L1 / REVIEWING_L2 / APPROVED / EXECUTING /
 * SUCCEEDED / PARTIAL_FAILED 七个源状态，共 17 条边），实现与对照表必须**双向**一致：
 * 少一条会漏掉合法路径，多一条会放过非法路径（如跳过终审、把执行中的单直接取消）。
 */
class TicketStateMachineTest {

    @Test
    void reviewing_l2_cannot_be_skipped_by_a_direct_approve() {
        assertThat(TicketStateMachine.canTransition(REVIEWING_L1, APPROVED)).isTrue();   // L1/L2 一级
        assertThat(TicketStateMachine.canTransition(DRAFT, APPROVED)).isFalse();
    }

    @Test
    void an_executing_ticket_cannot_be_cancelled_backwards() {
        assertThat(TicketStateMachine.canTransition(EXECUTING, CANCELLED)).isFalse();
    }

    @Test
    void only_the_documented_transitions_are_allowed() {
        Map<TicketStatus, Set<TicketStatus>> documented = documentedTransitions();

        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus to : TicketStatus.values()) {
                boolean expected = documented.getOrDefault(from, Set.of()).contains(to);
                assertThat(TicketStateMachine.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void the_documented_table_has_the_expected_number_of_edges() {
        // 七个源状态的边数：2 + 4 + 2 + 3 + 4 + 1 + 1 = 17。
        // 这条断言盯的是"对照表本身别被改动"，避免对照表与实现一起漂移后测试仍然全绿。
        assertThat(documentedTransitions().values().stream().mapToInt(Set::size).sum()).isEqualTo(17);
    }

    @Test
    void assert_transition_accepts_a_documented_edge() {
        TicketStateMachine.assertTransition(REVIEWING_L1, APPROVED);
        TicketStateMachine.assertTransition(REVIEWING_L2, APPROVED);
        TicketStateMachine.assertTransition(APPROVED, EXECUTING);
        TicketStateMachine.assertTransition(SUCCEEDED, ROLLED_BACK);
    }

    @Test
    void assert_transition_rejects_an_undocumented_edge_with_conflict() {
        BizException thrown = catchThrowableOfType(
                () -> TicketStateMachine.assertTransition(DRAFT, SUCCEEDED), BizException.class);

        assertThat(thrown.code()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(thrown).hasMessage("DRAFT 不能迁移到 SUCCEEDED");
    }

    @Test
    void a_null_status_is_never_a_valid_transition() {
        assertThat(TicketStateMachine.canTransition(null, DRAFT)).isFalse();
        assertThat(TicketStateMachine.canTransition(DRAFT, null)).isFalse();
        assertThat(TicketStateMachine.canTransition(null, null)).isFalse();

        BizException thrown = catchThrowableOfType(
                () -> TicketStateMachine.assertTransition(null, DRAFT), BizException.class);
        assertThat(thrown.code()).isEqualTo(ErrorCode.CONFLICT);
    }

    /** 终态不再迁移（ROLLED_BACK 之后既不回滚也不重跑，只能新建工单）。 */
    @Test
    void terminal_statuses_have_no_outgoing_transitions() {
        for (TicketStatus terminal : EnumSet.of(
                FAILED, NEEDS_MANUAL_CONFIRM, REJECTED, EXPIRED, CANCELLED, ROLLED_BACK)) {
            for (TicketStatus to : TicketStatus.values()) {
                assertThat(TicketStateMachine.canTransition(terminal, to))
                        .as("%s -> %s", terminal, to)
                        .isFalse();
            }
        }
    }

    private static Map<TicketStatus, Set<TicketStatus>> documentedTransitions() {
        Map<TicketStatus, Set<TicketStatus>> transitions = new EnumMap<>(TicketStatus.class);
        transitions.put(DRAFT, EnumSet.of(REVIEWING_L1, CANCELLED));
        transitions.put(REVIEWING_L1, EnumSet.of(REVIEWING_L2, APPROVED, REJECTED, CANCELLED));
        transitions.put(REVIEWING_L2, EnumSet.of(APPROVED, REJECTED));
        transitions.put(APPROVED, EnumSet.of(EXECUTING, EXPIRED, CANCELLED));
        transitions.put(EXECUTING, EnumSet.of(SUCCEEDED, PARTIAL_FAILED, FAILED, NEEDS_MANUAL_CONFIRM));
        transitions.put(SUCCEEDED, EnumSet.of(ROLLED_BACK));
        transitions.put(PARTIAL_FAILED, EnumSet.of(ROLLED_BACK));
        return transitions;
    }
}
