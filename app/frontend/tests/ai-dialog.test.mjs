import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { createAiDemoPlan, higherAiRisk } from "../src/ai-dialog.js";

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
    assert.match(component, /AI_COMPLETION_HOLD_MS = 2800/);
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
    assert.match(tokens, /--ai-plan-width:\s*220px;/);
    assert.match(tokens, /--duration-ai-call:\s*620ms;/);
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
});

test("AI dialog motion follows every directory theme without system motion queries", async () => {
    for (const theme of ["paper", "moss", "night", "study"]) {
        const motion = await read(`../../../themes/${theme}/motion.css`);
        assert.match(motion, /\[data-motion-scope="ai-dialog"\] \.ai-launcher/);
        assert.match(motion, /\[data-motion-scope="ai-dialog"\]\[data-mode="running"\]/);
        assert.doesNotMatch(motion, /prefers-reduced-motion/);
    }
});
