import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    canDragAiDock,
    executionCompletedModules,
    executionFailureMessage,
    executionSucceeded,
    higherAiRisk,
    invocationEventActivity,
    invocationEventModuleId,
    resolveAiPanelResizeWidth,
    resolveAiSnapPosition,
    resolveAiSurfaceLayout
} from "../src/ai-dialog.js";

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

test("AI risk ordering selects the highest action risk", () => {
    assert.equal(higherAiRisk("READ_ONLY", "WRITE"), "WRITE");
    assert.equal(higherAiRisk("WRITE", "DELETE"), "DELETE");
});

test("AI execution status uses the host result instead of the model message", () => {
    const failed = {
        status: "FAILED_ROLLED_BACK",
        steps: [{
            moduleId: "module_calendar",
            status: "FAILED",
            message: "时间格式不合法"
        }]
    };
    const completed = {
        status: "COMPLETED",
        steps: [{
            moduleId: "module_calendar",
            status: "COMPLETED",
            message: "已创建日程"
        }]
    };
    const inconsistent = {
        status: "COMPLETED",
        steps: [{
            moduleId: "module_calendar",
            status: "FAILED",
            message: "步骤失败"
        }]
    };
    const partial = {
        status: "PARTIAL",
        steps: [
            { moduleId: "module_calendar", status: "COMPLETED" },
            { moduleId: "module_fs", status: "FAILED", message: "目录不可用" }
        ]
    };
    const partialRollback = {
        status: "FAILED_PARTIALLY_ROLLED_BACK",
        steps: [{
            moduleId: "module_calendar",
            status: "ROLLBACK_FAILED",
            message: "回滚时文件被占用"
        }]
    };
    const rollbackFailed = {
        status: "ROLLBACK_FAILED",
        steps: []
    };
    const partiallyRolledBack = {
        status: "PARTIALLY_ROLLED_BACK",
        steps: []
    };

    assert.equal(executionSucceeded(failed), false);
    assert.equal(executionFailureMessage(failed), "时间格式不合法");
    assert.deepEqual(executionCompletedModules(failed), []);
    assert.equal(executionSucceeded(completed), true);
    assert.deepEqual(executionCompletedModules(completed), ["module_calendar"]);
    assert.equal(executionSucceeded(inconsistent), false);
    assert.deepEqual(executionCompletedModules(inconsistent), []);
    assert.equal(executionSucceeded(partial), false);
    assert.deepEqual(executionCompletedModules(partial), ["module_calendar"]);
    assert.equal(executionSucceeded(partialRollback), false);
    assert.equal(executionFailureMessage(partialRollback), "回滚时文件被占用");
    assert.deepEqual(executionCompletedModules(partialRollback), []);
    assert.equal(executionSucceeded(rollbackFailed), false);
    assert.equal(executionFailureMessage(rollbackFailed), "执行失败且回滚失败");
    assert.deepEqual(executionCompletedModules(rollbackFailed), []);
    assert.equal(executionSucceeded(partiallyRolledBack), false);
    assert.equal(executionFailureMessage(partiallyRolledBack), "执行仅部分回滚");
    assert.deepEqual(executionCompletedModules(partiallyRolledBack), []);
});

