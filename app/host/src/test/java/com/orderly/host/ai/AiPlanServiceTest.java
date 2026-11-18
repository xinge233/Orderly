package com.orderly.host.ai;

import com.orderly.host.event.ExecutionJournal;
import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.host.modulecontrol.InterfaceUsageRegistry;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiPlanServiceTest {

    @Test
    void createsAndConfirmsIndependentAiPlan() {
        AiActionService actions = mock(AiActionService.class);
        AiActionService.ActionCall call = new AiActionService.ActionCall(
                "module_fs",
                "fs.mkdir",
                Map.of("path", "归档")
        );
        when(actions.preview(anyList())).thenReturn(new AiActionService.Preview(
                List.of(call),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        "fs.mkdir",
                        "创建目录",
                        "创建目录",
                        RiskLevel.WRITE,
                        Map.of("path", "归档")
                )),
                RiskLevel.WRITE
        ));
        ExecutionRecord record = mock(ExecutionRecord.class);
        when(record.status()).thenReturn("COMPLETED");
        when(actions.executeAiPlan(anyList())).thenReturn(record);

        InterfaceUsageRegistry usage = new InterfaceUsageRegistry();
        ExecutionJournal journal = mock(ExecutionJournal.class);
        AiPlanService service = new AiPlanService(actions, usage, journal);
        AiContracts.Plan plan = service.create(
                "创建归档目录",
                List.of(new AiContracts.Action(
                        "module_fs",
                        "fs.mkdir",
                        Map.of("path", "归档")
                ))
        );

        assertThat(plan.status()).isEqualTo("PREVIEW");
        assertThat(plan.steps()).hasSize(1);
        assertThat(usage.isActionInUse("module_fs", "fs.mkdir", java.time.Instant.now()))
                .isTrue();
        verify(journal).append(
                "AI_PLAN",
                "AI_PLAN",
                plan.planId(),
                null,
                null,
                null,
                "PREVIEW",
                "独立 AI 计划已生成，引用接口进入使用中"
        );
        assertThat(service.confirm(plan.planId())).isSameAs(record);
        assertThat(usage.isActionInUse("module_fs", "fs.mkdir", java.time.Instant.now()))
                .isFalse();
        verify(actions).executeAiPlan(anyList());
    }

    @Test
    void failedExecutionReleasesPlanUsageAndCannotBeConfirmedAgain() {
        AiActionService actions = mock(AiActionService.class);
        AiActionService.ActionCall call = new AiActionService.ActionCall(
                "module_fs",
                "fs.mkdir",
                Map.of("path", "失败目录")
        );
        when(actions.preview(anyList())).thenReturn(new AiActionService.Preview(
                List.of(call),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        "fs.mkdir",
                        "创建目录",
                        "创建目录",
                        RiskLevel.WRITE,
                        Map.of("path", "失败目录")
                )),
                RiskLevel.WRITE
        ));
        when(actions.executeAiPlan(anyList()))
                .thenThrow(new IllegalStateException("测试执行失败"));

        InterfaceUsageRegistry usage = new InterfaceUsageRegistry();
        AiPlanService service = new AiPlanService(actions, usage, mock(ExecutionJournal.class));
        AiContracts.Plan plan = service.create(
                "创建失败目录",
                List.of(new AiContracts.Action(
                        "module_fs",
                        "fs.mkdir",
                        Map.of("path", "失败目录")
                ))
        );

        assertThatThrownBy(() -> service.confirm(plan.planId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("测试执行失败");
        assertThat(usage.isActionInUse("module_fs", "fs.mkdir", java.time.Instant.now()))
                .isFalse();
        assertThatThrownBy(() -> service.confirm(plan.planId()))
                .hasMessageContaining("已经确认");
    }

    @Test
    void auditFailureRollsBackPreviewPlanUsage() {
        AiActionService actions = mock(AiActionService.class);
        AiActionService.ActionCall call = new AiActionService.ActionCall(
                "module_fs",
                "fs.mkdir",
                Map.of("path", "审计失败")
        );
        when(actions.preview(anyList())).thenReturn(new AiActionService.Preview(
                List.of(call),
                List.of(new AiActionService.PreviewStep(
                        "module_fs",
                        "fs.mkdir",
                        "创建目录",
                        "创建目录",
                        RiskLevel.WRITE,
                        Map.of("path", "审计失败")
                )),
                RiskLevel.WRITE
        ));
        ExecutionJournal journal = mock(ExecutionJournal.class);
        doThrow(new IllegalStateException("审计写入失败"))
                .when(journal)
                .append(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );

        InterfaceUsageRegistry usage = new InterfaceUsageRegistry();
        AiPlanService service = new AiPlanService(actions, usage, journal);

        assertThatThrownBy(() -> service.create(
                "创建审计失败目录",
                List.of(new AiContracts.Action(
                        "module_fs",
                        "fs.mkdir",
                        Map.of("path", "审计失败")
                ))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("审计写入失败");
        assertThat(usage.isActionInUse("module_fs", "fs.mkdir", java.time.Instant.now()))
                .isFalse();
    }
}
