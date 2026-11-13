import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    createModuleMountContext,
    hasSystemAdminPrivilege,
    isBusinessModule,
    isModuleVisible,
    isSystemModule,
    moveModuleOrder,
    moveVisibleModuleOrder,
    normalizeModuleCatalogs,
    normalizeModulePreferences,
    normalizeModuleSettingSchema,
    normalizeModuleUi,
    readCount,
    reconcileModulePreferences,
    reconcileModuleOrder,
    reorderBefore,
    reorderRelative,
    resolveModuleUiCatalog,
    sortModules
} from "../src/modules.js";

async function readJson(relativePath) {
    return JSON.parse(await readFile(new URL(relativePath, import.meta.url), "utf8"));
}

test("module UI manifest is normalized and unsafe asset paths are rejected", () => {
    const ui = normalizeModuleUi("module_demo", {
        version: 1,
        view: { id: "demo", icon: "folder", caption: "演示" },
        card: { title: "演示卡", queryId: "demo.list", countKey: "count", unit: "项" },
        script: "module.js",
        style: "module.css"
    });
    assert.equal(ui.view.id, "demo");
    assert.equal(ui.card.queryId, "demo.list");
    assert.deepEqual(ui.settings.fields, []);
    assert.throws(() => normalizeModuleUi("module_demo", {
        view: { id: "demo" },
        script: "../escape.js"
    }), /资源名非法/);
});

test("module settings schema is normalized and rejects unsafe structures", () => {
    const settings = normalizeModuleSettingSchema({
        fields: [
            {
                key: "compactMode",
                label: "紧凑模式",
                type: "boolean",
                default: true
            },
            {
                key: "pageSize",
                label: "每页数量",
                type: "integer",
                default: 20
            },
            {
                key: "density",
                label: "密度",
                type: "select",
                default: "compact",
                options: [{ value: "compact", label: "紧凑" }]
            }
        ]
    });

    assert.equal(settings.fields[0].type, "boolean");
    assert.equal(settings.fields[0].default, true);
    assert.equal(settings.fields[1].type, "integer");
    assert.deepEqual(settings.fields[2].options, [
        { value: "compact", label: "紧凑" }
    ]);
    assert.throws(
        () => normalizeModuleSettingSchema({ fields: [
            { key: "same", type: "boolean", label: "一", default: true },
            { key: "same", type: "boolean", label: "二", default: false }
        ] }),
        /设置键重复/
    );
    assert.throws(
        () => normalizeModuleSettingSchema({ fields: [
            { key: "unsafe", type: "object" }
        ] }),
        /设置类型不受支持/
    );
    assert.equal(normalizeModuleSettingSchema([{
        key: "legacyShape",
        title: "兼容字段标题",
        type: "boolean",
        default: false
    }]).fields[0].label, "兼容字段标题");
});

test("contract settings samples validate defaults, limits and option membership", async () => {
    const valid = normalizeModuleUi(
        "module_demo",
        await readJson("../../../contracts/samples/valid/module-ui-settings.json")
    );
    assert.deepEqual(
        valid.settings.fields.map((field) => field.type),
        ["boolean", "integer", "select", "text"]
    );
    assert.equal(valid.settings.fields[1].min, 1);
    assert.equal(valid.settings.fields[2].default, "name");
    assert.equal(valid.settings.fields[3].maxLength, 80);

    await assert.rejects(
        async () => normalizeModuleUi(
            "module_demo",
            await readJson("../../../contracts/samples/invalid/module-ui-settings-duplicate-key.json")
        ),
        /设置键重复/
    );
    await assert.rejects(
        async () => normalizeModuleUi(
            "module_demo",
            await readJson("../../../contracts/samples/invalid/module-ui-settings-invalid-default.json")
        ),
        /默认值必须存在于 options/
    );
});

