package com.orderly.host.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 将完整执行记录以单个 JSON 文件持久化到应用数据目录。
 *
 * <p>executionId 先经过安全字符和长度校验，文件名不接收用户路径。写入先在同一目录
 * 生成 {@code .tmp} 文件，优先原子替换目标；平台不支持原子移动时回退为普通替换。</p>
 */
@Component
public class ExecutionRecordStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final ObjectMapper objectMapper;
    private final Path executionsDirectory;

    public ExecutionRecordStore(ObjectMapper objectMapper, WorkspaceRuntime runtime) {
        this.objectMapper = objectMapper;
        this.executionsDirectory = runtime.dataRoot().resolve("executions");
    }

    /** 创建执行记录目录，并拒绝符号链接或非普通目录。 */
    @PostConstruct
    public synchronized void initialize() {
        try {
            Files.createDirectories(executionsDirectory);
            if (Files.isSymbolicLink(executionsDirectory)
                    || !Files.isDirectory(executionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("执行记录目录必须是普通本地目录：" + executionsDirectory);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("初始化执行记录目录失败：" + executionsDirectory, exception);
        }
    }

    /**
     * 原子式保存完整执行记录。
     *
     * <p>先完整写入同目录临时文件，再尝试 ATOMIC_MOVE + REPLACE_EXISTING；仅当平台
     * 明确不支持原子移动时退化为普通替换。I/O 失败抛出带目标路径的运行时异常。</p>
     */
    public synchronized ExecutionRecord save(ExecutionRecord record) {
        requireSafeId(record.executionId());
        Path target = fileFor(record.executionId());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(executionsDirectory);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return record;
        } catch (IOException exception) {
            throw new IllegalStateException("写入执行记录失败：" + target, exception);
        }
    }

    /** 按安全 executionId 读取记录；目标不存在时返回 empty，存在但格式不合法时抛错。 */
    public Optional<ExecutionRecord> find(String executionId) {
        requireSafeId(executionId);
        Path file = fileFor(executionId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    /** 读取必须存在的执行记录，不存在时转换为 404 API 错误。 */
    public ExecutionRecord require(String executionId) {
        return find(executionId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "EXECUTION_NOT_FOUND",
                "执行记录不存在：" + executionId
        ));
    }

    /**
     * 读取目录内所有 JSON 执行记录并生成摘要。
     *
     * <p>目录不存在视为空列表；扫描先按文件名稳定处理，最终按 createdAt 倒序返回。
     * 任一记录损坏都会中止列表读取，避免静默隐藏审计数据。</p>
     */
    public synchronized List<ExecutionRecordSummary> list() {
        if (!Files.exists(executionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(executionsDirectory)) {
            List<ExecutionRecordSummary> summaries = new ArrayList<>();
            for (Path file : entries
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                ExecutionRecord record = read(file);
                summaries.add(new ExecutionRecordSummary(
                        record.executionId(),
                        record.origin(),
                        record.status(),
                        record.steps().size(),
                        record.createdAt(),
                        record.startedAt(),
                        record.completedAt(),
                        record.updatedAt()
                ));
            }
            return summaries.stream()
                    .sorted(Comparator.comparing(
                            ExecutionRecordSummary::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder())
                    ))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("读取执行记录目录失败：" + executionsDirectory, exception);
        }
    }

    /** 拒绝符号链接和非普通文件，并校验反序列化记录至少包含 executionId。 */
    private ExecutionRecord read(Path file) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("执行记录必须是普通本地文件：" + file);
        }
        try {
            ExecutionRecord record = objectMapper.readValue(file.toFile(), ExecutionRecord.class);
            if (record.executionId() == null || record.executionId().isBlank()) {
                throw new IllegalStateException("执行记录缺少 executionId");
            }
            return record;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "读取执行记录失败：" + file + "；原因：" + exception.getMessage(),
                    exception
            );
        }
    }

    /** 在已校验的应用数据目录内解析执行记录文件名。 */
    private Path fileFor(String executionId) {
        return executionsDirectory.resolve(executionId + ".json").normalize();
    }

    /** 限制 executionId 只能使用安全字符，防止路径穿越和非法文件名。 */
    private void requireSafeId(String executionId) {
        if (executionId == null || !SAFE_ID.matcher(executionId).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EXECUTION_ID",
                    "executionId 格式非法"
            );
        }
    }
}
