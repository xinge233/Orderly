import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("module_fs uses host file-root authorization and tokenized reads", async () => {
    const moduleUrl = new URL(
        "../../main/resources/orderly-ui/module.js",
        import.meta.url
    );
    const cssUrl = new URL(
        "../../main/resources/orderly-ui/module.css",
        import.meta.url
    );
    const manifestUrl = new URL(
        "../../main/resources/orderly-ui/module.json",
        import.meta.url
    );
    const [moduleSource, css, manifestText] = await Promise.all([
        readFile(moduleUrl, "utf8"),
        readFile(cssUrl, "utf8"),
        readFile(manifestUrl, "utf8")
    ]);
    const manifest = JSON.parse(manifestText);

    assert.match(moduleSource, /context\.fileRoots\.authorize\(/);
    assert.match(moduleSource, /context\.query\("fs\.roots"\)/);
    assert.match(moduleSource, /context\.query\("fs\.list"/);
    assert.match(moduleSource, /context\.fileRoots\.grantRead\(/);
    assert.match(moduleSource, /context\.fileRoots\.revoke\(/);
    assert.match(moduleSource, /context\.aiRiskPolicy\.get\(/);
    assert.match(moduleSource, /context\.aiRiskPolicy\.set\(/);
    assert.match(moduleSource, /AI 文件操作风险上限/);
    assert.match(moduleSource, /make\("button", "fs-ui-ai-risk"\)/);
    assert.match(moduleSource, /function cycleAiRiskLevel\(\)/);
    assert.match(moduleSource, /aiRiskControl\.addEventListener\("click", cycleAiRiskLevel\)/);
    assert.doesNotMatch(moduleSource, /fs-ui-ai-risk__select|make\("select"/);
    assert.match(moduleSource, /label: "删除"/);
    assert.match(moduleSource, /requestAiRiskConfirmation/);
    assert.match(moduleSource, /以后切换 AI 风险上限时不再提醒/);
    assert.match(moduleSource, /saveAiRiskReminderPreference\(false\)/);
    assert.match(moduleSource, /context\.saveSettings\(/);
    assert.doesNotMatch(moduleSource, /确认将 AI 风险上限设为/);
    assert.match(moduleSource, /只读/);
    assert.match(moduleSource, /写入/);
    assert.match(moduleSource, /宿主返回了无效的 AI 风险上限/);
    assert.match(moduleSource, /context\.query\("fs\.readGrantedText"/);
    assert.match(moduleSource, /context\.openAiWithText\(/);
    assert.doesNotMatch(moduleSource, /context\.fillCommand\(/);
    assert.match(moduleSource, /context\.executeActionWithFile\("fs\.upload"/);
    assert.match(moduleSource, /context\.executeAction\("fs\.trash"/);
    assert.match(moduleSource, /context\.executeAction\("fs\.restore"/);
    assert.doesNotMatch(moduleSource, /localStorage|sessionStorage/);
    assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b/i);
    assert.doesNotMatch(css, /rgb\(|hsl\(/i);
    assert.match(css, /\.fs-ui-ai-risk__track::before\s*\{[\s\S]*?transform:\s*translateX\(calc\(var\(--fs-risk-index\) \* 100%\)\);/);
    assert.match(css, /transform var\(--duration-normal\) var\(--ease-spring\)/);
    assert.match(css, /\.fs-ui-risk-dialog\s*\{[\s\S]*?position:\s*fixed;/);
    assert.match(css, /\.fs-ui-risk-dialog\[open\]\s*\{[\s\S]*?display:\s*grid;/);
    assert.match(
        css,
        /@media \(min-width: 1560px\)\s*\{\s*\.fs-ui-layout\s*\{[\s\S]*?grid-template-columns:\s*minmax\(220px,\s*240px\)\s*minmax\(0,\s*1fr\)\s*minmax\(320px,\s*360px\);/s
    );
    assert.match(css, /\.fs-ui-table th:first-child,[\s\S]*?width:\s*48%;/);
    assert.match(css, /\.fs-ui-table th:nth-child\(4\),[\s\S]*?width:\s*26%;/);
    assert.match(css, /\.fs-ui-table tbody tr\s*\{\s*height:\s*44px;\s*\}/);
    const riskReminderSetting = manifest.settings.find(
        (setting) => setting.key === "aiRiskChangeReminderEnabled"
    );
    assert.equal(riskReminderSetting?.type, "boolean");
    assert.equal(riskReminderSetting?.default, true);
    assert.equal(manifest.view.caption, "文件管理器");
    assert.equal(manifest.card.queryId, "fs.roots");
});
