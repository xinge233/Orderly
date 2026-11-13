package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** 模块状态读写与执行生命周期共用的协调器。 */
@Component
public class ModuleControlCoordinator {

    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);
    private int activeExecutions;

    public <T> T read(Supplier<T> operation) {
        stateLock.readLock().lock();
        try {
            return operation.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public <T> T mutate(Supplier<T> operation) {
        return mutate(false, operation);
    }

    /**
     * 停用操作在已有执行时必须立即失败，不能等待执行结束后偷偷生效。
     */
    public <T> T mutateRejectingDuringExecution(Supplier<T> operation) {
        return mutate(true, operation);
    }

    public ExecutionPermit beginExecution() {
        lifecycleLock.lock();
        try {
            stateLock.readLock().lock();
            activeExecutions++;
            return new ExecutionPermit(this);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private <T> T mutate(boolean rejectDuringExecution, Supplier<T> operation) {
        lifecycleLock.lock();
        try {
            if (rejectDuringExecution && activeExecutions > 0) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "MODULE_EXECUTION_IN_PROGRESS",
                        "计划执行期间不能停止模块；完成或失败后再试"
                );
            }
            stateLock.writeLock().lock();
            try {
                return operation.get();
            } finally {
                stateLock.writeLock().unlock();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void endExecution() {
        lifecycleLock.lock();
        try {
            if (activeExecutions <= 0) {
                throw new IllegalStateException("执行协调器计数不一致");
            }
            activeExecutions--;
            stateLock.readLock().unlock();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public static final class ExecutionPermit implements AutoCloseable {
        private final ModuleControlCoordinator coordinator;
        private boolean closed;

        private ExecutionPermit(ModuleControlCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                coordinator.endExecution();
            }
        }
    }
}
