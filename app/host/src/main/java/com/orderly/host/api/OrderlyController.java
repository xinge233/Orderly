package com.orderly.host.api;

import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleUiAsset;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.event.ExecutionEvent;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.execution.ExecutionRunResult;
import com.orderly.host.execution.ExecutionService;
import com.orderly.host.plan.PlanService;
import com.orderly.host.plan.PlanView;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.host.theme.ThemeAsset;
import com.orderly.host.theme.ThemeCatalog;
import com.orderly.host.theme.ThemeCatalogSnapshot;
import com.orderly.host.theme.ThemeDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.Optional;

/** 本地前端使用的受控 HTTP 接口。 */
@RestController
@RequestMapping("/api")
public class OrderlyController {

    private final ContractCatalog catalog;
    private final PlanService plans;
    private final ExecutionService execution;
    private final ExecutionJournal journal;
    private final WorkspaceRuntime runtime;
    private final ThemeCatalog themes;

    public OrderlyController(
            ContractCatalog catalog,
            PlanService plans,
            ExecutionService execution,
            ExecutionJournal journal,
            WorkspaceRuntime runtime,
            ThemeCatalog themes
    ) {
        this.catalog = catalog;
        this.plans = plans;
        this.execution = execution;
        this.journal = journal;
        this.runtime = runtime;
        this.themes = themes;
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

    @GetMapping("/themes")
    public ThemeCatalogSnapshot themes() {
        return themes.snapshot();
    }

    @GetMapping("/themes/{id}")
    public ThemeDefinition theme(@PathVariable String id) {
        try {
            return themes.require(id);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "THEME_NOT_FOUND", exception.getMessage());
        }
    }

    @GetMapping("/themes/{id}/assets/{*asset}")
    public ResponseEntity<byte[]> themeAsset(
            @PathVariable String id,
            @PathVariable("asset") String asset
    ) {
        ThemeAsset resource = themes.readAsset(id, asset).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "THEME_ASSET_NOT_FOUND",
                        "主题资源不存在或未登记：" + id + "/" + asset
                )
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resource.contentType()))
                .header("Cache-Control", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource.content());
    }

    @GetMapping("/modules/{moduleId}/query/{queryId}")
    public Map<String, Object> queryModule(
            @PathVariable String moduleId,
            @PathVariable String queryId,
            @RequestParam Map<String, String> parameters
    ) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "模块不存在：" + moduleId)
        );
        QueryContract query = module.queries().stream()
                .filter(candidate -> candidate.id().equals(queryId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_QUERY_NOT_FOUND",
                        "模块未声明查询：" + moduleId + "/" + queryId
                ));
        Map<String, String> validated = validateQueryParameters(query, parameters);
        return catalog.requireExecutor(moduleId).query(queryId, validated, runtime);
    }

    @GetMapping("/modules/{moduleId}/ui/{asset}")
    public ResponseEntity<byte[]> moduleUi(
            @PathVariable String moduleId,
            @PathVariable String asset
    ) {
        ModuleUiAsset resource = catalog.readUiAsset(moduleId, asset).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_UI_NOT_FOUND",
                        "模块 UI 资源不存在：" + moduleId + "/" + asset
                )
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resource.contentType()))
                .header("Cache-Control", "no-cache")
                .body(resource.content());
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

    private Map<String, String> validateQueryParameters(
            QueryContract query,
            Map<String, String> supplied
    ) {
        Map<String, String> validated = new java.util.LinkedHashMap<>();
        for (ParameterContract parameter : query.parameters()) {
            String value = supplied.get(parameter.name());
            if (parameter.required() && (value == null || value.isBlank())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_QUERY_PARAMETERS",
                        "查询 " + query.id() + " 缺少必填参数：" + parameter.name()
                );
            }
            if (value != null) {
                validateQueryParameterType(query.id(), parameter, value);
                validated.put(parameter.name(), value);
            }
        }
        Optional<String> unknown = supplied.keySet().stream()
                .filter(name -> query.parameters().stream().noneMatch(parameter -> parameter.name().equals(name)))
                .findFirst();
        if (unknown.isPresent()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY_PARAMETERS",
                    "查询 " + query.id() + " 包含契约外参数：" + unknown.orElseThrow()
            );
        }
        return Map.copyOf(validated);
    }

    private void validateQueryParameterType(
            String queryId,
            ParameterContract parameter,
            String value
    ) {
        boolean valid = switch (parameter.type()) {
            case "string" -> true;
            case "boolean" -> value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
            case "integer" -> {
                try {
                    Integer.parseInt(value);
                    yield true;
                } catch (NumberFormatException exception) {
                    yield false;
                }
            }
            default -> false;
        };
        if (!valid) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY_PARAMETERS",
                    "查询 " + queryId + " 参数 " + parameter.name() + " 类型应为 " + parameter.type()
            );
        }
    }
}