test("business and system catalogs split without trusting business system metadata", () => {
    const catalogs = normalizeModuleCatalogs(
        {
            modules: [
                { moduleId: "module_todo", order: 20, enabled: false },
                { moduleId: "module_control", system: { privileged: true } }
            ]
        },
        {
            systemModules: [{
                moduleId: "module_control",
                order: 10000,
                system: { role: "MODULE_CONTROL", protected: true }
            }]
        }
    );

    assert.deepEqual(
        catalogs.modules.map((module) => [module.moduleId, module.source]),
        [
            ["module_todo", "business"],
            ["module_control", "system"]
        ]
    );
    assert.equal(isBusinessModule(catalogs.businessModules[0]), true);
    assert.equal(isSystemModule(catalogs.systemModules[0]), true);
    assert.equal(catalogs.systemModules[0].system.role, "MODULE_CONTROL");
});

test("disabled business modules are filtered while system modules remain available", () => {
    const catalogs = normalizeModuleCatalogs(
        [
            { moduleId: "module_disabled", enabled: false },
            { moduleId: "module_enabled", enabled: true }
        ],
        [{
            moduleId: "module_control",
            enabled: false,
            order: 10000,
            system: { role: "MODULE_CONTROL", protected: true }
        }]
    );
    const visibleIds = catalogs.modules.filter(isModuleVisible)
        .map((module) => module.moduleId);

    assert.deepEqual(visibleIds, ["module_enabled", "module_control"]);
    assert.deepEqual(
        reconcileModulePreferences(catalogs.modules, {}).sidebarOrder,
        ["module_disabled", "module_enabled", "module_control"]
    );
});

test("systemAdmin is injected only into the system module_control context", () => {
    const business = {
        moduleId: "module_control",
        source: "business",
        enabled: true,
        system: { privileged: true }
    };
    const system = {
        moduleId: "module_control",
        source: "system",
        enabled: true,
        system: { role: "MODULE_CONTROL", protected: true }
    };
    const admin = { listManagementModules() {} };

    assert.equal(hasSystemAdminPrivilege(business), false);
    assert.equal(hasSystemAdminPrivilege(system), true);
    assert.equal("systemAdmin" in createModuleMountContext({
        module: business,
        context: {},
        settings: { compact: true },
        systemAdmin: admin
    }), false);
    const context = createModuleMountContext({
        module: system,
        context: {},
        settings: { compact: true },
        systemAdmin: admin
    });
    assert.equal(context.systemAdmin, admin);
    assert.deepEqual(context.settings, { compact: true });
});

test("module_control bridge exposes the required systemAdmin surface", async () => {
    const app = await readFile(new URL("../src/app.js", import.meta.url), "utf8");
    const control = await readFile(
        new URL(
            "../../../Module/module_control/src/main/resources/orderly-ui/module.js",
            import.meta.url
        ),
        "utf8"
    );
    const methods = [
        "getState",
        "listManagementModules",
        "setModuleEnabled",
        "refreshModules",
        "openModuleFolder",
        "saveManagementOrder",
        "saveModuleSettings",
        "resetModuleSettings",
        "getManagementOrder"
    ];

    methods.forEach((method) => assert.match(app, new RegExp(`\\b${method}\\s*\\(`)));
    [
        "getState",
        "setEnabled",
        "refreshModules",
        "openModuleFolder",
        "saveManagementOrder",
        "saveModuleSettings",
        "resetModuleSettings"
    ].forEach((method) => assert.match(control, new RegExp(`["']${method}["']`)));
    assert.match(app, /system:\s*module\.system\s*\?\?/);
    assert.doesNotMatch(control, /!module\.protected/);
    assert.match(control, /protectedModule/);
});

