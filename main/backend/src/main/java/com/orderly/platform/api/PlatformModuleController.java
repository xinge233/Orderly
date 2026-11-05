package com.orderly.platform.api;

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

    private final ModuleCatalogMapper mapper;

    /**
     * 注入公共契约映射器。
     *
     * @param mapper 内部模型到公共 DTO 的唯一映射器
     */
    public PlatformModuleController(ModuleCatalogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回当前已装配的模块目录。
     *
     * @return 模块目录响应
     */
    @GetMapping("/modules")
    public ModuleCatalogResponse listModules() {
        return mapper.toResponse();
    }
}
