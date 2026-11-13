import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function read(relativePath) {
    return readFile(new URL(relativePath, import.meta.url), "utf8");
}

function between(source, startMarker, endMarker) {
    const start = source.indexOf(startMarker);
    const end = source.indexOf(endMarker, start + startMarker.length);
    assert.notEqual(start, -1, `缺少起始标记：${startMarker}`);
    assert.notEqual(end, -1, `缺少结束标记：${endMarker}`);
    return source.slice(start, end);
}

test("desktop sidebar starts collapsed and the shell only reserves the icon rail", async () => {
    const html = await read("../index.html");
    const tokens = await read("../styles/tokens.css");
    const appCss = await read("../styles/app.css");

    assert.match(
        html,
        /<aside class="sidebar"[\s\S]*?data-motion-state="collapsed"/
    );
    assert.match(tokens, /--sidebar-width:\s*64px;/);
    assert.match(tokens, /--sidebar-expanded-width:\s*208px;/);
    assert.match(
        appCss,
        /\.app-shell\s*\{[\s\S]*?grid-template-columns:\s*var\(--sidebar-width\) minmax\(0,\s*1fr\);/
    );
    assert.match(
        appCss,
        /\.sidebar\.is-expanded\s*\{[\s\S]*?width:\s*var\(--sidebar-expanded-width\);/
    );
    assert.doesNotMatch(appCss, /\.app-shell\s*\{[^}]*--sidebar-expanded-width/s);
});

test("desktop collapsed navigation hides text but names controls and keeps count badges", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const desktopCss = between(
        appCss,
        "@media (min-width: 821px)",
        ".app-main {"
    );
    const renderNavigation = between(
        app,
        "function renderNavigation",
        "function beginNavigationDrag"
    );
    const countRule = between(
        app,
        "function hasNavigationCount",
        "async function navigate"
    );

    for (const selector of [
        ".brand-copy",
        ".search-box input",
        ".search-box kbd",
        ".nav-group__label",
        ".nav-copy",
        ".nav-drag-handle",
        ".sandbox-status > span:not(.status-dot)",
        ".settings-trigger > span:not([data-icon])"
    ]) {
        assert.ok(desktopCss.includes(selector), `桌面收起态缺少隐藏规则：${selector}`);
    }
    assert.match(
        desktopCss,
        /\.nav-item\s*\{[\s\S]*?width:\s*40px;[\s\S]*?min-height:\s*40px;/
    );
    assert.match(
        desktopCss,
        /\.nav-count\s*\{[\s\S]*?position:\s*absolute;[\s\S]*?top:\s*-3px;[\s\S]*?right:\s*-3px;/
    );
    assert.match(
        desktopCss,
        /\.sidebar\.is-expanded \.nav-copy\s*\{\s*display:\s*flex;/
    );
    assert.match(renderNavigation, /button\.setAttribute\(\s*"aria-label"/);
    assert.match(renderNavigation, /hasNavigationCount\(count\)/);
    assert.match(renderNavigation, /badge\.setAttribute\("aria-hidden", "true"\)/);
    assert.match(countRule, /count == null/);
    assert.match(countRule, /numeric !== 0/);
    assert.match(html, /aria-label="搜索功能"/);
    assert.match(html, /aria-label="打开设置"/);
});

test("collapsed health status keeps an independent live accessible name", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const updateHealth = between(app, "function updateHealth", "function renderNavigation");

    assert.match(
        html,
        /<div class="sandbox-status" role="status" aria-live="polite" aria-label="正在连接本地服务，等待健康检查">/
    );
    assert.match(
        updateHealth,
        /const sandboxStatus = document\.querySelector\("\.sandbox-status"\);/
    );
    assert.match(
        updateHealth,
        /const statusText = online \? "本地服务已连接" : "本地服务未连接";/
    );
    assert.match(
        updateHealth,
        /const detailText = online[\s\S]*?沙箱：\$\{state\.health\?\.workspace \?\? "\.orderly-data\/workspace"\}[\s\S]*?: "请确认 Java 宿主已启动";/
    );
    assert.match(
        updateHealth,
        /sandboxStatus\?\.setAttribute\("aria-label", `\$\{statusText\}；\$\{detailText\}`\);/
    );
    assert.match(updateHealth, /label\.textContent = statusText;/);
    assert.match(updateHealth, /detail\.textContent = detailText;/);
});

test("desktop expansion uses one state timer and the confirmed hover delays", async () => {
    const app = await read("../src/app.js");
    const scheduler = between(
        app,
        "function scheduleDesktopSidebarTransition",
        "function clearDesktopSidebarTimer"
    );

    assert.match(app, /const SIDEBAR_POINTER_QUERY = "\(min-width: 821px\) and \(hover: hover\) and \(pointer: fine\)";/);
    assert.match(app, /const SIDEBAR_EXPAND_DELAY_MS = 140;/);
    assert.match(app, /const SIDEBAR_COLLAPSE_DELAY_MS = 400;/);
    assert.match(scheduler, /clearDesktopSidebarTimer\(\);/);
    assert.match(scheduler, /desktopSidebar\.timer = window\.setTimeout/);
    assert.equal(
        (app.match(/desktopSidebar\.timer = window\.setTimeout/g) ?? []).length,
        1
    );
    assert.match(app, /desktopSidebar\.expanded \? "expanded" : "collapsed"/);
});

test("focus, search, slash and drag expand immediately while safe blockers delay collapse", async () => {
    const app = await read("../src/app.js");
    const bind = between(app, "function bindGlobalControls", "function bindDesktopSidebarControls");
    const controls = between(
        app,
        "function bindDesktopSidebarControls",
        "function bindSidebarViewport"
    );
    const blocked = between(
        app,
        "function desktopSidebarCollapseBlocked",
        "function reconcileDesktopSidebar"
    );
    const drag = between(app, "function activateNavigationDrag", "function moveNavigationDrag");
    const clearDrag = between(app, "function clearNavigationDrag", "function clearNavigationDropTargets");
    const settings = between(app, "function openSettings", "function renderSettings");

    assert.match(controls, /sidebar\.addEventListener\("focusin"/);
    assert.match(controls, /expandDesktopSidebarImmediately\(\)/);
    assert.match(bind, /search\?\.addEventListener\("pointerdown"/);
    assert.match(bind, /search\?\.addEventListener\("focus"/);
    assert.match(bind, /event\.key === "\/"[\s\S]*?focusDesktopSidebarSearch\(\)/);
    assert.match(blocked, /sidebar\?\.contains\(document\.activeElement\)/);
    assert.match(blocked, /search\?\.value\.trim\(\)/);
    assert.match(blocked, /navigationDrag\?\.active === true/);
    assert.match(blocked, /is-navigation-dragging/);
    assert.match(blocked, /dialog\?\.open === true/);
    assert.match(drag, /expandDesktopSidebarImmediately\(\)/);
    assert.match(clearDrag, /reconcileDesktopSidebar\(\)/);
    assert.match(settings, /queueMicrotask\(reconcileDesktopSidebar\)/);
});

test("plan attention temporarily overlays the real expanded or collapsed state", async () => {
    const app = await read("../src/app.js");
    const pulse = between(app, "function pulsePlanModules", "function loadHistory");

    assert.match(pulse, /clearSidebarAttention\(\)/);
    assert.match(pulse, /sidebar\.setAttribute\("data-motion-state", "attention"\)/);
    assert.match(pulse, /desktopSidebar\.attentionTimer = window\.setTimeout/);
    assert.match(pulse, /desktopSidebar\.expanded \? "expanded" : "collapsed"/);
    assert.doesNotMatch(pulse, /setAttribute\("data-motion-state", "expanded"\)/);
});

test("mobile drawer keeps its width, inert/ARIA behavior and is isolated from desktop expansion", async () => {
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const mobile = appCss.slice(appCss.indexOf("@media (max-width: 820px)"));
    const controls = between(
        app,
        "function bindDesktopSidebarControls",
        "function bindSidebarViewport"
    );
    const mobileOpen = between(app, "function openMobileNav", "function closeMobileNav");

    assert.match(
        mobile,
        /\.sidebar\s*\{[\s\S]*?width:\s*min\(300px, calc\(100vw - 48px\)\);/
    );
    assert.match(mobile, /\.sidebar\.is-open\s*\{\s*transform:\s*translateX\(0\);/);
    assert.match(controls, /if \(!desktopSidebar\.active[\s\S]*?return;/);
    assert.match(app, /sidebar\.inert = hidden;/);
    assert.match(app, /setAttribute\("aria-hidden", "true"\)/);
    assert.match(mobileOpen, /sidebar\?\.classList\.add\("is-open"\)/);
    assert.match(mobileOpen, /data-motion-state", "open"/);
});

test("sidebar width and padding motion lives only in each theme safety layer", async () => {
    const appCss = await read("../styles/app.css");
    const tokens = await read("../styles/tokens.css");

    assert.doesNotMatch(appCss, /(^|[\s;])transition\s*:/m);
    assert.match(tokens, /@layer orderly\.safety/);
    assert.match(tokens, /transition: none !important/);
    for (const id of ["paper", "moss", "night"]) {
        const motion = await read(`../../../themes/${id}/motion.css`);
        assert.match(motion, /^@layer orderly\.theme \{/);
        assert.match(motion, /width var\(--duration-normal\) var\(--ease-out\)/);
        assert.match(motion, /padding var\(--duration-normal\) var\(--ease-out\)/);
    }
});

test("command dock continues to derive available width from the 64px rail", async () => {
    const appCss = await read("../styles/app.css");
    const desktopRule = between(
        appCss,
        ".command-dock {",
        ".command-dock__glow {"
    );
    const compactRule = between(
        appCss,
        "@media (max-width: 1080px)",
        "@media (max-width: 820px)"
    );

    assert.match(
        desktopRule,
        /width:\s*min\(620px, calc\(100vw - var\(--sidebar-width\) - 80px\)\);/
    );
    assert.match(
        compactRule,
        /width:\s*min\(580px, calc\(100vw - var\(--sidebar-width\) - 48px\)\);/
    );
});
