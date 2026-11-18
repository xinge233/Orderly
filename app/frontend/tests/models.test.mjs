import assert from "node:assert/strict";
import test from "node:test";

import {
    formatBytes,
    joinPath,
    mergeEvents,
    normalizeAiQueryResponse,
    normalizePlan,
    parentPath,
    requiresDeletePhrase,
    summarizeQueryPayload,
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

test("query responses keep only valid statuses and local payload summaries", () => {
    const query = normalizeAiQueryResponse({
        kind: "QUERY",
        message: "已完成本地查询",
        queries: [
            { moduleId: "module_fs", queryId: "fs.list" },
            { moduleId: "module_todo", queryId: "todo.list" },
            { moduleId: "module_calendar", queryId: "calendar.list" }
        ],
        queryResults: [
            {
                moduleId: "module_fs",
                queryId: "fs.list",
                status: "OK",
                payload: { files: 2 },
                message: "目录已读取"
            },
            {
                moduleId: "module_todo",
                queryId: "todo.list",
                status: "FAILED",
                payload: { hidden: true },
                message: "待办不可用"
            },
            {
                moduleId: "module_calendar",
                queryId: "calendar.list",
                status: "TRUNCATED",
                payload: { hidden: true },
                message: "仅返回前 20 条"
            }
        ]
    });

    assert.equal(query.valid, true);
    assert.equal(query.status, "FAILED");
    assert.deepEqual(query.queries.map((item) => item.status), ["OK", "FAILED", "TRUNCATED"]);
    assert.deepEqual(query.queries[0].payload, { files: 2 });
    assert.equal(Object.hasOwn(query.queries[1], "payload"), false);
    assert.equal(Object.hasOwn(query.queries[2], "payload"), false);
    assert.match(summarizeQueryPayload(query.queries[0].payload), /files.*2/);
});

test("query responses cap display at four and degrade invalid items safely", () => {
    const queries = Array.from({ length: 5 }, (_, index) => ({
        moduleId: "module_fs",
        queryId: `fs.list.${index}`
    }));
    const query = normalizeAiQueryResponse({
        kind: "QUERY",
        queries,
        queryResults: queries.map((item) => ({
            ...item,
            status: "OK",
            payload: { count: 1 }
        }))
    });
    const invalid = normalizeAiQueryResponse({
        kind: "QUERY",
        queries: [{ moduleId: "module_fs", queryId: "fs.list" }],
        queryResults: [{
            moduleId: "module_fs",
            queryId: "fs.list",
            status: "UNKNOWN",
            payload: { hidden: true }
        }]
    });

    assert.equal(query.queries.length, 4);
    assert.equal(query.status, "TRUNCATED");
    assert.equal(query.degraded, true);
    assert.match(query.notice, /最多|仅展示前 4 条/);
    assert.equal(invalid.valid, true);
    assert.equal(invalid.queries[0].status, "FAILED");
    assert.equal(Object.hasOwn(invalid.queries[0], "payload"), false);
    assert.match(invalid.queries[0].message, /无法识别/);
});

test("malformed query envelopes and payload summaries never throw", () => {
    const invalid = normalizeAiQueryResponse({ kind: "QUERY", queries: [] });
    const circular = {};
    circular.self = circular;

    assert.equal(invalid.valid, false);
    assert.deepEqual(invalid.queries, []);
    assert.match(invalid.error, /1 至 4/);
    assert.equal(typeof summarizeQueryPayload(circular), "string");
    assert.ok(summarizeQueryPayload("x".repeat(30), 20).length <= 20);
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
