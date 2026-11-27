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
    assert.match(source, /框架动效复用公共挂点/);
    assert.match(source, /module-codegen-ui-layout is-module-workspace/);
    assert.match(source, /module-codegen-ui-module-prompt-panel/);
    assert.match(source, /module-codegen-ui-editor is-motion/);
    assert.match(source, /module-codegen-ui-target-trigger/);
    assert.match(source, /function openApiDialog/);
    assert.match(source, /dialog\.showModal\(\)/);
    assert.match(source, /context\.openSettings\("ai"\)/);
    assert.match(source, /generateButton\.addEventListener\("click", \(\) => generate\("module"\)\)/);
    assert.match(source, /generateButton\.addEventListener\("click", \(\) => generate\("motion"\)\)/);
    assert.doesNotMatch(source, /state\.apiKey/);
    assert.doesNotMatch(source, /buildModelField/);
    assert.doesNotMatch(source, /const generate = button/);
    assert.doesNotMatch(source, /risk\.textContent/);
    assert.match(style, /aspect-ratio:\s*16\s*\/\s*9/);
    assert.match(style, /\.module-codegen-ui-picker\s*\{/);
    assert.match(style, /\.module-codegen-ui-step-row\s*\{/);
    assert.match(style, /\.module-codegen-ui-preview-link\s*\{[^}]*text-decoration:\s*none/s);
    assert.match(style, /\.module-codegen-ui-thumb:disabled,[^}]*opacity:\s*0\.64/s);
    const shellRule = style.match(/\.module-codegen-ui-shell\s*\{([^}]*)\}/)?.[1] ?? "";
    assert.match(shellRule, /width:\s*calc\(/);
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
        /\.module-codegen-ui-editor\.is-motion\s*\{[^}]*grid-template-rows:\s*minmax\(12rem,\s*0\.85fr\)\s*minmax\(10rem,\s*1\.15fr\)/s
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
        /\.module-codegen-ui-editor\.is-motion > \.module-codegen-ui-target-field,[\s\S]*?grid-area:\s*auto/s
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
    assert.match(style, /\.module-codegen-ui-editor > \.module-codegen-ui-prompt/);
    assert.doesNotMatch(style, /\.module-codegen-ui-model-field/);
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
    assert.match(app, /function openSettings\(section = ""\)/);
    assert.match(app, /state\.settingsSection = section/);
});
