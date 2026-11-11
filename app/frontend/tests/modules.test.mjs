import assert from "node:assert/strict";
import test from "node:test";

import {
    moveModuleOrder,
    normalizeModuleUi,
    readCount,
    reconcileModuleOrder,
    reorderBefore,
    sortModules
} from "../src/modules.js";

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
    assert.throws(() => normalizeModuleUi("module_demo", {
        view: { id: "demo" },
        script: "../escape.js"
    }), /资源名非法/);
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
});

test("card count supports arrays, scalar counts and nested keys", () => {
    assert.equal(readCount({ entries: [1, 2] }, "entries"), 2);
    assert.equal(readCount({ count: 4 }, "count"), 4);
    assert.equal(readCount({ summary: { total: 3 } }, "summary.total"), 3);
    assert.equal(readCount({}, "missing"), "—");
});
