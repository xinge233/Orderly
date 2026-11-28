import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { OrderlyApi } from "../src/api.js";
import {
    EVENT_ORIGIN,
    inferEventOrigin,
    normalizeExecutionRecord,
    statusLabel
} from "../src/models.js";
import {
    normalizeAiPlanFeature,
    normalizePlanningFeature,
    SettingsStore
} from "../src/settings.js";

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
    await api.setAiPlanEnabled(false);
    await api.getExecutions();
    await api.rollbackExecution("execution/a?b");

    assert.deepEqual(requests.map(({ url }) => url), [
        "/api/system/features",
        "/api/system/features/planning",
        "/api/system/features/ai-plan",
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
            ["PUT", { enabled: false }],
            ["GET", null],
            ["POST", {}]
        ]
    );
});

test("planning features default to disabled and never enter local appearance storage", () => {
    assert.equal(normalizePlanningFeature(undefined), false);
    assert.equal(normalizePlanningFeature({ planningEnabled: true }), true);
    assert.equal(normalizePlanningFeature({ planningEnabled: "yes" }), false);
    assert.equal(normalizeAiPlanFeature(undefined), true);
    assert.equal(normalizeAiPlanFeature({ aiPlanEnabled: false }), false);
    assert.equal(normalizeAiPlanFeature({ aiPlanEnabled: "no" }), true);

    const storage = memoryStorage();
    const store = new SettingsStore({ storage, storageKey: "features" });
    store.update({ planningEnabled: true, aiPlanEnabled: false, motion: "minimal" });

    assert.equal("planningEnabled" in store.getSnapshot(), false);
    assert.equal("aiPlanEnabled" in store.getSnapshot(), false);
    assert.doesNotMatch(storage.getItem(), /planningEnabled/);
    assert.doesNotMatch(storage.getItem(), /aiPlanEnabled/);
});

