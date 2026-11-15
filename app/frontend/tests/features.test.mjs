import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { OrderlyApi } from "../src/api.js";
import {
    EVENT_ORIGIN,
    inferEventOrigin,
    normalizeExecutionRecord
} from "../src/models.js";
import { normalizePlanningFeature, SettingsStore } from "../src/settings.js";

function read(relativePath) {
    return readFile(new URL(relativePath, import.meta.url), "utf8");
}

function between(source, startMarker, endMarker) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start + startMarker.length);
    assert.notEqual(start, -1, `缺少起始标记：${startMarker}`);
    assert.notEqual(end, -1, `缺少结束标记：${endMarker}`);
    return source.slice(start, end);
}

function memoryStorage() {
    let value = null;
    return {
        getItem() {
            return value;
        },
        setItem(_key, next) {
            value = next;
        },
        removeItem() {
            value = null;
        }
    };
}

test("feature and execution API calls use the host contract", async () => {
    const requests = [];
    const api = new OrderlyApi({
        fetchImpl: async (url, options) => {
            requests.push({ url, options });
            return new Response("{}", { status: 200 });
        }
    });

    await api.getSystemFeatures();
    await api.setPlanningEnabled(true);
    await api.getExecutions();
    await api.rollbackExecution("execution/a?b");

    assert.deepEqual(requests.map(({ url }) => url), [
        "/api/system/features",
        "/api/system/features/planning",
        "/api/executions",
        "/api/executions/execution%2Fa%3Fb/rollback"
    ]);
    assert.deepEqual(
        requests.slice(1).map(({ options }) => [
            options.method,
            options.body ? JSON.parse(options.body) : null
        ]),
        [
            ["PUT", { enabled: true }],
            ["GET", null],
            ["POST", {}]
        ]
    );
});

test("planning features default to disabled and never enter local appearance storage", () => {
    assert.equal(normalizePlanningFeature(undefined), false);
    assert.equal(normalizePlanningFeature({ planningEnabled: true }), true);
    assert.equal(normalizePlanningFeature({ planningEnabled: "yes" }), false);

    const storage = memoryStorage();
    const store = new SettingsStore({ storage, storageKey: "features" });
    store.update({ planningEnabled: true, motion: "minimal" });

    assert.equal("planningEnabled" in store.getSnapshot(), false);
    assert.doesNotMatch(storage.getItem(), /planningEnabled/);
});

test("event origins are explicit or inferred from legacy type and plan id", () => {
    assert.equal(inferEventOrigin({ origin: "module-control" }), EVENT_ORIGIN.MODULE_CONTROL);
    assert.equal(inferEventOrigin({ type: "AI_DIRECT_EXECUTION" }), EVENT_ORIGIN.AI_DIRECT);
    assert.equal(inferEventOrigin({ type: "SYSTEM_FEATURE" }), EVENT_ORIGIN.SYSTEM);
    assert.equal(inferEventOrigin({ planId: "plan-1" }), EVENT_ORIGIN.PLAN);
    assert.equal(inferEventOrigin({}), EVENT_ORIGIN.SYSTEM);
});

test("execution records preserve rollback identity and availability", () => {
    const completed = normalizeExecutionRecord({
        id: "exec-1",
        origin: "AI_DIRECT",
        status: "COMPLETED",
        goal: "整理待办",
        createdAt: "2026-11-15T10:00:00Z"
    });
    const unsupported = normalizeExecutionRecord({
        executionId: "exec-2",
        origin: "MODULE_CONTROL",
        status: "COMPLETED",
        canRollback: false,
        rollbackDisabledReason: "系统记录不可回滚"
    });

    assert.equal(completed.executionId, "exec-1");
    assert.equal(completed.origin, EVENT_ORIGIN.AI_DIRECT);
    assert.equal(completed.canRollback, true);
    assert.equal(unsupported.canRollback, false);
    assert.equal(unsupported.rollbackDisabledReason, "系统记录不可回滚");
});

test("frontend gates plan surfaces with host state and keeps history independently available", async () => {
    const app = await read("../src/app.js");
    const html = await read("../index.html");
    const applyFeature = between(
        app,
        "function applyPlanningFeatureState",
        "function setSettingsStatus"
    );
    const overview = between(app, "function renderOverview", "async function loadOverviewCounts");
    const history = between(app, "async function loadHistory", "function renderHistory");
    const settings = between(app, "function persistPlanningFeature", "function settingRow");

    assert.match(app, /const HISTORY_VIEWS = new Set\(\["timeline", "summary"\]\);/);
    assert.match(applyFeature, /document\.documentElement\.dataset\.planning/);
    assert.match(applyFeature, /dock\.hidden = !next;/);
    assert.match(applyFeature, /dock\.inert = !next;/);
    assert.match(overview, /const planningRegions = state\.planningEnabled/);
    assert.match(overview, /data-planning-region="plan"/);
    assert.match(overview, /data-planning-region="execution"/);
    assert.match(history, /Promise\.allSettled\(\[\s*api\.getExecutions\(\),\s*api\.getRuns\(\),\s*api\.getEvents\(\)/);
    assert.ok(history.indexOf("api.getExecutions()") < history.indexOf("api.getRuns()"));
    assert.match(settings, /api\.setPlanningEnabled\(enabled\)/);
    assert.match(settings, /normalizePlanningFeature/);
    assert.match(settings, /input\.checked = previous;/);
    assert.match(settings, /setSettingsStatus\(message, \{ error: true \}\)/);
    assert.doesNotMatch(app, /api\.rollbackPlan\(/);
    assert.match(app, /api\.rollbackExecution\(/);
    assert.match(html, /id="history-view-rail"[\s\S]*?data-history-view="timeline"[\s\S]*?data-history-view="summary"/);
    assert.match(html, /data-history-view="timeline" aria-pressed="true"/);
});
