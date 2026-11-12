import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    isModuleHidden,
    normalizeSettings,
    normalizeThemeCatalog,
    reconcileAppliedTheme,
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

function wait(milliseconds = 0) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function fakeDocument({ failLink = () => false, loadDelay = 0 } = {}) {
    const root = { dataset: {} };
    const metas = {
        scheme: { content: "" },
        browser: { content: "" }
    };
    const head = {
        children: [],
        append(link) {
            this.children.push(link);
            setTimeout(() => {
                if (failLink(link)) {
                    link.emit("error");
                } else {
                    link.emit("load");
                }
            }, loadDelay);
        },
        removeChild(link) {
            this.children = this.children.filter((candidate) => candidate !== link);
        }
    };
    const documentRef = {
        documentElement: root,
        head,
        createElement(tag) {
            assert.equal(tag, "link");
            const listeners = new Map();
            const link = {
                rel: "",
                media: "",
                href: "",
                dataset: {},
                parentNode: head,
                addEventListener(type, listener) {
                    listeners.set(type, listener);
                },
                emit(type) {
                    if (head.children.includes(link)) {
                        listeners.get(type)?.();
                    }
                },
                remove() {
                    head.removeChild(link);
                }
            };
            return link;
        },
        querySelector(selector) {
            if (selector.includes("color-scheme")) {
                return metas.scheme;
            }
            if (selector.includes("theme-color-meta")) {
                return metas.browser;
            }
            return null;
        }
    };
    return { documentRef, root, head, metas };
}

function detail(id, styles = ["tokens.css", "motion.css"]) {
    return {
        schemaVersion: 1,
        id,
        label: id,
        styles,
        assets: [],
        colorScheme: id === "night" ? "dark" : "light",
        browserColor: id === "night" ? "#161b23" : "Canvas"
    };
}

function fetchDetails({ delayById = {}, invalidIds = new Set() } = {}) {
    return async (url) => {
        const id = decodeURIComponent(url.split("/").at(-1));
        await wait(delayById[id] ?? 0);
        if (invalidIds.has(id)) {
            return new Response(JSON.stringify({ message: "missing" }), { status: 404 });
        }
        return new Response(JSON.stringify(detail(id)), {
            status: 200,
            headers: { "Content-Type": "application/json" }
        });
    };
}

const CATALOG = {
    schemaVersion: 1,
    items: [
        { schemaVersion: 1, id: "paper", label: "纸页", order: 10, default: true },
        { schemaVersion: 1, id: "night", label: "夜航", order: 30, default: false }
    ],
    issues: [],
    builtInFallbackActive: false
};

test("invalid settings fall back to safe defaults", () => {
    assert.deepEqual(normalizeSettings({
        theme: "unknown",
        motion: "explode",
        notifications: "yes"
    }), {
        theme: "unknown",
        motion: "gentle",
        notifications: true,
        hiddenModuleIds: [],
        moduleOrderIds: [],
        sidebarModuleOrderIds: []
    });
});

