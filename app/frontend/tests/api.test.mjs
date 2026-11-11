import assert from "node:assert/strict";
import test from "node:test";

import { ApiError, OrderlyApi } from "../src/api.js";

test("API client sends JSON and returns parsed payload", async () => {
    let captured;
    const api = new OrderlyApi({
        fetchImpl: async (url, options) => {
            captured = { url, options };
            return new Response(JSON.stringify({ planId: "p-1", kind: "PLAN" }), {
                status: 200,
                headers: { "Content-Type": "application/json" }
            });
        }
    });

    const result = await api.draftPlan("创建目录 归档");

    assert.equal(captured.url, "/api/plans");
    assert.equal(captured.options.method, "POST");
    assert.equal(captured.options.headers["Content-Type"], "application/json");
    assert.deepEqual(JSON.parse(captured.options.body), { goal: "创建目录 归档" });
    assert.equal(result.planId, "p-1");
});

test("API client wraps structured server errors", async () => {
    const api = new OrderlyApi({
        fetchImpl: async () => new Response(
            JSON.stringify({ message: "计划尚未确认" }),
            { status: 409, headers: { "Content-Type": "application/json" } }
        )
    });

    await assert.rejects(
        () => api.confirmPlan("p-1", { confirmed: false, confirmation: "" }),
        (error) => {
            assert.ok(error instanceof ApiError);
            assert.equal(error.status, 409);
            assert.equal(error.message, "计划尚未确认");
            return true;
        }
    );
});

test("API client turns transport failures into a local-service message", async () => {
    const api = new OrderlyApi({
        fetchImpl: async () => {
            throw new TypeError("fetch failed");
        }
    });

    await assert.rejects(
        () => api.health(),
        (error) => error instanceof ApiError
            && error.message.includes("无法连接本地服务")
    );
});

test("API client percent-encodes plan ids", async () => {
    let requestedUrl = "";
    const api = new OrderlyApi({
        fetchImpl: async (url) => {
            requestedUrl = url;
            return new Response("{}", { status: 200 });
        }
    });

    await api.rollbackPlan("plan/a?b");
    assert.equal(requestedUrl, "/api/plans/plan%2Fa%3Fb/rollback");
});

test("API client reads dynamic module and theme catalogs", async () => {
    const urls = [];
    const api = new OrderlyApi({
        fetchImpl: async (url) => {
            urls.push(url);
            return new Response("[]", { status: 200 });
        }
    });

    await api.getModules();
    await api.getThemes();
    await api.getTheme("theme/a");

    assert.deepEqual(urls, ["/api/modules", "/api/themes", "/api/themes/theme%2Fa"]);
});

test("API client addresses module UI and generic queries", async () => {
    const urls = [];
    const api = new OrderlyApi({
        fetchImpl: async (url) => {
            urls.push(url);
            return new Response("{}", { status: 200 });
        }
    });

    await api.getModuleUi("module demo");
    await api.queryModule("module demo", "demo.list", { path: "归档" });
    assert.deepEqual(urls, [
        "/api/modules/module%20demo/ui/module.json",
        "/api/modules/module%20demo/query/demo.list?path=%E5%BD%92%E6%A1%A3"
    ]);
    assert.equal(
        api.moduleAssetUrl("module demo", "module.js"),
        "/api/modules/module%20demo/ui/module.js"
    );
});
