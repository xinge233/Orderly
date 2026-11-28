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
});

test("collapsed desktop rows keep complete icon bases without clipped row chrome", async () => {
    const tokens = await read("../styles/tokens.css");
    const appCss = await read("../styles/app.css");
    const desktopCss = between(
        appCss,
        "@media (min-width: 821px)",
        ".app-main {"
    );
    const mobileCss = appCss.slice(appCss.indexOf("@media (max-width: 820px)"));
    const collapsedRows = between(
        desktopCss,
        ".sidebar:not(.is-expanded) .search-box,",
        ".brand {"
    );
    const baseSearch = between(
        appCss,
        ".search-box {",
        ".search-box:focus-within {"
    );
    const baseWorkspace = between(
        appCss,
        ".workspace-icon {",
        ".workspace-path {"
    );
    const expandedWorkspace = between(
        desktopCss,
        ".sandbox-status {",
        ".sandbox-status .workspace-icon {"
    );

    assert.match(
        collapsedRows,
        /^\.sidebar:not\(\.is-expanded\) \.search-box,\s*\.sidebar:not\(\.is-expanded\) \.sandbox-status\s*\{/
    );
    assert.match(collapsedRows, /border-color:\s*transparent;/);
    assert.match(collapsedRows, /background:\s*transparent;/);
    assert.doesNotMatch(
        collapsedRows,
        /\b(?:width|height|padding|transform|transition)\s*:/
    );
    assert.match(
        desktopCss,
        /\.search-box__icon\s*\{\s*display:\s*grid;\s*place-items:\s*center;\s*width:\s*var\(--sidebar-icon-size\);\s*height:\s*var\(--sidebar-icon-size\);\s*border-radius:\s*var\(--radius-sm\);\s*background:\s*var\(--color-surface-muted\);\s*\}/
    );
    assert.match(
        baseSearch,
        /border:\s*1px solid transparent;[\s\S]*?background:\s*color-mix\(in srgb,\s*var\(--color-surface\) 66%,\s*transparent\);/
    );
    assert.match(
        appCss,
        /\.search-box:focus-within\s*\{\s*border-color:\s*var\(--color-line-strong\);\s*background:\s*var\(--color-surface\);\s*\}/
    );
    assert.match(
        baseWorkspace,
        /width:\s*24px;[\s\S]*?height:\s*24px;[\s\S]*?border-radius:\s*10px;[\s\S]*?background:\s*var\(--color-surface-muted\);/
    );
    assert.match(
        desktopCss,
        /\.sandbox-status \.workspace-icon\s*\{\s*width:\s*36px;\s*height:\s*36px;\s*justify-self:\s*center;\s*\}/
    );
    assert.match(
        expandedWorkspace,
        /border-color:\s*var\(--color-line\);[\s\S]*?background:\s*color-mix\(in srgb,\s*var\(--color-surface\) 54%,\s*transparent\);/
    );
    assert.doesNotMatch(
        mobileCss,
        /\.sidebar:not\(\.is-expanded\) \.(?:search-box|sandbox-status)/
    );
    assert.match(tokens, /--sidebar-width:\s*72px;/);
    assert.match(tokens, /--sidebar-expanded-width:\s*272px;/);
    assert.match(tokens, /--sidebar-row-height:\s*48px;/);
    assert.match(
        desktopCss,
        /\.sidebar\s*\{[\s\S]*?width:\s*var\(--sidebar-expanded-width\);[\s\S]*?clip-path:\s*inset\(0 calc\(100% - var\(--sidebar-width\)\) 0 0\);/
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
    assert.match(renderNavigation, /`\$\{item\.label\}，\$\{item\.caption\}`/);
    assert.doesNotMatch(renderNavigation, /nav-count|nav-copy small|element\("small"/);
    assert.match(renderNavigation, /syncAiModuleActivity\(button, item\.moduleId\)/);
    assert.doesNotMatch(renderNavigation, /navigationCount|countLabel/);
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

test("AI module feedback anchors to the sidebar edge with a sharp tail", async () => {
    const html = await read("../index.html");
    const app = await read("../src/app.js");
    const appCss = await read("../styles/app.css");
    const renderNavigation = between(
        app,
        "function renderNavigation",
        "function beginNavigationDrag"
    );
    const activity = between(
        app,
        "function applyAiModuleActivity",
        "async function navigate"
    );

    assert.doesNotMatch(html, /sidebar-count-layer|sidebar-count-bubble/);
    assert.doesNotMatch(appCss, /sidebar-count-layer|sidebar-count-bubble/);
    assert.match(html, /id="nav-ai-result-layer"/);
    assert.match(appCss, /\.nav-ai-progress\s*\{[\s\S]*?height:\s*2px;/);
    assert.match(
        appCss,
        /\.nav-ai-result-layer\s*\{[\s\S]*?position:\s*fixed;[\s\S]*?pointer-events:\s*none;/
    );
    assert.match(
        appCss,
        /\.nav-ai-result\s*\{[\s\S]*?border-radius:\s*var\(--radius-sm\) var\(--radius-sm\) var\(--radius-sm\) var\(--radius-xs\);/
    );
    assert.match(
        appCss,
        /\.nav-ai-result::after\s*\{[\s\S]*?left:\s*-7px;[\s\S]*?clip-path:\s*polygon\(100% 0, 100% 100%, 0 50%\);/
    );
    assert.match(
        appCss,
        /\.nav-ai-result__icon\s*\{[\s\S]*?filter:\s*drop-shadow\(/
    );
    assert.match(
        appCss,
        /\.sidebar:not\(\.is-expanded\) \.nav-ai-progress\s*\{[\s\S]*?right:\s*calc\(100% - var\(--sidebar-width\) \+ var\(--space-4\)\);/
    );
    assert.match(
        appCss,
        /\.nav-ai-result-layer \.nav-ai-result\s*\{[\s\S]*?position:\s*fixed;/
    );
    assert.match(appCss, /\.nav-ai-result\[data-phase="success"\]/);
    assert.match(appCss, /\.nav-ai-result\[data-phase="failure"\]/);
    assert.match(appCss, /\.nav-ai-result\.is-leaving\s*\{\s*opacity:\s*0;/);
    assert.match(renderNavigation, /syncAiModuleActivity\(button, item\.moduleId\)/);
    assert.doesNotMatch(renderNavigation, /navigationCount|countLabel/);
    assert.match(activity, /activity\.running\.add/);
    assert.match(activity, /activity\.running\.size > 0/);
    assert.match(activity, /activity\.running\.delete\(AI_ANONYMOUS_STEP\)/);
    assert.match(app, /const AI_ANONYMOUS_STEP = Symbol\("ai-anonymous-step"\)/);
    assert.match(activity, /AI_MODULE_RESULT_HOLD_MS/);
    assert.match(activity, /AI_MODULE_RESULT_EXIT_MS/);
    assert.match(activity, /function positionAiModuleResult/);
    assert.match(activity, /function bindAiModuleResultLayer/);
    assert.match(activity, /#nav-ai-result-layer/);
    assert.match(activity, /role", "progressbar"/);
    assert.match(activity, /icon\(successful \? "check-plain" : "close", 12\)/);
    assert.match(activity, /result\.setAttribute\("role", "status"\)/);
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
    assert.doesNotMatch(tokens, /prefers-reduced-motion/);
    assert.match(tokens, /@layer orderly\.safety/);
    assert.match(tokens, /transition: none !important/);
    for (const id of ["paper", "moss", "night", "study"]) {
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
        assert.match(
            motion,
            /\.nav-ai-progress::after\s*\{[\s\S]*?animation:[\s\S]*?var\(--duration-ai-activity\)/
        );
        assert.match(
            motion,
            /\.nav-ai-result\[data-phase\]:not\(\.is-leaving\)\s*\{[\s\S]*?animation:[\s\S]*?var\(--duration-slow\)/
        );
        assert.match(
            motion,
            /\.nav-ai-result\[data-phase="success"\]:not\(\.is-leaving\)\s*\{[\s\S]*?animation:\s*[\s\S]*?var\(--duration-slow\)[\s\S]*?var\(--duration-ai-activity\)/
        );
        assert.match(
            motion,
            /@keyframes (?:\w+-)?nav-ai-result-sway\s*\{[\s\S]*?translate:\s*-0\.6px 0;[\s\S]*?rotate:\s*-1deg;/
        );
    }
});

test("AI composer stays anchored to the draggable dock instead of the sidebar", async () => {
    const appCss = await read("../styles/app.css");
    const desktopRule = between(
        appCss,
        ".ai-composer {",
        ".ai-dock[data-composer=\"open\"] .ai-composer {"
    );
    const compactRule = between(
        appCss,
        "@media (max-width: 1080px)",
        "@media (max-width: 820px)"
    );

    assert.match(
        desktopRule,
        /right:\s*0;/
    );
    assert.match(desktopRule, /width:\s*min\(var\(--ai-composer-current-width\)/);
    assert.doesNotMatch(desktopRule, /left:\s*calc/);
    assert.doesNotMatch(compactRule, /\.ai-composer\s*\{[\s\S]*?left:/);
});