test("AI query-result sharing is immutable and absent from appearance settings", async () => {
    const app = await read("../src/app.js");
    const settings = await read("../src/settings.js");
    const aiSettings = between(
        app,
        "function renderAiSettings",
        "function renderSystemSettings"
    );
    const persist = between(
        app,
        "async function persistAiSettings",
        "async function clearAiSettings"
    );

    assert.doesNotMatch(aiSettings, /allowSendQueryResultsToModel/);
    assert.doesNotMatch(persist, /allowSendQueryResultsToModel/);
    assert.doesNotMatch(persist, /localStorage|settingsStore/);
    assert.doesNotMatch(settings, /allowSendQueryResultsToModel/);
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
        stepCount: 2,
        canRollback: true,
        rollbackDisabledReason: "",
        createdAt: "2026-11-15T10:00:00Z"
    });
    const partial = normalizeExecutionRecord({
        executionId: "exec-2",
        origin: "MODULE_CONTROL",
        status: "PARTIALLY_ROLLED_BACK",
        canRollback: true
    });
    const unsupported = normalizeExecutionRecord({
        executionId: "exec-3",
        origin: "MODULE_CONTROL",
        status: "COMPLETED",
        canRollback: false,
        rollbackDisabledReason: "系统记录不可回滚"
    });

    assert.equal(completed.executionId, "exec-1");
    assert.equal(completed.origin, EVENT_ORIGIN.AI_DIRECT);
    assert.equal(completed.goal, "整理待办");
    assert.equal(completed.stepCount, 2);
    assert.equal(completed.canRollback, true);
    assert.equal(partial.canRollback, true);
    assert.equal(partial.goal, "执行 #exec-2");
    assert.equal(statusLabel(partial.status), "部分回滚");
    assert.equal(statusLabel("STOPPING"), "停止中");
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
    const aiExecutionPolicy = between(
        app,
        "function persistAiPlanFeature",
        "function renderSystemSettings"
    );

    assert.match(app, /const HISTORY_VIEWS = new Set\(\["timeline", "summary"\]\);/);
    assert.match(applyFeature, /document\.documentElement\.dataset\.planning/);
    assert.doesNotMatch(applyFeature, /#ai-dock|aiDialog/);
    assert.doesNotMatch(app, /aiDialog\?\.setEnabled/);
    assert.match(overview, /const planningRegions = state\.planningEnabled/);
    assert.match(overview, /data-planning-region="plan"/);
    assert.match(overview, /data-planning-region="execution"/);
    assert.match(app, /input\.id = "plan-goal-input"/);
    assert.match(app, /input\.setAttribute\("aria-label", "计划目标"\)/);
    assert.match(app, /void draftGoal\(input\.value\)/);
    const fillCommand = between(app, "function fillCommand", "function connectEventStream");
    assert.match(fillCommand, /#plan-goal-input/);
    assert.match(fillCommand, /navigate\("overview"\)/);
    assert.doesNotMatch(fillCommand, /aiDialog/);
    assert.match(history, /Promise\.allSettled\(\[\s*api\.getExecutions\(\),\s*api\.getRuns\(\),\s*api\.getEvents\(\)/);
    assert.ok(history.indexOf("api.getExecutions()") < history.indexOf("api.getRuns()"));
    assert.match(app, /const HISTORY_EVENT_LIMIT = 30;/);
    assert.doesNotMatch(app, /ROLLBACK_ORIGINS/);
    assert.match(app, /if \(!run\.canRollback\)/);
    assert.match(app, /最近 \$\{HISTORY_EVENT_LIMIT\}/);
    assert.match(settings, /api\.setPlanningEnabled\(enabled\)/);
    assert.match(settings, /normalizePlanningFeature/);
    assert.match(settings, /input\.checked = previous;/);
    assert.match(settings, /setSettingsStatus\(message, \{ error: true \}\)/);
    assert.match(aiExecutionPolicy, /api\.setAiPlanEnabled\(enabled\)/);
    assert.match(aiExecutionPolicy, /normalizeAiPlanFeature/);
    assert.match(aiExecutionPolicy, /input\.checked = previous;/);
    assert.match(aiExecutionPolicy, /setSettingsStatus\(message, \{ error: true \}\)/);
    assert.match(app, /"aiPlanEnabled",\s*"允许 AI 生成计划"/);
    assert.doesNotMatch(app, /api\.rollbackPlan\(/);
    assert.match(app, /api\.rollbackExecution\(/);
    assert.match(html, /id="history-view-rail"[\s\S]*?data-history-view="timeline"[\s\S]*?data-history-view="summary"/);
    assert.match(html, /data-history-view="timeline" aria-pressed="true"/);
});

test("motion preview iframes do not hold host event-stream connections", async () => {
    const app = await read("../src/app.js");
    const bootstrap = between(app, "async function bootstrap", "async function loadModuleUis");

    assert.match(bootstrap, /const previewRequest = motionPreviewRequest\(\);/);
    assert.match(bootstrap, /if \(!previewRequest\) \{\s*connectEventStream\(\);/);
    assert.match(bootstrap, /if \(!previewRequest\) \{\s*scheduleModuleBuildPoll\(\);/);
});

test("preview console replays AI module-call feedback without host side effects", async () => {
    const preview = await read("../preview/dev-preview.js");
    const css = await read("../preview/dev-preview.css");
    const playback = between(preview, "async function playAiModuleCallSequence", "function createConsole");

    assert.match(preview, /import \{ icon \} from "\.\.\/src\/icons\.js";/);
    assert.match(preview, /element\("button", "preview-console__ai-motion", "AI 调用"\)/);
    assert.match(preview, /querySelectorAll\("\.nav-item--module\[data-module-id\]"\)/);
    assert.match(playback, /moduleButton\.dataset\.aiActivity = "running"/);
    assert.match(playback, /element\("span", "nav-ai-progress"\)/);
    assert.match(playback, /element\("span", "nav-ai-result"\)/);
    assert.match(playback, /icon\("check-plain", 12\)/);
    assert.match(playback, /result\.dataset\.phase = "success"/);
    assert.match(playback, /querySelector\("#nav-ai-result-layer"\)/);
    assert.match(playback, /result\.classList\.add\("is-leaving"\)/);
    assert.match(playback, /root\.dataset\.orderlyPreviewAiCallPlaying = "true"/);
    assert.match(playback, /querySelector\("#mobile-menu"\)\?\.click\(\)/);
    assert.match(playback, /openedMobileSidebar[\s\S]*?querySelector\("#mobile-scrim"\)\?\.click\(\)/);
    assert.match(playback, /root\.dataset\.motion = previousMotion/);
    assert.match(playback, /previousMotion === "none"[\s\S]*?root\.dataset\.motion = "gentle"/);
    assert.doesNotMatch(playback, /fetch\(|OrderlyApi|interpretGoal|confirmAiPlan/);
    assert.doesNotMatch(playback, /#ai-launcher|ai-composer-close/);
    assert.match(css, /\.preview-console__actions/);
    assert.match(css, /\.preview-console__ai-motion\[data-playing="true"\]/);
    assert.doesNotMatch(css, /preview-ai-ring|ai-launcher__ring|ai-launcher__status/);
});

test("settings provide reusable accessible help tooltips", async () => {
    const app = await read("../src/app.js");
    const css = await read("../styles/app.css");
    const helper = between(app, "function settingHelp", "function settingRow");

    assert.match(helper, /function settingLabel/);
    assert.match(helper, /icon\("info", 15\)/);
    assert.match(helper, /tooltip\.setAttribute\("role", "tooltip"\)/);
    assert.match(helper, /trigger\.setAttribute\("aria-describedby", tooltip\.id\)/);
    assert.match(helper, /event\.key === "Escape"/);

    assert.match(
        app,
        /"aiPlanEnabled",\s*"允许 AI 生成计划",[\s\S]*?开启后，AI 可先生成需要确认的计划/
    );
    assert.match(
        app,
        /"aiPlanEnabled",\s*"允许 AI 生成计划",[\s\S]*?写入与删除仍由宿主统一校验/
    );
    assert.match(css, /\.setting-help__trigger:hover \+ \.setting-help__tooltip/);
    assert.match(css, /\.setting-help__trigger:focus-visible \+ \.setting-help__tooltip/);
    assert.doesNotMatch(css, /\.setting-help:hover \.setting-help__tooltip/);
    assert.doesNotMatch(css, /\.setting-help:focus-within \.setting-help__tooltip/);
    assert.match(
        css,
        /@media \(max-width: 620px\)[\s\S]*?\.setting-help \{[\s\S]*?position: static;/
    );
    assert.match(css, /max-width: calc\(100% - var\(--space-8\)\);/);
});
