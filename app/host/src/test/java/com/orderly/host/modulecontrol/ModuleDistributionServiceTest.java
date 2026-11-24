package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.event.ExecutionJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void loadedModulesCannotRetryBuildWhenBackgroundBuilderIsDisabled() throws IOException {
        Files.createDirectories(tempDirectory.resolve("Module").resolve("module_todo"));
        ContractCatalog catalog = mock(ContractCatalog.class);
        ModuleLifecycleService lifecycle = mock(ModuleLifecycleService.class);
        when(catalog.findModule("module_todo")).thenReturn(Optional.of(new ModuleContract(
                "module_todo",
                "待办",
                "待办模块",
                100,
                null,
                List.of(),
                List.of(),
                List.of()
        )));
        when(lifecycle.snapshot()).thenReturn(new ModuleLifecycleService.Snapshot(
                1,
                "eager",
                "2026-11-25T00:00:00Z",
                true,
                List.of()
        ));

        ModuleDistributionService service = service(false, catalog, lifecycle);
        try {
            ModuleDistributionService.CatalogView catalogView = service.enrich(service.parseCatalog(
                    catalogBytes("module_todo")
            ));

            assertThat(catalogView.items()).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo("INSTALLED");
                assertThat(item.canInstall()).isFalse();
                assertThat(item.canRetryBuild()).isFalse();
            });
        } finally {
            service.close();
        }
    }

    @Test
    void backgroundModeStillAllowsRetryWhenSourceIsNotLoaded() throws IOException {
        Files.createDirectories(tempDirectory.resolve("Module").resolve("module_todo"));
        ContractCatalog catalog = mock(ContractCatalog.class);
        ModuleLifecycleService lifecycle = mock(ModuleLifecycleService.class);
        when(catalog.findModule("module_todo")).thenReturn(Optional.empty());
        when(lifecycle.snapshot()).thenReturn(new ModuleLifecycleService.Snapshot(
                1,
                "background",
                "2026-11-25T00:00:00Z",
                false,
                List.of()
        ));

        ModuleDistributionService service = service(true, catalog, lifecycle);
        try {
            ModuleDistributionService.CatalogView catalogView = service.enrich(service.parseCatalog(
                    catalogBytes("module_todo")
            ));

            assertThat(catalogView.items()).singleElement().satisfies(item -> {
                assertThat(item.status()).isEqualTo("SOURCE_PRESENT");
                assertThat(item.canInstall()).isFalse();
                assertThat(item.canRetryBuild()).isTrue();
            });
        } finally {
            service.close();
        }
    }

    private byte[] catalogBytes(String moduleId) {
        return """
                {
                  "schemaVersion":1,
                  "apiVersion":1,
                  "generatedAt":"2026-11-22T12:00:00Z",
                  "sourceCommit":"e7ac57d",
                  "modules":[{
                    "moduleId":"%s",
                    "title":"待办",
                    "description":"待办模块",
                    "version":"0.1.0+e7ac57d",
                    "packagePath":"packages/%s.zip",
                    "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "size":1024
                  }]
                }
                """.formatted(moduleId, moduleId).getBytes(StandardCharsets.UTF_8);
    }

    private ModuleDistributionService service() {
        return service(
                false,
                mock(ContractCatalog.class),
                mock(ModuleLifecycleService.class)
        );
    }

    private ModuleDistributionService service(
            boolean backgroundBuildEnabled,
            ContractCatalog catalog,
            ModuleLifecycleService lifecycle
    ) {
        return new ModuleDistributionService(
                catalog,
                lifecycle,
                mock(ModulePackageInstaller.class),
                new ObjectMapper(),
                mock(ExecutionJournal.class),
                tempDirectory.resolve("Module").toString(),
                tempDirectory.resolve(".orderly-data").toString(),
                "https://raw.githubusercontent.com/xinge233/Orderly/module-distribution/catalog.json",
                262144,
                20971520,
                60,
                backgroundBuildEnabled
        );
    }
}
