import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("file root authorization stays a local host API and is exposed through mount context", async () => {
    const apiSource = await readFile(new URL("../src/api.js", import.meta.url), "utf8");
    const appSource = await readFile(new URL("../src/app.js", import.meta.url), "utf8");

    assert.match(apiSource, /getFileRoots\(options\)/);
    assert.match(apiSource, /authorizeFileRoot\(path, label = "", allowWrite = false, allowDelete = false/);
    assert.match(apiSource, /X-Orderly-Action": "authorize-file-root"/);
    assert.match(apiSource, /revokeFileRoot\(rootId, options = \{\}\)/);
    assert.match(apiSource, /X-Orderly-Action": "revoke-file-root"/);
    assert.match(apiSource, /grantFileRead\(rootId, path, purpose = "preview"/);
    assert.match(apiSource, /X-Orderly-Action": "grant-file-read"/);
    assert.match(apiSource, /confirmed: true/);
    assert.match(appSource, /fileRoots: \{/);
    assert.match(appSource, /authorize: \(path, label, allowWrite, allowDelete, options\)/);
    assert.match(appSource, /revoke: \(rootId, options\) => api\.revokeFileRoot/);
    assert.match(appSource, /grantRead: \(rootId, path, purpose, options\)/);
    assert.match(appSource, /openAiWithText: \(text = ""\) => aiDialog\?\.openWithText/);
    assert.doesNotMatch(apiSource, /module_fs/);
});
