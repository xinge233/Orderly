import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const moduleUrl = new URL(
    "../../main/resources/orderly-ui/module.js",
    import.meta.url
);
const styleUrl = new URL(
    "../../main/resources/orderly-ui/module.css",
    import.meta.url
);
const appUrl = new URL(
    "../../../../../app/frontend/src/app.js",
    import.meta.url
);
const contractUrl = new URL(
    "../../main/resources/orderly-modules/module_system_codegen.json",
    import.meta.url
);
const codegenModule = await import(moduleUrl.href);

test("codegen target catalog contains business modules only and preserves order", () => {
    const modules = codegenModule.normalizeCodegenModules([
        {
            moduleId: "module_todo",
            title: "待办",
            source: "business",
            order: 20,
            actions: [{ id: "todo.create", title: "创建待办", risk: "WRITE" }]
        },
        {
            moduleId: "module_system_control",
            title: "模组设置",
            source: "system",
            order: 10000,
            system: { role: "MODULE_CONTROL" }
        },
        {
            moduleId: "module_fs",
            title: "文件",
            source: "business",
            order: 10,
            queries: [{ id: "fs.list", title: "列出文件" }]
        }
    ]);

    assert.deepEqual(modules.map((module) => module.moduleId), [
        "module_fs",
        "module_todo"
    ]);
    assert.equal(modules[0].order, 10);
});

test("interface rows expose actions, queries and planner rules without a second schema", () => {
    const rows = codegenModule.interfaceRows({
        actions: [{
            id: "todo.create",
            title: "创建待办",
            risk: "WRITE",
            parameters: [{ name: "title" }, { name: "dueAt" }]
        }],
        queries: [{
            id: "todo.list",
            title: "待办列表",
            parameters: []
        }],
        plannerRules: [{
            id: "todo.rule.create",
            title: "创建待办规则",
            pattern: "创建待办"
        }]
    });

    assert.deepEqual(rows.map((row) => [row.kind, row.id, row.risk]), [
        ["Action", "todo.create", "WRITE"],
        ["Query", "todo.list", "READ_ONLY"],
        ["Plan", "todo.rule.create", "LOCAL"]
    ]);
});

