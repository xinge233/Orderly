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
