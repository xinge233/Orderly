package com.orderly.host.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.runtime.WorkspaceRuntime;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地审计日志、运行态事件快照与 SSE 推送中心。
 *
 * <p>事件序号在启动时从 JSONL 文件恢复并继续单调递增；每次追加先加入内存列表并写
 * JSONL，再广播给当前 SSE 订阅者。运行态列表超过上限时移除最旧事件，持久文件保留
 * 追加历史。事件写入失败会抛出异常，不伪造成功。</p>
 */
@Component
public class ExecutionJournal {

    private static final int MAX_EVENTS = 2_000;

    private final AtomicLong sequence = new AtomicLong();
    private final ObjectMapper objectMapper;
    private final Path journalFile;
    private final List<ExecutionEvent> events = new ArrayList<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public ExecutionJournal(ObjectMapper objectMapper, WorkspaceRuntime runtime) {
        this.objectMapper = objectMapper;
        this.journalFile = runtime.dataRoot().resolve("events.jsonl");
    }

    /** 恢复本机审计序号，使重启后的事件编号继续单调递增。 */
    /**
     * 从 {@code events.jsonl} 恢复事件序号和内存快照。
     *
     * <p>文件不存在时创建空文件；空行跳过，缺失来源的旧事件标记为 LEGACY。
     * 任一 JSON 解析或 I/O 失败都会阻止启动。</p>
     */
    @PostConstruct
    public synchronized void load() {
        try {
            Files.createDirectories(journalFile.getParent());
            if (!Files.exists(journalFile)) {
                Files.writeString(journalFile, "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
                return;
            }
            for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                ExecutionEvent event = objectMapper.readValue(line, ExecutionEvent.class);
                if (event.origin() == null || event.origin().isBlank()) {
                    event = event.withOrigin("LEGACY");
                }
                events.add(event);
                sequence.accumulateAndGet(event.seq(), Math::max);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("恢复本地审计日志失败", exception);
        }
    }

    /** 以 SYSTEM 来源追加审计事件。 */
    public synchronized ExecutionEvent append(
            String type,
            String planId,
            String stepId,
            String moduleId,
            String actionId,
            String status,
            String message
    ) {
        return append(type, "SYSTEM", planId, stepId, moduleId, actionId, status, message);
    }

    /**
     * 追加一条只增事件。
     *
     * <p>分配全局单调序号，更新运行态列表（超过上限移除最旧项），持久化成功后才向
     * SSE 订阅者广播。持久化异常原样向上抛出。</p>
     */
    public synchronized ExecutionEvent append(
            String type,
            String origin,
            String planId,
            String stepId,
            String moduleId,
            String actionId,
            String status,
            String message
    ) {
        ExecutionEvent event = new ExecutionEvent(
                sequence.incrementAndGet(),
                type,
                origin,
                planId,
                stepId,
                moduleId,
                actionId,
                status,
                message,
                Instant.now()
        );
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
        persist(event);
        emitters.forEach(emitter -> send(emitter, event));
        return event;
    }

    /** 返回当前运行态事件的不可变快照。 */
    public synchronized List<ExecutionEvent> snapshot() {
        return List.copyOf(events);
    }

    /**
     * 建立 SSE 订阅并先回放当前事件快照。
     *
     * <p>完成、超时或错误时移除订阅者；连接保持到客户端或服务端关闭。</p>
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        Runnable remove = () -> emitters.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        snapshot().forEach(event -> send(emitter, event));
        return emitter;
    }

    /** 向单个订阅者发送事件；发送失败时移除并完成该订阅，不影响其他订阅者。 */
    private void send(SseEmitter emitter, ExecutionEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.seq()))
                    .name("execution")
                    .data(event));
        } catch (IOException | IllegalStateException exception) {
            emitters.remove(emitter);
            emitter.complete();
        }
    }

    /** 以 UTF-8 单行 JSON 追加写入本地审计日志。 */
    private void persist(ExecutionEvent event) {
        try {
            Files.writeString(
                    journalFile,
                    objectMapper.writeValueAsString(event) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("写入本地审计日志失败", exception);
        }
    }
}
