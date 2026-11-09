package com.orderly.api;

import com.orderly.kernel.ModuleRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public final class HealthController {
    private final ModuleRegistry registry;
    private final String version;

    public HealthController(
            ModuleRegistry registry,
            @Value("${orderly.version:0.1.0-SNAPSHOT}") String version) {
        this.registry = registry;
        this.version = version;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(ServiceStatus.OK, registry.moduleCount(), version);
    }
}
