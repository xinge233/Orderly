package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.event.ExecutionJournal;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * 业务模组移除流程。
 *
 * <p>运行中的模块不能热卸载。请求先把源码移入模块回收站、停用模块并登记
 * {@code PENDING_RESTART}，由下一次启动跳过并清理 JAR。撤销只在重启前可用，撤销时
 * 同时恢复源码和原启用状态。</p>
 */
@Service
public class ModuleRemovalService {

    private final ContractCatalog catalog;
    private final ModulePreferencesService preferences;
    private final ModuleControlCoordinator coordinator;
    private final ModuleRemovalStore store;
    private final ModuleTrashService trash;
    private final ModuleLifecycleService lifecycle;
    private final ExecutionJournal journal;
    private final Path moduleSourceDirectory;

    public ModuleRemovalService(
            ContractCatalog catalog,
            ModulePreferencesService preferences,
            ModuleControlCoordinator coordinator,
            ModuleRemovalStore store,
            ModuleTrashService trash,
            ModuleLifecycleService lifecycle,
            ExecutionJournal journal,
            @Value("${orderly.module-source-dir:Module}") String moduleSourceDirectory
    ) {
        this.catalog = catalog;
        this.preferences = preferences;
        this.coordinator = coordinator;
        this.store = store;
        this.trash = trash;
        this.lifecycle = lifecycle;
        this.journal = journal;
        this.moduleSourceDirectory = Path.of(moduleSourceDirectory).toAbsolutePath().normalize();
    }

