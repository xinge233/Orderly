import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    normalizeSettings,
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
        notifications: "yes",
        showFileModule: false
    }), {
        theme: "paper",
        motion: "gentle",
        notifications: true,
        showFileModule: false,
        showTodoModule: true,
        showCalendarModule: true
    });
});

test("settings store persists only normalized values", () => {
    const storage = memoryStorage();
    const store = new SettingsStore({ storage, storageKey: "test" });

    store.update({ theme: "night", motion: "none", notifications: false });

    assert.deepEqual(store.getSnapshot(), {
        theme: "night",
        motion: "none",
        notifications: false,
        showFileModule: true,
        showTodoModule: true,
        showCalendarModule: true
    });
    assert.match(storage.getItem(), /"theme":"night"/);
});

test("theme manager applies only token-shaped values", async () => {
    const properties = new Map();
    const root = {
        dataset: {},
        style: {
            setProperty(key, value) {
                properties.set(key, value);
            }
        }
    };
    const meta = { content: "" };
    const documentRef = {
        documentElement: root,
        querySelector(selector) {
            return selector.includes("theme-color-meta") ? meta : null;
        }
    };
    const manager = new ThemeManager({
        documentRef,
        fetchImpl: async () => new Response(JSON.stringify({
            colorScheme: "dark",
            browserColor: "rebeccapurple",
            tokens: {
                "--color-accent": "tomato",
                unsafeName: "ignored"
            }
        }), { status: 200 })
    });

    await manager.apply("night");

    assert.equal(root.dataset.theme, "night");
    assert.equal(properties.get("--color-accent"), "tomato");
    assert.equal(properties.has("unsafeName"), false);
    assert.equal(meta.content, "rebeccapurple");
});

test("paper theme resolves to the canonical default token file", async () => {
    let requestedUrl = "";
    const root = {
        dataset: {},
        style: { setProperty() {} }
    };
    const manager = new ThemeManager({
        documentRef: {
            documentElement: root,
            querySelector() {
                return null;
            }
        },
        baseUrl: "/themes",
        fetchImpl: async (url) => {
            requestedUrl = url;
            return new Response(JSON.stringify({
                colorScheme: "light",
                tokens: {}
            }), { status: 200 });
        }
    });

    await manager.apply("paper");
    assert.equal(requestedUrl, "/themes/default.json");
});

test("component CSS contains no literal color values", async () => {
    const css = await readFile(new URL("../styles/app.css", import.meta.url), "utf8");
    assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b/i);
    assert.doesNotMatch(css, /\brgba?\(/i);
});
