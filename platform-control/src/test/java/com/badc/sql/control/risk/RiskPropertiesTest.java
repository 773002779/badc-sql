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
 * 走构造器绑定；它多了一个无参构造器给单测用，若绑定器因此选不中 5 参构造器，会退化成"绑定不上、静默用默认值"。
 * 那种失败在单测里全绿，只有到了部署环境改配置却不生效时才暴露——所以这里同时钉住"缺省 → 默认值"与
 * "配置 → 覆盖"两条路径，字段名写错（如 {@code l1MaxRows} 少个短横）也会在这里红。
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
