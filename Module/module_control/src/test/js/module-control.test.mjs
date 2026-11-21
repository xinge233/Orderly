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

test("module_control bridge exposes the required systemAdmin surface", async () => {
    const app = await readFile(appUrl, "utf8");
    const control = await readFile(controlUrl, "utf8");
    const methods = [
        "getState",
        "getInterfaceState",
        "listManagementModules",
        "setModuleEnabled",
        "refreshModules",
        "openModuleFolder",
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

test("module_control renders interface AI switches with locking states", async () => {
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
