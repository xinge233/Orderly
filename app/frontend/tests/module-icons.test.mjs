import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { paintModuleIcon, resolveModuleIcon } from "../src/icons.js";

function fakeElement() {
    const classes = new Set();
    const properties = new Map();
    return {
        dataset: {},
        style: {
            setProperty(name, value) {
                properties.set(name, value);
            },
            removeProperty(name) {
                properties.delete(name);
            },
            getPropertyValue(name) {
                return properties.get(name) ?? "";
            }
        },
        classList: {
            add(name) {
                classes.add(name);
            },
            remove(name) {
                classes.delete(name);
            },
            contains(name) {
                return classes.has(name);
            }
        },
        children: [],
        innerHTML: "",
        replaceChildren(...nodes) {
            this.children = nodes;
            this.innerHTML = "";
        }
    };
}

test("module icons resolve declared assets through a restricted API URL", () => {
    const source = resolveModuleIcon(
        "materials",
        { materials: "icons/materials.svg" },
        (asset) => `/api/modules/module_fs/ui/${asset}`
    );

    assert.deepEqual(source, {
        kind: "asset",
        name: "materials",
        url: "/api/modules/module_fs/ui/icons/materials.svg"
    });
});

test("module icons fall back to framework SVG for missing or unsafe resources", () => {
    assert.deepEqual(
        resolveModuleIcon("file", {}, () => "/api/modules/module_fs/ui/icons/file.svg"),
        { kind: "builtin", name: "file" }
    );
    assert.deepEqual(
        resolveModuleIcon(
            "materials",
            { materials: "icons/materials.svg" },
            () => "javascript:alert(1)"
        ),
        { kind: "builtin", name: "materials" }
    );
});

test("paintModuleIcon applies a currentColor mask without inlining module SVG", () => {
    const element = fakeElement();

    paintModuleIcon(element, "materials", {
        icons: { materials: "icons/materials.svg" },
        assetUrl: (asset) => `/api/modules/module_fs/ui/${asset}`,
        size: 22
    });

    assert.equal(element.dataset.iconSource, "module");
    assert.equal(element.classList.contains("module-icon--asset"), true);
    assert.equal(element.style.getPropertyValue("--module-icon-size"), "22px");
    assert.equal(
        element.style.getPropertyValue("--module-icon-image"),
        'url("/api/modules/module_fs/ui/icons/materials.svg")'
    );
    assert.deepEqual(element.children, []);
    assert.equal(element.innerHTML, "");
});

test("paintModuleIcon restores framework SVG when no module asset is declared", () => {
    const element = fakeElement();

    paintModuleIcon(element, "file", {
        icons: {},
        assetUrl: null,
        size: 21
    });

    assert.equal(element.dataset.iconSource, "framework");
    assert.equal(element.classList.contains("module-icon--asset"), false);
    assert.equal(element.style.getPropertyValue("--module-icon-image"), "");
    assert.match(element.innerHTML, /^<svg /);
});

test("module icon masks preserve wrapper geometry and use currentColor", async () => {
    const css = await readFile(
        new URL("../styles/app.css", import.meta.url),
        "utf8"
    );

    assert.match(
        css,
        /\.module-icon--asset::before\s*\{[\s\S]*?width:\s*var\(--module-icon-size[\s\S]*?background-color:\s*currentColor;[\s\S]*?mask:\s*var\(--module-icon-image\)/
    );
});
