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

test("desktop collapsed navigation keeps a 40px rail, names controls and hides descriptions", async () => {
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
        "function navigationCount",
        "async function navigate"
    );

    for (const selector of [
        ".brand-copy",
        ".search-box input",
        ".search-box kbd",
        ".nav-group__label",
        ".nav-copy",
        ".nav-drag-handle",
        ".workspace-path",
        ".settings-trigger > span:not([data-icon])"
    ]) {
        assert.ok(desktopCss.includes(selector), `桌面收起态缺少隐藏规则：${selector}`);
    }
    assert.match(
        desktopCss,
        /\.nav-item\s*\{[\s\S]*?width:\s*40px;[\s\S]*?height:\s*40px;[\s\S]*?min-height:\s*40px;/
    );
    assert.match(
        desktopCss,
        /\.sidebar\.is-expanded \.nav-item\s*\{[\s\S]*?height:\s*40px;[\s\S]*?min-height:\s*40px;/
    );
    assert.match(
        desktopCss,
        /\.sidebar\.is-expanded \.nav-copy\s*\{[\s\S]*?position:\s*static;/
    );
    assert.match(appCss, /\.nav-icon\s*\{[^}]*width:\s*34px;[^}]*height:\s*34px;/);
    assert.match(renderNavigation, /button\.setAttribute\(\s*"aria-label"/);
    assert.match(renderNavigation, /`\$\{item\.label\}，\$\{item\.caption\}\$\{countLabel\}`/);
    assert.doesNotMatch(renderNavigation, /nav-count|nav-copy small|element\("small"/);
    assert.match(countRule, /Number\.isInteger\(numeric\) && numeric > 0/);
    assert.match(countRule, /\^\\d\+\$/);
    assert.doesNotMatch(html, /<small>外观、动效与系统<\/small>/);
    assert.doesNotMatch(html, /id="health-label"|id="health-detail"|class="status-dot"/);
    assert.match(html, /aria-label="搜索功能"/);
    assert.match(html, /aria-label="打开设置：外观、动效与系统"/);
});

test("workspace footer fits the full relative path in a 24px icon rail", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const updateHealth = between(app, "function updateHealth", "function renderNavigation");
    const safePath = between(app, "function safeWorkspacePath", "function updateHealth");
    const desktopCss = between(
        appCss,
        "@media (min-width: 821px)",
        ".app-main {"
    );

    assert.match(
        html,
        /<div class="sandbox-status" role="status" aria-live="polite" aria-label="正在连接本地服务，等待工作区状态">/
    );
    assert.match(html, /class="workspace-icon" data-icon="folder"/);
    assert.match(html, /id="workspace-path">\.orderly-data\/workspace<\/span>/);
    assert.match(
        appCss,
        /\.sandbox-status\s*\{\s*grid-template-columns:\s*24px minmax\(0, 1fr\);/
    );
    assert.match(
        appCss,
        /\.workspace-icon\s*\{[\s\S]*?width:\s*24px;[\s\S]*?height:\s*24px;/
    );
    assert.match(
        appCss,
        /\.workspace-path\s*\{[\s\S]*?min-width:\s*0;[\s\S]*?overflow:\s*hidden;[\s\S]*?font-size:\s*0\.6rem;[\s\S]*?text-overflow:\s*clip;[\s\S]*?white-space:\s*nowrap;/
    );
    assert.doesNotMatch(
        between(appCss, ".workspace-path {", ".settings-trigger {"),
        /text-overflow:\s*ellipsis/
    );
    assert.match(
        desktopCss,
        /\.sandbox-status\s*\{[\s\S]*?width:\s*40px;[\s\S]*?height:\s*40px;[\s\S]*?grid-template-columns:\s*24px;[\s\S]*?place-items:\s*center;[\s\S]*?justify-content:\s*center;/
    );
    assert.match(
        desktopCss,
        /\.sidebar\.is-expanded \.sandbox-status\s*\{[\s\S]*?grid-template-columns:\s*24px minmax\(0, 1fr\);[\s\S]*?justify-content:\s*normal;[\s\S]*?gap:\s*6px;[\s\S]*?padding:\s*3px 6px;/
    );
    assert.match(
        updateHealth,
        /const sandboxStatus = document\.querySelector\("\.sandbox-status"\);/
    );
    assert.match(
        updateHealth,
        /const statusText = online[\s\S]*?工作区已就绪；路径 \$\{workspacePath\}[\s\S]*?: "工作区不可用；请启动本地 Java 宿主";/
    );
    assert.match(
        updateHealth,
        /workspacePathNode\.textContent = workspacePath;/
    );
    assert.match(
        updateHealth,
        /sandboxStatus\?\.setAttribute\("aria-label", statusText\);/
    );
    assert.match(safePath, /\.orderly-data\/workspace/);
    assert.doesNotMatch(
        updateHealth,
        /health-dot|health-label|health-detail|label\.textContent|detail\.textContent/
    );
});

test("desktop expansion uses one state timer and the confirmed hover delays", async () => {
    const app = await read("../src/app.js");
    const scheduler = between(
        app,
        "function scheduleDesktopSidebarTransition",
        "function clearDesktopSidebarTimer"
    );

    assert.match(app, /const SIDEBAR_POINTER_QUERY = "\(min-width: 821px\) and \(hover: hover\) and \(pointer: fine\)";/);
    assert.match(app, /const SIDEBAR_EXPAND_DELAY_MS = 40;/);
    assert.match(app, /const SIDEBAR_COLLAPSE_DELAY_MS = 120;/);
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

test("module counts use an external noninteractive portal and never enter the clipped rail", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const renderCounts = between(
        app,
        "function renderSidebarCountLayer",
        "function clearSidebarCountLayer"
    );
    const renderNavigation = between(
        app,
        "function renderNavigation",
        "function beginNavigationDrag"
    );
    const countRule = between(app, "function navigationCount", "async function navigate");

    assert.ok(
        html.indexOf('id="sidebar-count-layer"')
        > html.indexOf('class="app-shell"')
    );
    assert.ok(
        html.indexOf('id="sidebar-count-layer"')
        < html.indexOf('class="command-dock"')
    );
    assert.match(
        html,
        /id="sidebar-count-layer" aria-hidden="true"><\/div>/
    );
    assert.match(
        appCss,
        /\.sidebar-count-layer\s*\{[\s\S]*?position:\s*fixed;[\s\S]*?z-index:\s*25;[\s\S]*?pointer-events:\s*none;/
    );
    assert.match(
        appCss,
        /\.sidebar-count-bubble\s*\{[\s\S]*?position:\s*fixed;[\s\S]*?pointer-events:\s*none;/
    );
    assert.match(renderCounts, /\.nav-item--module\[data-module-id\]/);
    assert.match(renderCounts, /button\.getBoundingClientRect\(\)/);
    assert.match(renderCounts, /sidebarRect\.left \+ sidebarRailWidth\(\) \+ 4/);
    assert.match(
        renderCounts,
        /element\("span", "sidebar-count-bubble", String\(count\)\)/
    );
    assert.doesNotMatch(renderCounts, /item\.label|module\.title|card\.unit|caption/);
    assert.match(
        renderCounts,
        /desktopSidebar\.expanded[\s\S]*?window\.matchMedia\(MOBILE_NAV_QUERY\)\.matches/
    );
    assert.match(countRule, /typeof raw === "number"/);
    assert.match(countRule, /\^\\d\+\$/);
    assert.match(countRule, /Number\.isInteger\(numeric\) && numeric > 0/);
    assert.match(app, /#sidebar-count-layer"\)\?\.replaceChildren\(\)/);
    assert.match(app, /function scheduleSidebarCountRefreshAfterCollapse/);
    assert.match(app, /addEventListener\(\s*"scroll",\s*queueSidebarCountRender/s);
    assert.match(app, /addEventListener\("resize", queueSidebarCountRender/);
    assert.match(
        app,
        /if \(expanded\) \{[\s\S]*?clearSidebarCountLayer\(\);[\s\S]*?\} else if \(wasExpanded\) \{[\s\S]*?scheduleSidebarCountRefreshAfterCollapse\(\);/
    );
    assert.match(renderNavigation, /queueSidebarCountRender\(\);/);
});

test("desktop sidebar motion is width-only and mobile keeps its transform transition", async () => {
    const appCss = await read("../styles/app.css");
    const tokens = await read("../styles/tokens.css");

    assert.doesNotMatch(appCss, /(^|[\s;])transition\s*:/m);
    assert.match(tokens, /--duration-sidebar:\s*120ms;/);
    assert.match(
        tokens,
        /html\[data-motion="minimal"\]\s*\{[\s\S]*?--duration-sidebar:\s*80ms;/
    );
    assert.match(
        tokens,
        /html\[data-motion="none"\]\s*\{[\s\S]*?--duration-sidebar:\s*0ms;/
    );
    assert.match(
        tokens,
        /@media \(prefers-reduced-motion: reduce\)\s*\{[\s\S]*?--duration-sidebar:\s*0ms;/
    );
    assert.match(tokens, /@layer orderly\.safety/);
    assert.match(tokens, /transition: none !important/);
    for (const id of ["paper", "moss", "night"]) {
        const motion = await read(`../../../themes/${id}/motion.css`);
        assert.match(motion, /^@layer orderly\.theme \{/);
        assert.match(
            motion,
            /\[data-motion-scope="sidebar"\]\s*\{\s*transition:\s*width var\(--duration-sidebar\) var\(--ease-out\);/
        );
        assert.doesNotMatch(motion, /padding var\(/);
        assert.match(
            motion,
            /@media \(max-width: 820px\)\s*\{[\s\S]*?transition:\s*transform var\(--duration-normal\) var\(--ease-out\);/
        );
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
