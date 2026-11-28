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
        materialDropTitle,
        materialFormatGroup,
        materialHasFileDrag,
        materialSummarySupported,
        materialUploadIssue,
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
    assert.equal(materialSummarySupported("pdf"), true);
    assert.equal(materialSummarySupported("PNG"), true);
    assert.equal(materialSummarySupported("mp4"), false);
    assert.equal(materialFormatGroup("xlsx"), "sheet");
    assert.equal(materialFormatGroup("pptx"), "slide");
    assert.equal(materialFormatGroup("webm"), "video");
});

test("module_materials accepts file drags and rejects unsupported or oversized files", async () => {
    const {
        materialDropTitle,
        materialHasFileDrag,
        materialUploadIssue
    } = await import(moduleUrl);

    assert.equal(materialHasFileDrag({ types: ["Files"] }), true);
    assert.equal(materialHasFileDrag({ types: ["text/plain"] }), false);
    assert.equal(materialHasFileDrag(null), false);
    assert.equal(materialUploadIssue({ name: "讲义.pdf", size: 1024 }), "");
    assert.equal(materialUploadIssue({ name: "脚本.exe", size: 1024 }), "该文件格式暂不支持。");
    assert.equal(
        materialUploadIssue({ name: "大文件.mp4", size: 64 * 1024 * 1024 + 1 }),
        "文件不能超过 64 MB。"
    );
    assert.equal(materialDropTitle("系统设计.pdf"), "系统设计");
    assert.equal(materialDropTitle("无扩展名"), "无扩展名");
});

test("module_materials skips cached, failed, trashed and unsupported auto summaries", async () => {
    const {
        MATERIAL_SUMMARY_CACHE,
        materialAutoSummaryCandidates
    } = await import(moduleUrl);
    MATERIAL_SUMMARY_CACHE.clear();
    MATERIAL_SUMMARY_CACHE.set("ready", { status: "ready", text: "已有摘要" });
    MATERIAL_SUMMARY_CACHE.set("failed", { status: "error", message: "失败" });
    MATERIAL_SUMMARY_CACHE.set("loading", { status: "loading" });
    const items = [
        { id: "ready", format: "PDF", status: "ACTIVE" },
        { id: "failed", format: "DOCX", status: "ACTIVE" },
        { id: "loading", format: "XLSX", status: "ACTIVE" },
        { id: "video", format: "MP4", status: "ACTIVE" },
        { id: "trashed", format: "TXT", status: "TRASHED" },
        { id: "new", format: "TXT", status: "ACTIVE" },
        { id: "new", format: "TXT", status: "ACTIVE" }
    ];

    assert.deepEqual(
        materialAutoSummaryCandidates(items, MATERIAL_SUMMARY_CACHE)
            .map((item) => item.id),
        ["new"]
    );
    assert.equal(MATERIAL_SUMMARY_CACHE.get("ready").text, "已有摘要");
    MATERIAL_SUMMARY_CACHE.clear();
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
    assert.match(source, /autoSummarizeAll/);
    assert.match(source, /summaries: MATERIAL_SUMMARY_CACHE/);
    assert.match(source, /context\.summarizeModuleData\(/);
    assert.match(source, /context\.saveSettings\(/);
    assert.match(source, /new AbortController\(\)/);
    assert.match(source, /uploadController\?\.abort\(\)/);
    assert.match(source, /materials-ui-upload-alert/);
    assert.match(source, /dataset\.orderlyFileDropZone/);
    assert.match(source, /uploadController\.signal/);
    assert.doesNotMatch(source, /openAiWithText/);
});
