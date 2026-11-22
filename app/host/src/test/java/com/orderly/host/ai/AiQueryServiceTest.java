package com.orderly.host.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.orderly.host.contract.ContractCatalog;
import com.orderly.host.contract.ModuleContract;
import com.orderly.host.contract.ModuleSystemMetadata;
import com.orderly.host.contract.ParameterContract;
import com.orderly.host.contract.QueryContract;
import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.modulecontrol.ModuleControlQueryService;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiQueryServiceTest {

    private final ContractCatalog catalog = mock(ContractCatalog.class);
    private final ModuleControlService moduleControl = mock(ModuleControlService.class);
    private final ModuleControlQueryService moduleControlQueries =
            mock(ModuleControlQueryService.class);
    private final WorkspaceRuntime runtime = mock(WorkspaceRuntime.class);
    private final OrderlyModule executor = mock(OrderlyModule.class);
    private final ExecutionJournal journal = mock(ExecutionJournal.class);
    private AiQueryService service;
    private ModuleContract module;

    @BeforeEach
    void setUp() {
        service = new AiQueryService(
                catalog,
                moduleControl,
                moduleControlQueries,
                runtime,
                new ObjectMapper(),
                journal
        );
        QueryContract query = new QueryContract(
                "test.list",
                "测试查询",
                "读取测试数据",
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
    void emitsInvocationBeforeQueryAndTerminalStatusAfterward() {
        when(executor.query(eq("test.list"), anyMap(), eq(runtime)))
                .thenReturn(Map.of("items", List.of()));

        List<AiContracts.QueryResult> results = service.executeAiQueries(List.of(query()));

        assertThat(results).singleElement().satisfies(result ->
                assertThat(result.status()).isEqualTo(AiContracts.QueryStatus.OK));
        var order = inOrder(journal, executor);
        order.verify(journal).append(
                "QUERY",
                "AI_QUERY",
                null,
                "q1",
                "module_test",
                null,
                "RUNNING",
                "AI 正在读取模块查询：test.list"
        );
        order.verify(executor).query(eq("test.list"), anyMap(), eq(runtime));
        order.verify(journal).append(
                "QUERY",
                "AI_QUERY",
                null,
                "q1",
                "module_test",
                null,
                "OK",
                "查询完成"
        );
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
    void dynamicAiCallableOnlyControlsAiQuery() {
        when(moduleControl.isQueryAiCallableEnabled("module_test", "test.list"))
                .thenReturn(false);
        when(executor.query(eq("test.list"), anyMap(), eq(runtime)))
                .thenReturn(Map.of("items", List.of()));

        Map<String, Object> direct = service.executeHttpQuery(
                "module_test",
                "test.list",
                Map.of()
        );
        assertThat(direct).containsEntry("items", List.of());

        List<AiContracts.QueryResult> dynamicDisabled = service.executeAiQueries(List.of(query()));

        assertThat(dynamicDisabled.get(0).status()).isEqualTo(AiContracts.QueryStatus.FAILED);
        verify(executor).query(eq("test.list"), anyMap(), eq(runtime));
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
        verifyNoInteractions(journal);
        verifyNoInteractions(executor);
    }

    @Test
    void routesWhitelistedControlQueriesThroughHostGovernanceService() {
        QueryContract query = new QueryContract(
                "module_system_control.module.list",
                "查询业务模块",
                "读取模块摘要",
                List.of()
        );
        ModuleContract control = new ModuleContract(
                "module_system_control",
                "模组设置",
                "系统治理",
                10_000,
                new ModuleSystemMetadata(
                        "MODULE_CONTROL",
                        true,
                        List.of("MODULE_GOVERNANCE")
                ),
                List.of(),
                List.of(query),
                List.of()
        );
        when(catalog.isSystemModule("module_system_control")).thenReturn(true);
        when(catalog.findModule("module_system_control")).thenReturn(java.util.Optional.of(control));
        when(moduleControlQueries.supports(
                "module_system_control",
                "module_system_control.module.list"
        )).thenReturn(true);
        when(moduleControl.isQueryAiCallableEnabled(
                "module_system_control",
                "module_system_control.module.list"
        )).thenReturn(true);
        when(moduleControlQueries.query(
                "module_system_control.module.list",
                Map.of()
        )).thenReturn(Map.of("items", List.of(), "count", 0));

        List<AiContracts.QueryResult> results = service.executeAiQueries(List.of(
                new AiContracts.QueryCall(
                        "module_system_control",
                        "module_system_control.module.list",
                        Map.of()
                )
        ));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(AiContracts.QueryStatus.OK);
            assertThat(result.payload()).isEqualTo(Map.of("items", List.of(), "count", 0));
        });
        verifyNoInteractions(executor);
    }

    private AiContracts.QueryCall query() {
        return new AiContracts.QueryCall("module_test", "test.list", Map.of());
    }
}
