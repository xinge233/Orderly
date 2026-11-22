package com.orderly.host.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlInterfaces;
import com.orderly.host.modulecontrol.ModuleControlQueryService;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 校验并执行模块只读 Query。
 *
 * <p>HTTP 查询与 AI 查询共用同一套契约参数校验和模块运行时入口。AI 查询额外检查
 * 每回合数量、动态 AI 开关以及单条和总量结果上限；所有原始结果
 * 默认只回本地前端，不会由本服务直接发送给模型。</p>
 */
@Service
public class AiQueryService {

    /** 单个 AI 回合最多执行的 Query 数量。 */
    static final int MAX_QUERIES = 4;
    /** 单条查询 JSON 结果的发送上限。 */
    static final int MAX_SINGLE_RESULT_BYTES = 16 * 1024;
    /** 一回合全部查询 JSON 结果的累计发送上限。 */
    static final int MAX_TOTAL_RESULT_BYTES = 64 * 1024;

    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final ModuleControlQueryService moduleControlQueries;
    private final WorkspaceRuntime runtime;
    private final ObjectMapper objectMapper;
    private final ExecutionJournal journal;

    public AiQueryService(
            ContractCatalog catalog,
            ModuleControlService moduleControl,
            ModuleControlQueryService moduleControlQueries,
            WorkspaceRuntime runtime,
            ObjectMapper objectMapper,
            ExecutionJournal journal
    ) {
        this.catalog = catalog;
        this.moduleControl = moduleControl;
        this.moduleControlQueries = moduleControlQueries;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
        this.journal = journal;
    }

    /**
     * 执行模块自身或本地前端发起的只读 Query。
     *
     * <p>该入口仍要求业务模块启用并校验契约参数，但不检查 AI 专用可调用开关。</p>
     */
    public Map<String, Object> executeHttpQuery(
            String moduleId,
            String queryId,
            Map<String, String> arguments
    ) {
        return executeOne(moduleId, queryId, arguments, false, () -> {});
    }

    /**
     * 执行 Python 提议的 1 至 4 条 Query。
     *
     * <p>每次执行都会重新校验模块与查询授权。单条 JSON 结果超过 16 KiB 或累计超过
     * 64 KiB 时只返回截断状态而不保留 payload；单条执行失败被映射为失败结果，
     * 以便宿主把受控状态返回前端或显式允许后的模型续聊。</p>
     *
     * <p>只有模块、AI 开关和参数校验全部通过后才写入 RUNNING 事件，使前端调用提示
     * 与实际模块读取保持同一时序；终态事件只记录状态和摘要，不包含 Query payload。</p>
     */
    public List<AiContracts.QueryResult> executeAiQueries(
            List<AiContracts.QueryCall> queries
    ) {
        if (queries == null || queries.isEmpty() || queries.size() > MAX_QUERIES) {
            throw invalidResponse("QUERY 必须包含 1 至 4 条查询");
        }
        List<AiContracts.QueryResult> results = new ArrayList<>();
        int totalBytes = 0;
        for (int index = 0; index < queries.size(); index++) {
            AiContracts.QueryCall query = queries.get(index);
            if (query == null || blank(query.moduleId()) || blank(query.queryId())) {
                throw invalidResponse("QUERY 字段不完整");
            }
            String stepId = "q" + (index + 1);
            boolean[] invoked = {false};
            AiContracts.QueryResult result;
            try {
                Map<String, Object> payload = executeOne(
                        query.moduleId(),
                        query.queryId(),
                        query.arguments(),
                        true,
                        () -> {
                            journal.append(
                                    "QUERY",
                                    "AI_QUERY",
                                    null,
                                    stepId,
                                    query.moduleId(),
                                    null,
                                    "RUNNING",
                                    "AI 正在读取模块查询：" + query.queryId()
                            );
                            invoked[0] = true;
                        }
                );
                int payloadBytes = jsonBytes(payload);
                if (payloadBytes > MAX_SINGLE_RESULT_BYTES) {
                    result = new AiContracts.QueryResult(
                            query.moduleId(),
                            query.queryId(),
                            AiContracts.QueryStatus.TRUNCATED,
                            null,
                            "查询结果超过单条 16 KiB 限制，已省略 payload"
                    );
                } else {
                    result = new AiContracts.QueryResult(
                            query.moduleId(),
                            query.queryId(),
                            AiContracts.QueryStatus.OK,
                            payload,
                            "查询完成"
                    );
                }
            } catch (RuntimeException exception) {
                result = new AiContracts.QueryResult(
                        query.moduleId(),
                        query.queryId(),
                        AiContracts.QueryStatus.FAILED,
                        null,
                        safeMessage(exception)
                );
            }

            int resultBytes = jsonBytes(result);
            if (result.status() == AiContracts.QueryStatus.OK
                    && totalBytes + resultBytes > MAX_TOTAL_RESULT_BYTES) {
                result = new AiContracts.QueryResult(
                        query.moduleId(),
                        query.queryId(),
                        AiContracts.QueryStatus.TRUNCATED,
                        null,
                        "查询结果超过总 64 KiB 限制，已省略 payload"
                );
                resultBytes = jsonBytes(result);
            }
            if (invoked[0]) {
                journal.append(
                        "QUERY",
                        "AI_QUERY",
                        null,
                        stepId,
                        query.moduleId(),
                        null,
                        result.status().name(),
                        result.message()
                );
            }
            totalBytes += resultBytes;
            results.add(result);
        }
        return List.copyOf(results);
    }

