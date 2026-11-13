package com.orderly.host.support;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TestBusinessModule implements OrderlyModule {

    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;

    public static void blockNextExecution() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    public static void awaitExecutionStarted() throws InterruptedException {
        if (entered == null || !entered.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("测试模块未进入执行");
        }
    }

    public static void releaseExecution() {
        CountDownLatch current = release;
        if (current != null) {
            current.countDown();
        }
        entered = null;
        release = null;
    }

    @Override
    public String moduleId() {
        return "module_business";
    }

    @Override
    public Map<String, Object> query(
            String queryId,
            Map<String, String> parameters,
            ModuleRuntime runtime
    ) {
        return Map.of("count", 1, "queryId", queryId);
    }

    @Override
    public Map<String, Object> prepareSnapshot(ModuleInvocation invocation, ModuleRuntime runtime) {
        return Map.of("test", true);
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        CountDownLatch currentEntered = entered;
        CountDownLatch currentRelease = release;
        if (currentEntered != null && currentRelease != null) {
            currentEntered.countDown();
            try {
                if (!currentRelease.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试执行等待超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试执行被中断", exception);
            }
        }
        return ModuleResult.completed("测试动作完成");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("测试动作已回滚");
    }
}
