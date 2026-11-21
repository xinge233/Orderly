import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { OrderlyApi } from "../src/api.js";
import { normalizeBuildSettings } from "../src/settings.js";

test("build setting normalization accepts only the host-supported range", () => {
    assert.deepEqual(normalizeBuildSettings({
        maxParallel: 3,
        minimum: 1,
        maximum: 4
    }), {
        maxParallel: 3,
        minimum: 1,
        maximum: 4
    });
    assert.deepEqual(normalizeBuildSettings({
        maxParallel: 9,
        minimum: 1,
        maximum: 4
    }, 2), {
        maxParallel: 2,
        minimum: 1,
        maximum: 4
    });
});

test("API client updates build concurrency with an explicit action header", async () => {
    let captured;
    const api = new OrderlyApi({
        fetchImpl: async (url, options) => {
            captured = { url, options };
            return new Response(JSON.stringify({
                maxParallel: 3,
                minimum: 1,
                maximum: 4
            }), {
                status: 200,
                headers: { "Content-Type": "application/json" }
            });
        }
    });

    const result = await api.setBuildConcurrency(3);

    assert.equal(captured.url, "/api/system/build-settings");
    assert.equal(captured.options.method, "PUT");
    assert.equal(captured.options.headers["X-Orderly-Action"], "update-build-settings");
    assert.deepEqual(JSON.parse(captured.options.body), { maxParallel: 3 });
    assert.equal(result.maxParallel, 3);
});

test("system settings render a host-backed 1-4 build concurrency control", async () => {
    const app = await readFile(new URL("../src/app.js", import.meta.url), "utf8");
    const appCss = await readFile(new URL("../styles/app.css", import.meta.url), "utf8");

    assert.match(app, /模块构建并发/);
    assert.match(app, /api\.setBuildConcurrency\(value\)/);
    assert.match(app, /state\.buildSettings\.minimum/);
    assert.match(app, /state\.buildSettings\.maximum/);
    assert.match(app, /segmented segmented--four/);
    assert.match(appCss, /\.segmented--four\s*\{[\s\S]*?repeat\(4, minmax\(0, 1fr\)\)/);
});
