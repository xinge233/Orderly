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
    assert.equal(captured.options.cache, "no-store");
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

test("API client uses the module governance endpoints and action header", async () => {
    const requests = [];
    const api = new OrderlyApi({
        fetchImpl: async (url, options) => {
            requests.push({ url, options });
            return new Response("{}", { status: 200 });
        }
    });

    await api.getSystemModules();
    await api.getModulePreferences();
    await api.setModuleEnabled("module/todo", false);
    await api.saveSidebarModuleOrder(["module_todo", "module_control"]);
    await api.saveManagementModuleOrder(["module_todo"]);
    await api.saveModuleSettings("module/todo", { compact: true });
    await api.resetModuleSettings("module/todo");
    await api.refreshSystemModules();
    await api.openModuleFolder();
    await api.getModuleInterfaceState();
    await api.setInterfaceAiCallable("module/todo", "query", "todo.list", false);

    assert.deepEqual(requests.map(({ url }) => url), [
        "/api/system-modules",
        "/api/system/module-preferences",
        "/api/modules/module%2Ftodo/activation",
        "/api/system/module-preferences/sidebar-order",
        "/api/system/module-preferences/management-order",
        "/api/modules/module%2Ftodo/settings",
        "/api/modules/module%2Ftodo/settings/reset",
        "/api/system/modules/refresh",
        "/api/system/module-folder/open",
        "/api/system/module-interfaces",
        "/api/modules/module%2Ftodo/interfaces/query/todo.list/ai-callable"
    ]);
    assert.deepEqual(
        requests.slice(2, 7).map(({ options }) => [
            options.method,
            options.body ? JSON.parse(options.body) : null
        ]),
        [
            ["PATCH", { enabled: false }],
            ["PUT", { moduleIds: ["module_todo", "module_control"] }],
            ["PUT", { moduleIds: ["module_todo"] }],
            ["PUT", { compact: true }],
            ["POST", null]
        ]
    );
    assert.deepEqual(
        requests.slice(-1).map(({ options }) => [
            options.method,
            JSON.parse(options.body)
        ]),
        [["PATCH", { aiCallable: false }]]
    );
    assert.equal(requests[7].options.method, "POST");
    assert.equal(
        requests[8].options.headers["X-Orderly-Action"],
        "open-module-folder"
    );
});

test("API client reads and prioritizes background module builds", async () => {
    const requests = [];
    const api = new OrderlyApi({
        fetchImpl: async (url, options = {}) => {
            requests.push({ url, options });
            return new Response("{}", { status: 200 });
        }
    });

    await api.getModuleBuilds();
    await api.prioritizeModuleBuild("module_todo");

    assert.deepEqual(requests.map(({ url }) => url), [
        "/api/system/module-builds",
        "/api/system/module-builds/prioritize"
    ]);
    assert.equal(requests[1].options.method, "POST");
    assert.deepEqual(JSON.parse(requests[1].options.body), {
        sourceName: "module_todo"
    });
});

test("API client requests host shutdown with a non-simple action header", async () => {
    let captured;
    const api = new OrderlyApi({
        fetchImpl: async (url, options) => {
            captured = { url, options };
            return new Response(JSON.stringify({
                status: "STOPPING",
                message: "正在停止本地宿主与相关进程"
            }), {
                status: 202,
                headers: { "Content-Type": "application/json" }
            });
        }
    });

    const response = await api.shutdownHost();

    assert.equal(captured.url, "/api/system/shutdown");
    assert.equal(captured.options.method, "POST");
    assert.equal(captured.options.headers["X-Orderly-Action"], "shutdown");
    assert.equal(captured.options.body, undefined);
    assert.equal(response.status, "STOPPING");
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

test("API client addresses module data and control actions", async () => {
    const requests = [];
    const api = new OrderlyApi({
        fetchImpl: async (url, options = {}) => {
            requests.push({ url, options });
            return new Response("{}", { status: 200 });
        }
    });

    assert.equal(
        api.moduleDataUrl("module_calendar", "spaces/one/media.png"),
        "/api/modules/module_calendar/data/spaces/one/media.png"
    );
    await api.executeModuleAction(
        "module_calendar",
        "calendar.todo.complete",
        { id: "todo-1" },
        true
    );

    assert.equal(
        requests[0].url,
        "/api/modules/module_calendar/actions/calendar.todo.complete"
    );
    assert.equal(requests[0].options.method, "POST");
    assert.deepEqual(JSON.parse(requests[0].options.body), {
        confirmed: true,
        arguments: { id: "todo-1" }
    });
});

test("API client uses independent AI settings, turns and plan confirmation endpoints", async () => {
    const requests = [];
    const api = new OrderlyApi({
        fetchImpl: async (url, options = {}) => {
            requests.push({ url, options });
            return new Response("{}", { status: 200 });
        }
    });

    await api.getAiSettings();
    await api.saveAiSettings({
        mode: "DEEPSEEK",
        apiKey: "temporary-key",
        baseUrl: null,
        model: null,
        allowDeleteDirect: false,
        allowSendQueryResultsToModel: true
    });
    await api.testAiSettings();
    await api.interpretAiTurn({
        goal: "创建归档目录",
        conversationId: null
    });
    await api.confirmAiPlan("ai/plan?1");
    await api.clearAiSettings();

    assert.deepEqual(requests.map(({ url }) => url), [
        "/api/ai/settings",
        "/api/ai/settings",
        "/api/ai/settings/test",
        "/api/ai/turns",
        "/api/ai/plans/ai%2Fplan%3F1/confirm",
        "/api/ai/settings"
    ]);
    assert.deepEqual(
        requests.map(({ options }) => options.method),
        ["GET", "PUT", "POST", "POST", "POST", "DELETE"]
    );
    assert.deepEqual(JSON.parse(requests[3].options.body), {
        goal: "创建归档目录",
        conversationId: null
    });
    assert.deepEqual(JSON.parse(requests[1].options.body), {
        mode: "DEEPSEEK",
        apiKey: "temporary-key",
        baseUrl: null,
        model: null,
        allowDeleteDirect: false,
        allowSendQueryResultsToModel: true
    });
});
