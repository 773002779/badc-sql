package com.badc.sql.control;

import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.badc.sql.control.risk.PolicyValidator;
import com.badc.sql.control.risk.RiskEngine;
import com.badc.sql.control.risk.RiskProperties;

/**
 * 控制面入口：身份、权限、元数据、历史与审计。
 * 由后续任务补齐安全、组织、查询与审计能力。
 *
 * <p>身份只来自 JWT，排除 Spring Boot 默认的内存用户（否则会凭空生成一个带随机密码的账号）。
 *
 * <p><b>为什么是 {@code @EnableConfigurationProperties} 而不是 {@code @ConfigurationPropertiesScan}</b>：
 * 两者在完整应用里等价，但切片测试（{@code @WebMvcTest}）会用自己的 {@code TypeExcludeFilter} 过滤掉扫描出来的
 * 配置类——{@code @ConfigurationPropertiesScan} 走的是类路径扫描，于是切片上下文里 {@link RiskProperties}
 * 不见了，而本类的 {@code riskEngine} {@code @Bean} 又要它，结果是每个 {@code @WebMvcTest} 都以
 * {@code No qualifying bean of type 'RiskProperties'} 启动失败。{@code @EnableConfigurationProperties}
 * 直接登记指定类型、不过滤，切片与完整应用行为一致。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties(RiskProperties.class)
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }

    /**
     * 全局时钟（UTC）。
     *
     * <p>结果缓存的过期、历史与审计的时间戳都走它；测试注册自己的 {@link Clock} bean 即可控制时间，
     * 不必等真实时间流逝。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 规范校验器。
     *
     * <p>它是<b>纯函数、无状态</b>的（{@code risk} 包刻意不依赖 Spring），本来可以随处 {@code new}；
     * 但 {@link RiskEngine} 要按构造器注入它，所以在这里注册一次，单测仍直接 {@code new PolicyValidator()}。
     */
    @Bean
    public PolicyValidator policyValidator() {
        return new PolicyValidator();
    }

    /**
     * 风险分级引擎。
     *
     * <p>同样刻意不标 {@code @Component}：整个 {@code risk} 包保持"纯计算、无框架依赖"，
     * 装配集中在本入口。引擎自己也不连库——EXPLAIN 估算由调用方先取好再放进
     * {@link RiskEngine.RiskRequest}（见任务 7）。
     */
    @Bean
    public RiskEngine riskEngine(PolicyValidator policyValidator, RiskProperties riskProperties) {
        return new RiskEngine(policyValidator, riskProperties);
    }

    /**
     * 工单执行线程池（任务 11）。
     *
     * <p>core 2 / max 4 / queue 100，<b>拒绝策略必须是 {@code CallerRunsPolicy}</b>：真正的执行并发上界由
     * {@code datasource_exec_lock}（单数据源写并发 = 1）决定，池子只是把请求线程从"等执行跑完"里解放出来。
     * 队列满了就让提交请求的线程自己跑——把任务静默丢掉（{@code AbortPolicy} 的默认行为）会让工单永远停在
     * {@code EXECUTING}，那是最坏的结果：用户以为在跑，其实没人跑；宁可让这次请求慢一点。
     *
     * <p>{@code waitForTasksToCompleteOnShutdown} 打开：优雅停机时正在执行的 DDL 不能被直接掐断——半条
     * {@code alter} 的后果比停机多等几秒严重得多。
     */
    @Bean("ticketExecutor")
    public ThreadPoolTaskExecutor ticketExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ticket-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }
}
