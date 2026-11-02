package com.badc.sql.control.notify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 配置绑定：{@code badcsql.notify.*} 能不能在启动期真的绑进 {@link NotificationProperties}。
 *
 * <p>与 {@code RiskPropertiesTest} 同因同果：record 走构造器绑定，一旦有人给它加第二个构造器，
 * 绑定器会退化成 JavaBean 绑定（record 没有 setter）→ 配置<b>静默失效</b>。所以这里钉住
 * "缺省 → 默认值""配置 → 覆盖""值写错 → 启动失败"三条。
 *
 * <p>{@code badcsql.notify} 整段不配时，嵌套组 {@code wecom}/{@code mail}/{@code retry} 必须是
 * <b>有默认值的实例</b>而不是 {@code null}：否则 sender 的 {@code isConfigured()} 会在启动后第一次投递时 NPE。
 */
class NotificationPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void the_defaults_are_bound_when_nothing_is_configured() {
        runner.run(context -> {
            NotificationProperties properties = context.getBean(NotificationProperties.class);

            assertThat(properties.wecom().enabled()).isFalse();
            assertThat(properties.wecom().baseUrl()).isEqualTo("https://qyapi.weixin.qq.com");
            assertThat(properties.wecom().corpId()).isEmpty();
            assertThat(properties.wecom().secret()).isEmpty();
            assertThat(properties.mail().enabled()).isFalse();
            assertThat(properties.mail().from()).isEmpty();
            assertThat(properties.retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.retry().baseBackoffMillis()).isEqualTo(500);
        });
    }

    @Test
    void configured_values_override_the_defaults() {
        runner.withPropertyValues(
                "badcsql.notify.wecom.enabled=true",
                "badcsql.notify.wecom.corp-id=corp-42",
                "badcsql.notify.wecom.agent-id=1001",
                "badcsql.notify.wecom.secret=s3cret",
                "badcsql.notify.wecom.base-url=http://127.0.0.1:9999",
                "badcsql.notify.mail.enabled=true",
                "badcsql.notify.mail.from=noreply@badc.test",
                "badcsql.notify.retry.max-attempts=5",
                "badcsql.notify.retry.base-backoff-millis=100")
                .run(context -> {
                    NotificationProperties properties = context.getBean(NotificationProperties.class);

                    assertThat(properties.wecom().enabled()).isTrue();
                    assertThat(properties.wecom().corpId()).isEqualTo("corp-42");
                    assertThat(properties.wecom().agentId()).isEqualTo(1001L);
                    assertThat(properties.wecom().secret()).isEqualTo("s3cret");
                    assertThat(properties.wecom().baseUrl()).isEqualTo("http://127.0.0.1:9999");
                    assertThat(properties.mail().enabled()).isTrue();
                    assertThat(properties.mail().from()).isEqualTo("noreply@badc.test");
                    assertThat(properties.retry().maxAttempts()).isEqualTo(5);
                    assertThat(properties.retry().baseBackoffMillis()).isEqualTo(100);
                });
    }

    @Test
    void a_broken_value_fails_the_context_instead_of_being_ignored() {
        runner.withPropertyValues("badcsql.notify.retry.max-attempts=not-a-number")
                .run(context -> assertThat(context).hasFailed());
    }
}
