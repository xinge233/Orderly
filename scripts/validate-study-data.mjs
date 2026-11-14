#!/usr/bin/env node

import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const scriptRoot = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptRoot, "..");

const MODULE_IDS = [
    "module_study_assistant",
    "module_study_map",
    "module_study_review",
    "module_fs"
];

const KINDS_BY_MODULE = new Map([
    ["module_study_assistant", "assistant"],
    ["module_study_map", "knowledge-map"],
    ["module_study_review", "review"],
    ["module_fs", "materials"]
]);

function fail(scope, message) {
    throw new Error(`${scope}: ${message}`);
}

function assert(condition, scope, message) {
    if (!condition) {
        fail(scope, message);
    }
}

function isObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function assertNoDuplicateJsonKeys(raw, scope = "JSON") {
    const tokens = String(raw).match(
        /"(?:\\u[0-9a-fA-F]{4}|\\[^u]|[^"\\])*"|[{}\[\]:,]|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null/g
    ) ?? [];
    const stack = [];

    function markValueConsumed() {
        const parent = stack.at(-1);
        if (parent) {
            parent.expect = parent.type === "object" ? "comma" : "comma";
        }
    }

    for (const token of tokens) {
        if (token === "{") {
            markValueConsumed();
            stack.push({ type: "object", expect: "key", keys: new Set() });
            continue;
        }
        if (token === "[") {
            markValueConsumed();
            stack.push({ type: "array", expect: "value" });
            continue;
        }
        if (token === "}" || token === "]") {
            stack.pop();
            continue;
        }

        const frame = stack.at(-1);
        if (!frame) {
            continue;
        }
        if (frame.type === "object") {
            if (frame.expect === "key") {
                if (!token.startsWith('"')) {
                    fail(scope, `对象键位置出现非法 token：${token}`);
                }
                const key = JSON.parse(token);
                assert(!frame.keys.has(key), scope, `对象存在重复键：${key}`);
                frame.keys.add(key);
                frame.expect = "colon";
            } else if (frame.expect === "colon") {
                assert(token === ":", scope, "对象键后缺少冒号");
                frame.expect = "value";
            } else if (frame.expect === "value") {
                frame.expect = "comma";
            } else if (token === ",") {
                frame.expect = "key";
            } else {
                fail(scope, `对象值后出现非法 token：${token}`);
            }
        } else if (frame.expect === "value") {
            frame.expect = "comma";
        } else if (token === ",") {
            frame.expect = "value";
        } else {
            fail(scope, `数组值后出现非法 token：${token}`);
        }
    }
}

function assertString(value, scope, field) {
    assert(typeof value === "string" && value.trim().length > 0, scope, `${field} 必须是非空字符串`);
}

function assertArray(value, scope, field) {
    assert(Array.isArray(value), scope, `${field} 必须是数组`);
}

function assertUniqueIds(items, scope, field) {
    const ids = new Set();
    for (const item of items) {
        assertString(item?.id, scope, `${field}[].id`);
        assert(!ids.has(item.id), scope, `${field} 包含重复 id：${item.id}`);
        ids.add(item.id);
    }
    return ids;
}

function assertSources(document, scope) {
    assertArray(document.sources, scope, "sources");
    const sourceIds = assertUniqueIds(document.sources, scope, "sources");
    for (const source of document.sources) {
        assertString(source.label, scope, "sources[].label");
        assert(["module-data", "material", "note", "rule"].includes(source.kind),
            scope, `来源 ${source.id} 的 kind 非法`);
        assertString(source.location, scope, "sources[].location");
    }
    return sourceIds;
}

function assertSourceRef(sourceId, sourceIds, scope, field) {
    assertString(sourceId, scope, field);
    assert(sourceIds.has(sourceId), scope, `${field} 引用了不存在的来源：${sourceId}`);
}

function validateAssistant(document, sourceIds, scope) {
    const payload = document.payload;
    assert(payload.planner === "local-rules", scope, "assistant.planner 必须是 local-rules");
    assert(payload.externalModelConnected === false, scope,
        "assistant 不得声称连接外部模型");
    assertArray(payload.tasks, scope, "assistant.tasks");
    assertArray(payload.conversation, scope, "assistant.conversation");
    assertArray(payload.toolTrace, scope, "assistant.toolTrace");
    assertArray(payload.citations, scope, "assistant.citations");
    assertUniqueIds(payload.tasks, scope, "tasks");
    assertUniqueIds(payload.conversation, scope, "conversation");
    assertUniqueIds(payload.toolTrace, scope, "toolTrace");
    assertUniqueIds(payload.citations, scope, "citations");
    for (const task of payload.tasks) {
        assertString(task.title, scope, "tasks[].title");
        assertString(task.detail, scope, "tasks[].detail");
    }
    for (const message of payload.conversation) {
        assert(["user", "assistant"].includes(message.role), scope,
            `消息 ${message.id} 的 role 非法`);
        assertString(message.content, scope, "conversation[].content");
        assert(message.readOnly === true, scope, `消息 ${message.id} 必须只读`);
    }
    for (const step of payload.toolTrace) {
        assertString(step.label, scope, "toolTrace[].label");
        assert(["sample", "not-connected", "available"].includes(step.state), scope,
            `工具轨迹 ${step.id} 的 state 非法`);
        assertString(step.detail, scope, "toolTrace[].detail");
    }
    for (const citation of payload.citations) {
        assertString(citation.title, scope, "citations[].title");
        assertString(citation.excerpt, scope, "citations[].excerpt");
        assertSourceRef(citation.sourceId, sourceIds, scope, "citations[].sourceId");
    }
    assert(document.count === payload.tasks.length, scope,
        "assistant.count 必须与本周任务数量一致");
}

