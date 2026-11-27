import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const moduleUrl = new URL(
    "../../main/resources/orderly-ui/module.js",
    import.meta.url
);

test("module_materials filters metadata locally and formats file sizes", async () => {
    const {
        formatBytes,
        materialMatches
    } = await import(moduleUrl);
    const item = {
        title: "系统设计资料",
        fileName: "architecture.pdf",
        category: "课程",
        format: "PDF",
        description: "架构设计讲义",
        tags: ["系统", "设计"]
    };

    assert.equal(materialMatches(item, { keyword: "架构" }), true);
    assert.equal(materialMatches(item, { keyword: "设计", category: "课程" }), true);
    assert.equal(materialMatches(item, { format: "pdf" }), true);
    assert.equal(materialMatches(item, { keyword: "设计", format: "DOCX" }), false);
    assert.equal(formatBytes(0), "0 B");
    assert.equal(formatBytes(1536), "1.5 KB");
    assert.equal(formatBytes("bad"), "大小未知");
});

test("module_materials refreshes only on terminal module events", async () => {
    const { materialDataEvent } = await import(moduleUrl);
    const moduleId = "module_materials";

    assert.equal(materialDataEvent({
        type: "STEP",
        moduleId,
        status: "COMPLETED"
    }, moduleId), true);
    assert.equal(materialDataEvent({
        type: "ROLLBACK_STEP",
        moduleId,
        status: "FAILED"
    }, moduleId), true);
    assert.equal(materialDataEvent({
        type: "STEP",
        moduleId,
        status: "RUNNING"
    }, moduleId), false);
    assert.equal(materialDataEvent({
        type: "STEP",
        moduleId: "module_todo",
        status: "COMPLETED"
    }, moduleId), false);
});

test("module_materials exposes guarded import, trash, restore and cleanup paths", async () => {
    const source = await readFile(moduleUrl, "utf8");

    assert.match(source, /context\.executeActionWithFile\(/);
    assert.match(source, /"materials\.trash"/);
    assert.match(source, /"materials\.restore"/);
    assert.match(source, /new MutationObserver/);
    assert.match(source, /unsubscribeEvents\?\.\(\)/);
    assert.match(source, /confirmDialog\.ask/);
    assert.match(source, /file\.size > MAX_UPLOAD_BYTES/);
});
