package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.event.ExecutionJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ModuleDistributionServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void parsesOnlyStrictOfficialCatalogShape() {
        ModuleDistributionService service = service();
        try {
            ModuleDistributionService.RemoteCatalog catalog = service.parseCatalog("""
                    {
                      "schemaVersion":1,
                      "apiVersion":1,
                      "generatedAt":"2026-11-22T12:00:00Z",
                      "sourceCommit":"e7ac57d",
                      "modules":[{
                        "moduleId":"module_todo",
                        "title":"待办",
                        "description":"待办模块",
                        "version":"0.1.0+e7ac57d",
                        "packagePath":"packages/module_todo.zip",
                        "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "size":1024
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8));

            assertThat(catalog.modules()).extracting(ModuleDistributionService.RemoteModule::moduleId)
                    .containsExactly("module_todo");
        } finally {
            service.close();
        }
    }

    @Test
    void rejectsSystemModulesUnknownFieldsAndDuplicateKeys() {
        ModuleDistributionService service = service();
        try {
            assertThatThrownBy(() -> service.parseCatalog("""
                    {
                      "schemaVersion":1,
                      "apiVersion":1,
                      "generatedAt":"2026-11-22T12:00:00Z",
                      "modules":[{
                        "moduleId":"module_system_control",
                        "title":"控制",
                        "description":"控制模块",
                        "version":"1",
                        "packagePath":"packages/control.zip",
                        "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "size":1
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("受保护系统模块");

            assertThatThrownBy(() -> service.parseCatalog("""
                    {
                      "schemaVersion":1,
                      "apiVersion":1,
                      "generatedAt":"2026-11-22T12:00:00Z",
                      "unexpected":true,
                      "modules":[]
                    }
                    """.getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("未知字段");

            assertThatThrownBy(() -> service.parseCatalog("""
                    {
                      "schemaVersion":1,
                      "apiVersion":1,
                      "generatedAt":"2026-11-22T12:00:00Z",
                      "generatedAt":"2026-11-22T12:00:01Z",
                      "modules":[]
                    }
                    """.getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("无法解析");
        } finally {
            service.close();
        }
    }

    private ModuleDistributionService service() {
        return new ModuleDistributionService(
                mock(ContractCatalog.class),
                mock(ModuleLifecycleService.class),
                mock(ModulePackageInstaller.class),
                new ObjectMapper(),
                mock(ExecutionJournal.class),
                tempDirectory.resolve("Module").toString(),
                tempDirectory.resolve(".orderly-data").toString(),
                "https://raw.githubusercontent.com/xinge233/Orderly/module-distribution/catalog.json",
                262144,
                20971520,
                60,
                false
        );
    }
}
