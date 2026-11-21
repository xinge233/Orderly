import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import test from "node:test";

async function read(relativePath) {
    return readFile(new URL(relativePath, import.meta.url), "utf8");
}

async function collectCss(relativeDirectory) {
    const files = [];

    async function visit(directoryUrl, relativePath) {
        const entries = await readdir(directoryUrl, { withFileTypes: true });
        for (const entry of entries) {
            if (entry.name === "target" || entry.name === "node_modules") {
                continue;
            }

            const childRelativePath = `${relativePath}/${entry.name}`;
            const childUrl = new URL(
                `${entry.name}${entry.isDirectory() ? "/" : ""}`,
                directoryUrl
            );
            if (entry.isDirectory()) {
                await visit(childUrl, childRelativePath);
            } else if (entry.isFile() && entry.name.endsWith(".css")) {
                files.push({ relativePath: childRelativePath, url: childUrl });
            }
        }
    }

    await visit(
        new URL(relativeDirectory, import.meta.url),
        relativeDirectory.replace(/\/$/, "")
    );
    return files;
}

function between(source, startMarker, endMarker) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start + startMarker.length);
    assert.notEqual(start, -1, `缺少起始标记：${startMarker}`);
    assert.notEqual(end, -1, `缺少结束标记：${endMarker}`);
    return source.slice(start, end);
}

test("program motion tiers and the none safety layer disable all motion", async () => {
    const tokens = await read("../styles/tokens.css");
    const gentle = between(
        tokens,
        ":root {",
        'html[data-motion="minimal"]'
    );
    const minimal = between(
        tokens,
        'html[data-motion="minimal"]',
        'html[data-motion="none"]'
    );
    const none = between(
        tokens,
        'html[data-motion="none"] {',
        "@layer orderly.safety"
    );
    const safetyStart = tokens.indexOf("@layer orderly.safety");
    assert.notEqual(safetyStart, -1, "缺少 orderly.safety 层");
    const safety = tokens.slice(safetyStart);
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
    assert.match(gentle, /--duration-fast:\s*140ms;/);
    assert.match(gentle, /--duration-normal:\s*240ms;/);
    assert.match(gentle, /--duration-slow:\s*520ms;/);
    assert.match(gentle, /--duration-sidebar:\s*200ms;/);
    assert.match(minimal, /--duration-fast:\s*80ms;/);
    assert.match(minimal, /--duration-normal:\s*110ms;/);
    assert.match(minimal, /--duration-slow:\s*150ms;/);
    assert.match(minimal, /--duration-sidebar:\s*110ms;/);
    assert.match(none, /--duration-fast:\s*0ms;/);
    assert.match(none, /--duration-normal:\s*0ms;/);
    assert.match(none, /--duration-slow:\s*0ms;/);
    assert.match(none, /--duration-sidebar:\s*0ms;/);
    assert.match(safety, /animation:\s*none !important/);
    assert.match(safety, /transition:\s*none !important/);
    assert.match(safety, /scroll-behavior:\s*auto !important/);
    assert.doesNotMatch(tokens, /prefers-reduced-motion/);
    assert.match(hostileTheme, /!important/);
});

test("framework, module and theme CSS never consult the system motion preference", async () => {
    const cssFiles = (
        await Promise.all([
            collectCss("../styles/"),
            collectCss("../../../Module/"),
            collectCss("../../../themes/")
        ])
    ).flat();
    const moduleControl = await read(
        "../../../Module/module_control/src/main/resources/orderly-ui/module.css"
    );

    assert.ok(cssFiles.length > 0);
    for (const file of cssFiles) {
        const source = await readFile(file.url, "utf8");
        assert.doesNotMatch(
            source,
            /\bprefers-reduced-motion\b/,
            `${file.relativePath} 不得响应系统动效偏好`
        );
    }
    assert.match(
        moduleControl,
        /\[data-motion="none"\] \.module-control-ui-button,[\s\S]*?transition:\s*none;/
    );
});

test("base component CSS uses safe scrolling and excludes theme decoration hooks", async () => {
    const appCss = await read("../styles/app.css");

    assert.match(appCss, /scroll-behavior:\s*auto/);
    assert.doesNotMatch(appCss, /scroll-behavior:\s*smooth/);
    assert.doesNotMatch(appCss, /theme-texture/);
    assert.doesNotMatch(appCss, /theme-swatch/);
});

test("AI dock hides inactive surfaces and keeps demo disclosure visible", async () => {
    const appCss = await read("../styles/app.css");
    const html = await read("../index.html");
    const dialog = html.slice(
        html.indexOf('class="ai-dock"'),
        html.indexOf('class="ai-call-layer"')
    );

    assert.match(
        appCss,
        /\.ai-dock \.ai-composer\[aria-hidden="true"\][\s\S]*?visibility:\s*hidden;/
    );
    assert.match(dialog, /AI 执行进度/);
    assert.match(dialog, /输入 API Key 接入/);
    assert.match(dialog, /aria-label="关闭计划"/);
    assert.doesNotMatch(appCss, /\.planner-disclosure\s*\{[^}]*display:\s*none;/s);
});

test("AI dock morph and snap use program duration tokens and honor none immediately", async () => {
    const appCss = await read("../styles/app.css");
    const tokens = await read("../styles/tokens.css");
    const dialog = await read("../src/ai-dialog.js");

    assert.match(dialog, /getPropertyValue\("--duration-normal"\)/);
    assert.doesNotMatch(dialog, /prefers-reduced-motion/);
    assert.doesNotMatch(appCss, /prefers-reduced-motion/);
    assert.match(
        tokens,
        /html\[data-motion="none"\]\s*\{[\s\S]*?--duration-normal:\s*0ms;/
    );
    assert.match(tokens, /html\[data-motion="none"\] \*,[\s\S]*?animation:\s*none !important/);
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
    assert.match(
        app,
        /async getState\(\)\s*\{\s*await Promise\.all\(\[\s*refreshModuleCatalogState\(\),\s*systemAdminApi\.getInterfaceState\(\)\s*\]\);\s*return systemAdminSnapshot\(\);/s
    );
    assert.match(
        app,
        /async getInterfaceState\(\)\s*\{\s*const normalized = normalizeModuleInterfaceState\(\s*await api\.getModuleInterfaceState\(\)/s
    );
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
        "ai-dialog",
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
