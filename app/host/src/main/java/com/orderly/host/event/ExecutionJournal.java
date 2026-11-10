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

/** 内存执行日志与 SSE 推送中心。 */
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
                events.add(event);
                sequence.accumulateAndGet(event.seq(), Math::max);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("恢复本地审计日志失败", exception);
        }
    }

    public synchronized ExecutionEvent append(
            String type,
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

    public synchronized List<ExecutionEvent> snapshot() {
        return List.copyOf(events);
    }

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
