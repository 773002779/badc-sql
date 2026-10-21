package com.badc.sql.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 执行器入口：只认"连接配置 + SQL"，不连平台库、不认用户。
 * 由后续任务补齐适配器、执行与探测接口。
 */
@SpringBootApplication
public class ExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExecutorApplication.class, args);
    }
}