test("system service delegates generation to the protected host API", async () => {
    const contract = JSON.parse(await readFile(contractUrl, "utf8"));
    const source = await readFile(moduleUrl, "utf8");
    const style = await readFile(styleUrl, "utf8");

    assert.equal(contract.moduleId, "module_system_codegen");
    assert.equal(contract.system.role, "SYSTEM_SERVICE");
    assert.equal(contract.system.protected, true);
    assert.deepEqual(contract.system.capabilities, []);
    assert.deepEqual(contract.actions, []);
    assert.deepEqual(contract.queries, []);
    assert.match(source, /\/api\/codegen\/sessions/);
    assert.match(source, /generate-codegen/);
    assert.match(source, /preview-codegen/);
    assert.match(source, /restoreSession/);
    assert.match(source, /codegenPreviewAction/);
    assert.match(source, /target = "_blank"/);
    assert.match(source, /module-codegen-ui-preview-link/);
    assert.doesNotMatch(source, /globalThis\.open\(/);
    assert.match(source, /apply-codegen-version/);
    assert.match(source, /rollback-codegen-step/);
    assert.match(source, /module-codegen-ui-layout is-module-workspace/);
    assert.match(source, /module-codegen-ui-module-prompt-panel/);
    assert.match(source, /module-codegen-ui-target-trigger/);
    assert.match(source, /function openApiDialog/);
    assert.match(source, /dialog\.showModal\(\)/);
    assert.match(source, /context\.openSettings\("ai"\)/);
    assert.match(source, /generateButton\.addEventListener\("click", \(\) => generate\("module"\)\)/);
    assert.match(source, /module-codegen-ui-motion-studio/);
    assert.match(source, /module-codegen-ui-motion-target-panel/);
    assert.match(source, /module-codegen-ui-hook-card/);
    assert.match(source, /module-codegen-ui-motion-progress/);
    assert.match(source, /module-codegen-ui-motion-composer-region/);
    assert.match(source, /buildMotionCandidateActions/);
    assert.match(source, /buildMotionProgressSteps/);
    assert.match(source, /state\.motionGenerating \|\| state\.session/);
    assert.match(source, /state\.status = "版本已回滚，生成前文件已恢复。"/);
    assert.match(source, /const actionLabel = version\.status === "ROLLED_BACK"/);
    assert.match(source, /module-codegen-ui-motion-side-tabs/);
    assert.match(source, /module-codegen-ui-motion-rollback-panel/);
    assert.match(source, /module-codegen-ui-live-preview-frame/);
    assert.match(source, /module-codegen-ui-annotation-canvas/);
    assert.match(source, /motionPreviewSource/);
    assert.match(source, /data-motion-preview-detail/);
    assert.match(source, /motionAnnotations: state\.motionAnnotations/);
    assert.match(source, /MOTION_PREVIEW_CHANNEL/);
    assert.match(source, /\/api\/codegen\/versions/);
    assert.match(source, /rollbackMotionVersion/);
    assert.match(source, /input\.placeholder = "请输入 API"/);
    assert.match(source, /module-codegen-ui-motion-composer-confirm/);
    assert.match(source, /context\.toast\(message, \{ error \}\)/);
    assert.match(source, /toggleMotionPreviewFocus/);
    assert.match(source, /saveMotionApiKey/);
    assert.match(source, /const bodyText = await response\.text\(\);/);
    assert.doesNotMatch(source, /const text = await response\.text\(\);/);
    assert.doesNotMatch(source, /state\.apiKey/);
    assert.doesNotMatch(source, /buildModelField/);
    assert.doesNotMatch(source, /const generate = button/);
    assert.doesNotMatch(source, /risk\.textContent/);
    assert.match(style, /aspect-ratio:\s*16\s*\/\s*9/);
    assert.match(style, /\.module-codegen-ui-picker\s*\{/);
    assert.match(style, /\.module-codegen-ui-step-row\s*\{/);
    assert.match(style, /\.module-codegen-ui-preview-link\s*\{[^}]*text-decoration:\s*none/s);
    assert.match(style, /\.module-codegen-ui-live-preview\s*\{/);
    assert.match(style, /\.module-codegen-ui-live-preview\.is-detail\s+\.module-codegen-ui-live-preview-frame\s*\{/);
    assert.match(style, /\.module-codegen-ui-annotation-toolbar\s*\{/);
    assert.match(style, /\.module-codegen-ui-thumb:disabled,[^}]*opacity:\s*0\.64/s);
    const shellRule = style.match(/\.module-codegen-ui-shell\s*\{([^}]*)\}/)?.[1] ?? "";
    assert.match(shellRule, /width:\s*min\(/);
    assert.match(shellRule, /calc\(100vw - var\(--sidebar-width\) - var\(--space-6\)\)/);
    assert.match(shellRule, /height:\s*max\(46rem,\s*calc\(100dvh - 3rem\)\)/);
    assert.doesNotMatch(shellRule, /height:\s*clamp/);
    assert.match(shellRule, /border:\s*0/);
    assert.match(shellRule, /border-radius:\s*0/);
    assert.match(shellRule, /background:\s*transparent/);
    assert.match(shellRule, /box-shadow:\s*none/);
    assert.doesNotMatch(shellRule, /aspect-ratio/);
    assert.match(
        style,
        /\.module-codegen-ui-heading h1\s*\{[^}]*font-size:\s*1\.85rem/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-textarea,[\s\S]*?font-size:\s*0\.92rem/
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-studio\s*\{[^}]*grid-template-areas:\s*"overview side"\s*"composer side"/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-studio > \.module-codegen-ui-motion-side\s*\{[^}]*grid-area:\s*side/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-studio > \.module-codegen-ui-motion-composer-region\s*\{[^}]*grid-area:\s*composer/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-progress-actions\s*\{[^}]*display:\s*flex[^}]*flex-wrap:\s*wrap/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-layout\.is-module-workspace\s*\{[^}]*grid-template-areas:\s*"left preview history"\s*"prompt prompt history"/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-layout\.is-module-workspace > \.module-codegen-ui-preview-panel\s*\{[^}]*border-inline:\s*1px solid var\(--color-line\)/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-composer:focus-within\s*\{[^}]*min-height:\s*10rem/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-composer:focus-within \.module-codegen-ui-motion-composer-field\.is-api\s*\{[^}]*min-height:\s*7\.6rem/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-composer-confirm\s*\{[^}]*position:\s*absolute[^}]*right:\s*var\(--space-2\)/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-target-options\s*\{[^}]*position:\s*absolute/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-hook-card:hover,\s*\.module-codegen-ui-hook-card\.is-selected\s*\{[^}]*border-color:\s*var\(--color-accent\)/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-hook-card-list\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-motion-side-tabs\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-rollback-version-card\s*\{[^}]*grid-template-columns:\s*auto minmax\(0,\s*1fr\) auto/s
    );
    assert.match(style, /\.module-codegen-ui-motion-swap\.is-disabled\s*\{/);
    assert.match(
        style,
        /\.module-codegen-ui-motion-preview-pane\.is-main \.module-codegen-ui-motion-surface\s*\{[^}]*animation:\s*module-codegen-surface-in\s+var\(--duration-normal\)\s+var\(--ease-out\)/s
    );
    assert.match(
        style,
        /\[data-motion="none"\] \.module-codegen-ui-motion-progress-details/
    );
    assert.match(
        style,
        /\[data-motion="none"\] \.module-codegen-ui-motion-surface\s*\{[^}]*animation:\s*none/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-preview-panel-body\s*\{[^}]*container-type:\s*size/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-target-panel-body\s*\{[^}]*position:\s*absolute/s
    );
    assert.match(
        style,
        /\.module-codegen-ui-api-dialog\s*\{[^}]*background:\s*var\(--color-surface-raised\)/s
    );
    assert.match(style, /@keyframes module-codegen-surface-in/);
    assert.doesNotMatch(style, /\.module-codegen-ui-model-field/);
    assert.doesNotMatch(style, /\.module-codegen-ui-records/);
    assert.doesNotMatch(source, /候选记录与回滚/);
    assert.doesNotMatch(style, /#[0-9a-fA-F]{3,8}\b/);
});

test("session projection keeps steps and version state without file bodies", () => {
    const session = codegenModule.normalizeCodegenSession({
        sessionId: "session-1",
        mode: "module",
        moduleId: "module_demo",
        summary: "候选",
        status: "GENERATED",
        buildStatus: "READY",
        previewStatus: "READY",
        previewUrl: "http://127.0.0.1:18081/",
        steps: [{
            stepId: "s1",
            index: 1,
            title: "契约",
            path: "orderly-modules/module_demo.json",
            status: "GENERATED",
            existedBefore: false,
            bytes: 42,
            afterContent: "must not survive normalization"
        }],
        versions: [{
            versionId: "version-1",
            status: "PENDING_RESTART"
        }]
    });

    assert.equal(session.moduleId, "module_demo");
    assert.equal(session.steps[0].stepId, "s1");
    assert.equal(Object.hasOwn(session.steps[0], "afterContent"), false);
    assert.equal(session.versions[0].status, "PENDING_RESTART");
});

test("session catalog selects the latest matching mode and target", () => {
    const sessions = codegenModule.normalizeCodegenSessions([
        {
            sessionId: "older-target",
            mode: "module",
            moduleId: "module_demo",
            updatedAt: "2026-11-26T10:00:00Z"
        },
        {
            sessionId: "latest-target",
            mode: "module",
            moduleId: "module_demo",
            updatedAt: "2026-11-26T12:00:00Z"
        },
        {
            sessionId: "latest-motion",
            mode: "motion",
            updatedAt: "2026-11-26T13:00:00Z"
        }
    ]);

    assert.equal(sessions[0].sessionId, "latest-motion");
    assert.equal(
        codegenModule.selectCodegenSession(sessions, {
            mode: "module",
            moduleId: "module_demo"
        }).sessionId,
        "latest-target"
    );
    assert.equal(
        codegenModule.selectCodegenSession(sessions, {
            mode: "module",
            moduleId: "module_missing",
            allowFallback: false
        }),
        null
    );
    assert.equal(
        codegenModule.selectCodegenSession(sessions, {
            mode: "module",
            moduleId: "module_missing",
            allowFallback: true
        }).sessionId,
        "latest-target"
    );
});

test("motion sessions distinguish framework and module targets", () => {
    const sessions = codegenModule.normalizeCodegenSessions([
        {
            sessionId: "motion-module",
            mode: "motion",
            moduleId: "module_todo",
            updatedAt: "2026-11-27T10:00:00Z"
        },
        {
            sessionId: "motion-framework-old",
            mode: "motion",
            moduleId: "",
            updatedAt: "2026-11-27T09:00:00Z"
        },
        {
            sessionId: "motion-framework-new",
            mode: "motion",
            moduleId: "",
            updatedAt: "2026-11-27T11:00:00Z"
        }
    ]);

    assert.equal(
        codegenModule.selectCodegenSession(sessions, {
            mode: "motion",
            moduleId: "",
            allowFallback: false
        }).sessionId,
        "motion-framework-new"
    );
    assert.equal(
        codegenModule.selectCodegenSession(sessions, {
            mode: "motion",
            moduleId: "module_todo",
            allowFallback: false
        }).sessionId,
        "motion-module"
    );
});

test("motion hooks deduplicate, preserve selection and fall back honestly", () => {
    const scopes = codegenModule.normalizeMotionScopes([
        { scope: "sidebar", state: "expanded", phase: "settled" },
        { scope: "sidebar", state: "expanded", phase: "settled" },
        { scope: "feedback", state: "success", phase: "active" },
        { scope: "", state: "idle", phase: "" }
    ]);

    assert.deepEqual(scopes, [
        { scope: "sidebar", state: "expanded", phase: "settled" },
        { scope: "feedback", state: "success", phase: "active" }
    ]);
    assert.deepEqual(
        codegenModule.resolveMotionHook(
            scopes,
            codegenModule.motionHookKey(scopes[1])
        ),
        scopes[1]
    );
    assert.deepEqual(codegenModule.resolveMotionHook(scopes, "missing"), scopes[0]);
    assert.equal(codegenModule.resolveMotionHook([]), null);
    assert.deepEqual(codegenModule.availableMotionScopes({
        motionTarget: "module_todo",
        candidateHooks: [],
        currentHooks: scopes
    }), []);
    assert.deepEqual(codegenModule.availableMotionScopes({
        motionTarget: "framework",
        candidateHooks: scopes.slice(0, 1),
        currentHooks: scopes
    }), scopes);
});

test("motion composer prioritizes API entry before prompt and progress", () => {
    assert.equal(codegenModule.motionComposerMode({
        configured: false,
        generating: false
    }), "api");
    assert.equal(codegenModule.motionComposerMode({
        configured: true,
        generating: false
    }), "prompt");
    assert.equal(codegenModule.motionComposerMode({
        configured: true,
        generating: true
    }), "progress");
});

test("motion preview prefers a ready candidate and otherwise uses the running loopback UI", () => {
    const session = {
        sessionId: "motion-session",
        previewStatus: "READY",
        previewUrl: "http://127.0.0.1:18084/"
    };
    const candidate = codegenModule.motionPreviewSource({
        session,
        motionTarget: "module_todo",
        moduleIds: ["module_todo"],
        appOrigin: "http://127.0.0.1:8080"
    });
    const candidateUrl = new URL(candidate.url);
    assert.equal(candidate.kind, "candidate");
    assert.equal(candidateUrl.origin, "http://127.0.0.1:18084");
    assert.equal(candidateUrl.searchParams.get("motionEditor"), "1");
    assert.equal(candidateUrl.searchParams.get("motionPreviewModule"), "module_todo");
    assert.equal(candidateUrl.searchParams.get("motionPreviewSession"), "motion-session");

    const current = codegenModule.motionPreviewSource({
        session: {
            sessionId: "rolled-back-session",
            previewStatus: "STOPPED",
            previewUrl: ""
        },
        motionTarget: "module_todo",
        moduleIds: ["module_todo"],
        appOrigin: "http://127.0.0.1:8080"
    });
    const currentUrl = new URL(current.url);
    assert.equal(current.kind, "current");
    assert.equal(currentUrl.origin, "http://127.0.0.1:8080");
    assert.equal(currentUrl.searchParams.get("motionPreviewModule"), "module_todo");
    assert.equal(currentUrl.searchParams.get("motionPreviewSession"), "rolled-back-session");
});

test("motion preview rejects missing modules and centers a bounded detail zoom", () => {
    assert.equal(codegenModule.motionPreviewSource({
        session: null,
        motionTarget: "module_missing",
        moduleIds: ["module_todo"],
        appOrigin: "http://127.0.0.1:8080"
    }).kind, "unavailable");

    assert.deepEqual(
        codegenModule.motionPreviewTransform({
            x: 0.4,
            y: 0.45,
            width: 0.2,
            height: 0.1
        }, 800, 400),
        {
            scale: 3.2,
            translateX: -880,
            translateY: -440
        }
    );
    assert.deepEqual(codegenModule.motionPreviewTransform(null, 800, 400), {
        scale: 1,
        translateX: 0,
        translateY: 0
    });
    const compact = codegenModule.motionPreviewTransform({
        x: 0.0085,
        y: 0.0152,
        width: 0.9721,
        height: 0.5152
    }, 1404, 787, 448, 224);
    assert.ok(compact.scale > 0.2 && compact.scale < 0.4);
    assert.ok(compact.translateX > 0 && compact.translateX < 100);
    assert.ok(compact.translateY > 0 && compact.translateY < 100);
});

test("motion version history filters the active target and keeps rollback source", () => {
    const targetKey = codegenModule.codegenVersionTargetKey({
        motionTarget: "framework",
        themeId: "paper"
    });
    const versions = codegenModule.normalizeCodegenVersions([
        {
            versionId: "module-version",
            targetKey: "module:module_todo",
            status: "ACTIVE",
            sessionId: "module-session",
            createdAt: "2026-11-27T10:00:00Z"
        },
        {
            versionId: "older-theme-version",
            targetKey: "theme:paper",
            status: "DEPRECATED",
            sessionId: "older-session",
            createdAt: "2026-11-27T09:00:00Z"
        },
        {
            versionId: "current-theme-version",
            targetKey: "theme:paper",
            status: "PENDING_RESTART",
            sessionId: "current-session",
            createdAt: "2026-11-27T11:00:00Z"
        }
    ], { targetKey });

    assert.equal(targetKey, "theme:paper");
    assert.deepEqual(versions.map((version) => version.versionId), [
        "current-theme-version",
        "older-theme-version"
    ]);
    assert.equal(versions[0].sessionId, "current-session");
});

test("preview action starts first and opens through a direct user link", () => {
    assert.deepEqual(codegenModule.codegenPreviewAction(null), {
        kind: "unavailable",
        label: "生成后可预览",
        url: ""
    });
    assert.deepEqual(codegenModule.codegenPreviewAction({
        sessionId: "session-1",
        buildStatus: "BUILDING",
        previewStatus: "STOPPED"
    }), {
        kind: "unavailable",
        label: "等待构建完成",
        url: ""
    });
    assert.deepEqual(codegenModule.codegenPreviewAction({
        sessionId: "session-1",
        buildStatus: "READY",
        previewStatus: "STOPPED"
    }), {
        kind: "start",
        label: "启动镜像预览",
        url: ""
    });
    assert.deepEqual(codegenModule.codegenPreviewAction({
        sessionId: "session-1",
        buildStatus: "READY",
        previewStatus: "READY",
        previewUrl: "http://127.0.0.1:18081/"
    }), {
        kind: "open",
        label: "打开镜像预览",
        url: "http://127.0.0.1:18081/"
    });
});

test("framework mount context exposes read-only module, theme and motion catalogs", async () => {
    const app = await readFile(appUrl, "utf8");

    assert.match(app, /moduleCatalog:\s*state\.modules\.map/);
    assert.match(app, /themeCatalog:\s*state\.themes\.map/);
    assert.match(app, /listMotionScopes:\s*\(\)\s*=>/);
    assert.match(app, /querySelectorAll\("\[data-motion-scope\]"\)/);
    assert.match(app, /openSettings:\s*\(section = ""\)\s*=>\s*openSettings\(section\)/);
    assert.match(app, /function startMotionPreviewBridge/);
    assert.match(app, /function motionPreviewRequest/);
    assert.match(app, /motionPreviewMode\(\) \? motionTracedElement : element/);
    assert.match(app, /data-orderly-motion-node/);
    assert.match(app, /function openSettings\(section = ""\)/);
    assert.match(app, /state\.settingsSection = section/);
});
