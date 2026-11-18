package com.orderly.host.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.modulecontrol.ModuleControlService;
import com.orderly.host.runtime.WorkspaceRuntime;
import com.orderly.kernel.OrderlyModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiQueryServiceTest {

    private final ContractCatalog catalog = mock(ContractCatalog.class);
    private final ModuleControlService moduleControl = mock(ModuleControlService.class);
    private final WorkspaceRuntime runtime = mock(WorkspaceRuntime.class);
    private final OrderlyModule executor = mock(OrderlyModule.class);
    private AiQueryService service;
    private ModuleContract module;

    @BeforeEach
    void setUp() {
        service = new AiQueryService(catalog, moduleControl, runtime, new ObjectMapper());
        QueryContract query = new QueryContract(
                "test.list",
                "测试查询",
                "读取测试数据",
                true,
                List.of(new ParameterContract("label", "string", false, "标签"))
        );
        module = new ModuleContract(
                "module_test",
                "测试",
                "测试模块",
                1,
                null,
                List.of(),
                List.of(query),
                List.of()
        );
        when(moduleControl.withEnabledBusinessModule(eq("module_test"), any()))
                .thenAnswer(invocation -> {
                    Function<ModuleContract, Map<String, Object>> operation =
                            invocation.getArgument(1);
                    return operation.apply(module);
                });
        when(moduleControl.isQueryAiCallableEnabled("module_test", "test.list"))
                .thenReturn(true);
        when(catalog.requireExecutor("module_test")).thenReturn(executor);
    }

    @Test
    void executesAtMostFourQueries() {
        when(executor.query(eq("test.list"), anyMap(), eq(runtime)))
                .thenReturn(Map.of("items", List.of()));
        List<AiContracts.QueryCall> calls = List.of(
                query(),
                query(),
                query(),
                query()
        );

        List<AiContracts.QueryResult> results = service.executeAiQueries(calls);

        assertThat(results).hasSize(4).allMatch(result ->
                result.status() == AiContracts.QueryStatus.OK);
        verify(executor, org.mockito.Mockito.times(4))
                .query(eq("test.list"), anyMap(), eq(runtime));
        assertThatThrownBy(() -> service.executeAiQueries(List.of(
                query(), query(), query(), query(), query()
        )))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE"));
    }

    @Test
    void truncatesSingleAndAggregateOversizedResults() {
        int fourResultsEachUnderSingleLimit = 16_360;
        when(executor.query(eq("test.list"), anyMap(), eq(runtime)))
                .thenReturn(Map.of("text", "x".repeat(fourResultsEachUnderSingleLimit)));

        List<AiContracts.QueryResult> aggregate = service.executeAiQueries(List.of(
                query(), query(), query(), query()
        ));

        assertThat(aggregate.get(3).status()).isEqualTo(AiContracts.QueryStatus.TRUNCATED);
        assertThat(aggregate.get(3).payload()).isNull();

        when(executor.query(eq("test.list"), anyMap(), eq(runtime)))
                .thenReturn(Map.of("text", "x".repeat(17_000)));
        List<AiContracts.QueryResult> single = service.executeAiQueries(List.of(query()));

        assertThat(single.get(0).status()).isEqualTo(AiContracts.QueryStatus.TRUNCATED);
        assertThat(single.get(0).payload()).isNull();
    }

    @Test
    void staticAndDynamicAiCallableAreBothEnforced() {
        QueryContract hidden = new QueryContract(
                "test.hidden",
                "隐藏查询",
                "静态禁止",
                false,
                List.of()
        );
        module = new ModuleContract(
                module.moduleId(),
                module.title(),
                module.description(),
                module.order(),
                null,
                List.of(),
                List.of(hidden),
                List.of()
        );

        List<AiContracts.QueryResult> staticDisabled = service.executeAiQueries(List.of(
                new AiContracts.QueryCall("module_test", "test.hidden", Map.of())
        ));

        assertThat(staticDisabled.get(0).status()).isEqualTo(AiContracts.QueryStatus.FAILED);
        verifyNoInteractions(executor);

        module = new ModuleContract(
                module.moduleId(),
                module.title(),
                module.description(),
                module.order(),
                null,
                List.of(),
                List.of(new QueryContract(
                        "test.list",
                        "测试查询",
                        "读取测试数据",
                        true,
                        List.of()
                )),
                List.of()
        );
        when(moduleControl.isQueryAiCallableEnabled("module_test", "test.list"))
                .thenReturn(false);

        List<AiContracts.QueryResult> dynamicDisabled = service.executeAiQueries(List.of(query()));

        assertThat(dynamicDisabled.get(0).status()).isEqualTo(AiContracts.QueryStatus.FAILED);
        verifyNoInteractions(executor);
    }

    @Test
    void disabledModuleAndInvalidArgumentsReturnFailedResults() {
        when(moduleControl.withEnabledBusinessModule(eq("module_test"), any()))
                .thenThrow(new ApiException(
                        HttpStatus.CONFLICT,
                        "MODULE_DISABLED",
                        "模块已停用"
                ));
        List<AiContracts.QueryResult> disabled = service.executeAiQueries(List.of(query()));

        assertThat(disabled.get(0).status()).isEqualTo(AiContracts.QueryStatus.FAILED);
        assertThat(disabled.get(0).payload()).isNull();

        QueryContract required = new QueryContract(
                "test.list",
                "测试查询",
                "读取测试数据",
                true,
                List.of(new ParameterContract("label", "string", true, "标签"))
        );
        module = new ModuleContract(
                module.moduleId(),
                module.title(),
                module.description(),
                module.order(),
                null,
                List.of(),
                List.of(required),
                List.of()
        );
        when(moduleControl.withEnabledBusinessModule(eq("module_test"), any()))
                .thenAnswer(invocation -> {
                    Function<ModuleContract, Map<String, Object>> operation =
                            invocation.getArgument(1);
                    return operation.apply(module);
                });

        List<AiContracts.QueryResult> invalidArguments =
                service.executeAiQueries(List.of(query()));
        assertThat(invalidArguments.get(0).status()).isEqualTo(AiContracts.QueryStatus.FAILED);
        verifyNoInteractions(executor);
    }

    private AiContracts.QueryCall query() {
        return new AiContracts.QueryCall("module_test", "test.list", Map.of());
    }
}
