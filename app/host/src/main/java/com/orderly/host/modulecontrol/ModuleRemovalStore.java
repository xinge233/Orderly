package com.orderly.host.modulecontrol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 持久化待重启移除的模块登记。
 *
 * <p>状态文件只记录已经由宿主接受、但尚未完成启动清理的业务模块。扫描运行 JAR
 * 之前必须读取该文件，避免重启后再次加载已删除模块。状态损坏应阻止启动，不能
 * 静默忽略后把已删除模块重新载入。</p>
 */
@Component
public class ModuleRemovalStore {

    private static final int VERSION = 1;
    private static final long MAX_STATE_BYTES = 256L * 1024L;
    private static final Pattern MODULE_ID = Pattern.compile("^module_[a-z0-9_]+$");
    private static final Pattern TRASH_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,127}$");
    private static final Set<String> ROOT_FIELDS = Set.of("version", "modules");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "moduleId",
            "trashId",
            "jarFileName",
            "previousEnabled",
            "requestedAt"
    );

    private final ObjectMapper objectMapper;
    private final Path stateFile;
    private final boolean ephemeral;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    @Autowired
    public ModuleRemovalStore(
            ObjectMapper objectMapper,
            @Value("${orderly.data-dir:.orderly-data}") String dataDirectory
    ) {
        this(
                objectMapper,
                Path.of(dataDirectory).toAbsolutePath().normalize().resolve("module-removals.json"),
                false
        );
    }

    ModuleRemovalStore(ObjectMapper objectMapper, Path stateFile, boolean ephemeral) {
        this.objectMapper = objectMapper;
        this.stateFile = stateFile;
        this.ephemeral = ephemeral;
    }

    /** 提供给只验证模块扫描的轻量测试和旧构造入口使用的空登记。 */
    public static ModuleRemovalStore empty(ObjectMapper objectMapper) {
        return new ModuleRemovalStore(objectMapper, null, true);
    }

    /** 在模块扫描之前读取并严格校验状态文件。 */
    @PostConstruct
    public synchronized void initialize() {
        if (ephemeral) {
            return;
        }
        readState();
    }

    /** 返回当前待移除模块 ID；只读且不暴露可变集合。 */
    public synchronized Set<String> pendingModuleIds() {
        return Set.copyOf(entries.keySet());
    }

    /** 返回当前待移除登记快照。 */
    public synchronized Snapshot snapshot() {
        return new Snapshot(VERSION, List.copyOf(entries.values()));
    }

    /** 查询单个待移除登记。 */
    public synchronized Entry find(String moduleId) {
        return entries.get(moduleId);
    }

    /** 写入或替换一条待移除登记并立即持久化。 */
    public synchronized void markPending(Entry entry) {
        if (ephemeral) {
            throw new IllegalStateException("空移除登记不能写入");
        }
        validateEntry(entry);
        entries.put(entry.moduleId(), entry);
        persist();
    }

    /** 清除一条登记；不存在时保持幂等。 */
    public synchronized void clear(String moduleId) {
        if (ephemeral) {
            return;
        }
        if (entries.remove(moduleId) != null) {
            persist();
        }
    }

    private void readState() {
        if (stateFile == null
                || !Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(stateFile)
                || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw corrupted("模块移除状态必须是普通文件");
        }
        try {
            if (Files.size(stateFile) > MAX_STATE_BYTES) {
                throw corrupted("模块移除状态超过大小限制");
            }
            JsonNode root;
            try (var parser = objectMapper.getFactory().createParser(Files.readAllBytes(stateFile))) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = objectMapper.readTree(parser);
            }
            if (root == null || !root.isObject()) {
                throw corrupted("根节点必须是对象");
            }
            rejectUnknownFields(root, ROOT_FIELDS, "根节点");
            if (root.path("version").asInt(-1) != VERSION) {
                throw corrupted("version 必须为 " + VERSION);
            }
            JsonNode modules = root.path("modules");
            if (!modules.isArray()) {
                throw corrupted("modules 必须是数组");
            }
            Map<String, Entry> loaded = new LinkedHashMap<>();
            for (JsonNode node : modules) {
                Entry entry = parseEntry(node);
                if (loaded.putIfAbsent(entry.moduleId(), entry) != null) {
                    throw corrupted("模块移除状态包含重复 moduleId：" + entry.moduleId());
                }
            }
            entries.clear();
            entries.putAll(loaded);
        } catch (IOException exception) {
            throw corrupted("读取模块移除状态失败：" + exception.getMessage());
        } catch (ModuleRemovalException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw corrupted("解析模块移除状态失败：" + exception.getMessage());
        }
    }

    private Entry parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw corrupted("modules 条目必须是对象");
        }
        rejectUnknownFields(node, ENTRY_FIELDS, "modules 条目");
        Entry entry = new Entry(
                requiredText(node, "moduleId"),
                requiredText(node, "trashId"),
                requiredText(node, "jarFileName"),
                requiredBoolean(node, "previousEnabled"),
                requiredText(node, "requestedAt")
        );
        validateEntry(entry);
        return entry;
    }

    private void persist() {
        if (stateFile == null) {
            return;
        }
        try {
            Files.createDirectories(stateFile.getParent());
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", VERSION);
            ArrayNode modules = root.putArray("modules");
            for (Entry entry : entries.values()) {
                ObjectNode node = modules.addObject();
                node.put("moduleId", entry.moduleId());
                node.put("trashId", entry.trashId());
                node.put("jarFileName", entry.jarFileName());
                node.put("previousEnabled", entry.previousEnabled());
                node.put("requestedAt", entry.requestedAt());
            }
            Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(
                    temporary,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("保存模块移除状态失败：" + stateFile, exception);
        }
    }

    private void validateEntry(Entry entry) {
        if (entry == null
                || entry.moduleId() == null
                || !MODULE_ID.matcher(entry.moduleId()).matches()
                || entry.moduleId().startsWith("module_system_")) {
            throw corrupted("模块移除状态包含非法业务模块 ID");
        }
        if (entry.trashId() == null || !TRASH_ID.matcher(entry.trashId()).matches()) {
            throw corrupted("模块移除状态包含非法 trashId");
        }
        if (!(entry.moduleId() + ".jar").equals(entry.jarFileName())) {
            throw corrupted("模块移除状态的 jarFileName 与 moduleId 不匹配");
        }
        if (entry.requestedAt() == null || entry.requestedAt().isBlank()) {
            throw corrupted("模块移除状态缺少 requestedAt");
        }
        try {
            Instant.parse(entry.requestedAt());
        } catch (RuntimeException exception) {
            throw corrupted("模块移除状态 requestedAt 不是 ISO-8601 时间");
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw corrupted("模块移除状态缺少文本字段：" + field);
        }
        return value.asText();
    }

    private boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw corrupted("模块移除状态缺少布尔字段：" + field);
        }
        return value.asBoolean();
    }

    private void rejectUnknownFields(JsonNode node, Set<String> allowed, String description) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw corrupted(description + "包含未知字段：" + field);
            }
        });
    }

    private ModuleRemovalException corrupted(String message) {
        return new ModuleRemovalException(
                "模块移除状态损坏：" + stateFile + "；" + message
        );
    }

    /** 一条待重启移除登记。 */
    public record Entry(
            String moduleId,
            String trashId,
            String jarFileName,
            boolean previousEnabled,
            String requestedAt
    ) {
    }

    /** 状态文件快照。 */
    public record Snapshot(int version, List<Entry> modules) {
        public Snapshot {
            modules = List.copyOf(modules);
        }
    }

    /** 状态文件损坏或条目非法。 */
    public static class ModuleRemovalException extends RuntimeException {
        public ModuleRemovalException(String message) {
            super(message);
        }
    }
}