test("host preferences preserve valid order and reject stale module settings", () => {
    const modules = normalizeModuleCatalogs(
        [
            { moduleId: "module_old", order: 10 },
            { moduleId: "module_new", order: 90 }
        ],
        [{
            moduleId: "module_control",
            order: 10000,
            system: { role: "MODULE_CONTROL", protected: true }
        }]
    ).modules;
    const preferences = reconcileModulePreferences(modules, {
        sidebarOrder: ["module_old", "module_removed", "module_new"],
        managementOrder: ["module_new", "module_removed"],
        moduleSettings: {
            module_old: { compact: true },
            module_removed: { compact: false },
            module_control: { unsafe: true }
        }
    });

    assert.deepEqual(preferences.sidebarOrder, [
        "module_old",
        "module_new",
        "module_control"
    ]);
    assert.deepEqual(preferences.managementOrder, [
        "module_new",
        "module_old",
        "module_control"
    ]);
    assert.deepEqual(preferences.moduleSettings, {
        module_old: { compact: true }
    });
    assert.deepEqual(normalizeModulePreferences({}).moduleSettings, {});
});

test("module UI resolution preserves framework and duplicate view conflicts", () => {
    const modules = [
        { moduleId: "module_business", source: "business" },
        { moduleId: "module_control", source: "system" }
    ];
    const systemUi = {
        view: { id: "shared", caption: "系统", icon: "modules" }
    };
    const resolved = resolveModuleUiCatalog(modules, [
        {
            moduleId: "module_business",
            ui: { view: { id: "shared", caption: "业务", icon: "modules" } }
        },
        { moduleId: "module_control", ui: systemUi }
    ]);

    assert.equal(resolved.moduleUi.module_control, systemUi);
    assert.equal("module_business" in resolved.moduleUi, false);
    assert.match(resolved.moduleErrors.module_business, /module_control/);

    const frameworkConflict = resolveModuleUiCatalog([modules[0]], [{
        moduleId: "module_business",
        ui: { view: { id: "overview", caption: "冲突", icon: "modules" } }
    }]);
    assert.match(frameworkConflict.moduleErrors.module_business, /框架视图冲突/);
});

test("module ordering reconciles removed and newly installed modules", () => {
    const modules = [
        { moduleId: "module_b", order: 20 },
        { moduleId: "module_a", order: 10 },
        { moduleId: "module_c", order: 30 }
    ];
    const order = reconcileModuleOrder(modules, ["module_c", "module_removed", "module_a"]);
    assert.deepEqual(order, ["module_c", "module_a", "module_b"]);
    assert.deepEqual(sortModules(modules, order).map((item) => item.moduleId), order);
});

test("drag and keyboard ordering operations are deterministic", () => {
    const ids = ["a", "b", "c"];
    assert.deepEqual(reorderBefore(ids, "c", "a"), ["c", "a", "b"]);
    assert.deepEqual(moveModuleOrder(ids, "a", 1), ["b", "a", "c"]);
    assert.deepEqual(moveModuleOrder(ids, "c", -1), ["a", "c", "b"]);
    assert.deepEqual(moveModuleOrder(ids, "missing", 1), ids);
    assert.deepEqual(reorderRelative(ids, "a", "c", true), ["b", "c", "a"]);
    assert.deepEqual(reorderRelative(ids, "c", "a", false), ["c", "a", "b"]);
    assert.deepEqual(
        moveVisibleModuleOrder(["a", "hidden", "b", "c"], ["a", "b", "c"], "a", 1),
        ["hidden", "b", "a", "c"]
    );
    assert.deepEqual(
        moveVisibleModuleOrder(["a", "hidden", "b", "c"], ["a", "b", "c"], "b", -1),
        ["b", "a", "hidden", "c"]
    );
    assert.deepEqual(
        moveVisibleModuleOrder(["a", "hidden", "b"], ["a", "b"], "b", 1),
        ["a", "hidden", "b"]
    );
});

test("card count supports arrays, scalar counts and nested keys", () => {
    assert.equal(readCount({ entries: [1, 2] }, "entries"), 2);
    assert.equal(readCount({ count: 4 }, "count"), 4);
    assert.equal(readCount({ summary: { total: 3 } }, "summary.total"), 3);
    assert.equal(readCount({}, "missing"), "—");
});
