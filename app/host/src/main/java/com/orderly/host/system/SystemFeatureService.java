package com.orderly.host.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * 宿主级功能开关的读取、校验和持久化服务。
 *
 * <p>设置保存在应用自管数据目录的 {@code system-features.json}。首次启动写入安全默认
 * 值：旧版计划关闭、AI 计划返回开启。开关只控制系统入口和 AI 计划返回路径，不会
 * 删除历史记录，也不会绕过执行风险、确认、快照或审计。</p>
 */
@Service
public class SystemFeatureService {

    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;
    private final Path settingsFile;
    private volatile FeatureSettings settings = new FeatureSettings(false, true);

    /** 绑定对象映射、应用数据目录和审计日志；构造过程不读写设置文件。 */
    public SystemFeatureService(
            ObjectMapper objectMapper,
            WorkspaceRuntime runtime,
            ExecutionJournal journal
    ) {
        this.objectMapper = objectMapper;
        this.journal = journal;
        this.settingsFile = runtime.dataRoot().resolve("system-features.json");
    }

    /**
     * 加载已有设置或创建默认设置文件。
     *
     * <p>文件不存在时写入默认值；存在时拒绝符号链接和非普通文件，再完整解析 JSON。
     * 解析或写入失败会抛出带路径和原因的异常并中止启动，不会静默回退为空状态。
     * 方法可重复执行，但每次都会重新读取磁盘并覆盖当前内存值。</p>
     */
    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(settingsFile.getParent());
            if (!Files.exists(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                settings = new FeatureSettings(false, true);
                persist(settings);
                return;
            }
            if (Files.isSymbolicLink(settingsFile) || !Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("系统功能设置必须是普通本地文件：" + settingsFile);
            }
            FeatureSettings loaded = objectMapper.readValue(settingsFile.toFile(), FeatureSettings.class);
            settings = new FeatureSettings(loaded.planningEnabled(), loaded.aiPlanEnabled());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "读取系统功能设置失败：" + settingsFile + "；原因：" + exception.getMessage(),
                    exception
            );
        }
    }

    /** 返回旧版计划功能当前是否开启；读取无 I/O 副作用。 */
    public boolean planningEnabled() {
        return settings.planningEnabled();
    }

    /** 返回 AI 计划返回是否开启；兼容旧文件缺失该字段时默认视为开启。 */
    public boolean aiPlansEnabled() {
        return settings.aiPlanEnabled() == null || settings.aiPlanEnabled();
    }

    /** 返回前端设置页使用的当前开关快照；不暴露设置文件路径。 */
    public Map<String, Object> features() {
        return Map.of(
                "planningEnabled", planningEnabled(),
                "aiPlanEnabled", aiPlansEnabled()
        );
    }

    /**
     * 持久化旧版计划开关并记录系统事件。
     *
     * <p>方法先原子替换设置文件，成功后才发布新的内存值并追加审计事件；写入失败时
     * 当前内存值保持不变。重复设置同一值仍会再次写文件并产生事件，因此不是幂等操作。</p>
     */
    public synchronized Map<String, Object> setPlanningEnabled(boolean enabled) {
        FeatureSettings next = new FeatureSettings(enabled, aiPlansEnabled());
        persist(next);
        settings = next;
        journal.append(
                "SYSTEM_FEATURE",
                "SYSTEM",
                null,
                null,
                null,
                "planning",
                enabled ? "ENABLED" : "DISABLED",
                enabled ? "计划生成功能已启用" : "计划生成功能已停用"
        );
        return features();
    }

    /**
     * 持久化 AI 计划返回开关并记录系统事件。
     *
     * <p>持久化成功后才更新内存状态，失败时不会产生“已开关”的内存假象。重复设置同一
     * 值仍会再次写入并审计；该开关不会改变模型凭据或启用本地规则回退。</p>
     */
    public synchronized Map<String, Object> setAiPlanEnabled(boolean enabled) {
        FeatureSettings next = new FeatureSettings(planningEnabled(), enabled);
        persist(next);
        settings = next;
        journal.append(
                "SYSTEM_FEATURE",
                "SYSTEM",
                null,
                null,
                null,
                "ai-plan",
                enabled ? "ENABLED" : "DISABLED",
                enabled ? "AI 计划返回已启用" : "AI 计划返回已停用"
        );
        return features();
    }

    /**
     * 要求旧版计划功能已开启。
     *
     * <p>关闭时抛出 HTTP 409 业务异常，提示计划入口不可用但历史记录仍可读取。该方法
     * 只读取当前内存快照，不修改设置或记录事件。</p>
     */
    public void requirePlanningEnabled() {
        if (!planningEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "FEATURE_DISABLED",
                    "计划生成功能已关闭；历史执行记录仍可读取"
            );
        }
    }

    /**
     * 以同目录临时文件替换设置文件。
     *
     * <p>调用方需已持有本实例锁。优先请求原子移动，不支持时回退普通替换；所有 I/O
     * 异常统一转换为包含目标路径的启动/写入失败，不删除旧文件来制造成功。</p>
     */
    private void persist(FeatureSettings value) {
        Path temporary = settingsFile.resolveSibling("system-features.json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(
                        temporary,
                        settingsFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入系统功能设置失败：" + settingsFile, exception);
        }
    }

    /**
     * 功能开关的磁盘表示。
     *
     * @param planningEnabled 旧版计划是否开启
     * @param aiPlanEnabled AI 计划返回是否开启；旧文件缺失时为 {@code null}
     */
    public record FeatureSettings(boolean planningEnabled, Boolean aiPlanEnabled) {
    }
}
