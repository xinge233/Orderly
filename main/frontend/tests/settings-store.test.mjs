import assert from "node:assert/strict";
import test from "node:test";

import { SettingsStore } from "../src/core/settings-store.js";

/** 最小内存存储，用于在 Node 中验证设置读写而不依赖浏览器。 */
class MemoryStorage {
    values = new Map();

    getItem(key) {
        return this.values.get(key) ?? null;
    }

    setItem(key, value) {
        this.values.set(key, value);
    }
}

test("settings store exposes validated defaults", () => {
    const store = new SettingsStore({ storage: new MemoryStorage() });
    assert.deepEqual(store.getSnapshot(), {
        theme: "paper",
        motion: "gentle",
        language: "zh-CN",
        notifications: true
    });
});

test("settings store ignores unknown fields and invalid options", () => {
    const store = new SettingsStore({ storage: new MemoryStorage() });
    const settings = store.update({
        theme: "not-a-theme",
        motion: "minimal",
        unknownField: "ignored"
    });

    assert.equal(settings.theme, "paper");
    assert.equal(settings.motion, "minimal");
    assert.equal("unknownField" in settings, false);
});

test("settings store persists changes and notifies subscribers", () => {
    const storage = new MemoryStorage();
    const store = new SettingsStore({ storage });
    let notificationCount = 0;
    store.subscribe(() => {
        notificationCount += 1;
    });

    store.update({ theme: "midnight", notifications: false });

    const restored = new SettingsStore({ storage });
    assert.equal(restored.getSnapshot().theme, "midnight");
    assert.equal(restored.getSnapshot().notifications, false);
    assert.equal(notificationCount, 1);
});

test("settings reset returns to defaults", () => {
    const store = new SettingsStore({ storage: new MemoryStorage() });
    store.update({ theme: "moss", language: "en-US" });
    const settings = store.reset();

    assert.equal(settings.theme, "paper");
    assert.equal(settings.language, "zh-CN");
});
