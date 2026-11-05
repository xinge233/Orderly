import { appendFile, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

/**
 * 无第三方依赖的浏览器冒烟检查。
 *
 * 使用本机 Microsoft Edge 的 Chromium DevTools Protocol，验证页面脚本是否真正启动，
 * 并覆盖主题、设置、导航、聊天占位和窄屏横向溢出。截图保存在被测应用的 target
 * 目录中，只作为本次验证产物，不进入源码交付。
 */

const APP_URL = process.env.ORDERLY_APP_URL ?? "http://127.0.0.1:8080/";
const ARTIFACT_DIR = process.env.ORDERLY_ARTIFACT_DIR
    ?? join(process.cwd(), "..", "backend", "target", "verification");
const EXTERNAL_CDP_PORT = Number(process.env.ORDERLY_CDP_PORT ?? 0);
const DEBUG_LOG = join(ARTIFACT_DIR, "browser-smoke.log");

/**
 * 在自动化失败时写入分阶段日志，避免只得到无输出的进程退出。
 *
 * @param {string} message 阶段说明
 */
async function debug(message) {
    if (process.env.ORDERLY_SMOKE_DEBUG !== "1") {
        return;
    }
    await appendFile(DEBUG_LOG, `${new Date().toISOString()} ${message}\n`, "utf8");
}

/**
 * 等待条件成立，超时后返回明确的错误，避免无限挂起。
 *
 * @param {() => Promise<boolean>} predicate 条件
 * @param {string} description 错误描述
 * @param {number} timeoutMs 最大等待时间
 */
async function waitUntil(predicate, description, timeoutMs = 10_000) {
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeoutMs) {
        if (await predicate()) {
            return;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error(`等待超时：${description}`);
}

class CdpClient {
    #socket;
    #nextId = 1;
    #pending = new Map();
    #listeners = new Map();
    errors = [];

    /**
     * @param {string} webSocketUrl DevTools WebSocket 地址
     */
    constructor(webSocketUrl) {
        this.#socket = new WebSocket(webSocketUrl);
    }

    async connect() {
        await new Promise((resolve, reject) => {
            let settled = false;
            const finish = (callback, value) => {
                if (settled) {
                    return;
                }
                settled = true;
                clearTimeout(timeout);
                callback(value);
            };
            const timeout = setTimeout(() => {
                finish(reject, new Error("连接 Edge DevTools 超时"));
            }, 5_000);

            this.#socket.addEventListener(
                "open",
                () => finish(resolve),
                { once: true }
            );
            this.#socket.addEventListener(
                "error",
                () => finish(reject, new Error("连接 Edge DevTools 失败")),
                { once: true }
            );
            this.#socket.addEventListener(
                "close",
                () => finish(reject, new Error("Edge DevTools 连接提前关闭")),
                { once: true }
            );
        });

        this.#socket.addEventListener("message", (event) => {
            const message = JSON.parse(event.data);

            if (message.id) {
                const pending = this.#pending.get(message.id);
                if (!pending) {
                    return;
                }
                this.#pending.delete(message.id);
                if (message.error) {
                    pending.reject(new Error(message.error.message));
                } else {
                    pending.resolve(message.result);
                }
                return;
            }

            // 浏览器异常和 console.error 都视为冒烟失败信号。
            if (message.method === "Runtime.exceptionThrown") {
                this.errors.push(
                    message.params.exceptionDetails?.exception?.description
                    ?? message.params.exceptionDetails?.text
                    ?? "未知浏览器异常"
                );
            }
            if (
                message.method === "Runtime.consoleAPICalled"
                && message.params.type === "error"
            ) {
                this.errors.push(
                    message.params.args
                        .map((argument) => argument.value ?? argument.description ?? "")
                        .join(" ")
                );
            }

            const eventListeners = this.#listeners.get(message.method) ?? [];
            eventListeners.forEach((listener) => listener(message.params));
        });
    }

    /**
     * 发送 CDP 命令并等待结果。
     *
     * @param {string} method 命令名称
     * @param {Record<string, unknown>} params 命令参数
     * @returns {Promise<unknown>}
     */
    send(method, params = {}) {
        const id = this.#nextId++;
        return new Promise((resolve, reject) => {
            this.#pending.set(id, { resolve, reject });
            this.#socket.send(JSON.stringify({ id, method, params }));
        });
    }

    /**
     * 监听一次 CDP 事件。
     *
     * @param {string} method 事件名称
     * @param {number} timeoutMs 最大等待时间
     */
    waitForEvent(method, timeoutMs = 10_000) {
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error(`等待事件超时：${method}`)), timeoutMs);
            const listener = (params) => {
                clearTimeout(timeout);
                const listeners = this.#listeners.get(method) ?? [];
                this.#listeners.set(
                    method,
                    listeners.filter((candidate) => candidate !== listener)
                );
                resolve(params);
            };
            const listeners = this.#listeners.get(method) ?? [];
            this.#listeners.set(method, [...listeners, listener]);
        });
    }

    /** 关闭 DevTools WebSocket，不负责终止浏览器进程。 */
    close() {
        this.#socket.close();
    }
}

