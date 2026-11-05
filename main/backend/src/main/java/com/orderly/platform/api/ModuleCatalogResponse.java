package com.orderly.platform.api;

import com.orderly.platform.module.ModuleDescriptor;

import java.util.List;

/**
 * 模块发现接口的稳定响应信封。
 *
 * @param mode 当前平台运行模式的明确标识
 * @param modules 可用模块的只读描述
 */
public record ModuleCatalogResponse(
        String mode,
        List<ModuleDescriptor> modules
) {
    public ModuleCatalogResponse {
        modules = List.copyOf(modules);
    }
}
