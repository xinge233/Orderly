package com.orderly.platform.execution;

/**
 * 回滚阶段结果。
 *
 * @param attempted 是否实际尝试回滚
 * @param successful 回滚是否成功
 * @param message 可读说明
 */
public record RollbackResult(boolean attempted, boolean successful, String message) {

    /**
     * 创建无需回滚的结果。
     *
     * @param message 说明
     * @return 未尝试回滚的结果
     */
    public static RollbackResult notRequired(String message) {
        return new RollbackResult(false, true, message);
    }
}
