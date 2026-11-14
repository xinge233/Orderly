import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function read(relativePath) {
    return readFile(new URL(relativePath, import.meta.url), "utf8");
}

test("theme safety layer wins before theme declarations and disables all motion", async () => {
    const tokens = await read("../styles/tokens.css");
    const hostileTheme = `
        @layer orderly.theme {
            html[data-motion="none"] #main-content *,
            html[data-motion="none"] #main-content *::before {
                animation: hostile 9s infinite !important;
                transition: all 9s !important;
                scroll-behavior: smooth !important;
            }
        }
    `;

    assert.match(tokens, /^@layer orderly\.safety, orderly\.reset, orderly\.base, orderly\.theme;/);
    assert.match(tokens, /html\[data-motion="none"\] \*/);
    assert.match(tokens, /@media \(prefers-reduced-motion: reduce\)/);
    assert.match(tokens, /animation: none !important/);
    assert.match(tokens, /transition: none !important/);
    assert.match(tokens, /scroll-behavior: auto !important/);
    assert.match(hostileTheme, /!important/);
});

test("base component CSS contains no hardcoded keyframes, animations or transitions", async () => {
    const appCss = await read("../styles/app.css");

    assert.match(appCss, /scroll-behavior:\s*auto/);
    assert.doesNotMatch(appCss, /scroll-behavior:\s*smooth/);
    assert.doesNotMatch(appCss, /theme-texture/);
    assert.doesNotMatch(appCss, /theme-swatch/);
    assert.doesNotMatch(appCss, /@keyframes\b/);
    assert.doesNotMatch(appCss, /(^|[\s;])animation\s*:/m);
    assert.doesNotMatch(appCss, /(^|[\s;])transition\s*:/m);
});

