package com.orderly.host.ai;

import com.orderly.host.execution.AiActionService;
import com.orderly.host.execution.ExecutionRecord;
import com.orderly.kernel.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
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

        AiPlanService service = new AiPlanService(actions);
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
        assertThat(service.confirm(plan.planId())).isSameAs(record);
        verify(actions).executeAiPlan(anyList());
    }
}
