package com.orderly.platform.api;

import com.orderly.platform.module.ModuleRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台模块发现接口。
 *
 * <p>当前只开放只读目录查询，不开放任意命令执行端点。真实动作接入时，
 * 必须逐项定义接口契约并经过权限、确认和审计设计评审。</p>
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformModuleController {

    private final ModuleRegistry registry;

    /**
     * 注入只读模块注册表。
     *
     * @param registry 平台模块目录
     */
    public PlatformModuleController(ModuleRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回当前已装配的模块目录。
     *
     * @return 模块目录响应
     */
    @GetMapping("/modules")
    public ModuleCatalogResponse listModules() {
        return new ModuleCatalogResponse("modular-skeleton", registry.listDescriptors());
    }
}
