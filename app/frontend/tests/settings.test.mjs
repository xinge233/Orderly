import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    isModuleHidden,
    normalizeSettings,
    reconcileSettingsForCatalog,
    resolveThemeSelection,
    setModuleHidden,
    SettingsStore,
    ThemeManager
} from "../src/settings.js";

function memoryStorage(initial = null) {
    let value = initial;
    return {
        getItem() {
            return value;
        },
        setItem(_key, next) {
            value = next;
        },
        removeItem() {
            value = null;
        }
    };
}

test("invalid settings fall back to safe defaults", () => {
    assert.deepEqual(normalizeSettings({
        theme: "unknown",
        motion: "explode",
        notifications: "yes"
    }), {
        theme: "unknown",
        motion: "gentle",
        notifications: true,
        hiddenModuleIds: []
    });
});

test("legacy module booleans migrate once into hiddenModuleIds", () => {
    assert.deepEqual(normalizeSettings({
        showFileModule: false,
        showTodoModule: true,
        showCalendarModule: false
    }).hiddenModuleIds, ["module_fs", "module_calendar"]);
});

test("settings store persists dynamic module visibility without fixed module keys", () => {
    const storage = memoryStorage();
    const store = new SettingsStore({ storage, storageKey: "test" });

    const next = setModuleHidden(store.getSnapshot(), "module_new", true);
    store.update({ hiddenModuleIds: next.hiddenModuleIds, motion: "none" });

    assert.equal(isModuleHidden(store.getSnapshot(), "module_new"), true);
    assert.deepEqual(store.getSnapshot().hiddenModuleIds, ["module_new"]);
    assert.match(storage.getItem(), /"hiddenModuleIds":\["module_new"\]/);
    assert.doesNotMatch(storage.getItem(), /showFileModule/);
});

test("deleted selected theme falls back to default before first item", () => {
    const themes = [
        { id: "first", label: "第一", order: 1, default: false },
        { id: "fallback", label: "默认", order: 20, default: true },
        { id: "last", label: "末项", order: 30, default: false }
    ];
    assert.equal(resolveThemeSelection("deleted", themes), "fallback");
    assert.equal(resolveThemeSelection("deleted", themes.map(({ default: _default, ...theme }) => theme)), "first");
    assert.equal(resolveThemeSelection("last", themes), "last");
    assert.equal(resolveThemeSelection("deleted", []), "");
});

test("catalog reconciliation updates stale theme and removes unknown hidden modules", () => {
    const reconciled = reconcileSettingsForCatalog({
        theme: "removed",
        hiddenModuleIds: ["module_kept", "module_removed"]
    }, [
        { moduleId: "module_kept" }
    ], [
        { id: "default-theme", order: 10, default: true }
    ]);

    assert.equal(reconciled.theme, "default-theme");
    assert.deepEqual(reconciled.hiddenModuleIds, ["module_kept"]);
});

test("theme manager loads detail, applies only token-shaped values, and clears previous injection", async () => {
    const properties = new Map();
    const removed = [];
    const root = {
        dataset: {},
        style: {
            setProperty(key, value) {
                properties.set(key, value);
            },
            removeProperty(key) {
                removed.push(key);
                properties.delete(key);
            }
        }
    };
    const metas = {
        scheme: { content: "" },
        browser: { content: "" }
    };
    const documentRef = {
        documentElement: root,
        querySelector(selector) {
            return selector.includes("color-scheme") ? metas.scheme : metas.browser;
        }
    };
    const manager = new ThemeManager({
        documentRef,
        fetchImpl: async (url) => new Response(JSON.stringify(url.endsWith("/alpha")
            ? {
                colorScheme: "dark",
                browserColor: "rebeccapurple",
                tokens: { "--color-accent": "tomato", unsafeName: "ignored" }
            }
            : {
                colorScheme: "light",
                browserColor: "Canvas",
                tokens: { "--color-accent": "green", "--color-text": "black" }
            }), { status: 200 })
    });

    await manager.apply("alpha");
    assert.equal(root.dataset.theme, "alpha");
    assert.equal(properties.get("--color-accent"), "tomato");
    assert.equal(properties.has("unsafeName"), false);
    assert.equal(metas.scheme.content, "dark");
    assert.equal(metas.browser.content, "rebeccapurple");

    await manager.apply("beta");
    assert.deepEqual(removed, ["--color-accent"]);
    assert.equal(properties.get("--color-accent"), "green");
    assert.equal(root.dataset.theme, "beta");
});

test("theme manager clears dynamic tokens when no theme is available", async () => {
    const properties = new Map();
    const root = {
        dataset: { theme: "old" },
        style: {
            setProperty(key, value) {
                properties.set(key, value);
            },
            removeProperty(key) {
                properties.delete(key);
            }
        }
    };
    const manager = new ThemeManager({
        documentRef: { documentElement: root, querySelector: () => null },
        fetchImpl: async () => new Response(JSON.stringify({ tokens: { "--color-accent": "red" } }))
    });

    await manager.apply("alpha");
    manager.clear();

    assert.equal(properties.has("--color-accent"), false);
    assert.equal("theme" in root.dataset, false);
});

test("frontend source contains no static theme option list", async () => {
    const settings = await readFile(new URL("../src/settings.js", import.meta.url), "utf8");
    const app = await readFile(new URL("../src/app.js", import.meta.url), "utf8");
    assert.doesNotMatch(settings, /THEME_OPTIONS/);
    assert.doesNotMatch(app, /THEME_OPTIONS/);
});

test("component CSS contains no literal color values", async () => {
    const css = await readFile(new URL("../styles/app.css", import.meta.url), "utf8");
    assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b/i);
    assert.doesNotMatch(css, /\brgba?\(/i);
});