test("collapsed compact command dock hides controls but keeps planner disclosure visible", async () => {
    const appCss = await read("../styles/app.css");
    const html = await read("../index.html");
    const intro = html.slice(
        html.indexOf('<div class="command-intro">'),
        html.indexOf('<div class="command-examples"')
    );

    assert.match(
        appCss,
        /\.command-dock:not\(\.is-expanded\) \.command-form,[\s\S]*\.command-dock:not\(\.is-expanded\) \.command-examples,[\s\S]*\.command-dock:not\(\.is-expanded\) \.dock-footnote\s*\{\s*display:\s*none;/
    );
    assert.match(
        intro,
        /id="planner-badge">本地规则计划器 · 未连接外部模型<\/small>/
    );
    assert.doesNotMatch(
        appCss,
        /\.command-intro small\s*\{[^}]*display:\s*none;/s
    );
    assert.doesNotMatch(
        appCss,
        /\.planner-disclosure\s*\{[^}]*display:\s*none;/s
    );
});

test("each directory theme owns layered tokens and motion CSS", async () => {
    for (const id of ["paper", "moss", "night"]) {
        const tokens = await read(`../../../themes/${id}/tokens.css`);
        const motion = await read(`../../../themes/${id}/motion.css`);
        const manifest = JSON.parse(await read(`../../../themes/${id}/theme.json`));

        assert.equal(manifest.schemaVersion, 1);
        assert.equal(manifest.id, id);
        assert.deepEqual(manifest.styles, ["tokens.css", "motion.css"]);
        assert.match(tokens, /^@layer orderly\.theme \{/);
        assert.match(motion, /^@layer orderly\.theme \{/);
        assert.match(motion, /@keyframes orderly-view-enter/);
        assert.match(motion, /@keyframes orderly-target-pulse/);
        assert.match(motion, /@keyframes orderly-spin/);
        assert.match(motion, /scroll-behavior:\s*smooth/);
        assert.match(
            motion,
            /\[data-motion-scope="sidebar"\]\s*\{\s*transition:\s*clip-path var\(--duration-sidebar\) var\(--ease-out\);/
        );
        assert.doesNotMatch(
            motion,
            /\[data-motion-scope="sidebar"\]\s*\{\s*transition:\s*width var\(/
        );
        assert.doesNotMatch(
            motion,
            /\[data-motion-scope="sidebar"\]\s*\{[\s\S]*?padding var\(--duration/
        );
        assert.doesNotMatch(
            motion,
            /\[data-motion-scope="sidebar"\]\[data-motion-state="attention"\]::after/
        );
        assert.match(
            motion,
            /@media \(max-width: 820px\)\s*\{[\s\S]*?transition:\s*transform var\(--duration-normal\) var\(--ease-out\);/
        );
        assert.ok(manifest.assets.every((asset) => !asset.endsWith(".svg")));
        assert.doesNotMatch(`${tokens}\n${motion}`, /module-view|data-module|module_fs|module_todo|module_calendar/);
        assert.doesNotMatch(`${tokens}\n${motion}`, /--theme-texture/);
    }
});

test("settings uses dedicated live regions and keeps the panel mounted on changes", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const applySettings = app.slice(
        app.indexOf("function applySettings"),
        app.indexOf("async function applyThemeSelection")
    );
    const closeDialog = app.slice(
        app.indexOf("const closeDialog"),
        app.indexOf("document.querySelector(\"#reset-settings\")")
    );

    assert.doesNotMatch(html, /id="settings-panel"[^>]*aria-live/);
    assert.match(html, /id="settings-status" role="status"/);
    assert.match(app, /button\.setAttribute\("aria-controls", "settings-panel"\)/);
    assert.match(app, /button\.setAttribute\("aria-current", "page"\)/);
    assert.match(app, /heading\.tabIndex = -1/);
    assert.match(app, /target\?\.focus\(\{ preventScroll: true \}\)/);
    assert.match(app, /const patch = reconcileAppliedTheme\(requested, theme\)/);
    assert.match(app, /settingsStore\.update\(patch\)/);
    assert.match(app, /alert\.setAttribute\("role", "alert"\)/);
    assert.equal((app.match(/setAttribute\("role", "alert"\)/g) ?? []).length, 1);
    assert.doesNotMatch(app, /id:\s*"modules",\s*label:\s*"模块管理"/);
    assert.match(app, /getState\(\)\s*\{\s*return systemAdminSnapshot\(\)/s);
    assert.match(app, /const issues = element\("section", "theme-issues"\)/);
    assert.match(app, /未配置可用默认主题/);
    assert.doesNotMatch(app, /内置安全外观已启用/);
    assert.doesNotMatch(app, /theme-swatch/);
    assert.doesNotMatch(applySettings, /renderSettings\(/);
    assert.match(applySettings, /syncSettingControls\(settings\)/);
    assert.match(closeDialog, /dialog\.close\(\)/);
    assert.doesNotMatch(closeDialog, /await|setTimeout/);
});

test("framework motion scopes cover only the public shell contract", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const required = [
        "app-shell",
        "sidebar",
        "main",
        "overview",
        "module-card",
        "settings-dialog",
        "command-panel",
        "feedback"
    ];

    for (const scope of required) {
        assert.match(`${html}\n${app}`, new RegExp(`["']${scope}["']`));
    }
    assert.doesNotMatch(html, /module-view[^>]*data-motion-scope/);
});

test("sidebar refinement uses semantic sizes and never animates geometry or type", async () => {
    const tokens = await read("../styles/tokens.css");
    const appCss = await read("../styles/app.css");
    const desktopCss = appCss.slice(
        appCss.indexOf("@media (min-width: 821px)"),
        appCss.indexOf(".app-main {", appCss.indexOf("@media (min-width: 821px)"))
    );

    assert.match(tokens, /--sidebar-width:\s*72px;/);
    assert.match(tokens, /--sidebar-expanded-width:\s*272px;/);
    assert.match(tokens, /--sidebar-row-height:\s*48px;/);
    assert.match(tokens, /--sidebar-icon-size:\s*40px;/);
    assert.match(tokens, /--content-max:\s*1520px;/);
    assert.match(tokens, /--duration-sidebar:\s*200ms;/);
    assert.match(
        tokens,
        /html\[data-motion="minimal"\]\s*\{[\s\S]*?--duration-sidebar:\s*110ms;/
    );
    assert.match(
        tokens,
        /html\[data-motion="none"\]\s*\{[\s\S]*?--duration-sidebar:\s*0ms;/
    );
    assert.match(appCss, /font-size:\s*var\(--font-size-control\);/);
    assert.doesNotMatch(appCss, /\bzoom\s*:/);
    assert.doesNotMatch(desktopCss, /\bscale\s*\(/);
    assert.doesNotMatch(
        desktopCss,
        /transition\s*:[^;]*(?:font-size|width|height|transform)/
    );
});
