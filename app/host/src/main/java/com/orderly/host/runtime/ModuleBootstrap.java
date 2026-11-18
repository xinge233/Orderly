package com.orderly.host.runtime;

import com.orderly.host.contract.ContractCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Spring 启动阶段的模块初始化门。
 *
 * <p>本组件依赖契约目录和共享运行时已经构造完成。初始化时先验证运行集中恰好存在
 * 一个受保护的 {@code module_control}，再按契约声明的稳定排序调用每个模块的幂等
 * {@code initialize}。任何校验或初始化异常都会继续抛出并中止宿主启动，避免以不完整
 * 模块集合对外服务。</p>
 */
@Component
public class ModuleBootstrap {

    private final ContractCatalog catalog;
    private final WorkspaceRuntime runtime;

    public ModuleBootstrap(ContractCatalog catalog, WorkspaceRuntime runtime) {
        this.catalog = catalog;
        this.runtime = runtime;
    }

    /**
     * 在 Spring 完成依赖注入后初始化全部已装载模块。
     *
     * <p>方法只应由容器调用一次；模块自身必须容忍 Initialize 被重试。系统模块校验
     * 失败、模块初始化异常或模块级持久状态错误都会阻止应用进入就绪状态。</p>
     */
    @PostConstruct
    public void initializeModules() {
        catalog.validateSystemModules();
        catalog.initializeModules(runtime);
    }
}
