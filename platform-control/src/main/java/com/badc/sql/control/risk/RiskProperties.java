package com.badc.sql.control.risk;

import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 风险分级的可配置口径：按行数分档的两个阈值 + 执行窗口。
 *
 * <p>对应配置键（默认值写在这里，{@code application.yml} 或环境变量可不配）：
 * <ul>
 *   <li>{@code badcsql.risk.l1-max-rows=100}：估算行数 ≤ 100（含）→ L1；</li>
 *   <li>{@code badcsql.risk.l2-max-rows=1000}：101..1000（含）→ L2，超过则 L3；</li>
 *   <li>{@code badcsql.risk.window-start=20:00} / {@code window-end=06:00}：业务窗口（可跨零点）；</li>
 *   <li>{@code badcsql.risk.window-zone=Asia/Shanghai}：窗口按这个时区的墙上时间判。</li>
 * </ul>
 *
 * <p><b>这里能调的只有"多大算大"和"什么时候是窗口内"</b>。结构性破坏恒 L3、BLOCK 恒拒绝、
 * "写语句无 where"恒 L3 都不读这张配置——所以 {@link #relaxed()} 把阈值与窗口都放到最宽，
 * 硬规则也不会松动（这正是它存在的唯一用途：对照用例）。
 *
 * <p>绑定方式：控制面入口上的 {@code @EnableConfigurationProperties(RiskProperties.class)}。因为本类型是 record，
 * Spring 走<b>构造器绑定</b>；{@code @DefaultValue} 保证配置缺省时不会绑出 {@code 0} 或 {@code null}
 * ——一个 {@code 0} 阈值会让所有 DML 都落到 L3，一个 {@code null} 窗口会在构造 {@link ExecWindow} 时炸掉。
 * 绑定成立这件事由 {@code RiskPropertiesTest} 钉住（缺省 → 默认值、配置 → 覆盖、写错 → 启动失败）。
 *
 * <p><b>本类型只能有一个构造器</b>（就是 5 参的规范构造器）。给单测图方便再加一个无参构造器会让
 * Boot 的"唯一参数化构造器"推断失效，退化成 JavaBean 绑定：没有 setter 的 record 绑不上任何字段，
 * 于是 {@code badcsql.risk.*} <b>静默失效</b>——配置写错也不报错，改配置也不生效，直到生产上改了阈值
 * 却没人被拦住才发现。所以单测要用默认值时走 {@link #defaults()}，不加构造器。
 */
@ConfigurationProperties(prefix = "badcsql.risk")
public record RiskProperties(
        @DefaultValue("100") int l1MaxRows,
        @DefaultValue("1000") int l2MaxRows,
        @DefaultValue("20:00") LocalTime windowStart,
        @DefaultValue("06:00") LocalTime windowEnd,
        @DefaultValue("Asia/Shanghai") ZoneId windowZone) {

    /** 默认阈值：≤ 100 行 → L1。 */
    public static final int DEFAULT_L1_MAX_ROWS = 100;

    /** 默认阈值：≤ 1000 行 → L2，超过 → L3。 */
    public static final int DEFAULT_L2_MAX_ROWS = 1000;

    /** 默认窗口起点：业务高峰开始（20:00，{@code window-zone} 的墙上时间）。 */
    public static final LocalTime DEFAULT_WINDOW_START = LocalTime.of(20, 0);

    /** 默认窗口终点：06:00。 */
    public static final LocalTime DEFAULT_WINDOW_END = LocalTime.of(6, 0);

    /** 默认时区：展示与窗口判定都用它（存储仍为 UTC）。 */
    public static final ZoneId DEFAULT_WINDOW_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 不带参数的默认口径：{@code 100 / 1000 / 20:00–06:00 / Asia/Shanghai}。
     *
     * <p><b>刻意写成静态工厂而不是无参构造器</b>：多一个构造器会让 Spring 的构造器绑定推断失效、
     * 退化成绑不上任何字段的 JavaBean 绑定（见类注释）。它是给单测与"没有配置中心也要能算"的调用方用的，
     * 免得每处都重复写五个默认值。
     */
    public static RiskProperties defaults() {
        return new RiskProperties(DEFAULT_L1_MAX_ROWS, DEFAULT_L2_MAX_ROWS,
                DEFAULT_WINDOW_START, DEFAULT_WINDOW_END, DEFAULT_WINDOW_ZONE);
    }

    /** 把窗口配置翻译成可判定的 {@link ExecWindow}。 */
    public ExecWindow toWindow() {
        return new ExecWindow(windowStart, windowEnd, windowZone);
    }

    /**
     * 全放宽口径：阈值放到 {@link Integer#MAX_VALUE}、窗口 {@code 00:00}–{@code 23:59}。
     *
     * <p><b>只用于"硬规则不可放宽"的对照用例</b>，不要在生产配置里使用。它证明的是：即使把所有可调项
     * 都推到极限，{@code drop table}、{@code drop database}、{@code update} 无 where 的等级也不会下降。
     */
    public static RiskProperties relaxed() {
        return new RiskProperties(Integer.MAX_VALUE, Integer.MAX_VALUE,
                LocalTime.MIDNIGHT, LocalTime.of(23, 59), DEFAULT_WINDOW_ZONE);
    }
}
