package com.orderly.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 归序本地 Java 宿主进程入口。
 *
 * <p>启动 Spring 容器并装入模块契约、工作区运行时、计划/执行、审计、API 与主题组件。
 * 宿主负责最终校验、授权、快照、回滚和审计；具体业务仍由外部模块 SPI 提供。容器启动
 * 失败会沿 {@link SpringApplication#run(Class, String...)} 抛出并终止进程，不会以半
 * 就绪状态继续服务。</p>
 */
@SpringBootApplication
public class OrderlyHostApplication {

    /**
     * 启动本地宿主并阻塞到容器退出。
     *
     * <p>{@code args} 交给 Spring Boot 解析标准启动参数。方法本身不执行 Action、不写入
     * 工作区；初始化副作用由容器中带生命周期注解的组件按依赖顺序完成。</p>
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderlyHostApplication.class, args);
    }
}