    /** 完成上一次进程留下的待移除 JAR 清理。 */
    @PostConstruct
    public void finalizePendingRemovals() {
        for (ModuleRemovalStore.Entry entry : store.snapshot().modules()) {
            try {
                Path source = moduleSourceDirectory.resolve(entry.moduleId()).normalize();
                if (source.startsWith(moduleSourceDirectory)
                        && Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(source)) {
                    trash.moveSource(entry.moduleId(), entry.trashId());
                }
                trash.moveJar(entry.moduleId(), entry.trashId());
                store.clear(entry.moduleId());
                journal.append(
                        "MODULE_CONTROL",
                        "MODULE_CONTROL",
                        null,
                        null,
                        entry.moduleId(),
                        "module-removal",
                        "REMOVED",
                        "模块已从运行目录移除：" + entry.moduleId()
                );
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "完成待移除模块失败：" + entry.moduleId() + "；" + safeMessage(exception),
                        exception
                );
            }
        }
    }

    /** 返回当前业务模组的移除状态。 */
    public Snapshot snapshot() {
        return coordinator.read(() -> {
            List<RemovalView> modules = catalog.businessModules().stream()
                    .map(module -> {
                        ModuleRemovalStore.Entry entry = store.find(module.moduleId());
                        return new RemovalView(
                                module.moduleId(),
                                entry == null ? "NONE" : "PENDING_RESTART",
                                entry == null,
                                entry == null ? "" : "重启后生效",
                                entry == null ? "" : entry.trashId(),
                                entry == null ? "" : entry.requestedAt(),
                                entry != null && entry.previousEnabled()
                        );
                    })
                    .toList();
            return new Snapshot(1, modules, preferences.revision());
        });
    }

    /**
     * 标记业务模组删除。源码立即进入模块回收站，当前 JAR 必须等待重启清理。
     */
    public Change remove(String moduleId) {
        ModuleContract module = requireBusinessModule(moduleId);
        return coordinator.mutateRejectingDuringExecution(() -> {
            ModuleRemovalStore.Entry existing = store.find(module.moduleId());
            if (existing != null) {
                return new Change(
                        module.moduleId(),
                        "PENDING_RESTART",
                        "模块已标记删除，重启后生效",
                        existing.trashId(),
                        preferences.revision()
                );
            }
            requireNotBuilding(module.moduleId());

            boolean previousEnabled = preferences.isEnabled(module.moduleId());
            String trashId = trash.newTrashId();
            ModuleRemovalStore.Entry entry = new ModuleRemovalStore.Entry(
                    module.moduleId(),
                    trashId,
                    module.moduleId() + ".jar",
                    previousEnabled,
                    Instant.now().toString()
            );

            // 先登记再移动，进程在两步之间中断时下一次启动仍会跳过旧 JAR。
            store.markPending(entry);
            boolean sourceMoved = false;
            try {
                trash.moveSource(module.moduleId(), trashId);
                sourceMoved = true;
                preferences.setEnabled(module.moduleId(), false);
                journal.append(
                        "MODULE_CONTROL",
                        "MODULE_CONTROL",
                        null,
                        null,
                        module.moduleId(),
                        "module-removal",
                        "PENDING_RESTART",
                        "模块已标记删除，重启后生效：" + module.moduleId()
                );
                return new Change(
                        module.moduleId(),
                        "PENDING_RESTART",
                        "已标记删除，重启后生效",
                        trashId,
                        preferences.revision()
                );
            } catch (RuntimeException exception) {
                rollback(module.moduleId(), trashId, previousEnabled, sourceMoved);
                throw removalFailure(exception);
            }
        });
    }

    /** 重启前撤销移除，恢复源码和原启用状态。 */
    public Change undo(String moduleId) {
        ModuleContract module = requireBusinessModule(moduleId);
        return coordinator.mutate(() -> {
            ModuleRemovalStore.Entry entry = store.find(module.moduleId());
            if (entry == null) {
                throw new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_REMOVAL_NOT_FOUND",
                        "模块没有待撤销的删除标记：" + module.moduleId()
                );
            }
            try {
                trash.restoreSource(module.moduleId(), entry.trashId());
                store.clear(module.moduleId());
                if (entry.previousEnabled()) {
                    preferences.setEnabled(module.moduleId(), true);
                }
                journal.append(
                        "MODULE_CONTROL",
                        "MODULE_CONTROL",
                        null,
                        null,
                        module.moduleId(),
                        "module-removal",
                        "RESTORED",
                        "已撤销模块删除：" + module.moduleId()
                );
                return new Change(
                        module.moduleId(),
                        "RESTORED",
                        "已撤销删除",
                        entry.trashId(),
                        preferences.revision()
                );
            } catch (RuntimeException exception) {
                throw removalFailure(exception);
            }
        });
    }

    private ModuleContract requireBusinessModule(String moduleId) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_NOT_FOUND",
                        "模块不存在：" + moduleId
                )
        );
        if (module.system() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_PROTECTED",
                    "系统模块不允许删除：" + moduleId
            );
        }
        return module;
    }

    private void requireNotBuilding(String moduleId) {
        boolean building = lifecycle.snapshot().modules().stream()
                .filter(entry -> moduleId.equals(entry.sourceName()))
                .map(ModuleLifecycleService.Entry::status)
                .anyMatch(status -> "QUEUED".equals(status) || "BUILDING".equals(status));
        if (building) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_BUILD_IN_PROGRESS",
                    "模块仍在排队或构建，完成后再删除"
            );
        }
    }

    private void rollback(
            String moduleId,
            String trashId,
            boolean previousEnabled,
            boolean sourceMoved
    ) {
        if (sourceMoved) {
            try {
                trash.restoreSource(moduleId, trashId);
            } catch (RuntimeException ignored) {
                // 保留原始失败；下一次启动会依据登记继续完成移除或在日志中暴露问题。
            }
        }
        try {
            store.clear(moduleId);
        } catch (RuntimeException ignored) {
            // 状态写入失败时优先保留原始异常，启动阶段会严格校验状态文件。
        }
        if (previousEnabled && !preferences.isEnabled(moduleId)) {
            try {
                preferences.setEnabled(moduleId, true);
            } catch (RuntimeException ignored) {
                // 不覆盖原始移除错误。
            }
        }
    }

    private ApiException removalFailure(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return apiException;
        }
        String message = exception instanceof ModuleTrashService.ModuleTrashException trashException
                ? trashException.getMessage()
                : safeMessage(exception);
        return new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MODULE_REMOVAL_FAILED",
                "模块删除失败：" + message
        );
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }

    /** 单个业务模组的移除状态。 */
    public record RemovalView(
            String moduleId,
            String status,
            boolean canRemove,
            String reason,
            String trashId,
            String requestedAt,
            boolean previousEnabled
    ) {
    }

    /** 移除状态快照。 */
    public record Snapshot(
            int version,
            List<RemovalView> modules,
            String revision
    ) {
        public Snapshot {
            modules = List.copyOf(modules);
        }
    }

    /** 移除、撤销或幂等请求的结果。 */
    public record Change(
            String moduleId,
            String status,
            String message,
            String trashId,
            String revision
    ) {
    }

    /** 移除请求体。 */
    public record Request(String moduleId) {
    }
}
