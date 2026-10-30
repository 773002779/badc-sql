package com.badc.sql.control.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 配置绑定：{@code badcsql.risk.*} 能不能在启动期真的绑进 {@link RiskProperties}。
 *
 * <p>这条用例守的不是"默认值写对了"，而是<b>绑定本身成立</b>：{@link RiskProperties} 是 record，
 * 走构造器绑定，而构造器绑定能不能被选中取决于它<b>只有一个构造器</b>（见 {@link RiskProperties} 类注释）。
 * 一旦有人给这个 record 再加一个构造器（最容易被加回来的就是"给单测用的无参构造器"），绑定器会退化成
 * JavaBean 绑定：没有 setter 的 record 一个字段也绑不上，于是 {@code badcsql.risk.*} <b>静默失效</b>——
 * 配置写错不报错、改配置不生效，只有到了部署环境才发现。
 *
 * <p>所以这里同时钉住三条：缺省 → 默认值（{@code @DefaultValue} 生效）、配置 → 覆盖（构造器绑定生效）、
 * 值写错 → 启动失败（不是静默忽略）。
 */
class RiskPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RiskProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void the_defaults_are_bound_when_nothing_is_configured() {
        runner.run(context -> {
            RiskProperties properties = context.getBean(RiskProperties.class);

            assertThat(properties.l1MaxRows()).isEqualTo(100);
            assertThat(properties.l2MaxRows()).isEqualTo(1000);
            assertThat(properties.windowStart()).isEqualTo(LocalTime.of(20, 0));
            assertThat(properties.windowEnd()).isEqualTo(LocalTime.of(6, 0));
            assertThat(properties.windowZone()).isEqualTo(ZoneId.of("Asia/Shanghai"));
        });
    }

    @Test
    void configured_values_override_the_defaults() {
        runner.withPropertyValues(
                "badcsql.risk.l1-max-rows=7",
                "badcsql.risk.l2-max-rows=70",
                "badcsql.risk.window-start=09:00",
                "badcsql.risk.window-end=17:30",
                "badcsql.risk.window-zone=UTC")
                .run(context -> {
                    RiskProperties properties = context.getBean(RiskProperties.class);

                    assertThat(properties.l1MaxRows()).isEqualTo(7);
                    assertThat(properties.l2MaxRows()).isEqualTo(70);
                    assertThat(properties.windowStart()).isEqualTo(LocalTime.of(9, 0));
                    assertThat(properties.windowEnd()).isEqualTo(LocalTime.of(17, 30));
                    assertThat(properties.windowZone()).isEqualTo(ZoneId.of("UTC"));
                });
    }

    /** 绑定失败必须是启动失败，不能静默返回一个"看起来像默认值"的对象。 */
    @Test
    void a_broken_value_fails_the_context_instead_of_being_ignored() {
        runner.withPropertyValues("badcsql.risk.l1-max-rows=not-a-number")
                .run(context -> assertThat(context).hasFailed());
    }
}
