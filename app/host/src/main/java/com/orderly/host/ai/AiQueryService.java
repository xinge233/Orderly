package com.orderly.host.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 校验并执行模块 Query；AI 查询仅在宿主授权边界内运行。 */
@Service
public class AiQueryService {

    static final int MAX_QUERIES = 4;
    static final int MAX_SINGLE_RESULT_BYTES = 16 * 1024;
    static final int MAX_TOTAL_RESULT_BYTES = 64 * 1024;

    private final ContractCatalog catalog;
    private final ModuleControlService moduleControl;
    private final WorkspaceRuntime runtime;
    private final ObjectMapper objectMapper;

    public AiQueryService(
            ContractCatalog catalog,
            ModuleControlService moduleControl,
            WorkspaceRuntime runtime,
            ObjectMapper objectMapper
    ) {
        this.catalog = catalog;
        this.moduleControl = moduleControl;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> executeHttpQuery(
            String moduleId,
            String queryId,
            Map<String, String> arguments
    ) {
        return executeOne(moduleId, queryId, arguments, false);
    }

    public List<AiContracts.QueryResult> executeAiQueries(
            List<AiContracts.QueryCall> queries
    ) {
        if (queries == null || queries.isEmpty() || queries.size() > MAX_QUERIES) {
            throw invalidResponse("QUERY 必须包含 1 至 4 条查询");
        }
        List<AiContracts.QueryResult> results = new ArrayList<>();
        int totalBytes = 0;
        for (AiContracts.QueryCall query : queries) {
            if (query == null || blank(query.moduleId()) || blank(query.queryId())) {
                throw invalidResponse("QUERY 字段不完整");
            }
            AiContracts.QueryResult result;
            try {
                Map<String, Object> payload = executeOne(
                        query.moduleId(),
                        query.queryId(),
                        query.arguments(),
                        true
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
            totalBytes += resultBytes;
            results.add(result);
        }
        return List.copyOf(results);
    }

    private Map<String, Object> executeOne(
            String moduleId,
            String queryId,
            Map<String, ?> supplied,
            boolean requireAiCallable
    ) {
        return moduleControl.withEnabledBusinessModule(moduleId, module -> {
            QueryContract query = module.queries().stream()
                    .filter(candidate -> candidate.id().equals(queryId))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.NOT_FOUND,
                            "MODULE_QUERY_NOT_FOUND",
                            "模块未声明查询：" + moduleId + "/" + queryId
                    ));
            if (requireAiCallable
                    && (!query.aiCallable()
                    || !moduleControl.isQueryAiCallableEnabled(moduleId, queryId))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "QUERY_AI_DISABLED",
                        "查询未授权给 AI 调用：" + moduleId + "/" + queryId
                );
            }
            Map<String, String> validated = validateParameters(query, supplied);
            Map<String, Object> payload = catalog.requireExecutor(moduleId)
                    .query(queryId, validated, runtime);
            if (payload == null) {
                throw new IllegalStateException("查询未返回对象结果");
            }
            return payload;
        });
    }

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

    private ApiException invalidParameter(String queryId, ParameterContract parameter) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "INVALID_QUERY_PARAMETERS",
                "查询 " + queryId + " 参数 " + parameter.name()
                        + " 类型应为 " + parameter.type()
        );
    }

    private int jsonBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw invalidResponse("查询结果无法序列化");
        }
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "查询执行失败";
        }
        String trimmed = message.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private ApiException invalidResponse(String message) {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "AI_INVALID_RESPONSE",
                message
        );
    }
}