test("unknown legacy module booleans are ignored", () => {
    assert.deepEqual(normalizeSettings({
        showFileModule: false,
        showTodoModule: true,
        showCalendarModule: false
    }).hiddenModuleIds, []);
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

test("theme fallback selects only an available default and never the first item", () => {
    const themes = [
        { schemaVersion: 1, id: "first", label: "第一", order: 1, default: false },
        { schemaVersion: 1, id: "fallback", label: "默认", order: 20, default: true },
        { schemaVersion: 1, id: "last", label: "末项", order: 30, default: false }
    ];
    assert.equal(resolveThemeSelection("deleted", themes), "fallback");
    assert.equal(resolveThemeSelection("deleted", themes.filter((theme) => !theme.default)), "");
    assert.equal(resolveThemeSelection("last", themes), "last");
    assert.equal(resolveThemeSelection("deleted", []), "");
});

test("theme settings reconcile to the actually applied theme or built-in safety", () => {
    assert.equal(reconcileAppliedTheme("night", { ...detail("night") }), null);
    assert.deepEqual(
        reconcileAppliedTheme("night", { ...detail("paper") }),
        { theme: "paper" }
    );
    assert.deepEqual(reconcileAppliedTheme("night", null), { theme: "" });
    assert.deepEqual(
        reconcileAppliedTheme("night", { ...detail("night"), schemaVersion: 2 }),
        { theme: "" }
    );
});

test("new theme catalog keeps local issues and ignores unsupported summaries", () => {
    const catalog = normalizeThemeCatalog({
        items: [
            { schemaVersion: 1, id: "paper", order: 10, default: true },
            { schemaVersion: 2, id: "future", order: 20, default: false }
        ],
        issues: [{ id: "future", code: "UNSUPPORTED_SCHEMA_VERSION", message: "不支持" }],
        builtInFallbackActive: false
    });

    assert.deepEqual(catalog.items.map((theme) => theme.id), ["paper"]);
    assert.equal(catalog.issues[0].code, "UNSUPPORTED_SCHEMA_VERSION");
    assert.equal(catalog.builtInFallbackActive, false);
});

test("catalog reconciliation updates stale theme and removes unknown hidden modules", () => {
    const reconciled = reconcileSettingsForCatalog({
        theme: "removed",
        hiddenModuleIds: ["module_kept", "module_removed"]
    }, [
        { moduleId: "module_kept" }
    ], [
        { schemaVersion: 1, id: "default-theme", order: 10, default: true }
    ]);

    assert.equal(reconciled.theme, "default-theme");
    assert.deepEqual(reconciled.hiddenModuleIds, ["module_kept"]);
    assert.deepEqual(reconciled.moduleOrderIds, ["module_kept"]);
});

test("settings persist module card order and reconcile catalog changes", () => {
    const storage = memoryStorage();
    const store = new SettingsStore({ storage, storageKey: "order-test" });
    store.update({ moduleOrderIds: ["module_new", "module_removed"] });
    assert.deepEqual(store.getSnapshot().moduleOrderIds, ["module_new", "module_removed"]);

    const reconciled = reconcileSettingsForCatalog(store.getSnapshot(), [
        { moduleId: "module_new", order: 20 },
        { moduleId: "module_added", order: 10 }
    ]);
    assert.deepEqual(reconciled.moduleOrderIds, ["module_new", "module_added"]);
    assert.match(storage.getItem(), /"moduleOrderIds":\["module_new","module_removed"\]/);
});

test("card and sidebar module orders stay independent", () => {
    const storage = memoryStorage();
    const store = new SettingsStore({ storage, storageKey: "independent-order-test" });
    const modules = [
        { moduleId: "module_a", order: 10 },
        { moduleId: "module_b", order: 20 }
    ];
    store.update({
        moduleOrderIds: ["module_b", "module_a"],
        sidebarModuleOrderIds: ["module_a", "module_b"]
    });
    const settings = reconcileSettingsForCatalog({
        ...store.getSnapshot()
    }, modules);

    assert.deepEqual(settings.moduleOrderIds, ["module_b", "module_a"]);
    assert.deepEqual(settings.sidebarModuleOrderIds, ["module_a", "module_b"]);
});

test("theme manager commits all CSS links without injecting root tokens", async () => {
    const { documentRef, root, head, metas } = fakeDocument();
    const manager = new ThemeManager({ documentRef, fetchImpl: fetchDetails() });
    manager.setCatalog(CATALOG);

    await manager.apply("night");

    assert.equal(root.dataset.theme, "night");
    assert.equal(metas.scheme.content, "dark");
    assert.equal(metas.browser.content, "#161b23");
    assert.equal(head.children.length, 2);
    assert.deepEqual(head.children.map((link) => link.media), ["all", "all"]);
    assert.deepEqual(head.children.map((link) => link.href), [
        "/api/themes/night/assets/tokens.css",
        "/api/themes/night/assets/motion.css"
    ]);
    assert.equal("style" in root, false);
});

test("theme manager removes the old links only after a successful replacement", async () => {
    const { documentRef, root, head } = fakeDocument();
    const manager = new ThemeManager({ documentRef, fetchImpl: fetchDetails() });
    manager.setCatalog(CATALOG);

    await manager.apply("paper");
    await manager.apply("night");

    assert.equal(root.dataset.theme, "night");
    assert.equal(head.children.length, 2);
    assert.ok(head.children.every((link) => link.dataset.orderlyThemeStyle === "night"));
});

test("failed candidate CSS keeps the current theme and removes partial links", async () => {
    const { documentRef, root, head } = fakeDocument({
        failLink: (link) => link.href.includes("/night/") && link.href.endsWith("motion.css")
    });
    const manager = new ThemeManager({ documentRef, fetchImpl: fetchDetails() });
    manager.setCatalog(CATALOG);

    await manager.apply("paper");
    const result = await manager.apply("night");

    assert.equal(result.id, "paper");
    assert.equal(manager.current.id, "paper");
    assert.equal(root.dataset.theme, "paper");
    assert.equal(head.children.length, 2);
    assert.ok(head.children.every((link) => link.dataset.orderlyThemeStyle === "paper"));
});

test("request generations prevent a slow response from overwriting a newer selection", async () => {
    const { documentRef, root, head } = fakeDocument();
    const manager = new ThemeManager({
        documentRef,
        fetchImpl: fetchDetails({ delayById: { paper: 40, night: 0 } })
    });
    manager.setCatalog(CATALOG);

    const [slow, fast] = await Promise.all([
        manager.apply("paper"),
        manager.apply("night")
    ]);

    assert.equal(slow, null);
    assert.equal(fast.id, "night");
    assert.equal(manager.current.id, "night");
    assert.equal(root.dataset.theme, "night");
    assert.equal(head.children.length, 2);
    assert.ok(head.children.every((link) => link.dataset.orderlyThemeStyle === "night"));
});

test("initial CSS failure falls back to the available default before built-in safety", async () => {
    const { documentRef, root, head } = fakeDocument({
        failLink: (link) => link.href.includes("/night/")
    });
    const manager = new ThemeManager({ documentRef, fetchImpl: fetchDetails() });
    manager.setCatalog(CATALOG);

    const result = await manager.apply("night");

    assert.equal(result.id, "paper");
    assert.equal(root.dataset.theme, "paper");
    assert.equal(head.children.length, 2);
    assert.ok(head.children.every((link) => link.dataset.orderlyThemeStyle === "paper"));
});

test("empty selection fully unloads active CSS when no default theme exists", async () => {
    const { documentRef, root, head } = fakeDocument();
    const manager = new ThemeManager({ documentRef, fetchImpl: fetchDetails() });
    manager.setCatalog({
        items: [{ schemaVersion: 1, id: "night", order: 1, default: false }],
        issues: [],
        builtInFallbackActive: true
    });

    await manager.apply("night");
    assert.equal(manager.current.id, "night");
    assert.equal(root.dataset.theme, "night");
    assert.equal(head.children.length, 2);

    const result = await manager.apply("");

    assert.equal(result, null);
    assert.equal("theme" in root.dataset, false);
    assert.equal(head.children.length, 0);
    assert.equal(manager.current, null);
});

test("theme manager rejects unsupported detail schema and never injects JavaScript", async () => {
    const { documentRef, root, head } = fakeDocument();
    const manager = new ThemeManager({
        documentRef,
        fetchImpl: async (url) => {
            const id = url.split("/").at(-1);
            return new Response(JSON.stringify({ ...detail(id), schemaVersion: 2 }), { status: 200 });
        }
    });
    manager.setCatalog(CATALOG);

    const result = await manager.apply("paper");
    const source = await readFile(new URL("../src/settings.js", import.meta.url), "utf8");

    assert.equal(result, null);
    assert.equal("theme" in root.dataset, false);
    assert.equal(head.children.length, 0);
    assert.doesNotMatch(source, /createElement\(["']script["']\)|<script/i);
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
