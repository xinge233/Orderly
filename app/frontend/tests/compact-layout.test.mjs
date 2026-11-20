import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function read(relativePath) {
    return readFile(new URL(relativePath, import.meta.url), "utf8");
}

test("layout tokens keep typography, content width and remove the legacy topbar", async () => {
    const html = await read("../index.html");
    const tokens = await read("../styles/tokens.css");
    const appCss = await read("../styles/app.css");

    assert.doesNotMatch(html, /class="topbar"/);
    assert.doesNotMatch(html, /data-motion-scope="topbar"/);
    assert.doesNotMatch(html, /mode-badge/);
    assert.doesNotMatch(html, /id="breadcrumb"/);
    assert.doesNotMatch(`${tokens}\n${appCss}`, /--topbar-height/);
    assert.doesNotMatch(appCss, /\.topbar\b|\.breadcrumb\b/);
    assert.match(tokens, /--content-max:\s*1520px;/);
    assert.match(tokens, /--font-size-caption:\s*0\.75rem;/);
    assert.match(tokens, /--font-size-small:\s*0\.8125rem;/);
    assert.match(tokens, /--font-size-body:\s*0\.875rem;/);
    assert.match(tokens, /--font-size-control:\s*0\.9375rem;/);
    assert.match(
        appCss,
        /\.main-content\s*\{[\s\S]*?width:\s*min\(100%,\s*var\(--content-max\)\);[\s\S]*?max-width:\s*var\(--content-max\);[\s\S]*?margin-inline:\s*auto;[\s\S]*?padding-inline:\s*clamp\(var\(--space-6\),\s*4vw,\s*var\(--space-12\)\);/
    );
});
test("desktop sidebar rows use fixed 48px geometry and 40px navigation icons", async () => {
    const appCss = await read("../styles/app.css");
    const desktopCss = appCss.slice(
        appCss.indexOf("@media (min-width: 821px)"),
        appCss.indexOf(".app-main {", appCss.indexOf("@media (min-width: 821px)"))
    );

    assert.match(
        desktopCss,
        /\.search-box\s*\{[\s\S]*?grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(120px,\s*1fr\) auto;[\s\S]*?height:\s*var\(--sidebar-row-height\);/
    );
    assert.match(
        desktopCss,
        /\.search-box input\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?font-size:\s*var\(--font-size-control\);/
    );
    assert.match(
        desktopCss,
        /\.nav-item\s*\{[\s\S]*?grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(0,\s*1fr\);[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?min-height:\s*var\(--sidebar-row-height\);/
    );
    assert.match(
        desktopCss,
        /\.sandbox-status\s*\{[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?grid-template-columns:\s*36px minmax\(0,\s*1fr\);/
    );
    assert.match(
        desktopCss,
        /\.settings-trigger\s*\{[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(0,\s*1fr\) auto;/
    );
    assert.match(
        desktopCss,
        /\.brand-mark\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/
    );
    assert.match(
        desktopCss,
        /\.workspace-icon\s*\{[\s\S]*?width:\s*36px;[\s\S]*?height:\s*36px;/
    );
    assert.doesNotMatch(
        desktopCss,
        /\.sidebar\.is-expanded\s+(?:\.brand|\.search-box|\.nav-item|\.settings-trigger|\.sandbox-status)/
    );
});

test("mobile toolbar exposes the required height, menu contract and view title", async () => {
    const html = await read("../index.html");
    const tokens = await read("../styles/tokens.css");
    const appCss = await read("../styles/app.css");
    const mobileCss = appCss.slice(appCss.indexOf("@media (max-width: 820px)"));

    assert.match(html, /id="sidebar"/);
    assert.match(html, /<header class="mobile-toolbar">/);
    assert.match(html, /id="mobile-menu"[\s\S]*?aria-controls="sidebar"[\s\S]*?aria-expanded="false"/);
    assert.match(html, /id="mobile-view-title">总览<\/strong>/);
    assert.match(tokens, /--mobile-toolbar-height:\s*54px;/);
    assert.match(
        tokens,
        /@media \(max-width: 620px\)\s*\{\s*:root\s*\{\s*--mobile-toolbar-height:\s*48px;/
    );
    assert.match(
        appCss,
        /\.mobile-toolbar\s*\{[\s\S]*?height:\s*var\(--mobile-toolbar-height\);/
    );
    assert.match(
        mobileCss,
        /\.mobile-toolbar\s*\{[\s\S]*?display:\s*flex;/
    );
    assert.match(
        appCss,
        /\.mobile-toolbar \.icon-button\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/
    );
    assert.match(
        mobileCss,
        /\.main-content\s*\{[\s\S]*?min-height:\s*calc\(100vh - var\(--mobile-toolbar-height\)\);/
    );
    assert.match(
        mobileCss,
        /\.sidebar\s*\{[\s\S]*?width:\s*min\(300px, calc\(100vw - 48px\)\);/
    );
    assert.match(
        mobileCss,
        /\.sidebar\s*\{[\s\S]*?clip-path:\s*none;[\s\S]*?transform:\s*translateX\(-105%\);/
    );
});

test("mobile navigation syncs aria state, inert availability and focus safely", async () => {
    const app = await read("../src/app.js");
    const sync = app.slice(
        app.indexOf("function syncMobileSidebarAvailability"),
        app.indexOf("function openMobileNav")
    );
    const open = app.slice(
        app.indexOf("function openMobileNav"),
        app.indexOf("function closeMobileNav")
    );
    const close = app.slice(
        app.indexOf("function closeMobileNav"),
        app.indexOf("function openSettings")
    );
    const bind = app.slice(
        app.indexOf("function bindGlobalControls"),
        app.indexOf("function applySettings")
    );

    assert.match(sync, /window\.matchMedia\(MOBILE_NAV_QUERY\)\.matches/);
    assert.match(sync, /!sidebar\.classList\.contains\("is-open"\)/);
    assert.match(sync, /sidebar\.inert = hidden;/);
    assert.match(
        sync,
        /if \(hidden\) \{[\s\S]*?setAttribute\("aria-hidden", "true"\);[\s\S]*?\} else \{[\s\S]*?removeAttribute\("aria-hidden"\);/
    );
    assert.match(open, /setAttribute\("aria-expanded", "true"\)/);
    assert.match(open, /#nav-search"\)\?\.focus/);
    assert.match(close, /setAttribute\("aria-expanded", "false"\)/);
    assert.match(close, /sidebar\?\.contains\(document\.activeElement\)/);
    assert.match(close, /menu\?\.focus\(\{ preventScroll: true \}\)/);
    assert.match(close, /wasOpen[\s\S]*restoreFocus[\s\S]*focusWithinSidebar/);
    assert.ok(
        open.indexOf("syncMobileSidebarAvailability();")
        < open.indexOf('#nav-search")?.focus')
    );
    assert.ok(
        close.indexOf("menu?.focus({ preventScroll: true })")
        < close.indexOf("syncMobileSidebarAvailability();")
    );
    assert.equal(
        (bind.match(/syncMobileSidebarAvailability\(\);/g) ?? []).length,
        2
    );
    assert.match(app, /event\.key !== "Escape"[\s\S]*?closeMobileNav\(\)/);
});

test("mobile Escape yields to an open settings dialog and restores its trigger focus", async () => {
    const app = await read("../src/app.js");
    const escapeStart = app.indexOf(
        'const settingsDialog = document.querySelector("#settings-dialog");'
    );
    const escape = app.slice(
        escapeStart,
        app.indexOf("const mobileViewport", escapeStart)
    );
    const dialogGuard = escape.slice(0, escape.indexOf("return;"));
    const bindSettings = app.slice(
        app.indexOf("function bindSettingsDialog"),
        app.indexOf("function renderSettings")
    );

    assert.match(
        escape,
        /if \(event\.key !== "Escape" \|\| settingsDialog\?\.open\) \{\s*return;/
    );
    assert.doesNotMatch(dialogGuard, /preventDefault/);
    assert.match(
        bindSettings,
        /sidebar\?\.classList\.contains\("is-open"\)[\s\S]*?queueMicrotask[\s\S]*?settingsTrigger\?\.focus\(\{ preventScroll: true \}\)/
    );
});

test("navigation rendering and the AI dock keep demo honesty visible", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const navigate = app.slice(
        app.indexOf("async function navigate"),
        app.indexOf("function renderLoadError")
    );
    const renderNavigation = app.slice(
        app.indexOf("function renderNavigation"),
        app.indexOf("function beginNavigationDrag")
    );
    const aiDialog = html.slice(
        html.indexOf('class="ai-dock"'),
        html.indexOf('class="ai-call-layer"')
    );

    assert.match(renderNavigation, /button\.setAttribute\("aria-current", "page"\)/);
    assert.match(navigate, /#mobile-view-title/);
    assert.doesNotMatch(app, /#breadcrumb/);
    assert.match(aiDialog, /输入 DeepSeek API Key 接入/);
    assert.match(aiDialog, /AI 执行进度/);
    assert.match(aiDialog, /id="ai-plan-close"/);
    assert.match(appCss, /padding-bottom:\s*128px;/);
    assert.doesNotMatch(app, /#command-dock/);
});

test("sidebar labels stay single-line and internal count badges are never rendered", async () => {
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const renderNavigation = app.slice(
        app.indexOf("function renderNavigation"),
        app.indexOf("function beginNavigationDrag")
    );

    assert.match(
        appCss,
        /\.nav-item--module\s*\{[^}]*grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(0, 1fr\);/
    );
    assert.match(
        appCss,
        /\.nav-copy strong\s*\{[^}]*overflow:\s*hidden;[^}]*text-overflow:\s*ellipsis;[^}]*white-space:\s*nowrap;/
    );
    assert.doesNotMatch(appCss, /\.nav-count\b/);
    assert.doesNotMatch(appCss, /\.nav-copy small\b/);
    assert.doesNotMatch(renderNavigation, /nav-count|element\("small"/);
    assert.match(renderNavigation, /copy\.append\(element\("strong", "", item\.label\)\);/);
    assert.match(renderNavigation, /item\.moduleId \? navigationCount\(item\.moduleId\) : null/);
});

test("mobile navigation layers scrim above the AI dock and sidebar above both", async () => {
    const appCss = await read("../styles/app.css");
    const mobileStart = appCss.indexOf("@media (max-width: 820px)");
    const mobileEnd = appCss.indexOf("@media (max-width: 620px)", mobileStart);
    const mobileCss = appCss.slice(mobileStart, mobileEnd);

    assert.match(
        mobileCss,
        /\.ai-dock\s*\{[\s\S]*?z-index:\s*35;/
    );
    assert.match(
        mobileCss,
        /\.scrim\s*\{[\s\S]*?z-index:\s*40;/
    );
    assert.match(
        mobileCss,
        /\.sidebar\s*\{[\s\S]*?z-index:\s*50;/
    );
    assert.doesNotMatch(
        mobileCss,
        /\.settings-dialog\s*\{[^}]*z-index:/
    );
});

test("sidebar exposes a confirmed global host exit action", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");

    assert.match(
        html,
        /id="open-settings"[\s\S]*?id="exit-orderly"[\s\S]*?data-icon="power"[\s\S]*?退出归序/
    );
    assert.match(
        app,
        /async function requestHostShutdown\(\)[\s\S]*?window\.confirm\([\s\S]*?api\.shutdownHost\(\)/
    );
    assert.match(app, /state\.executing \|\| state\.shutdownRequested/);
    assert.match(
        appCss,
        /\.exit-trigger:hover\s*\{[\s\S]*?var\(--color-danger-soft\)/
    );
});