function validateMaterials(document, sourceIds, scope) {
    const payload = document.payload;
    assertArray(payload.courses, scope, "materials.courses");
    assertArray(payload.materials, scope, "materials.materials");
    const courseIds = assertUniqueIds(payload.courses, scope, "courses");
    assertUniqueIds(payload.materials, scope, "materials");
    for (const course of payload.courses) {
        assertString(course.title, scope, "courses[].title");
        assertString(course.code, scope, "courses[].code");
        assertString(course.description, scope, "courses[].description");
    }
    for (const material of payload.materials) {
        assertString(material.title, scope, "materials[].title");
        assert(courseIds.has(material.courseId), scope,
            `资料 ${material.id} 引用了不存在的课程：${material.courseId}`);
        assertString(material.chapter, scope, "materials[].chapter");
        assert(["PDF", "Markdown", "HTML", "DOCX"].includes(material.format), scope,
            `资料 ${material.id} 的 format 非法`);
        assert(["已整理", "待复核", "只读示例"].includes(material.status), scope,
            `资料 ${material.id} 的 status 非法`);
        assertSourceRef(material.sourceId, sourceIds, scope, "materials[].sourceId");
        assertString(material.excerpt, scope, "materials[].excerpt");
        assertArray(material.cards, scope, "materials[].cards");
        assertUniqueIds(material.cards, scope, `materials.${material.id}.cards`);
        for (const card of material.cards) {
            assertString(card.title, scope, "cards[].title");
            assertString(card.summary, scope, "cards[].summary");
        }
    }
    assert(document.count === payload.materials.length, scope,
        "materials.count 必须与资料数量一致");
}

function validateKnowledgeMap(document, sourceIds, scope) {
    const payload = document.payload;
    assertArray(payload.nodes, scope, "knowledge-map.nodes");
    assertArray(payload.edges, scope, "knowledge-map.edges");
    assert(payload.nodes.length > 0, scope, "knowledge-map 至少需要一个节点");
    const nodeIds = assertUniqueIds(payload.nodes, scope, "nodes");
    assertUniqueIds(payload.edges, scope, "edges");
    for (const node of payload.nodes) {
        assertString(node.label, scope, "nodes[].label");
        assert(["模块", "概念", "方法", "公式", "练习"].includes(node.category), scope,
            `节点 ${node.id} 的 category 非法`);
        assertString(node.courseId, scope, "nodes[].courseId");
        assertString(node.summary, scope, "nodes[].summary");
        assertArray(node.sourceIds, scope, "nodes[].sourceIds");
        node.sourceIds.forEach((sourceId, index) =>
            assertSourceRef(sourceId, sourceIds, scope, `nodes[].sourceIds[${index}]`));
        assert(Number.isInteger(node.layer) && node.layer >= 0 && node.layer <= 5, scope,
            `节点 ${node.id} 的 layer 非法`);
        assert(Number.isInteger(node.order) && node.order >= 0, scope,
            `节点 ${node.id} 的 order 非法`);
    }
    for (const edge of payload.edges) {
        assert(nodeIds.has(edge.source), scope,
            `边 ${edge.id} 的 source 不存在：${edge.source}`);
        assert(nodeIds.has(edge.target), scope,
            `边 ${edge.id} 的 target 不存在：${edge.target}`);
        assertString(edge.label, scope, "edges[].label");
        assert(["contains", "depends", "supports", "applies"].includes(edge.kind), scope,
            `边 ${edge.id} 的 kind 非法`);
    }
    assert(document.count === payload.nodes.length, scope,
        "knowledge-map.count 必须与节点数量一致");
}

function validateReview(document, sourceIds, scope) {
    const payload = document.payload;
    assert(payload.gradingMode === "none", scope, "review.gradingMode 必须是 none");
    assertArray(payload.questions, scope, "review.questions");
    assertUniqueIds(payload.questions, scope, "questions");
    for (const question of payload.questions) {
        assertString(question.courseId, scope, "questions[].courseId");
        assertString(question.chapter, scope, "questions[].chapter");
        assertString(question.knowledgePoint, scope, "questions[].knowledgePoint");
        assertString(question.prompt, scope, "questions[].prompt");
        assertString(question.answerOutline, scope, "questions[].answerOutline");
        assertSourceRef(question.sourceId, sourceIds, scope, "questions[].sourceId");
        assert(["基础", "进阶", "综合"].includes(question.difficulty), scope,
            `题目 ${question.id} 的 difficulty 非法`);
        assert(question.readOnly === true, scope, `题目 ${question.id} 必须只读`);
    }
    assert(document.count === payload.questions.length, scope,
        "review.count 必须与题目数量一致");
}

