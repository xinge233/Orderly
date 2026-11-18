package com.orderly.host.modulecontrol;

import com.orderly.host.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 模块状态读写与执行生命周期共用的协调器。
 *
 * <p>读操作共享状态读锁；治理写操作在生命周期锁内取得状态写锁。执行许可在生命周期
 * 锁内取得状态读锁并计数，直到关闭才释放。普通治理变更不预先检查 activeExecutions，
 * 而要求立即拒绝的变更会在发现 activeExecutions 大于零时失败。</p>
 */
@Component
public class ModuleControlCoordinator {

    private final ReentrantLock lifecycleLock = new ReentrantLock(true);
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);
    private int activeExecutions;

    /** 在共享状态读锁内执行只读操作，结束后必定释放锁。 */
    public <T> T read(Supplier<T> operation) {
        stateLock.readLock().lock();
        try {
            return operation.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /** 在生命周期锁和状态写锁内执行一次治理变更，不预先因执行计数拒绝。 */
    public <T> T mutate(Supplier<T> operation) {
        return mutate(false, operation);
    }

    /**
     * 执行治理变更，并在已有执行时立即拒绝，而不是等待执行结束后生效。
     *
     * <p>用于停用模块和刷新这类不能与正在执行的动作并发发生的操作。</p>
     */
    public <T> T mutateRejectingDuringExecution(Supplier<T> operation) {
        return mutate(true, operation);
    }

    /**
     * 开始一次执行并持有状态读锁。
     *
     * <p>返回值必须关闭。关闭后减少执行计数并释放读锁；读锁使执行期间的写治理
     * 无法越过执行边界。</p>
     */
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

    /**
     * 在生命周期锁内串行化治理写操作。
     *
     * <p>可选的前置执行检查发生在取得状态写锁之前，从而让“执行中使用”拒绝保持
     * 立即失败语义；operation 的异常会原样传播且锁始终释放。</p>
     */
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

    /** 校验执行计数后减一，并释放 beginExecution 持有的状态读锁。 */
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

    /**
     * 一次执行生命周期的协调许可。
     *
     * <p>关闭许可会释放执行读锁；正常使用只关闭一次，底层计数不一致时抛出异常。</p>
     */
    public static final class ExecutionPermit implements AutoCloseable {
        private final ModuleControlCoordinator coordinator;
        private boolean closed;

        private ExecutionPermit(ModuleControlCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        /** 结束执行并释放协调器持有的读锁。 */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                coordinator.endExecution();
            }
        }
    }
}
