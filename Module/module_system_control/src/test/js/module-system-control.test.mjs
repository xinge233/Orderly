import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const appUrl = new URL(
    "../../../../../app/frontend/src/app.js",
    import.meta.url
);
const controlUrl = new URL(
    "../../main/resources/orderly-ui/module.js",
    import.meta.url
);
const styleUrl = new URL(
    "../../main/resources/orderly-ui/module.css",
    import.meta.url
);
const controlModule = await import(controlUrl.href);

test("module_system_control bridge exposes the required systemAdmin surface", async () => {
    const app = await readFile(appUrl, "utf8");
    const control = await readFile(controlUrl, "utf8");
    const methods = [
        "getState",
        "getInterfaceState",
        "listManagementModules",
        "setModuleEnabled",
        "refreshModules",
        "openModuleFolder",
        "getModuleCatalog",
        "installModule",
        "getModuleInstallStatus",
        "retryModuleBuild",
        "removeModule",
        "undoModuleRemoval",
        "saveManagementOrder",
        "saveModuleSettings",
        "resetModuleSettings",
        "setInterfaceAiCallable",
        "getManagementOrder"
    ];

    methods.forEach((method) => assert.match(app, new RegExp(`\\b${method}\\s*\\(`)));
    [
        "getState",
        "getInterfaceState",
        "setEnabled",
        "refreshModules",
        "openModuleFolder",
        "getModuleCatalog",
        "installModule",
        "getModuleInstallStatus",
        "retryModuleBuild",
        "removeModule",
        "undoModuleRemoval",
        "saveManagementOrder",
        "saveModuleSettings",
        "resetModuleSettings",
        "setInterfaceAiCallable"
    ].forEach((method) => assert.match(control, new RegExp(`["']${method}["']`)));
    assert.match(app, /system:\s*module\.system\s*\?\?/);
    assert.match(app, /subscribeEvents:\s*subscribeFrameworkEvents/);
    assert.doesNotMatch(control, /!module\.protected/);
    assert.match(control, /protectedModule/);
});

