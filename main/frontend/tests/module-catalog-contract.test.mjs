import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    moduleCatalogToNavigation,
    parseModuleCatalogPayload
} from "../src/contracts/module-catalog.js";
import { hasView } from "../src/core/view-registry.js";

/**
 * 读取 Java 和前端共用的固定契约样例。
 *
 * @returns {Promise<unknown>} 原始 JSON 数据
 */
async function readContractFixture() {
    const file = await readFile(
        new URL("../../contracts/module-catalog.sample.json", import.meta.url),
        "utf8"
    );
    return JSON.parse(file);
}

test("fixed backend contract maps to a registered frontend view", async () => {
    const catalog = parseModuleCatalogPayload(await readContractFixture());
    const navigation = moduleCatalogToNavigation(catalog);
    const sampleNavigation = navigation.find((item) => item.moduleId === "sample-empty");

    assert.equal(catalog.contractVersion, "1.0");
    assert.ok(sampleNavigation);
    assert.equal(sampleNavigation.view, "lab");
    assert.equal(hasView(sampleNavigation.view), true);

    const sampleModule = catalog.modules.find((module) => module.id === "sample-empty");
    assert.ok(sampleModule);
    assert.equal(sampleModule.actions[0].id, "ping");
    assert.equal(sampleModule.actions[0].parameterSchema.additionalProperties, false);
    assert.equal(sampleModule.actions[0].confirmationRequired, false);
    assert.equal(sampleModule.actions[0].workspaceAccess, "NONE");
});

test("unsupported contract versions and missing view bindings fail explicitly", async () => {
    const fixture = await readContractFixture();
    const unsupported = structuredClone(fixture);
    unsupported.contractVersion = "2.0";
    assert.throws(
        () => parseModuleCatalogPayload(unsupported),
        /不支持的模块目录契约版本/
    );

    const catalog = parseModuleCatalogPayload(fixture);
    assert.throws(
        () => moduleCatalogToNavigation(catalog, {}),
        /缺少前端视图绑定/
    );
});

test("dangerous actions without confirmation are rejected by the parser", async () => {
    const fixture = structuredClone(await readContractFixture());
    fixture.modules[0].actions[0].riskLevel = "WRITE";
    fixture.modules[0].actions[0].confirmationRequired = false;

    assert.throws(
        () => parseModuleCatalogPayload(fixture),
        /必须声明 confirmationRequired=true/
    );
});
