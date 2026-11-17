package com.orderly.module.calendar;

import com.orderly.kernel.FileEntry;
import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.TrashEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarModuleTest {

    private final CalendarModule module = new CalendarModule();
    private final InMemoryRuntime runtime = new InMemoryRuntime();

    @Test
    void initializesCalendarOwnedTodoAndEventStateIdempotently() {
        module.initialize(runtime);
        module.initialize(runtime);

        assertThat(runtime.readItems("calendar")).hasSize(1);
        assertThat(runtime.readItems("module_calendar_todos")).hasSize(1);
    }

    @Test
    void createsUpdatesCompletesAndRollsBackCalendarTodo() {
        module.initialize(runtime);
        ModuleInvocation create = invocation(
                "calendar.todo.create",
                Map.of(
                        "title", "整理评审结论",
                        "dueAt", "2026-12-01 09:30",
                        "category", "工作",
                        "annotation", "补充风险项"
                )
        );
        Map<String, Object> snapshot = module.prepareSnapshot(create, runtime);
        ModuleResult created = module.execute(create, runtime);
        String id = String.valueOf(nested(created.output(), "item").get("id"));

        assertThat(runtime.readItems("module_calendar_todos"))
                .anyMatch(item -> id.equals(item.get("id")));

        ModuleInvocation update = invocation(
                "calendar.todo.update",
                Map.of("id", id, "annotation", "补充风险项与负责人")
        );
        module.execute(update, runtime);
        assertThat(runtime.readItems("module_calendar_todos"))
                .filteredOn(item -> id.equals(item.get("id")))
                .singleElement()
                .extracting(item -> item.get("annotation"))
                .isEqualTo("补充风险项与负责人");

        ModuleInvocation complete = invocation("calendar.todo.complete", Map.of("id", id));
        module.execute(complete, runtime);
        assertThat(runtime.readItems("module_calendar_todos"))
                .filteredOn(item -> id.equals(item.get("id")))
                .singleElement()
                .extracting(item -> item.get("status"))
                .isEqualTo("COMPLETED");

        module.rollback(create, snapshot, created.output(), runtime);
        assertThat(runtime.readItems("module_calendar_todos"))
                .noneMatch(item -> id.equals(item.get("id")));
    }

    @Test
    void createsReschedulesCancelsAndRollsBackEvents() {
        module.initialize(runtime);
        ModuleInvocation create = invocation(
                "calendar.event.create",
                Map.of(
                        "title", "方案讨论",
                        "startAt", "2026-12-02T10:00:00+08:00",
                        "endAt", "2026-12-02T11:00:00+08:00"
                )
        );
        Map<String, Object> createSnapshot = module.prepareSnapshot(create, runtime);
        ModuleResult created = module.execute(create, runtime);
        String id = String.valueOf(nested(created.output(), "event").get("id"));

        ModuleInvocation reschedule = invocation(
                "calendar.event.reschedule",
                Map.of("eventId", id, "newStartAt", "2026-12-02T14:00:00+08:00")
        );
        Map<String, Object> rescheduleSnapshot = module.prepareSnapshot(reschedule, runtime);
        module.execute(reschedule, runtime);
        assertThat(runtime.readItems("calendar"))
                .filteredOn(item -> id.equals(item.get("id")))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.get("startAt")).isEqualTo("2026-12-02T14:00:00+08:00");
                    assertThat(item.get("endAt")).isEqualTo("2026-12-02T15:00+08:00");
                });
        module.rollback(reschedule, rescheduleSnapshot, Map.of(), runtime);
        assertThat(runtime.readItems("calendar"))
                .filteredOn(item -> id.equals(item.get("id")))
                .singleElement()
                .extracting(item -> item.get("startAt"))
                .isEqualTo("2026-12-02T10:00:00+08:00");

        ModuleInvocation cancel = invocation("calendar.event.cancel", Map.of("eventId", id));
        module.execute(cancel, runtime);
        assertThat(runtime.readItems("calendar"))
                .filteredOn(item -> id.equals(item.get("id")))
                .singleElement()
                .extracting(item -> item.get("status"))
                .isEqualTo("CANCELLED");

        module.rollback(create, createSnapshot, created.output(), runtime);
        assertThat(runtime.readItems("calendar"))
                .noneMatch(item -> id.equals(item.get("id")));
    }

    @Test
    void acceptsLocalIsoDateTimeWithoutOffsetAndUsesSystemZone() {
        module.initialize(runtime);

        ModuleResult created = module.execute(invocation(
                "calendar.event.create",
                Map.of(
                        "title", "本地 ISO 日程",
                        "startAt", "2026-12-04T10:30",
                        "endAt", "2026-12-04T11:30"
                )
        ), runtime);

        Map<String, Object> event = nested(created.output(), "event");
        String startAt = String.valueOf(event.get("startAt"));
        String endAt = String.valueOf(event.get("endAt"));
        OffsetDateTime normalizedStart = OffsetDateTime.parse(startAt);
        assertThat(normalizedStart.getOffset())
                .isEqualTo(ZoneId.systemDefault().getRules().getOffset(normalizedStart.toLocalDateTime()));
        assertThat(startAt).startsWith("2026-12-04T10:30");
        assertThat(endAt).startsWith("2026-12-04T11:30");

        ModuleResult todo = module.execute(invocation(
                "calendar.todo.create",
                Map.of("title", "本地 ISO 待办", "dueAt", "2026-12-05T09:15")
        ), runtime);
        assertThat(String.valueOf(nested(todo.output(), "item").get("dueAt")))
                .startsWith("2026-12-05T09:15");
    }

    @Test
    void rejectsAmbiguousLegacyTitleAndInvalidTime() {
        module.initialize(runtime);
        ModuleInvocation create = invocation(
                "calendar.event.create",
                Map.of("title", "项目评审", "startAt", "2026-12-03T10:00:00+08:00")
        );
        module.execute(create, runtime);

        assertThatThrownBy(() -> module.execute(invocation(
                "calendar.reschedule",
                Map.of("title", "项目评审", "newStartAt", "2026-12-03T12:00:00+08:00")
        ), runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("多个同名日程");

        assertThatThrownBy(() -> module.execute(invocation(
                "calendar.event.create",
                Map.of("title", "错误时间", "startAt", "not-a-time")
        ), runtime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间必须");
    }

    private ModuleInvocation invocation(String actionId, Map<String, Object> arguments) {
        return new ModuleInvocation(
                CalendarModule.MODULE_ID,
                actionId,
                "plan-test",
                "step-test",
                arguments
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    private static final class InMemoryRuntime implements ModuleRuntime {

        private final Map<String, List<Map<String, Object>>> collections = new LinkedHashMap<>();

        @Override
        public String workspaceLabel() {
            return "test";
        }

        @Override
        public Path resolveWorkspacePath(String relativePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean exists(Path path) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FileEntry> listDirectory(Path directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path createDirectory(Path directory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path createTextFile(Path file, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readTextFile(Path file) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(Path source, Path target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TrashEntry moveToTrash(Path source, String planId, String stepId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path restoreFromTrash(String trashId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TrashEntry> listTrash() {
            return List.of();
        }

        @Override
        public List<Map<String, Object>> readItems(String collection) {
            return collections.getOrDefault(collection, List.of()).stream()
                    .map(LinkedHashMap::new)
                    .map(Map::copyOf)
                    .toList();
        }

        @Override
        public boolean hasItems(String collection) {
            return collections.containsKey(collection);
        }

        @Override
        public void writeItems(String collection, List<Map<String, Object>> items) {
            collections.put(collection, items.stream().map(LinkedHashMap::new).map(Map::copyOf).toList());
        }

        @Override
        public String newId() {
            return UUID.randomUUID().toString();
        }

        @Override
        public String relativePath(Path path) {
            throw new UnsupportedOperationException();
        }
    }
}
