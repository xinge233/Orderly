import assert from "node:assert/strict";
import test from "node:test";

import {
    flattenNavigation,
    getDefaultNavigationItem,
    navigationSections
} from "../src/config/navigation.js";

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
