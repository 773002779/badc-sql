package com.badc.sql.control.risk;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 执行窗口：判断一个时刻是否落在配置的业务窗口内（窗口外提交的工单要升一级审批）。
 *
 * <p><b>按 {@code zone} 的本地墙上时间判，不按 UTC 判</b>：{@code 20:00} 是业务高峰的开始，它对应 UTC 12:00；
 * 用 UTC 判会把整个升档窗口平移八小时，于是白天的变更被要求"同意在业务高峰执行"，而夜间的变更直接放过。
 *
 * <p><b>两种区间形状</b>（{@code start <= end} 时半开区间）：
 * <ul>
 *   <li>同一天内（{@code 09:00}–{@code 17:00}）：{@code [start, end)}，端点左闭右开——{@code 17:00:00}
 *       已经是窗口外。</li>
 *   <li>跨零点（{@code 20:00}–{@code 06:00}）：{@code >= start || < end}。照搬半开区间会得到一个空区间，
 *       于是全天都是"窗口外"，所有工单都升一级——升档理由变成噪音后，运维会习惯性忽略它。</li>
 *   <li>{@code start == end}：<b>空窗口</b>（{@code [t, t)}），不是 24 小时全开。配置写错时方向要偏严：
 *       解释成全天在窗口内会让所有语句少升一级（少一级审批），解释成空窗口只是多升一级。</li>
 * </ul>
 *
 * <p>纯计算、无 Spring 依赖：{@link RiskProperties#toWindow()} 负责把配置翻译成它，
 * 单测直接 {@code new ExecWindow(start, end, zone)} 就能把边界跑满。
 */
public record ExecWindow(LocalTime start, LocalTime end, ZoneId zone) {

    public ExecWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(zone, "zone");
    }

    /**
     * 该时刻是否落在窗口内。
     *
     * @param at 待判定的时刻（UTC 或任意时区都可以，内部先转到 {@link #zone()} 再取本地时间）
     */
    public boolean isWithin(Instant at) {
        LocalTime local = Objects.requireNonNull(at, "at").atZone(zone).toLocalTime();
        if (start.equals(end)) {
            return false;
        }
        if (start.isBefore(end)) {
            return !local.isBefore(start) && local.isBefore(end);
        }
        // 跨零点：>= start 或 < end，两段之间没有空隙。
        return !local.isBefore(start) || local.isBefore(end);
    }
}
