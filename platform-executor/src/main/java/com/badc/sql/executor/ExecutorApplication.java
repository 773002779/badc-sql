package com.badc.sql.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import com.badc.sql.executor.internal.InternalAuthFilter;

/**
 * 执行器入口：只认"连接配置 + SQL"，不连平台库、不认用户。
 * 由后续任务补齐适配器、执行与探测接口。
 */
@SpringBootApplication
public class ExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutorApplication.class, args);
    }

    /**
     * 内部令牌过滤器注册。
     *
     * <p>过滤器本身是容器管理的 bean（{@code @Component}），{@code @PostConstruct} 里
     * "令牌未配置"的告警才会在启动时打出来；这里只负责让它排在最前面——认证必须发生在
     * 请求体反序列化之前。
     */
    @Bean
    public FilterRegistrationBean<InternalAuthFilter> internalAuthFilterRegistration(
            InternalAuthFilter internalAuthFilter) {
        FilterRegistrationBean<InternalAuthFilter> registration =
                new FilterRegistrationBean<>(internalAuthFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