test("AI dialog delegates model work through injected callbacks", async () => {
    const component = await read("../src/ai-dialog.js");

    assert.match(
        component,
        /import \{ icon, normalizeAiIcon, paintAiIcon \} from "\.\/icons\.js"/
    );
    assert.match(component, /from "\.\/settings\.js"/);
    assert.doesNotMatch(component, /\bfetch\s*\(/);
    assert.doesNotMatch(component, /OrderlyApi|draftPlan|executeAiActions/);
    assert.match(component, /interpretGoal/);
    assert.match(component, /confirmAiPlan/);
    assert.match(component, /beforeAiTurn/);
    assert.match(component, /await beforeAiTurn\(\{ goal \}\)/);
    assert.match(component, /clientRequestId:/);
    assert.match(component, /API Key/);
});

test("AI dialog renders bounded QUERY results without inventing actions or plans", async () => {
    const component = await read("../src/ai-dialog.js");
    const css = await read("../styles/app.css");
    const models = await read("../src/models.js");

    assert.match(component, /normalizeAiQueryResponse/);
    assert.match(component, /buildQueryPayloadView/);
    assert.match(component, /normalizeQueryView/);
    assert.match(component, /response\?\.kind === "QUERY" \|\| response\?\.kind === "VIEW"/);
    assert.match(component, /mode: "query"/);
    assert.match(component, /function renderQueryResult\(\)/);
    assert.match(component, /function appendStructuredQueryView/);
    assert.match(component, /function appendQueryPayloadView/);
    assert.match(component, /item\.status === "OK"[\s\S]*?findQueryDisplay/);
    assert.match(component, /已读取本地数据，未执行任何动作/);
    assert.match(component, /query\.status === "TRUNCATED"/);
    assert.match(component, /TRUNCATED[\s\S]*?"truncated"/);
    assert.match(component, /"error",\s*"query"/);
    assert.match(component, /function appendQueryTimeline/);
    assert.match(component, /ai-query-timeline/);
    assert.match(component, /function appendQuerySource/);
    assert.match(component, /nodes\.run\.hidden = true/);
    assert.match(css, /\.ai-query-result\s*\{[\s\S]*?font-size:\s*var\(--font-size-small\);[\s\S]*?line-height:\s*1\.55;/);
    assert.match(css, /\.ai-query-table\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?font-size:\s*var\(--font-size-small\);/);
    assert.match(models, /declaredQueries\.slice\(0, AI_QUERY_LIMIT\)/);
    assert.match(models, /statusText === "OK" && Object\.hasOwn\(result, "payload"\)/);
});

test("AI query rendering exposes compact display and source details", async () => {
    const component = await read("../src/ai-dialog.js");
    const css = await read("../styles/app.css");

    assert.match(component, /function repeatedQueryFieldKeys/);
    assert.match(component, /view\.hideRepeatedFields/);
    assert.match(component, /view\.groupBy === "date"/);
    assert.match(component, /view\.detailFields/);
    assert.match(component, /aria-label",\s*`查看 \$\{queryTitle\(item\)\} 的来源与原始数据`/);
    assert.match(component, /currentEntries\.append\(item\)/);
    assert.match(component, /查看来源/);
    assert.match(css, /\.ai-query-timeline\s*\{[\s\S]*?display:\s*grid;/);
    assert.match(css, /\.ai-query-timeline__item\s*\{[\s\S]*?grid-template-columns:/);
    assert.match(css, /\.ai-query-source\s*\{/);
    assert.match(css, /\.ai-run-button\[hidden\]\s*\{[\s\S]*?display:\s*none;/);
});

test("AI clarification options use a full-width question layout", async () => {
    const component = await read("../src/ai-dialog.js");
    const css = await read("../styles/app.css");

    assert.match(component, /item\.className = "ai-question-option"/);
    assert.match(
        component,
        /button\.className = "text-button ai-question-option__button"/
    );
    assert.doesNotMatch(component, /ai-plan-step ai-plan-step--question/);
    assert.match(
        css,
        /\.ai-question-option\s*\{[\s\S]*?border-radius:\s*var\(--radius-xs\);[\s\S]*?background:\s*var\(--color-surface-muted\);/
    );
    assert.match(
        css,
        /\.ai-question-option__button\s*\{[\s\S]*?display:\s*block;[\s\S]*?width:\s*100%;[\s\S]*?text-align:\s*left;[\s\S]*?white-space:\s*normal;[\s\S]*?overflow-wrap:\s*anywhere;/
    );
    assert.doesNotMatch(css, /\.ai-plan-step--question/);
});

test("AI actions surface host failures and notify the framework after execution", async () => {
    const component = await read("../src/ai-dialog.js");
    const app = await read("../src/app.js");
    const refreshStart = app.indexOf("function refreshAfterAiExecution");
    const refreshEnd = app.indexOf("\n}", refreshStart);
    const refresh = app.slice(refreshStart, refreshEnd + 2);

    assert.match(component, /mode: succeeded \? "completed" : "failed"/);
    assert.match(component, /executionFailureMessage\(execution\)/);
    assert.match(component, /notifyExecutionFinished\(execution\)/);
    assert.match(component, /onExecutionFinished = \(\) => \{\}/);
    assert.match(component, /rollback_failed: "回滚失败"/);
    assert.match(component, /failed_partially_rolled_back: "执行失败，部分动作未能回滚"/);
    assert.match(app, /onExecutionFinished: refreshAfterAiExecution/);
    assert.match(refresh, /executionCompletedModules\(execution\)/);
    assert.match(refresh, /moduleForView\(state\.activeView\)/);
    assert.match(refresh, /await navigate\(state\.activeView\)/);
    assert.doesNotMatch(refresh, /module_calendar/);
});

test("AI module governance updates refresh the catalog before rendering switches", async () => {
    const app = await read("../src/app.js");
    const refreshStart = app.indexOf("async function refreshAfterAiExecution");
    const refreshEnd = app.indexOf("\n}", refreshStart);
    const refresh = app.slice(refreshStart, refreshEnd + 2);
    const catalogStart = app.indexOf("async function refreshModuleCatalogState");
    const catalogEnd = app.indexOf("\n}", catalogStart);
    const catalog = app.slice(catalogStart, catalogEnd + 2);
    const adminStart = app.indexOf("function createSystemAdminApi");
    const admin = app.slice(adminStart);

    assert.match(refresh, /await refreshModuleCatalogState\(\)/);
    assert.match(refresh, /refreshVisibleFrameworkViews\(\)/);
    assert.match(refresh, /activeViewBecameUnavailable\(\)/);
    assert.match(catalog, /api\.getModules\(\)/);
    assert.match(catalog, /api\.getSystemModules\(\)/);
    assert.match(catalog, /applyCatalogState\(catalogs, state\.modulePreferences\)/);
    assert.match(admin, /refreshModuleCatalogState\(\)/);
    assert.match(admin, /systemAdminApi\.getInterfaceState\(\)/);
});

test("AI dock exposes the required themed surfaces and states", async () => {
    const html = await read("../index.html");
    const css = await read("../styles/app.css");
    const tokens = await read("../styles/tokens.css");
    const dialog = html.slice(
        html.indexOf('class="ai-dock"'),
        html.indexOf('class="ai-call-layer"')
    );

    assert.match(dialog, /id="ai-launcher"/);
    assert.match(dialog, /id="ai-composer"/);
    assert.match(dialog, /id="ai-plan-panel"/);
    assert.match(dialog, /id="ai-panel-resizer"/);
    assert.match(dialog, /role="separator"/);
    assert.match(dialog, /aria-orientation="vertical"/);
    assert.match(dialog, /aria-controls="ai-plan-panel"/);
    assert.match(dialog, /id="ai-plan-list" aria-live="polite"/);
    assert.match(dialog, /id="ai-plan-close"/);
    assert.match(dialog, /id="ai-progress-popover"/);
    assert.match(dialog, /id="ai-progress-list" aria-live="polite"/);
    assert.match(dialog, /id="ai-risk-indicator"/);
    assert.match(dialog, /id="ai-run-button"/);
    assert.match(dialog, /id="ai-inline-api-key"/);
    assert.match(dialog, /输入 DeepSeek API Key 接入/);
    assert.match(dialog, /class="ai-demo-badge__compact">接入 AI/);
    assert.match(html, /id="ai-snap-target"/);
    assert.match(dialog, /data-anchor="default"/);
    assert.match(dialog, /data-composer="closed"/);
    assert.match(tokens, /--ai-panel-width:\s*460px;/);
    assert.match(tokens, /--ai-panel-min-width:\s*340px;/);
    assert.match(tokens, /--ai-panel-max-width:\s*720px;/);
    assert.match(tokens, /--ai-launcher-size:\s*58px;/);
    assert.match(tokens, /--ai-composer-width:\s*860px;/);
    assert.match(tokens, /--ai-composer-free-width:\s*720px;/);
    assert.match(tokens, /--ai-composer-height:\s*68px;/);
    assert.match(tokens, /--duration-ai-call:\s*280ms;/);
    assert.match(tokens, /--duration-ai-call:\s*180ms;/);
    assert.match(css, /\.ai-dock\[data-composer="open"\] \.ai-composer/);
    assert.match(css, /\.ai-dock\[data-panel="open"\] \.ai-plan-panel/);
    assert.match(css, /\.ai-panel-resizer\s*\{[\s\S]*?cursor:\s*ew-resize;/);
    assert.match(css, /\.ai-plan-panel\[data-resize-edge="left"\] \.ai-panel-resizer/);
    assert.match(css, /\.ai-plan-panel\[data-resize-edge="right"\] \.ai-panel-resizer/);
    assert.match(css, /\.ai-dock\[data-popover="open"\] \.ai-progress-popover/);
    assert.match(css, /\.ai-risk-indicator\[data-risk="WRITE"\][\s\S]*?var\(--color-warning-soft\)/);
    assert.match(css, /\.ai-risk-indicator\[data-risk="DELETE"\][\s\S]*?var\(--color-danger\)/);
});

test("AI target resolution prefers visible content and falls back to the sidebar", async () => {
    const component = await read("../src/ai-dialog.js");
    const resolver = between(
        component,
        "function findActionTarget",
        "function invocationOrigin"
    );

    const currentModuleAt = resolver.indexOf("#main-content [data-module-view=");
    const currentCardAt = resolver.indexOf("#main-content .module-card[data-module-id=");
    const sidebarAt = resolver.indexOf(".nav-item--module[data-module-id=");

    assert.ok(currentModuleAt >= 0);
    assert.ok(currentCardAt > currentModuleAt);
    assert.ok(sidebarAt > currentCardAt);
    assert.match(resolver, /visibleRect\(sidebarTarget\)/);
});

test("AI invocation events animate only current module calls", () => {
    const startedAt = Date.parse("2026-11-22T03:00:00.000Z");
    const base = {
        sequence: 7,
        status: "RUNNING",
        moduleId: "module_todo",
        timestamp: "2026-11-22T03:00:00.050Z",
        timeValue: startedAt + 50
    };

    assert.equal(
        invocationEventModuleId({ ...base, type: "STEP", origin: "AI_DIRECT" }, startedAt),
        "module_todo"
    );
    assert.equal(
        invocationEventModuleId({ ...base, type: "QUERY", origin: "AI_QUERY" }, startedAt),
        "module_todo"
    );
    assert.equal(
        invocationEventModuleId({ ...base, type: "STEP", origin: "AI_PLAN" }, startedAt),
        "module_todo"
    );
    assert.equal(
        invocationEventModuleId(
            {
                ...base,
                timestamp: "2026-11-22T02:59:59.999Z",
                timeValue: startedAt - 1
            },
            startedAt
        ),
        ""
    );
    assert.equal(
        invocationEventModuleId(
            { ...base, type: "STEP", origin: "AI_DIRECT", status: "COMPLETED" },
            startedAt
        ),
        ""
    );
    assert.equal(
        invocationEventModuleId(
            { ...base, type: "STEP", origin: "PLAN" },
            startedAt
        ),
        ""
    );
    assert.equal(invocationEventModuleId(base, 0), "");

    assert.deepEqual(
        invocationEventActivity(
            { ...base, type: "STEP", origin: "AI_DIRECT" },
            startedAt
        ),
        {
            moduleId: "module_todo",
            phase: "running",
            status: "RUNNING",
            stepId: ""
        }
    );
    assert.equal(
        invocationEventActivity(
            { ...base, type: "STEP", origin: "AI_DIRECT", stepId: "q1", status: "COMPLETED" },
            startedAt
        )?.phase,
        "success"
    );
    assert.equal(
        invocationEventActivity(
            { ...base, type: "STEP", origin: "AI_PLAN", stepId: "q1", status: "FAILED" },
            startedAt
        )?.phase,
        "failure"
    );
    assert.equal(
        invocationEventActivity(
            { ...base, type: "QUERY", origin: "AI_QUERY", stepId: "q1", status: "OK" },
            startedAt
        )?.phase,
        "success"
    );
    assert.equal(
        invocationEventActivity(
            { ...base, type: "QUERY", origin: "AI_QUERY", stepId: "q1", status: "TRUNCATED" },
            startedAt
        )?.phase,
        "success"
    );
});

test("framework events are routed to the active AI invocation tracker", async () => {
    const component = await read("../src/ai-dialog.js");
    const app = await read("../src/app.js");

    assert.match(component, /handleInvocationEvent/);
    assert.match(component, /dialog\.invocationChain\.then/);
    assert.match(component, /dialog\.invocationSeen\.has\(key\)/);
    assert.match(component, /onModuleActivity\(activity\)/);
    assert.match(component, /onModuleActivity\(\{ phase: "reset" \}\)/);
    assert.match(app, /aiDialog\?\.handleInvocationEvent\?\.\(event\)/);
});

test("AI invocation layer is non-interactive and the dot carries a trail", async () => {
    const css = await read("../styles/app.css");
    const component = await read("../src/ai-dialog.js");

    assert.match(
        css,
        /\.ai-call-layer\s*\{[\s\S]*?z-index:\s*60;[\s\S]*?pointer-events:\s*none;/
    );
    assert.match(css, /\.ai-call-dot::after\s*\{[\s\S]*?background:\s*linear-gradient/);
    assert.match(component, /dot\.animate\(/);
    assert.match(component, /getPropertyValue\("--duration-ai-call"\)/);
    assert.match(component, /cubic-bezier\(0\.32, 0, 0\.95, 0\.2\)/);
    assert.match(component, /scaleX\(2\.15\)/);
    assert.doesNotMatch(component, /ai-call-route/);
    assert.doesNotMatch(css, /\.ai-call-route/);
});

test("AI dock supports persistent dragging, snapping and viewport clamping", async () => {
    const component = await read("../src/ai-dialog.js");
    const css = await read("../styles/app.css");

    assert.match(component, /orderly\.ai\.dock\.position\.v1/);
    assert.match(component, /AI_DRAG_THRESHOLD_PX = 6/);
    assert.match(component, /AI_SNAP_DISTANCE_PX = 76/);
    assert.match(component, /setPointerCapture/);
    assert.match(component, /\.dataset\.visible/);
    assert.match(component, /canDragAiDock/);
    assert.match(component, /animateSnapToDefault/);
    assert.match(component, /getPropertyValue\("--duration-normal"\)/);
    assert.match(component, /dock\.animate\(/);
    assert.match(component, /snapAnimating/);
    assert.doesNotMatch(component, /collapseSurfacesForDrag/);
    assert.match(component, /setFreePosition/);
    assert.match(component, /localStorage/);
    assert.match(css, /\.ai-snap-target\[data-visible="true"\]/);
    assert.match(css, /\.ai-dock\[data-anchor="free"\]/);
});

test("AI panels share bounded pointer and keyboard resizing", async () => {
    const component = await read("../src/ai-dialog.js");
    const css = await read("../styles/app.css");

    assert.equal(resolveAiPanelResizeWidth({
        startWidth: 460,
        deltaX: 80,
        edge: "right",
        availableWidth: 720
    }), 540);
    assert.equal(resolveAiPanelResizeWidth({
        startWidth: 460,
        deltaX: -80,
        edge: "left",
        availableWidth: 720
    }), 540);
    assert.equal(resolveAiPanelResizeWidth({
        startWidth: 460,
        deltaX: 1000,
        edge: "right",
        availableWidth: 580
    }), 580);
    assert.equal(resolveAiPanelResizeWidth({
        startWidth: 460,
        deltaX: -1000,
        edge: "right"
    }), 340);

    assert.match(component, /getAiPanelWidth/);
    assert.match(component, /onAiPanelWidthChange/);
    assert.match(component, /function onPanelResizePointerDown/);
    assert.match(component, /function onPanelResizePointerMove/);
    assert.match(component, /function onPanelResizerKeyDown/);
    assert.match(component, /nodes\.resizer\.addEventListener\("dblclick", resetPanelWidth\)/);
    assert.match(component, /nodes\.resizer\.setPointerCapture/);
    assert.match(component, /dock\.dataset\.panelResizable/);
    assert.match(css, /\.ai-panel-resizer\[hidden\]/);
    assert.match(css, /\.ai-dock\[data-resizing="true"\] \.ai-plan-panel/);
});

test("only a fully collapsed dock accepts pointer or keyboard movement", () => {
    assert.equal(canDragAiDock({}), true);
    assert.equal(canDragAiDock({ composerOpen: true }), false);
    assert.equal(canDragAiDock({ mode: "composing" }), false);
    assert.equal(canDragAiDock({ panelOpen: true }), false);
    assert.equal(canDragAiDock({ morphing: true }), false);
    assert.equal(canDragAiDock({ snapAnimating: true }), false);
    assert.deepEqual(
        resolveAiSnapPosition({
            dockWidth: 58,
            dockHeight: 58,
            snapCenter: { x: 1200, y: 800 }
        }),
        { x: 1171, y: 771 }
    );
});

test("expanded composer keeps running progress reachable", async () => {
    const component = await read("../src/ai-dialog.js");

    assert.match(
        component,
        /nodes\.composer\.addEventListener\("pointerenter", showProgressPopover\)/
    );
    assert.match(
        component,
        /nodes\.composer\.addEventListener\("focusin", showProgressPopover\)/
    );
    assert.match(
        component,
        /nodes\.progress\.addEventListener\("pointerenter"[\s\S]*?clearLater\(dialog\.popoverTimer\)/
    );
});

test("launcher surface morphs into the composer and reuses its icon in Generate", async () => {
    const html = await read("../index.html");
    const css = await read("../styles/app.css");
    const component = await read("../src/ai-dialog.js");
    const dialog = html.slice(
        html.indexOf('class="ai-dock"'),
        html.indexOf('class="ai-call-layer"')
    );

    assert.match(dialog, /id="ai-send-icon"/);
    assert.match(dialog, /id="ai-launcher-icon" data-ai-icon="spark"/);
    assert.doesNotMatch(dialog, /id="ai-launcher-icon"[^>]*data-icon=/);
    assert.match(dialog, /<span>生成<\/span>/);
    assert.match(
        css,
        /\.ai-dock\[data-composer="open"\] \.ai-launcher\s*\{[\s\S]*?width:\s*var\(--ai-composer-current-width\);[\s\S]*?height:\s*var\(--ai-composer-height\);[\s\S]*?border-radius:\s*var\(--ai-composer-radius\);/
    );
    assert.match(
        css,
        /\.ai-dock\[data-morph="closing"\] \.ai-composer\[aria-hidden="true"\]/
    );
    assert.match(component, /nodes\.sendIcon\.append\(nodes\.launcherIcon\)/);
    assert.match(component, /nodes\.launcher\.append\(nodes\.launcherIcon\)/);
    assert.match(
        component,
        /paintAiIcon\(nodes\.launcherIcon, dialog\.aiIcon,[\s\S]*?url: dialog\.aiIconAssets\[dialog\.aiIcon\]/
    );
    assert.match(component, /setIconAssets\(assets\)/);
    assert.match(component, /nodes\.launcher\.animate\(/);
    assert.match(component, /nodes\.launcherIcon\.animate\(/);
    assert.match(component, /dock\.dataset\.morph = expanded \? "opening" : "closing"/);
});

test("theme AI icon images use a recolorable mask with generic fallback", async () => {
    const css = await read("../styles/app.css");
    const icons = await read("../src/icons.js");

    assert.match(css, /\.ai-launcher__icon\s*\{[\s\S]*?width:\s*26px;[\s\S]*?height:\s*26px;/);
    assert.match(css, /\.ai-send-button__icon \.ai-launcher__icon\s*\{[\s\S]*?width:\s*16px;/);
    assert.match(css, /\[data-icon-source="theme"\]::before[\s\S]*?background:\s*currentColor;/);
    assert.match(css, /mask-image:\s*var\(--ai-icon-image\);/);
    assert.match(css, /mask-size:\s*var\(--ai-icon-size\) var\(--ai-icon-size\);/);
    assert.match(icons, /element\.replaceChildren\(\)/);
    assert.match(icons, /element\.innerHTML = icon\("info", iconSize\)/);
    assert.match(icons, /THEME_AI_ICON_URL/);
});

test("AI surfaces stay inside four viewport corners without overlapping", () => {
    const viewports = [
        { width: 1440, height: 900 },
        { width: 390, height: 844 }
    ];
    const margin = 12;
    const dockSize = 58;
    const composerHeight = 68;
    const gap = 12;

    for (const viewport of viewports) {
        const positions = [
            { left: margin, top: margin },
            { left: viewport.width - dockSize - margin, top: margin },
            { left: margin, top: viewport.height - dockSize - margin },
            {
                left: viewport.width - dockSize - margin,
                top: viewport.height - dockSize - margin
            }
        ];

        for (const position of positions) {
            const layout = resolveAiSurfaceLayout({
                dockLeft: position.left,
                dockTop: position.top,
                dockWidth: dockSize,
                dockHeight: dockSize,
                viewportWidth: viewport.width,
                viewportHeight: viewport.height,
                margin,
                gap,
                composerWidth: 720,
                composerHeight,
                planWidth: 460,
                planContentHeight: 420,
                composerOpen: true
            });

            for (const box of [layout.composerBox, layout.planBox]) {
                assert.ok(
                    box.left >= margin,
                    `left overflow: ${JSON.stringify({ viewport, position, box })}`
                );
                assert.ok(
                    box.right <= viewport.width - margin,
                    `right overflow: ${JSON.stringify({ viewport, position, box })}`
                );
                assert.ok(
                    box.top >= margin,
                    `top overflow: ${JSON.stringify({ viewport, position, box })}`
                );
                assert.ok(
                    box.bottom <= viewport.height - margin,
                    `bottom overflow: ${JSON.stringify({ viewport, position, box })}`
                );
            }

            if (layout.vertical === "above") {
                assert.ok(layout.planBox.bottom <= layout.composerBox.top - gap);
            } else {
                assert.ok(layout.planBox.top >= layout.composerBox.bottom + gap);
            }
        }
    }
});

test("AI composer and plan panel coexist while outside clicks only close the plan", async () => {
    const component = await read("../src/ai-dialog.js");
    const surfaces = between(component, "function updateSurfaces", "function riskLabel");
    const outside = between(component, "function onDocumentPointerDown", "function onWindowResize");

    assert.match(surfaces, /const composerOpen = dialog\.composerOpen;/);
    assert.match(surfaces, /const busy = dialog\.busy === true;/);
    assert.match(surfaces, /nodes\.composerInput\.disabled = busy \|\| !configured;/);
    assert.match(surfaces, /nodes\.inlineKey\.disabled = busy \|\| configured;/);
    assert.match(surfaces, /nodes\.composerSend\.disabled = busy;/);
    assert.match(outside, /collapsePlanOnly\(\)/);
    assert.match(outside, /dock\.contains\(event\.target\)/);
    assert.match(component, /if \(dialog\.mode === "completed"\) \{[\s\S]*?collapseAndReset\(\{ focusLauncher: false \}\);[\s\S]*?openComposer\(\);/);
});

test("AI dialog motion follows every directory theme without system motion queries", async () => {
    for (const theme of ["paper", "moss", "night", "study"]) {
        const motion = await read(`../../../themes/${theme}/motion.css`);
        assert.match(motion, /\[data-motion-scope="ai-dialog"\] \.ai-launcher/);
        assert.match(motion, /\[data-motion-scope="ai-dialog"\]\[data-mode="running"\]/);
        assert.doesNotMatch(motion, /prefers-reduced-motion/);
    }
});
