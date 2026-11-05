package com.orderly.platform.api;

import java.util.List;

/**
 * 模块发现接口的版本化公共响应。
 *
 * @param contractVersion 公共契约版本
 * @param mode 当前平台运行模式的明确标识
 * @param modules 可用模块的公开摘要
 */
public record ModuleCatalogResponse(
        String contractVersion,
        String mode,
        List<ModuleSummaryResponse> modules
) {
    public ModuleCatalogResponse {
        modules = List.copyOf(modules);
    }
}
