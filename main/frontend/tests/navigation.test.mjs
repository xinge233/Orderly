import assert from "node:assert/strict";
import test from "node:test";

import {
    flattenNavigation,
    getDefaultNavigationItem,
    navigationSections
} from "../src/config/navigation.js";
import { getView, hasView, registeredViewNames } from "../src/core/view-registry.js";

test("navigation ids are unique and every section has items", () => {
    assert.ok(navigationSections.length > 0);
    for (const section of navigationSections) {
        assert.ok(section.items.length > 0);
    }

    const ids = flattenNavigation().map((item) => item.id);
    assert.equal(new Set(ids).size, ids.length);
});

test("default item is the first configured destination", () => {
    const first = flattenNavigation()[0];
    assert.equal(getDefaultNavigationItem(), first);
});

test("every navigation destination has a registered lifecycle view", () => {
    for (const item of flattenNavigation()) {
        assert.equal(
            hasView(item.view),
            true,
            `导航 ${item.id} 引用了未注册视图 ${item.view}`
        );
        const view = getView(item.view);
        assert.equal(typeof view.render, "function");
        assert.equal(typeof view.mount, "function");
        assert.equal(typeof view.unmount, "function");
    }
    assert.ok(registeredViewNames().includes("lab"));
});

test("unknown views fail explicitly instead of silently using a placeholder", () => {
    assert.throws(
        () => getView("missing-view"),
        /视图尚未注册/
    );
});