    /**
     * 通过宿主运行模块查询。
     *
     * <p>查询 ID、参数类型和未知参数均来自模块契约；AI 调用还必须满足治理层动态
     * AI 开关。业务模块执行器只能通过 {@link WorkspaceRuntime} 读取模块自有只读状态；
     * 系统控制模块查询由宿主治理查询服务实现。</p>
     */
    private Map<String, Object> executeOne(
            String moduleId,
            String queryId,
            Map<String, ?> supplied,
            boolean requireAiCallable,
            Runnable beforeExecute
    ) {
        if (catalog.isSystemModule(moduleId)) {
            return executeControlQuery(
                    moduleId,
                    queryId,
                    supplied,
                    requireAiCallable,
                    beforeExecute
            );
        }
        return moduleControl.withEnabledBusinessModule(moduleId, module -> {
            QueryContract query = requireQuery(module, queryId);
            if (requireAiCallable
                    && !moduleControl.isQueryAiCallableEnabled(moduleId, queryId)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "QUERY_AI_DISABLED",
                        "查询未授权给 AI 调用：" + moduleId + "/" + queryId
                );
            }
            Map<String, String> validated = validateParameters(query, supplied);
            beforeExecute.run();
            Map<String, Object> payload = catalog.requireExecutor(moduleId)
                    .query(queryId, validated, runtime);
            if (payload == null) {
                throw new IllegalStateException("查询未返回对象结果");
            }
            return payload;
        });
    }

    /** 系统控制模块只允许执行宿主白名单查询，并绕过业务模块启停检查。 */
    private Map<String, Object> executeControlQuery(
            String moduleId,
            String queryId,
            Map<String, ?> supplied,
            boolean requireAiCallable,
            Runnable beforeExecute
    ) {
        ModuleContract module = catalog.findModule(moduleId).orElseThrow(
                () -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_NOT_FOUND",
                        "模块不存在：" + moduleId
                )
        );
        if (!ModuleControlInterfaces.MODULE_ID.equals(moduleId)
                || !moduleControlQueries.supports(moduleId, queryId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "MODULE_PROTECTED",
                    "系统模块不通过普通查询入口调用：" + moduleId
            );
        }
        QueryContract query = requireQuery(module, queryId);
        if (requireAiCallable
                && !moduleControl.isQueryAiCallableEnabled(moduleId, queryId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "QUERY_AI_DISABLED",
                    "查询未授权给 AI 调用：" + moduleId + "/" + queryId
            );
        }
        Map<String, String> validated = validateParameters(query, supplied);
        beforeExecute.run();
        return moduleControlQueries.query(queryId, validated);
    }

    /** 从模块契约中解析指定查询。 */
    private QueryContract requireQuery(ModuleContract module, String queryId) {
        return module.queries().stream()
                .filter(candidate -> candidate.id().equals(queryId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "MODULE_QUERY_NOT_FOUND",
                        "模块未声明查询：" + module.moduleId() + "/" + queryId
                ));
    }

    /**
     * 按契约校验必填、类型和未知参数，并生成传给模块运行时的字符串参数。
     */
    private Map<String, String> validateParameters(
            QueryContract query,
            Map<String, ?> supplied
    ) {
        Map<String, ?> source = supplied == null ? Map.of() : supplied;
        Map<String, String> validated = new LinkedHashMap<>();
        for (ParameterContract parameter : query.parameters()) {
            Object rawValue = source.get(parameter.name());
            String value = parameterValue(query.id(), parameter, rawValue);
            if (parameter.required() && (value == null || value.isBlank())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "INVALID_QUERY_PARAMETERS",
                        "查询 " + query.id() + " 缺少必填参数：" + parameter.name()
                );
            }
            if (value != null) {
                validated.put(parameter.name(), value);
            }
        }
        Optional<String> unknown = source.keySet().stream()
                .filter(name -> query.parameters().stream()
                        .noneMatch(parameter -> parameter.name().equals(name)))
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

    /**
     * 将 IPC/HTTP 标量转换为契约声明类型。
     *
     * <p>当前 Query 执行器接收 {@code Map<String, String>}，因此字符串、布尔值和整数
     * 在入口处统一转成文本；对象、数组、小数和超出范围的整数会被拒绝。</p>
     */
    private String parameterValue(
            String queryId,
            ParameterContract parameter,
            Object rawValue
    ) {
        if (rawValue == null) {
            return null;
        }
        String value;
        if (rawValue instanceof String text) {
            value = text;
        } else if (rawValue instanceof Boolean bool) {
            value = bool.toString();
        } else if (rawValue instanceof Byte || rawValue instanceof Short
                || rawValue instanceof Integer || rawValue instanceof Long) {
            value = rawValue.toString();
        } else {
            throw invalidParameter(queryId, parameter);
        }
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
            throw invalidParameter(queryId, parameter);
        }
        return value;
    }

    /** 构造统一的 Query 参数类型错误。 */
    private ApiException invalidParameter(String queryId, ParameterContract parameter) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_QUERY_PARAMETERS",
                "查询 " + queryId + " 参数 " + parameter.name()
                        + " 类型应为 " + parameter.type()
        );
    }

    /** 以实际 UTF-8 JSON 编码长度执行结果大小限制。 */
    private int jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw invalidResponse("查询结果无法序列化");
        }
    }

    /** 保留可诊断原因并限制长度，避免失败结果无限膨胀。 */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "查询执行失败";
        }
        String trimmed = message.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    /** 判断 IPC 字段是否缺失或仅含空白。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 构造 Query 决策不符合 AI 契约时的网关错误。 */
    private ApiException invalidResponse(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                message
        );
    }
}
