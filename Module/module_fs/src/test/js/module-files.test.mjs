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
    assert.match(moduleSource, /context\.query\("fs\.readGrantedText"/);
    assert.match(moduleSource, /context\.openAiWithText\(/);
    assert.doesNotMatch(moduleSource, /context\.fillCommand\(/);
    assert.match(moduleSource, /context\.executeActionWithFile\("fs\.upload"/);
    assert.match(moduleSource, /context\.executeAction\("fs\.trash"/);
    assert.match(moduleSource, /context\.executeAction\("fs\.restore"/);
    assert.doesNotMatch(moduleSource, /localStorage|sessionStorage/);
    assert.doesNotMatch(css, /#[0-9a-f]{3,8}\b/i);
    assert.doesNotMatch(css, /rgb\(|hsl\(/i);
    assert.equal(manifest.view.caption, "文件管理器");
    assert.equal(manifest.card.queryId, "fs.roots");
});
