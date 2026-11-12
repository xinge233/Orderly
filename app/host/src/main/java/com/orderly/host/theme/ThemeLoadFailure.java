package com.orderly.host.theme;

/** 仅用于把单个目录主题的校验失败转换为局部 ThemeIssue。 */
final class ThemeLoadFailure extends Exception {

    private final ThemeIssue issue;

    ThemeLoadFailure(ThemeIssue issue) {
        super(issue.message());
        this.issue = issue;
    }

    ThemeIssue issue() {
        return issue;
    }

    static ThemeLoadFailure of(String id, String code, String message) {
        return new ThemeLoadFailure(ThemeIssue.of(id, code, message));
    }
}