export function validateStudyData(document, expectedOwnerModule = null) {
    const scope = expectedOwnerModule || document?.ownerModule || "study-data";
    assert(isObject(document), scope, "根节点必须是对象");
    const required = [
        "schemaVersion",
        "kind",
        "ownerModule",
        "readOnly",
        "title",
        "description",
        "sources",
        "count",
        "payload"
    ];
    for (const field of required) {
        assert(Object.hasOwn(document, field), scope, `缺少字段：${field}`);
    }
    const allowed = new Set(required);
    for (const field of Object.keys(document)) {
        assert(allowed.has(field), scope, `不允许的字段：${field}`);
    }
    assert(document.schemaVersion === 1, scope, "schemaVersion 必须是 1");
    assert(KINDS_BY_MODULE.has(document.ownerModule), scope,
        `ownerModule 不在批准的模块集合中：${document.ownerModule}`);
    if (expectedOwnerModule) {
        assert(document.ownerModule === expectedOwnerModule, scope,
            `ownerModule 必须与模块目录一致：${expectedOwnerModule}`);
    }
    assert(document.kind === KINDS_BY_MODULE.get(document.ownerModule), scope,
        `${document.ownerModule} 只能使用 kind=${KINDS_BY_MODULE.get(document.ownerModule)}`);
    assert(document.readOnly === true, scope, "学习数据必须声明 readOnly=true");
    assertString(document.title, scope, "title");
    assertString(document.description, scope, "description");
    assert(Number.isInteger(document.count) && document.count >= 0, scope,
        "count 必须是非负整数");
    assert(isObject(document.payload), scope, "payload 必须是对象");
    const sourceIds = assertSources(document, scope);
    switch (document.kind) {
        case "assistant":
            validateAssistant(document, sourceIds, scope);
            break;
        case "materials":
            validateMaterials(document, sourceIds, scope);
            break;
        case "knowledge-map":
            validateKnowledgeMap(document, sourceIds, scope);
            break;
        case "review":
            validateReview(document, sourceIds, scope);
            break;
        default:
            fail(scope, `未知 kind：${document.kind}`);
    }
    return document;
}

async function readJson(filePath) {
    const raw = await readFile(filePath, "utf8");
    assertNoDuplicateJsonKeys(raw, filePath);
    return JSON.parse(raw);
}

export async function validateModuleFiles(root = repoRoot) {
    for (const moduleId of MODULE_IDS) {
        const moduleRoot = path.join(root, "Module", moduleId);
        await readJson(path.join(
            moduleRoot,
            "src",
            "main",
            "resources",
            "orderly-modules",
            `${moduleId}.json`
        ));
        await readJson(path.join(
            moduleRoot,
            "src",
            "main",
            "resources",
            "orderly-ui",
            "module.json"
        ));
        const dataPath = path.join(
            moduleRoot,
            "src",
            "main",
            "resources",
            "orderly-data",
            `${moduleId}.json`
        );
        const document = await readJson(dataPath);
        validateStudyData(document, moduleId);
    }
}

export async function validateSamples(root = repoRoot) {
    const validRoot = path.join(root, "contracts", "samples", "valid");
    const invalidRoot = path.join(root, "contracts", "samples", "invalid");
    const validFiles = (await readdir(validRoot))
        .filter((name) => name.startsWith("study-") && name.endsWith(".json"))
        .sort();
    const invalidFiles = (await readdir(invalidRoot))
        .filter((name) => name.startsWith("study-") && name.endsWith(".json"))
        .sort();
    assert(validFiles.length === 4, "contracts/valid", "必须提供四种合法学习数据样例");
    assert(invalidFiles.length >= 2, "contracts/invalid", "至少提供两个非法学习数据样例");
    for (const name of validFiles) {
        validateStudyData(await readJson(path.join(validRoot, name)));
    }
    for (const name of invalidFiles) {
        let rejected = false;
        try {
            validateStudyData(await readJson(path.join(invalidRoot, name)));
        } catch {
            rejected = true;
        }
        assert(rejected, `contracts/invalid/${name}`, "非法样例未被拒绝");
    }
}

export async function main(root = repoRoot) {
    await validateSamples(root);
    await validateModuleFiles(root);
    console.log("学习数据契约通过：模块清单、模块自有数据和合法/非法样例均已校验。");
}

const invokedDirectly = process.argv[1] &&
    pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;

if (invokedDirectly) {
    main().catch((error) => {
        console.error(error.message);
        process.exitCode = 1;
    });
}