/**
 * 在页面上下文执行表达式并返回可序列化结果。
 *
 * @param {CdpClient} client CDP 客户端
 * @param {string} expression JavaScript 表达式
 */
async function evaluate(client, expression) {
    const response = await client.send("Runtime.evaluate", {
        expression,
        awaitPromise: true,
        returnByValue: true
    });

    if (response.exceptionDetails) {
        throw new Error(
            response.exceptionDetails.exception?.description
            ?? response.exceptionDetails.text
        );
    }
    return response.result?.value;
}

/**
 * 截取当前页面并写入验证产物目录。
 *
 * @param {CdpClient} client CDP 客户端
 * @param {string} filename 文件名
 */
async function capture(client, filename) {
    const result = await client.send("Page.captureScreenshot", {
        format: "png",
        fromSurface: true,
        captureBeyondViewport: false
    });
    await writeFile(join(ARTIFACT_DIR, filename), Buffer.from(result.data, "base64"));
}

async function main() {
    await debug("main:start");
    await mkdir(ARTIFACT_DIR, { recursive: true });
    if (!Number.isInteger(EXTERNAL_CDP_PORT) || EXTERNAL_CDP_PORT <= 0) {
        throw new Error(
            "缺少 ORDERLY_CDP_PORT。请先启动带 --remote-debugging-port 的本地 Edge，"
            + "再运行本检查。"
        );
    }
    await debug(`cdp:port ${EXTERNAL_CDP_PORT}`);

    try {
        let targets = [];
        await waitUntil(async () => {
            try {
                const response = await fetch(`http://127.0.0.1:${EXTERNAL_CDP_PORT}/json/list`);
                targets = response.ok ? await response.json() : [];
                return targets.some((target) => target.type === "page");
            } catch {
                return false;
            }
        }, "Edge DevTools 启动");
        await debug(`cdp:ready targets=${targets.length}`);

        const target = targets.find(
            (candidate) => candidate.type === "page" && candidate.url === "about:blank"
        ) ?? targets.find((candidate) => candidate.type === "page");
        if (!target) {
            throw new Error("未找到可测试的浏览器页面");
        }
        await debug(`cdp:target ${target.id}`);
        const client = new CdpClient(target.webSocketDebuggerUrl);
        await client.connect();
        await debug("cdp:connected");
        await client.send("Page.enable");
        await client.send("Runtime.enable");
        await client.send("Emulation.setDeviceMetricsOverride", {
            width: 1440,
            height: 960,
            deviceScaleFactor: 1,
            mobile: false
        });

        const loaded = client.waitForEvent("Page.loadEventFired");
        await client.send("Page.navigate", { url: APP_URL });
        await loaded;
        await waitUntil(
            async () => (await evaluate(client, "document.readyState")) === "complete",
            "页面加载完成"
        );
        await debug("page:loaded");

        const initial = await evaluate(client, `(() => ({
            title: document.title,
            heading: document.querySelector(".hero-panel h1")?.innerText ?? "",
            navigationCount: document.querySelectorAll("[data-navigation-id]").length,
            sidebarOverflow: getComputedStyle(document.querySelector("#sidebar-navigation")).overflowY,
            settingsInsideViewport:
                document.querySelector("#settings-trigger").getBoundingClientRect().bottom <= innerHeight,
            settingsOutsideNavigationScroll:
                !document.querySelector("#sidebar-navigation")
                    .contains(document.querySelector("#settings-trigger")),
            horizontalOverflow: document.documentElement.scrollWidth > innerWidth
        }))()`);

        if (!initial.title.includes("归序")) throw new Error("页面标题未加载");
        if (initial.navigationCount < 6) throw new Error("导航配置未完整渲染");
        if (initial.sidebarOverflow !== "auto") throw new Error("侧边栏未启用滚动");
        if (!initial.settingsInsideViewport) throw new Error("设置入口超出视口");
        if (!initial.settingsOutsideNavigationScroll) throw new Error("设置入口进入了导航滚动区");
        if (initial.horizontalOverflow) throw new Error("桌面布局出现横向溢出");

        await capture(client, "orderly-desktop.png");

        await evaluate(client, `document.querySelector("#settings-trigger").click()`);
        const settingsVisible = await evaluate(
            client,
            `document.querySelector("#settings-dialog").open`
        );
        if (!settingsVisible) throw new Error("设置对话框未打开");
        await capture(client, "orderly-settings.png");

        await evaluate(client, `(() => {
            const theme = document.querySelector('input[name="theme"][value="midnight"]');
            theme.click();
            return true;
        })()`);
        await waitUntil(
            async () => (await evaluate(client, "document.documentElement.dataset.theme")) === "midnight",
            "主题切换"
        );

        const persistedTheme = await evaluate(
            client,
            `JSON.parse(localStorage.getItem("orderly.ui.settings.v1")).theme`
        );
        if (persistedTheme !== "midnight") throw new Error("主题偏好未持久化");
        await capture(client, "orderly-midnight.png");

        await evaluate(client, `(() => {
            document.querySelector("#settings-close").click();
            document.querySelector('[data-navigation-id="lab"]').click();
            return true;
        })()`);
        await waitUntil(
            async () => (await evaluate(
                client,
                `document.querySelector(".page-heading h1")?.textContent.trim()`
            )) === "空功能试验台",
            "模块试验台导航"
        );
        await waitUntil(
            async () => (await evaluate(
                client,
                `document.querySelector("[data-contract-module-id]")?.textContent.trim()`
            )) === "sample-empty",
            "固定模块目录契约加载"
        );

        const contractStatus = await evaluate(
            client,
            `document.querySelector("[data-contract-status]")?.textContent ?? ""`
        );
        if (!contractStatus.includes("契约 v1.0")) {
            throw new Error("模块试验台未显示版本化契约状态");
        }

        // 切换到一个静态占位视图，再回到异步视图，验证两种生命周期视图可交替使用。
        await evaluate(client, `document.querySelector('[data-navigation-id="files"]').click()`);
        await waitUntil(
            async () => (await evaluate(
                client,
                `document.querySelector(".page-heading h1")?.textContent.trim()`
            )) === "文件与目录",
            "静态占位视图切换"
        );
        await evaluate(client, `document.querySelector('[data-navigation-id="lab"]').click()`);
        await waitUntil(
            async () => (await evaluate(
                client,
                `document.querySelector("[data-contract-module-id]")?.textContent.trim()`
            )) === "sample-empty",
            "异步视图重新挂载"
        );

        await evaluate(client, `(() => {
            const input = document.querySelector("#chat-input");
            input.value = "整理测试目录";
            input.dispatchEvent(new Event("input", { bubbles: true }));
            document.querySelector("#send-button").click();
            return true;
        })()`);
        await waitUntil(
            async () => (await evaluate(
                client,
                `document.querySelector("#toast").classList.contains("is-visible")`
            )),
            "聊天占位反馈"
        );

        const toastText = await evaluate(client, `document.querySelector("#toast").textContent`);
        if (!toastText.includes("尚未接入")) throw new Error("聊天占位提示不明确");

        await client.send("Emulation.setDeviceMetricsOverride", {
            width: 480,
            height: 900,
            deviceScaleFactor: 1,
            mobile: true
        });
        await new Promise((resolve) => setTimeout(resolve, 250));

        const mobile = await evaluate(client, `(() => ({
            width: innerWidth,
            sidebarWidth: Math.round(document.querySelector(".sidebar").getBoundingClientRect().width),
            horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
            composerVisible:
                document.querySelector(".composer").getBoundingClientRect().width > 220,
            chatDoesNotOverlapMain:
                document.querySelector(".chat-dock").getBoundingClientRect().top
                >= document.querySelector("#main-stage").getBoundingClientRect().bottom - 1
        }))()`);

        if (mobile.sidebarWidth > 76) throw new Error("窄屏侧边栏未收束");
        if (mobile.horizontalOverflow) throw new Error("窄屏出现横向溢出");
        if (!mobile.composerVisible) throw new Error("窄屏输入栏宽度异常");
        if (!mobile.chatDoesNotOverlapMain) throw new Error("窄屏聊天栏遮挡主内容");
        await capture(client, "orderly-mobile.png");

        await evaluate(client, `document.querySelector("#settings-trigger").click()`);
        const mobileSettings = await evaluate(client, `(() => {
            const dialog = document.querySelector("#settings-dialog");
            const box = dialog.getBoundingClientRect();
            return {
                open: dialog.open,
                fitsViewport:
                    box.left >= 0 && box.top >= 0
                    && box.right <= innerWidth && box.bottom <= innerHeight,
                panelOverflow: getComputedStyle(document.querySelector("#settings-panel")).overflowY
            };
        })()`);
        if (!mobileSettings.open) throw new Error("窄屏设置对话框未打开");
        if (!mobileSettings.fitsViewport) throw new Error("窄屏设置对话框超出视口");
        if (mobileSettings.panelOverflow !== "auto") throw new Error("窄屏设置内容不可滚动");
        await capture(client, "orderly-mobile-settings.png");
        await evaluate(client, `document.querySelector("#settings-close").click()`);

        if (client.errors.length > 0) {
            throw new Error(`浏览器控制台错误：\n${client.errors.join("\n")}`);
        }

        await debug("smoke:passed");
        console.log(JSON.stringify({
            status: "passed",
            initial,
            theme: "midnight",
            navigation: "空功能试验台",
            mobile,
            screenshots: [
                join(ARTIFACT_DIR, "orderly-desktop.png"),
                join(ARTIFACT_DIR, "orderly-settings.png"),
                join(ARTIFACT_DIR, "orderly-midnight.png"),
                join(ARTIFACT_DIR, "orderly-mobile.png"),
                join(ARTIFACT_DIR, "orderly-mobile-settings.png")
            ]
        }, null, 2));

        client.close();
    } finally {
        await debug("cleanup:start");
    }
}

main()
    .then(() => {
        // 让 stdout 有机会刷新，再显式结束 Node 进程。
        setTimeout(() => process.exit(0), 50);
    })
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
