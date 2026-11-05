import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
    settingDefaults,
    settingSections,
    themeBrowserColors
} from "../src/config/settings.js";

/**
 * 读取 CSS 文件中某个主题选择器的声明块。
 *
 * @param {string} css 完整 CSS 文本
 * @param {string} theme 主题名
 * @returns {string} 主题声明块
 */
function readThemeBlock(css, theme) {
    const match = css.match(
        new RegExp(`html\\[data-theme="${theme}"\\]\\s*\\{([\\s\\S]*?)\\n\\}`)
    );
    assert.ok(match, `缺少主题令牌块：${theme}`);
    return match[1];
}

test("all setting fields have valid defaults and options", () => {
    const fieldKeys = [];

    for (const section of settingSections) {
        assert.ok(section.fields.length > 0);
        for (const field of section.fields) {
            fieldKeys.push(field.key);
            assert.equal(
                Object.hasOwn(settingDefaults, field.key),
                true,
                `设置 ${field.key} 缺少默认值`
            );

            if (field.type === "toggle") {
                assert.equal(typeof settingDefaults[field.key], "boolean");
                continue;
            }

            assert.ok(Array.isArray(field.options) && field.options.length > 0);
            assert.ok(
                field.options.some((option) => option.value === settingDefaults[field.key]),
                `设置 ${field.key} 的默认值不在候选项中`
            );
            const optionValues = field.options.map((option) => option.value);
            assert.equal(new Set(optionValues).size, optionValues.length);
        }
    }

    assert.equal(new Set(fieldKeys).size, fieldKeys.length);
});

test("every theme has options, browser color and CSS tokens", async () => {
    const themeField = settingSections
        .flatMap((section) => section.fields)
        .find((field) => field.key === "theme");
    assert.ok(themeField);

    const css = await readFile(
        new URL("../styles/tokens.css", import.meta.url),
        "utf8"
    );

    for (const option of themeField.options) {
        assert.match(themeBrowserColors[option.value] ?? "", /^#[0-9a-f]{6}$/i);
        const block = readThemeBlock(css, option.value);
        for (const token of [
            "--canvas",
            "--surface",
            "--text",
            "--accent",
            "--line"
        ]) {
            assert.match(
                block,
                new RegExp(`${token}:\\s*[^;]+;`),
                `主题 ${option.value} 缺少 ${token}`
            );
        }
    }
});
