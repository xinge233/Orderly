import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    AI_MODES,
    normalizeAiSettings,
    validateAiSettingsDraft
} from "../src/ai-settings.js";

test("AI settings expose DeepSeek and custom modes", () => {
    assert.deepEqual(AI_MODES.map((item) => item.value), ["DEEPSEEK", "CUSTOM"]);
});

test("AI settings never trust or require a returned API key", () => {
    const settings = normalizeAiSettings({
        mode: "DEEPSEEK",
        baseUrl: "https://api.deepseek.com",
        model: "deepseek-flash",
        configured: true,
        allowDeleteDirect: false,
        allowLocalProvider: false,
        apiKey: "must-not-be-used"
    });

    assert.equal(settings.configured, true);
    assert.equal(Object.hasOwn(settings, "apiKey"), false);
});

test("custom AI settings require HTTPS and a model", () => {
    assert.match(
        validateAiSettingsDraft({
            mode: "CUSTOM",
            apiKey: "temporary-key",
            baseUrl: "http://example.com/v1",
            model: "custom-model"
        }),
        /HTTPS/
    );
    assert.match(
        validateAiSettingsDraft({
            mode: "CUSTOM",
            apiKey: "temporary-key",
            baseUrl: "https://example.com/v1",
            model: ""
        }),
        /Model/
    );
    assert.equal(
        validateAiSettingsDraft({
            mode: "DEEPSEEK",
            apiKey: "temporary-key",
            baseUrl: "",
            model: ""
        }),
        ""
    );
});

test("configured AI settings can retain the existing temporary key", () => {
    assert.equal(
        validateAiSettingsDraft({
            mode: "DEEPSEEK",
            apiKey: "",
            baseUrl: "",
            model: ""
        }, {
            configured: true
        }),
        ""
    );
    assert.match(
        validateAiSettingsDraft({
            mode: "DEEPSEEK",
            apiKey: "",
            baseUrl: "",
            model: ""
        }),
        /API Key/
    );
});

test("custom localhost HTTP is available only with the development switch", () => {
    const draft = {
        mode: "CUSTOM",
        apiKey: "temporary-key",
        baseUrl: "http://127.0.0.1:18080/v1",
        model: "custom-model"
    };
    assert.match(validateAiSettingsDraft(draft), /HTTPS/);
    assert.equal(
        validateAiSettingsDraft(draft, { allowLocalProvider: true }),
        ""
    );
});

test("settings dialog exposes a dedicated AI partition", async () => {
    const app = await readFile(new URL("../src/app.js", import.meta.url), "utf8");
    assert.match(app, /id: "ai", label: "AI 接入"/);
    assert.match(app, /function renderAiSettings/);
    assert.match(app, /api\.saveAiSettings/);
    assert.match(app, /api\.testAiSettings/);
    assert.match(app, /allowDeleteDirect/);
});
