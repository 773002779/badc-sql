package com.badc.sql.control.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.badc.sql.control.ticket.exec.StatementFingerprint;

/**
 * 语句指纹：同一数据源 + 同一组语句（按序）必须得到同一个指纹，任何一处变化都必须变。
 *
 * <p>这个指纹是执行前"语句未被篡改"的唯一依据，也是"审核后换库"的拦截点：它同时覆盖
 * 数据源、顺序与每条语句原文，三者缺一都会让任务 11 的校验漏掉一种篡改。
 */
class StatementFingerprintTest {

    @Test
    void the_same_statements_and_datasource_produce_the_same_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("update t set a = 1")))
                .isEqualTo(StatementFingerprint.of(101, List.of("update t set a = 1")));
    }

    @Test
    void changing_one_character_changes_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("update t set a = 1")))
                .isNotEqualTo(StatementFingerprint.of(101, List.of("update t set a = 2")));
    }

    @Test
    void reordering_statements_changes_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("a", "b")))
                .isNotEqualTo(StatementFingerprint.of(101, List.of("b", "a")));
    }

    /** 换库就是换工单：数据源变了指纹必须变，执行时才拦得住"审核后换库"。 */
    @Test
    void retargeting_another_datasource_changes_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("update t set a = 1")))
                .isNotEqualTo(StatementFingerprint.of(102, List.of("update t set a = 1")));
    }

    @Test
    void trailing_newlines_do_not_change_the_fingerprint() {
        assertThat(StatementFingerprint.of(101, List.of("select 1\n")))
                .isEqualTo(StatementFingerprint.of(101, List.of("select 1")));
    }
}
