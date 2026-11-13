import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { normalizeModuleUi } from "../../../../frontend/src/modules.js";

const samples = new URL("../../../../../contracts/samples/", import.meta.url);

async function readSample(relativePath) {
    return JSON.parse(await readFile(new URL(relativePath, samples), "utf8"));
}

test("valid module UI sample passes runtime normalization", async () => {
    const ui = normalizeModuleUi("module_demo", await readSample("valid/module-ui.json"));

    assert.equal(ui.version, 1);
    assert.equal(ui.view.id, "demo");
    assert.equal(ui.card.queryId, "demo.list");
    assert.equal(ui.script, "module.js");
    assert.equal(ui.style, "module.css");
});

test("invalid module UI sample rejects unsafe asset paths", async () => {
    const sample = await readSample("invalid/module-ui-unsafe-asset.json");
    assert.throws(() => normalizeModuleUi("module_demo", sample), /资源名非法/);
});

test("declarative settings sample normalizes every supported field type", async () => {
    const ui = normalizeModuleUi("module_demo", await readSample("valid/module-ui-settings.json"));

    assert.deepEqual(
        ui.settings.fields.map(({ key, type }) => [key, type]),
        [
            ["showDetails", "boolean"],
            ["pageSize", "integer"],
            ["sortBy", "select"],
            ["note", "text"]
        ]
    );
    assert.equal(ui.settings.fields[1].default, 20);
    assert.deepEqual(
        ui.settings.fields[2].options.map((option) => option.value),
        ["name", "modified"]
    );
});

test("declarative settings reject duplicate keys", async () => {
    const sample = await readSample("invalid/module-ui-settings-duplicate-key.json");
    assert.throws(() => normalizeModuleUi("module_demo", sample), /设置键重复/);
});
