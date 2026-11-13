package com.orderly.host.runtime;

import com.orderly.host.contract.ContractCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 在宿主通用运行时就绪后初始化所有已安装模块。 */
@Component
public class ModuleBootstrap {

    private final ContractCatalog catalog;
    private final WorkspaceRuntime runtime;

    public ModuleBootstrap(ContractCatalog catalog, WorkspaceRuntime runtime) {
        this.catalog = catalog;
        this.runtime = runtime;
    }

    @PostConstruct
    public void initializeModules() {
        catalog.validateSystemModules();
        catalog.initializeModules(runtime);
    }
}
