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

/** 将完整执行记录以单文件原子 JSON 保存在本地应用目录。 */
@Component
public class ExecutionRecordStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final ObjectMapper objectMapper;
    private final Path executionsDirectory;

    public ExecutionRecordStore(ObjectMapper objectMapper, WorkspaceRuntime runtime) {
        this.objectMapper = objectMapper;
        this.executionsDirectory = runtime.dataRoot().resolve("executions");
    }

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

    public Optional<ExecutionRecord> find(String executionId) {
        requireSafeId(executionId);
        Path file = fileFor(executionId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    public ExecutionRecord require(String executionId) {
        return find(executionId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND,
                "EXECUTION_NOT_FOUND",
                "执行记录不存在：" + executionId
        ));
    }

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

    private Path fileFor(String executionId) {
        return executionsDirectory.resolve(executionId + ".json").normalize();
    }

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
