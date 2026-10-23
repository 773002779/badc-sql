package com.badc.sql.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 控制面入口：身份、权限、元数据、历史与审计。
 * 由后续任务补齐安全、组织、查询与审计能力。
 *
 * <p>身份只来自 JWT，排除 Spring Boot 默认的内存用户（否则会凭空生成一个带随机密码的账号）。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }
}
