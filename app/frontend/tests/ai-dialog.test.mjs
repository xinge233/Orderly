import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    createAiDemoPlan,
    higherAiRisk,
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

test("AI demo plan stays local and selects the highest action risk", () => {
    assert.equal(higherAiRisk("READ_ONLY", "WRITE"), "WRITE");
    assert.equal(higherAiRisk("WRITE", "DELETE"), "DELETE");

    const plan = createAiDemoPlan("整理本周资料", [
        { moduleId: "module_fs", title: "资料管理", enabled: true },
        { moduleId: "module_todo", title: "待办事项", enabled: true }
    ]);

    assert.equal(plan.title, "整理本周资料");
    assert.equal(plan.actions.length, 2);
    assert.equal(plan.actions[0].risk, "READ_ONLY");
    assert.equal(plan.actions[1].risk, "WRITE");
    assert.equal(plan.risk, "WRITE");
    assert.ok(plan.actions.every((action) => action.status === "pending"));
});

test("AI dialog component never calls host planning or execution APIs", async () => {
    const component = await read("../src/ai-dialog.js");

    assert.doesNotMatch(component, /\bfetch\s*\(/);
    assert.doesNotMatch(component, /OrderlyApi|draftPlan|confirmPlan|executeAi|confirmPlan/);
    assert.match(component, /界面演示/);
    assert.match(component, /未接入 AI/);
    assert.match(component, /AI_COMPLETION_HOLD_MS = 4000/);
    assert.match(component, /AI_STEP_GAP_MS = 120/);
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
    assert.match(dialog, /id="ai-plan-list" aria-live="polite"/);
    assert.match(dialog, /id="ai-plan-close"/);
    assert.match(dialog, /id="ai-progress-popover"/);
    assert.match(dialog, /id="ai-progress-list" aria-live="polite"/);
    assert.match(dialog, /id="ai-risk-indicator"/);
    assert.match(dialog, /id="ai-run-button"/);
    assert.match(dialog, /界面演示 · 未接入 AI/);
    assert.match(dialog, /class="ai-demo-badge__compact">未接入 AI/);
    assert.match(html, /id="ai-snap-target"/);
    assert.match(dialog, /data-anchor="default"/);
    assert.match(dialog, /data-composer="closed"/);
    assert.match(tokens, /--ai-plan-width:\s*288px;/);
    assert.match(tokens, /--ai-launcher-size:\s*58px;/);
    assert.match(tokens, /--ai-composer-width:\s*860px;/);
    assert.match(tokens, /--ai-composer-free-width:\s*720px;/);
    assert.match(tokens, /--ai-composer-height:\s*68px;/);
    assert.match(tokens, /--duration-ai-call:\s*280ms;/);
    assert.match(tokens, /--duration-ai-call:\s*180ms;/);
    assert.match(css, /\.ai-dock\[data-composer="open"\] \.ai-composer/);
    assert.match(css, /\.ai-dock\[data-panel="open"\] \.ai-plan-panel/);
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
    assert.match(component, /collapseSurfacesForDrag/);
    assert.match(component, /setFreePosition/);
    assert.match(component, /localStorage/);
    assert.match(css, /\.ai-snap-target\[data-visible="true"\]/);
    assert.match(css, /\.ai-dock\[data-anchor="free"\]/);
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
                planWidth: 288,
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
    assert.match(surfaces, /const busy = \["planning", "running", "completed"\]\.includes\(dialog\.mode\);/);
    assert.match(surfaces, /nodes\.composerInput\.disabled = busy;/);
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
