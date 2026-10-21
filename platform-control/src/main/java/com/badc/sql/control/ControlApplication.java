package com.badc.sql.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 控制面入口：身份、权限、元数据、历史与审计。
 * 由后续任务补齐安全、组织、查询与审计能力。
 */
@SpringBootApplication
public class ControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlApplication.class, args);
    }
}
