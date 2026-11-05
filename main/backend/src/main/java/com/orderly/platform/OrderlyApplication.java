package com.orderly.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Orderly 控制层启动入口。
 *
 * <p>当前阶段只装配模块化骨架，不注册任何文件操作、任务执行或 AI 调用能力。
 * 后续功能应通过独立模块接入，并由控制层统一实施权限、确认、审计与回滚。</p>
 */
@SpringBootApplication
public class OrderlyApplication {

    /**
     * 启动本地 Spring Boot 应用。
     *
     * @param args 标准命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderlyApplication.class, args);
    }
}
