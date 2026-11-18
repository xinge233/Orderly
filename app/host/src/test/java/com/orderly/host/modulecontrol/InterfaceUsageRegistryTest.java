package com.orderly.host.modulecontrol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InterfaceUsageRegistryTest {

    @Test
    void planUsageTransfersAtomicallyToExecutionAndReleasesAfterward() {
        InterfaceUsageRegistry usage = new InterfaceUsageRegistry();
        Instant now = Instant.now();
        usage.registerPlan(
                "plan-1",
                now.plusSeconds(60),
                List.of(new InterfaceUsageRegistry.ActionRef("module_demo", "demo.write"))
        );

        assertThat(usage.isActionInUse("module_demo", "demo.write", now)).isTrue();
        try (var permit = usage.beginPlanExecution("plan-1")) {
            assertThat(usage.isActionInUse("module_demo", "demo.write", now)).isTrue();
            assertThat(usage.inUseActionKeys(now)).containsExactly("module_demo/demo.write");
        }
        assertThat(usage.isActionInUse("module_demo", "demo.write", now)).isFalse();
    }

    @Test
    void expiredPlanUsageIsRemovedWithoutAnExecutionTransition() {
        InterfaceUsageRegistry usage = new InterfaceUsageRegistry();
        Instant now = Instant.now();
        usage.registerPlan(
                "plan-expired",
                now.minusSeconds(1),
                List.of(new InterfaceUsageRegistry.ActionRef("module_demo", "demo.write"))
        );

        assertThat(usage.isActionInUse("module_demo", "demo.write", now)).isFalse();
        assertThat(usage.inUseActionKeys(now)).isEmpty();
    }
}
