import assert from "node:assert/strict";
import test from "node:test";

import {
    formatBytes,
    joinPath,
    mergeEvents,
    normalizePlan,
    parentPath,
    requiresDeletePhrase,
    validateConfirmation
} from "../src/models.js";

test("normalizes a structured plan without trusting extra fields", () => {
    const plan = normalizePlan({
        id: "plan-7",
        goal: "整理文件",
        impact: { files: 2, directories: 1 },
        steps: [{
            id: 1,
            title: "移动文件",
            moduleId: "module_fs",
            actionId: "fs.move",
            riskLevel: "write"
        }]
    });

    assert.equal(plan.planId, "plan-7");
    assert.equal(plan.kind, "PLAN");
    assert.equal(plan.steps[0].risk, "WRITE");
    assert.equal(plan.impact, "files：2；directories：1");
});

test("write confirmation requires an explicit checkbox", () => {
    const plan = normalizePlan({
        planId: "p1",
        kind: "PLAN",
        steps: [{ id: "1", risk: "WRITE", actionId: "fs.mkdir" }]
    });

    assert.equal(validateConfirmation(plan, {
        confirmed: false,
        phrase: ""
    }).allowed, false);
    assert.equal(validateConfirmation(plan, {
        confirmed: true,
        phrase: ""
    }).allowed, true);
});

test("delete confirmation requires the exact phrase", () => {
    const plan = normalizePlan({
        planId: "p2",
        kind: "PLAN",
        steps: [{ id: "1", risk: "DELETE", actionId: "fs.trash" }]
    });

    assert.equal(requiresDeletePhrase(plan), true);
    assert.equal(validateConfirmation(plan, {
        confirmed: true,
        phrase: "delete"
    }).allowed, false);
    assert.equal(validateConfirmation(plan, {
        confirmed: true,
        phrase: "删除"
    }).allowed, true);
});

test("question responses can never be confirmed", () => {
    const plan = normalizePlan({
        planId: "q1",
        kind: "QUESTION",
        message: "请明确要操作哪个目录"
    });

    assert.equal(validateConfirmation(plan, {
        confirmed: true,
        phrase: "删除"
    }).allowed, false);
});

test("events are deduplicated and ordered by sequence", () => {
    const result = mergeEvents(
        [
            { sequence: 2, status: "RUNNING", message: "执行" },
            { sequence: 1, status: "COMPLETED", message: "开始" }
        ],
        [
            { sequence: 2, status: "COMPLETED", message: "完成" },
            { sequence: 3, status: "ROLLED_BACK", message: "回滚" }
        ]
    );

    assert.deepEqual(
        result.map((event) => [event.sequence, event.status]),
        [[1, "COMPLETED"], [2, "COMPLETED"], [3, "ROLLED_BACK"]]
    );
    assert.equal(result[1].message, "完成");
});

test("path and size helpers remain deterministic", () => {
    assert.equal(joinPath("归档/", "/子目录//"), "归档/子目录");
    assert.equal(parentPath("归档/子目录/文件.txt"), "归档/子目录");
    assert.equal(formatBytes(1024), "1.0 KB");
    assert.equal(formatBytes(10 * 1024), "10 KB");
    assert.equal(formatBytes("invalid"), "—");
});
