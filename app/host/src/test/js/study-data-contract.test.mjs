import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    assertNoDuplicateJsonKeys,
    validateSamples,
    validateStudyData
} from "../../../../../scripts/validate-study-data.mjs";

const samples = new URL("../../../../../contracts/samples/", import.meta.url);

async function readSample(relativePath) {
    return JSON.parse(await readFile(new URL(relativePath, samples), "utf8"));
}

test("approved study data samples satisfy the shared contract", async () => {
    await assert.doesNotReject(validateSamples());
});

test("study data rejects a module owner mismatch", async () => {
    const sample = await readSample("valid/study-review.json");
    assert.throws(
        () => validateStudyData(sample, "module_study_map"),
        /ownerModule 必须与模块目录一致/
    );
});

test("assistant data cannot claim an external model connection", async () => {
    const sample = await readSample("invalid/study-assistant-external-model.json");
    assert.throws(
        () => validateStudyData(sample),
        /不得声称连接外部模型/
    );
});

test("knowledge map edges must reference existing nodes", async () => {
    const sample = await readSample("invalid/study-map-missing-edge-node.json");
    assert.throws(
        () => validateStudyData(sample),
        /target 不存在/
    );
});

test("study data count must match the actual collection", async () => {
    const sample = await readSample("valid/study-review.json");
    sample.count += 1;
    assert.throws(
        () => validateStudyData(sample),
        /review.count 必须与题目数量一致/
    );
});

test("JSON contract files reject duplicate object keys", () => {
    assert.throws(
        () => assertNoDuplicateJsonKeys('{"card":{},"card":{}}', "module.json"),
        /对象存在重复键：card/
    );
});
