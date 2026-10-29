package com.badc.sql.control.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * 执行窗口：窗口外提交要升一级审批，所以边界必须逐秒钉死——差一秒就是少一级审批。
 *
 * <p>默认窗口 {@code 20:00}–{@code 06:00} <b>跨零点</b>：区间是 {@code >= 20:00 || < 06:00}，
 * 而不是 {@code [20:00, 06:00)}（后者是个空区间，会让全天都算"窗口外"，把所有工单都升到 L3——
 * 方向虽然安全，但升档理由会变成噪音，运维很快就会习惯性忽略）。
 *
 * <p>"在不在窗口内"必须按 {@code window-zone} 的<b>本地时间</b>判，不能按 UTC 判：20:00 是业务高峰的开始，
 * 它对应 UTC 12:00。用 UTC 判会把整个夜间的升档窗口平移八小时。
 */
class ExecWindowTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 默认的跨零点窗口：20:00–06:00（Asia/Shanghai）。 */
    private final ExecWindow night = new ExecWindow(LocalTime.of(20, 0), LocalTime.of(6, 0), SHANGHAI);

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SHANGHAI).toInstant();
    }

    private static ExecWindow sameDay(LocalTime start, LocalTime end) {
        return new ExecWindow(start, end, SHANGHAI);
    }

    // ------------------------------------------------------------------ 跨零点边界

    @Test
    void the_window_starts_sharply_at_twenty() {
        assertThat(night.isWithin(at("2026-10-29T19:59:59"))).isFalse();
        assertThat(night.isWithin(at("2026-10-29T20:00:00"))).isTrue();
    }

    @Test
    void the_window_ends_sharply_at_six() {
        assertThat(night.isWithin(at("2026-10-30T05:59:59"))).isTrue();
        assertThat(night.isWithin(at("2026-10-30T06:00:00"))).isFalse();
    }

    @Test
    void the_middle_of_the_night_is_inside_and_the_middle_of_the_day_is_outside() {
        assertThat(night.isWithin(at("2026-10-30T00:00:00"))).isTrue();
        assertThat(night.isWithin(at("2026-10-30T03:30:00"))).isTrue();
        assertThat(night.isWithin(at("2026-10-29T12:00:00"))).isFalse();
        assertThat(night.isWithin(at("2026-10-29T23:59:59"))).isTrue();
    }

    // ------------------------------------------------------------------ 同一天内不跨零点

    @Test
    void a_same_day_window_is_half_open() {
        ExecWindow business = sameDay(LocalTime.of(9, 0), LocalTime.of(17, 0));

        assertThat(business.isWithin(at("2026-10-29T08:59:59"))).isFalse();
        assertThat(business.isWithin(at("2026-10-29T09:00:00"))).isTrue();
        assertThat(business.isWithin(at("2026-10-29T16:59:59"))).isTrue();
        assertThat(business.isWithin(at("2026-10-29T17:00:00"))).isFalse();
    }

    // ------------------------------------------------------------------ 时区

    /** 20:00 上海时间 = 12:00 UTC：按 UTC 判会得出"窗口外"，整夜升档全部错位八小时。 */
    @Test
    void the_window_is_measured_in_the_configured_zone_not_in_utc() {
        assertThat(night.isWithin(Instant.parse("2026-10-29T12:00:00Z"))).isTrue();
        assertThat(night.isWithin(Instant.parse("2026-10-29T11:59:59Z"))).isFalse();
        assertThat(night.isWithin(Instant.parse("2026-10-29T22:00:00Z"))).isFalse();
    }

    /** 同一时刻在不同时区里是不同的"墙上时间"：窗口判定必须跟着配置的时区走。 */
    @Test
    void the_same_instant_can_be_inside_or_outside_depending_on_the_zone() {
        Instant instant = Instant.parse("2026-10-29T12:00:00Z"); // 上海 20:00 / UTC 12:00

        assertThat(new ExecWindow(LocalTime.of(20, 0), LocalTime.of(6, 0), SHANGHAI).isWithin(instant)).isTrue();
        assertThat(new ExecWindow(LocalTime.of(20, 0), LocalTime.of(6, 0), ZoneId.of("UTC")).isWithin(instant))
                .isFalse();
    }

    // ------------------------------------------------------------------ 空窗口与非法参数

    /**
     * {@code start == end} 视为<b>空窗口</b>（{@code [t, t)}），而不是 24 小时全开。
     *
     * <p>配置写错时方向必须偏严：解释成"24 小时都在窗口内"会让所有语句少升一级（少一级审批），
     * 解释成空窗口只会多升一级、多一条终审确认。宁可多审一次。
     */
    @Test
    void a_zero_length_window_is_empty_rather_than_all_day() {
        ExecWindow empty = sameDay(LocalTime.of(20, 0), LocalTime.of(20, 0));

        assertThat(empty.isWithin(at("2026-10-29T20:00:00"))).isFalse();
        assertThat(empty.isWithin(at("2026-10-29T03:00:00"))).isFalse();
        assertThat(empty.isWithin(at("2026-10-29T12:00:00"))).isFalse();
    }

    @Test
    void a_window_without_a_zone_is_rejected_at_construction() {
        assertThatThrownBy(() -> new ExecWindow(LocalTime.of(20, 0), LocalTime.of(6, 0), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ExecWindow(null, LocalTime.of(6, 0), SHANGHAI))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ 与 RiskProperties 的接线

    /** 默认配置（20:00–06:00 / Asia/Shanghai）能被属性对象直接转成窗口。 */
    @Test
    void the_default_properties_define_the_night_window() {
        ExecWindow window = RiskProperties.defaults().toWindow();

        assertThat(window.isWithin(at("2026-10-29T20:00:00"))).isTrue();
        assertThat(window.isWithin(at("2026-10-30T05:59:59"))).isTrue();
        assertThat(window.isWithin(at("2026-10-30T06:00:00"))).isFalse();
        assertThat(window.isWithin(at("2026-10-29T19:59:59"))).isFalse();
    }

    /**
     * {@code relaxed()} 的窗口覆盖全天（右开端点 {@code 23:59} 除外，见下）。
     *
     * <p>它只是"硬规则不可放宽"的对照面，一整天里差最后一分钟不影响任何真实判定：窗口外的升档由调用方传入的
     * {@code withinWindow} 表达，{@code relaxed()} 存在的意义是"把所有可调项都推到极限"。
     */
    @Test
    void the_relaxed_properties_cover_the_whole_day() {
        ExecWindow window = RiskProperties.relaxed().toWindow();

        assertThat(window.isWithin(at("2026-10-29T00:00:00"))).isTrue();
        assertThat(window.isWithin(at("2026-10-29T12:00:00"))).isTrue();
        assertThat(window.isWithin(at("2026-10-29T23:58:59"))).isTrue();
        assertThat(window.isWithin(at("2026-10-29T23:59:00"))).isFalse();
    }
}
