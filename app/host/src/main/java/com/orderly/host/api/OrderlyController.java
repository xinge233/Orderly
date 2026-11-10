package com.orderly.host.api;

import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.event.ExecutionEvent;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.FileEntry;
import com.orderly.kernel.TrashEntry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 本地前端使用的受控 HTTP 接口。 */
@RestController
@RequestMapping("/api")
public class OrderlyController {

    private final ContractCatalog catalog;
    private final PlanService plans;
    private final ExecutionService execution;
    private final ExecutionJournal journal;
    private final WorkspaceRuntime runtime;

    public OrderlyController(
            ContractCatalog catalog,
            PlanService plans,
            ExecutionService execution,
            ExecutionJournal journal,
            WorkspaceRuntime runtime
    ) {
        this.catalog = catalog;
        this.plans = plans;
        this.execution = execution;
        this.journal = journal;
        this.runtime = runtime;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "planner", "LOCAL_RULES",
                "workspace", runtime.workspaceLabel(),
                "javaVersion", Runtime.version().feature()
        );
    }

    @GetMapping("/modules")
    public List<ModuleContract> modules() {
        return catalog.modules();
    }

    @GetMapping("/files")
    public Map<String, Object> files(@RequestParam(defaultValue = "") String path) {
        List<FileEntry> entries = runtime.listDirectory(runtime.resolveWorkspacePath(path));
        return Map.of("path", path, "entries", entries);
    }

    @GetMapping("/todos")
    public List<Map<String, Object>> todos() {
        return runtime.readItems("todos");
    }

    @GetMapping("/calendar")
    public List<Map<String, Object>> calendar() {
        return runtime.readItems("calendar");
    }

    @GetMapping("/trash")
    public List<TrashEntry> trash() {
        return runtime.listTrash();
    }

    @PostMapping("/plans")
    public PlanView createPlan(@RequestBody GoalRequest request) {
        return plans.create(request.goal());
    }

    @PostMapping("/plans/{planId}/confirm")
    public ExecutionRunResult confirm(
            @PathVariable String planId,
            @RequestBody ConfirmRequest request
    ) {
        return execution.confirm(planId, request.confirmed(), request.confirmation());
    }

    @PostMapping("/plans/{planId}/rollback")
    public ExecutionRunResult rollback(@PathVariable String planId) {
        return execution.rollback(planId);
    }

    @GetMapping("/runs")
    public List<PlanView> runs() {
        return plans.list();
    }

    @GetMapping("/events")
    public List<ExecutionEvent> events() {
        return journal.snapshot();
    }

    @GetMapping(path = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return journal.subscribe();
    }

    /** 用户目标。 */
    public record GoalRequest(String goal) {
    }

    /** 计划确认参数。 */
    public record ConfirmRequest(boolean confirmed, String confirmation) {
        public ConfirmRequest {
            confirmation = confirmation == null ? "" : confirmation;
        }
    }
}
