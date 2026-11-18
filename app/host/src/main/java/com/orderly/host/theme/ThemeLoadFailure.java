package com.orderly.host.theme;

/**
 * 单个目录主题加载失败的内部受检异常。
 *
 * <p>异常只携带可公开的 {@link ThemeIssue}，用于在扫描循环中隔离问题主题。目录级
 * 重复 ID、多个默认主题等无法局部消解的冲突不会使用该异常，而会中止启动。</p>
 */
final class ThemeLoadFailure extends Exception {

    private final ThemeIssue issue;

    /** 使用问题说明作为异常消息，保留结构化问题供扫描器记录。 */
    ThemeLoadFailure(ThemeIssue issue) {
        super(issue.message());
        this.issue = issue;
    }

    /** 返回稳定错误码、说明和可选版本信息组成的问题。 */
    ThemeIssue issue() {
        return issue;
    }

    /** 创建不带版本详情的局部加载失败。 */
    static ThemeLoadFailure of(String id, String code, String message) {
        return new ThemeLoadFailure(ThemeIssue.of(id, code, message));
    }
}