test("module download opens from the toolbar and renders catalog and install states", async () => {
    const control = await readFile(controlUrl, "utf8");
    const style = await readFile(styleUrl, "utf8");

    assert.match(control, /const catalog = button\("模块下载", "download"\);/);
    assert.match(control, /toolbar\.append\(refresh, folder, catalog, deleteMode\);/);
    assert.match(control, /catalog\.addEventListener\("click", openCatalog\);/);
    assert.match(control, /state\.subpage = "catalog"/);
    assert.match(control, /buildCatalogPanel\(\)/);
    assert.match(control, /getModuleCatalog/);
    assert.match(control, /installModule/);
    assert.match(control, /getModuleInstallStatus/);
    assert.match(control, /retryModuleBuild/);
    assert.match(control, /catalogStatusLabel/);
    assert.match(control, /scheduleCatalogPoll/);
    assert.match(control, /payload\.terminal === true/);
    assert.match(control, /job\.terminal !== true/);
    assert.match(control, /模块已构建并启用/);
    assert.match(style, /\.module-control-ui-catalog\s*\{/);
    assert.match(style, /\.module-control-ui-catalog-row\s*\{/);
    assert.match(style, /\.module-control-ui-catalog-action\s*\{/);
});

test("installed catalog entries are status-only regardless of retry metadata", async () => {
    const control = await readFile(controlUrl, "utf8");
    const installed = controlModule.catalogActionState(
        { canInstall: false, canRetryBuild: true },
        "INSTALLED",
        { status: "BUILD_FAILED", terminal: true },
        { install: true, retryBuild: true }
    );
    assert.deepEqual(installed, {
        kind: "none",
        label: "已启用",
        icon: "check",
        disabled: true
    });

    const sourcePresent = controlModule.catalogActionState(
        { canInstall: false, canRetryBuild: true },
        "SOURCE_PRESENT",
        null,
        { install: true, retryBuild: true }
    );
    assert.equal(sourcePresent.kind, "retry-build");
    assert.equal(sourcePresent.label, "重试构建");
    assert.equal(sourcePresent.disabled, false);

    const available = controlModule.catalogActionState(
        { canInstall: true, canRetryBuild: false },
        "AVAILABLE",
        null,
        { install: true, retryBuild: true }
    );
    assert.equal(available.kind, "install");
    assert.equal(available.label, "下载");

    const buildFailed = controlModule.catalogActionState(
        { canInstall: false, canRetryBuild: false },
        "BUILD_FAILED",
        null,
        {}
    );
    assert.equal(buildFailed.kind, "none");
    assert.equal(buildFailed.label, "构建失败");

    assert.match(control, /catalogActionState\(item, status, job,/);
    assert.match(control, /if \(actionState\.kind !== "none"\)/);
    assert.match(control, /item\.status === "INSTALLED" \|\| !item\.canInstall/);
    assert.match(control, /item\.status === "INSTALLED" \|\| !item\.canRetryBuild/);
    assert.match(control, /status: "INSTALL_FAILED"/);
    assert.match(control, /status: "BUILD_FAILED"/);
});

test("module_system_control renders interface AI switches with locking states", async () => {
    const control = await readFile(controlUrl, "utf8");
    const style = await readFile(styleUrl, "utf8");

    assert.match(control, /buildInterfaceGroup\(module, "动作接口", module\.actions, "action"\)/);
    assert.match(control, /buildInterfaceGroup\(module, "查询接口", module\.queries, "query"\)/);
    assert.match(control, /module-control-ui-interface__title/);
    assert.match(control, /module-control-ui-interface__description/);
    assert.match(control, /使用中/);
    assert.doesNotMatch(control, /模块禁止 AI 调用/);
    assert.doesNotMatch(control, /AI 查询通道尚未接入/);
    assert.match(control, /仅影响 AI 查询；模块自身查询不受限制/);
    assert.match(control, /setInterfaceAiCallable/);
    assert.match(control, /interfacePanel\.classList\.toggle\("is-open", expanded\);/);
    assert.match(control, /interfacePanel\.toggleAttribute\("inert", !expanded\);/);
    assert.match(control, /interfacePanel\.setAttribute\("aria-hidden", String\(!expanded\)\);/);
    assert.match(control, /interfaceStateFor\(\s*state\.interfaceState,/);
    assert.match(control, /actionIsInUse\(state\.interfaceState,/);
    assert.match(control, /const previous = interfaceStateFor\(/);
    assert.match(control, /canExpandInterfaces/);
    assert.match(control, /const canExpandInterfaces = true;/);
    assert.match(control, /宿主治理接口/);
    assert.match(control, /module-control-ui-interface__state--fixed/);
    assert.match(control, /const nextExpanded = !state\.expandedIds\.has\(module\.moduleId\);/);
    assert.match(control, /row\.classList\.toggle\("is-expanded", nextExpanded\);/);
    assert.match(control, /interfacePanel\.classList\.toggle\("is-open", nextExpanded\);/);
    assert.match(control, /interfacePanel\.toggleAttribute\("inert", !nextExpanded\);/);
    assert.match(control, /interfacePanel\.setAttribute\("aria-hidden", String\(!nextExpanded\)\);/);
    assert.match(control, /classList\.add\("is-static"\)/);
    assert.match(control, /context\.subscribeEvents/);
    assert.match(control, /header\.classList\.add\("is-expandable"\);/);
    const expandListener = control.match(
        /header\.addEventListener\("click", \(event\) => \{([\s\S]*?)\n\s*\}\);/
    )?.[1] ?? "";
    assert.match(expandListener, /event\.target\.closest\?\.\(/);
    assert.match(expandListener, /\.module-control-ui-drag,/);
    assert.match(expandListener, /\.module-control-ui-row-actions button,/);
    assert.match(expandListener, /\.module-control-ui-switch/);
    assert.match(expandListener, /toggleInterfaces\(\);/);
    assert.doesNotMatch(expandListener, /\brender\(\)/);
    assert.doesNotMatch(control, /copy\.addEventListener\("click"/);
    const interfaceRule = style.match(/\.module-control-ui-interfaces\s*\{([\s\S]*?)\}/)?.[1] ?? "";
    const openInterfaceRule = style.match(/\.module-control-ui-interfaces\.is-open\s*\{([\s\S]*?)\}/)?.[1] ?? "";
    assert.match(interfaceRule, /grid-template-rows:\s*0fr;/);
    assert.match(interfaceRule, /transition:[\s\S]*?grid-template-rows var\(--duration-normal\) var\(--ease-out\)/);
    assert.doesNotMatch(interfaceRule, /visibility:/);
    assert.match(openInterfaceRule, /grid-template-rows:\s*1fr;/);
    assert.doesNotMatch(openInterfaceRule, /transition:/);
    assert.match(style, /\.module-control-ui-interfaces__clip\s*\{\s*min-height:\s*0;\s*overflow:\s*hidden;/);
    assert.match(style, /\.module-control-ui-interfaces__content\s*\{[\s\S]*?padding:\s*var\(--space-2\) var\(--space-6\) var\(--space-5\)/);
    assert.match(style, /\[data-motion="none"\] \.module-control-ui-interfaces,/);
    assert.match(style, /\.module-control-ui-copy\.is-static\s*\{\s*cursor:\s*default;/);
    assert.match(style, /\.module-control-ui-row-header\.is-expandable\s*\{\s*cursor:\s*pointer;/);
    const rowRule = style.match(/\.module-control-ui-row\s*\{([\s\S]*?)\}/)?.[1] ?? "";
    assert.match(rowRule, /flex:\s*0 0 auto;/);
    assert.match(rowRule, /min-height:\s*5rem;/);
    assert.doesNotMatch(rowRule, /flex:\s*1 1/);
    assert.match(style, /\.module-control-ui-list-scroll\s*\{\s*overflow-anchor:\s*none;/);
    assert.doesNotMatch(style, /grid-auto-rows:\s*minmax\(4\.75rem,\s*1fr\)/);
});

test("module deletion mode replaces switches, confirms, marks restart and supports undo", async () => {
    const control = await readFile(controlUrl, "utf8");
    const style = await readFile(styleUrl, "utf8");

    assert.match(control, /state\.deleteMode/);
    assert.match(control, /state\.pendingDeleteId/);
    assert.match(control, /beginDeleteConfirmation/);
    assert.match(control, /toggleDeleteMode/);
    assert.match(control, /module-control-ui-toolbar-delete/);
    assert.match(control, /确认删除？/);
    assert.match(control, /已标记删除，重启后生效/);
    assert.match(control, /restoreModuleRemoval/);
    assert.match(control, /module-control-ui-removal-badge/);
    assert.match(control, /is-pending-removal/);
    assert.match(control, /module\.canRemove/);
    assert.match(style, /\.module-control-ui-toolbar-delete\s*\{/);
    assert.match(style, /\.module-control-ui-row\.is-pending-removal/);
    assert.match(style, /\.module-control-ui-button\.is-danger\s*\{/);
});

test("module control preserves list scroll and deletion focus across full rerenders", async () => {
    const control = await readFile(controlUrl, "utf8");
    const listScroller = { scrollTop: 486 };
    const viewCalls = [];
    const root = {
        querySelector(selector) {
            return selector === ".module-control-ui-list-scroll" ? listScroller : null;
        },
        ownerDocument: {
            defaultView: {
                scrollX: 12,
                scrollY: 880,
                scrollTo(options) {
                    viewCalls.push(options);
                }
            }
        }
    };

    const snapshot = controlModule.captureViewScroll(root, "list");
    assert.deepEqual(snapshot, {
        subpage: "list",
        scrollerTop: 486,
        windowLeft: 12,
        windowTop: 880
    });
    assert.equal(viewCalls.length, 0);

    const replacementScroller = { scrollTop: 0 };
    const replacementCalls = [];
    const replacementRoot = {
        querySelector(selector) {
            return selector === ".module-control-ui-list-scroll" ? replacementScroller : null;
        },
        ownerDocument: {
            defaultView: {
                scrollX: 0,
                scrollY: 0,
                scrollTo(options) {
                    replacementCalls.push(options);
                }
            }
        }
    };

    assert.equal(controlModule.restoreViewScroll(replacementRoot, snapshot, "list"), true);
    assert.equal(replacementScroller.scrollTop, 486);
    assert.deepEqual(replacementCalls, [{ left: 12, top: 880, behavior: "auto" }]);

    assert.equal(controlModule.restoreViewScroll(replacementRoot, snapshot, "settings"), false);
    assert.equal(replacementScroller.scrollTop, 486);
    assert.equal(replacementCalls.length, 1);
    assert.equal(controlModule.captureViewScroll(root, "unknown"), null);

    assert.match(control, /const scrollSnapshot = captureViewScroll\(root, state\.subpage\);/);
    assert.match(
        control,
        /root\.replaceChildren\(panel\);\s*restoreViewScroll\(root, scrollSnapshot, state\.subpage\);/
    );
    assert.match(control, /focusDeletionAction\(module\.moduleId\);/);
    assert.match(control, /focus\?\.\(\{ preventScroll: true \}\)/);
});
