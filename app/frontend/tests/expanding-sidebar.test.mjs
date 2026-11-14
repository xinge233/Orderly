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

test("desktop sidebar is a fixed 272px overlay revealed by clip-path", async () => {
    const html = await read("../index.html");
    const tokens = await read("../styles/tokens.css");
    const appCss = await read("../styles/app.css");
    const app = await read("../src/app.js");
    const desktopCss = between(
        appCss,
        "@media (min-width: 821px)",
        ".app-main {"
    );
    const collapsedRule = between(
        desktopCss,
        ".sidebar {",
        ".sidebar.is-expanded {"
    );
    const expandedRule = between(
        desktopCss,
        ".sidebar.is-expanded {",
        ".brand {"
    );

    assert.match(
        html,
        /<aside class="sidebar"[\s\S]*?data-motion-state="collapsed"/
    );
    assert.match(tokens, /--sidebar-width:\s*72px;/);
    assert.match(tokens, /--sidebar-expanded-width:\s*272px;/);
    assert.match(tokens, /--sidebar-row-height:\s*48px;/);
    assert.match(tokens, /--sidebar-icon-size:\s*40px;/);
    assert.match(tokens, /--content-max:\s*1520px;/);
    assert.match(
        appCss,
        /\.app-shell\s*\{[\s\S]*?grid-template-columns:\s*var\(--sidebar-width\) minmax\(0,\s*1fr\);/
    );
    assert.match(
        collapsedRule,
        /width:\s*var\(--sidebar-expanded-width\);/
    );
    assert.match(
        collapsedRule,
        /clip-path:\s*inset\(0 calc\(100% - var\(--sidebar-width\)\) 0 0\);/
    );
    assert.match(
        expandedRule,
        /clip-path:\s*inset\(0 0 0 0\);/
    );
    assert.match(expandedRule, /background-image:\s*none;/);
    assert.doesNotMatch(expandedRule, /width:/);
    assert.doesNotMatch(`${collapsedRule}\n${expandedRule}`, /transition:\s*width/);
    assert.match(
        collapsedRule,
        /background-image:\s*[\s\S]*?linear-gradient\([\s\S]*?transparent calc\(var\(--sidebar-width\) - 1px\)[\s\S]*?var\(--color-line\) var\(--sidebar-width\)/
    );
    assert.match(collapsedRule, /background-repeat:\s*no-repeat;/);
    assert.doesNotMatch(desktopCss, /\.sidebar::(?:before|after)/);
    assert.doesNotMatch(appCss, /\.app-shell\s*\{[^}]*--sidebar-expanded-width/s);
    assert.match(
        app,
        /return Number\.isFinite\(width\) && width > 0 \? width : 72;/
    );
});

test("desktop sidebar geometry stays fixed while hover and selection stay compact", async () => {
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

    const expandedRule = between(
        desktopCss,
        ".sidebar.is-expanded {",
        ".brand {"
    );
    assert.match(expandedRule, /clip-path:\s*inset\(0 0 0 0\);/);
    assert.doesNotMatch(desktopCss, /\.sidebar\.is-expanded\s+\./);
    assert.match(
        desktopCss,
        /\.nav-item\s*\{\s*grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(0,\s*1fr\);[\s\S]*?gap:\s*var\(--space-5\);[\s\S]*?width:\s*100%;[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?min-height:\s*var\(--sidebar-row-height\);/
    );
    assert.match(
        desktopCss,
        /\.nav-item\s*\{[\s\S]*?padding:\s*var\(--space-1\);/
    );
    assert.match(
        desktopCss,
        /\.nav-copy\s*\{\s*position:\s*static;\s*width:\s*auto;/
    );
    assert.match(
        desktopCss,
        /\.brand-copy\s*\{\s*display:\s*flex;\s*\}/
    );
    assert.match(
        desktopCss,
        /\.search-box\s*\{[\s\S]*?grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(120px,\s*1fr\) auto;[\s\S]*?gap:\s*var\(--space-3\);/
    );
    assert.match(
        desktopCss,
        /\.search-box input\s*\{[\s\S]*?position:\s*static;[\s\S]*?min-width:\s*0;[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?font-size:\s*var\(--font-size-control\);[\s\S]*?opacity:\s*1;/
    );
    assert.match(
        desktopCss,
        /\.nav-group__label\s*\{[\s\S]*?padding:\s*0 var\(--space-3\) 0\s+calc\(var\(--sidebar-width\) \+ var\(--space-1\)\);/
    );
    assert.match(
        appCss,
        /\.nav-icon\s*\{[^}]*width:\s*var\(--sidebar-icon-size\);[^}]*height:\s*var\(--sidebar-icon-size\);/
    );
    assert.match(
        desktopCss,
        /\.brand-mark\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/
    );
    assert.match(
        desktopCss,
        /\.workspace-icon\s*\{[\s\S]*?width:\s*36px;[\s\S]*?height:\s*36px;/
    );
    assert.match(
        desktopCss,
        /\.settings-trigger > \[data-icon\]\s*\{\s*width:\s*var\(--sidebar-icon-size\);\s*height:\s*var\(--sidebar-icon-size\);/
    );
    assert.match(
        desktopCss,
        /\.nav-item\s*\{[^}]*padding:\s*var\(--space-1\);/
    );
    assert.match(
        appCss,
        /\.nav-item\s*\{[^}]*border-radius:\s*var\(--radius-sm\);/
    );
    assert.match(
        desktopCss,
        /\.nav-item:hover,\s*\.nav-item\.is-active\s*\{\s*background:\s*transparent;\s*box-shadow:\s*none;/
    );
    assert.match(
        desktopCss,
        /\.nav-item:hover \.nav-icon\s*\{\s*background:\s*var\(--color-surface\);\s*\}/
    );
    assert.match(
        desktopCss,
        /\.nav-item\.is-active \.nav-icon\s*\{\s*background:\s*var\(--color-accent-soft\);/
    );
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

test("workspace footer fits the path in a 36px icon row and ellipsizes overflow", async () => {
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
        desktopCss,
        /grid-template-columns:\s*36px minmax\(0, 1fr\);/
    );
    assert.match(
        desktopCss,
        /\.workspace-icon\s*\{[\s\S]*?width:\s*36px;[\s\S]*?height:\s*36px;/
    );
    assert.match(
        appCss,
        /\.workspace-path\s*\{[\s\S]*?max-width:\s*100%;[\s\S]*?min-width:\s*0;[\s\S]*?overflow:\s*hidden;[\s\S]*?font-size:\s*var\(--font-size-caption\);[\s\S]*?text-overflow:\s*ellipsis;[\s\S]*?white-space:\s*nowrap;/
    );
    assert.match(
        between(appCss, ".workspace-path {", ".settings-trigger {"),
        /text-overflow:\s*ellipsis/
    );
    assert.match(
        desktopCss,
        /\.sandbox-status\s*\{[\s\S]*?width:\s*100%;[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?grid-template-columns:\s*36px minmax\(0,\s*1fr\);[\s\S]*?gap:\s*var\(--space-5\);[\s\S]*?padding:\s*var\(--space-1\) var\(--space-2\);/
    );
    assert.match(
        desktopCss,
        /\.settings-trigger\s*\{[\s\S]*?width:\s*100%;[\s\S]*?height:\s*var\(--sidebar-row-height\);[\s\S]*?grid-template-columns:\s*var\(--sidebar-icon-size\) minmax\(0,\s*1fr\) auto;[\s\S]*?gap:\s*var\(--space-5\);/
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
    const activate = between(app, "function activateDesktopSidebar", "function deactivateDesktopSidebar");

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
    assert.match(activate, /sidebar\.inert = false;/);
    assert.match(activate, /sidebar\.removeAttribute\("aria-hidden"\);/);
    assert.doesNotMatch(activate, /setAttribute\("aria-hidden", "true"\)/);
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
    assert.match(
        mobile,
        /\.sidebar\s*\{[\s\S]*?clip-path:\s*none;[\s\S]*?transform:\s*translateX\(-105%\);/
    );
    assert.match(mobile, /\.sidebar\.is-open\s*\{\s*transform:\s*translateX\(0\);/);
    assert.match(controls, /if \(!desktopSidebar\.active[\s\S]*?return;/);
    assert.match(app, /sidebar\.inert = hidden;/);
    assert.match(app, /setAttribute\("aria-hidden", "true"\)/);
    assert.match(mobileOpen, /sidebar\?\.classList\.add\("is-open"\)/);
    assert.match(mobileOpen, /data-motion-state", "open"/);
});

test("module counts interpolate with parsed clip width through expansion and collapse", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const renderCounts = between(
        app,
        "function renderSidebarCountLayer",
        "function clearSidebarCountLayer"
    );
    const textAnchor = between(
        app,
        "function navigationTextRect",
        "function clipInsetValues"
    );
    const clipParser = between(
        app,
        "function clipInsetValues",
        "function isSidebarClipTransition"
    );
    const expansionState = between(
        app,
        "function setDesktopSidebarExpansion",
        "function renderSidebarCountLayer"
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
        /\.sidebar-count-bubble\s*\{[\s\S]*?position:\s*fixed;[\s\S]*?border-radius:\s*var\(--radius-xs\);[\s\S]*?pointer-events:\s*none;/
    );
    assert.match(
        appCss,
        /\.sidebar-count-bubble::before\s*\{[\s\S]*?left:\s*-6px;[\s\S]*?clip-path:\s*polygon\(100% 0,\s*100% 100%,\s*0 50%\);/
    );
    assert.match(renderCounts, /\.nav-item--module\[data-module-id\]/);
    assert.match(renderCounts, /button\.getBoundingClientRect\(\)/);
    assert.match(renderCounts, /window\.getComputedStyle\(sidebar\)\.clipPath/);
    assert.match(renderCounts, /sidebarVisibleWidth\(sidebarRect\.width, clipPath\)/);
    assert.match(renderCounts, /const railRight = sidebarRect\.left \+ railWidth;/);
    assert.match(renderCounts, /const expansionProgress = Math\.max/);
    assert.match(renderCounts, /const collapsedLeft = railRight \+ 4;/);
    assert.match(renderCounts, /const expandedLeft = textRect \? textRect\.right \+ 7 : collapsedLeft;/);
    assert.match(
        renderCounts,
        /let left = collapsedLeft\s*\+ \(expandedLeft - collapsedLeft\) \* expansionProgress;/
    );
    assert.match(clipParser, /function clipInsetValues/);
    assert.match(clipParser, /function sidebarVisibleWidth/);
    assert.match(clipParser, /const rightValue = values\.length >= 2 \? values\[1\] : values\[0\];/);
    assert.match(clipParser, /return Math\.max\(0, Math\.min\(sidebarWidth, sidebarWidth - left - right\)\);/);
    assert.match(renderCounts, /const existing = new Map/);
    assert.match(renderCounts, /existing\.get\(moduleId\)[\s\S]*?element\("span", "sidebar-count-bubble"/);
    assert.match(textAnchor, /button\.querySelector\("\.nav-copy strong"\)/);
    assert.match(textAnchor, /const range = document\.createRange\(\);/);
    assert.match(textAnchor, /range\.selectNodeContents\(text\);/);
    assert.match(textAnchor, /range\.getBoundingClientRect\(\)/);
    assert.match(renderCounts, /const textRect = navigationTextRect\(button\);/);
    assert.match(
        renderCounts,
        /left - 6 < handleRect\.right[\s\S]*?left \+ bubbleWidth > handleRect\.left[\s\S]*?left = handleRect\.right \+ 7;/
    );
    assert.match(
        renderCounts,
        /left = Math\.max\(railRight \+ 4, left\);[\s\S]*?const currentVisibleRight = sidebarRect\.left \+ visibleWidth;[\s\S]*?sidebarCountTrackingFrame != null[\s\S]*?visibleWidth > railWidth[\s\S]*?left \+ bubbleWidth > currentVisibleRight[\s\S]*?left = currentVisibleRight - bubbleWidth;/
    );
    assert.doesNotMatch(renderCounts, /querySelector\("\.nav-copy"\)\?\.getBoundingClientRect\(\)/);
    const measurementsAt = renderCounts.indexOf("const measurements = entries.map");
    const handleAvoidanceAt = renderCounts.indexOf("left = handleRect.right + 7;");
    const clipClampAt = renderCounts.indexOf("left = currentVisibleRight - bubbleWidth;");
    const styleWriteAt = renderCounts.indexOf("bubble.style.left");
    assert.ok(measurementsAt >= 0);
    assert.ok(handleAvoidanceAt > measurementsAt);
    assert.ok(clipClampAt > handleAvoidanceAt);
    assert.ok(styleWriteAt > measurementsAt);
    assert.ok(styleWriteAt > clipClampAt);
    assert.doesNotMatch(
        renderCounts.slice(measurementsAt, styleWriteAt),
        /bubble\.style\s*=/
    );
    assert.match(
        renderCounts,
        /element\("span", "sidebar-count-bubble", String\(count\)\)/
    );
    assert.doesNotMatch(renderCounts, /item\.label|module\.title|card\.unit|caption/);
    assert.doesNotMatch(
        renderCounts,
        /desktopSidebar\.active\s*\|\|\s*desktopSidebar\.expanded/
    );
    assert.match(countRule, /typeof raw === "number"/);
    assert.match(countRule, /\^\\d\+\$/);
    assert.match(countRule, /Number\.isInteger\(numeric\) && numeric > 0/);
    assert.match(app, /#sidebar-count-layer"\)\?\.replaceChildren\(\)/);
    assert.match(app, /function scheduleSidebarCountRefreshAfterCollapse/);
    assert.match(app, /function beginSidebarCountTracking/);
    assert.match(app, /function finishSidebarCountTracking/);
    assert.match(app, /function cancelSidebarCountTracking/);
    assert.match(app, /sidebarCountTrackingFrame = requestSidebarFrame\(track\)/);
    assert.match(
        app,
        /function startSidebarCountFrameLoop\(\)\s*\{\s*const track = \(\) => \{[\s\S]*?renderSidebarCountLayer\(\);[\s\S]*?sidebarCountTrackingFrame = requestSidebarFrame\(track\);/
    );
    assert.match(app, /event\.propertyName === "clip-path"/);
    assert.equal(
        (app.match(/if \([^\n]*isSidebarClipTransition\(event, sidebar\)/g) ?? []).length,
        2
    );
    assert.equal(
        (app.match(/addEventListener\("transitionend", (?:handler|finish)\)/g) ?? []).length,
        2
    );
    assert.match(
        app,
        /function beginSidebarCountTracking\(\)\s*\{\s*cancelSidebarCountTracking\(\);\s*cancelSidebarCountRefresh\(\);/
    );
    assert.match(
        app,
        /function scheduleSidebarCountRefreshAfterCollapse\(\)\s*\{\s*cancelSidebarCountTracking\(\);\s*cancelSidebarCountRefresh\(\);/
    );
    assert.doesNotMatch(
        between(
            app,
            "function scheduleSidebarCountRefreshAfterCollapse",
            "function beginSidebarCountTracking"
        ),
        /clearSidebarCountLayer/
    );
    assert.match(
        expansionState,
        /if \(duration <= 0\) \{\s*cancelSidebarCountTracking\(\);\s*cancelSidebarCountRefresh\(\);\s*renderSidebarCountLayer\(\);/
    );
    assert.match(
        expansionState,
        /else if \(expanded\) \{\s*beginSidebarCountTracking\(\);\s*\} else \{\s*scheduleSidebarCountRefreshAfterCollapse\(\);/
    );
    assert.match(app, /addEventListener\(\s*"scroll",\s*queueSidebarCountRender/s);
    assert.match(app, /addEventListener\("resize", queueSidebarCountRender/);
    assert.match(renderNavigation, /queueSidebarCountRender\(\);/);
});

test("desktop sidebar motion is clip-path-only and mobile keeps transform", async () => {
    const appCss = await read("../styles/app.css");
    const tokens = await read("../styles/tokens.css");
    const desktopCss = between(
        appCss,
        "@media (min-width: 821px)",
        ".app-main {"
    );

    assert.doesNotMatch(appCss, /(^|[\s;])transition\s*:/m);
    assert.doesNotMatch(desktopCss, /\bscale\s*\(/);
    assert.doesNotMatch(
        desktopCss,
        /transition\s*:[^;]*(?:font-size|width|height|transform)/
    );
    assert.doesNotMatch(desktopCss, /(?:height|transform)\s*:[^;]*var\(--duration/);
    assert.match(tokens, /--duration-sidebar:\s*200ms;/);
    assert.match(
        tokens,
        /html\[data-motion="minimal"\]\s*\{[\s\S]*?--duration-sidebar:\s*110ms;/
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
            /\[data-motion-scope="sidebar"\]\s*\{\s*transition:\s*clip-path var\(--duration-sidebar\) var\(--ease-out\);/
        );
        assert.doesNotMatch(
            motion,
            /\[data-motion-scope="sidebar"\]\s*\{\s*transition:\s*width var\(/
        );
        assert.doesNotMatch(motion, /padding var\(/);
        assert.doesNotMatch(
            between(
                motion,
                '[data-motion-scope="sidebar"] {',
                "}"
            ),
            /\b\d+(?:\.\d+)?m?s\b/
        );
        assert.match(
            motion,
            /@media \(max-width: 820px\)\s*\{[\s\S]*?transition:\s*transform var\(--duration-normal\) var\(--ease-out\);/
        );
    }
});

test("command dock clears the expanded 272px sidebar between 821px and 1080px", async () => {
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
        /width:\s*clamp\(\s*320px,\s*calc\(100vw - var\(--sidebar-expanded-width\) - 48px\),\s*580px\s*\);/
    );
});
