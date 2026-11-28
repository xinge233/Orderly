const SETTINGS_KEY = "orderly.settings.v1";
const MOTION_VALUES = new Set(["gentle", "minimal", "none"]);

function readSettings() {
    try {
        const parsed = JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}");
        return parsed && typeof parsed === "object" && !Array.isArray(parsed)
            ? parsed
            : {};
    } catch {
        return {};
    }
}

function updateSettings(patch) {
    try {
        localStorage.setItem(SETTINGS_KEY, JSON.stringify({
            ...readSettings(),
            ...patch
        }));
    } catch {
        // 预览设置不可写时仍允许刷新页面，由当前界面状态继续工作。
    }
}

function delay(milliseconds) {
    return new Promise((resolve) => {
        window.setTimeout(resolve, Math.max(0, milliseconds));
    });
}

function parseCssDuration(value, fallback) {
    const match = String(value ?? "").trim().match(/^(\d+(?:\.\d+)?)(ms|s)$/i);
    if (!match) {
        return fallback;
    }
    const amount = Number.parseFloat(match[1]);
    return match[2].toLowerCase() === "s" ? amount * 1000 : amount;
}

async function waitForCondition(condition, timeoutMs = 1600) {
    const deadline = performance.now() + timeoutMs;
    while (performance.now() < deadline) {
        if (condition()) {
            return true;
        }
        await delay(40);
    }
    throw new Error("等待 AI 界面状态超时。");
}

async function playAiMotionSequence(button, hint, defaultHint) {
    if (button.dataset.playing === "true") {
        return;
    }
    const dock = document.querySelector("#ai-dock");
    const launcher = document.querySelector("#ai-launcher");
    if (!dock || !launcher) {
        hint.textContent = "未找到 AI Dock，无法播放动效。";
        return;
    }
    if (["planning", "running"].includes(dock.dataset.mode)) {
        hint.textContent = "AI 当前正在执行，未覆盖运行中的界面状态。";
        return;
    }

    const consoleNode = button.closest(".preview-console");
    const toggleButton = consoleNode?.querySelector(".preview-console__toggle");
    const shouldRestoreConsole = window.matchMedia("(max-width: 820px)").matches
        && consoleNode?.dataset.collapsed !== "true";
    if (shouldRestoreConsole) {
        consoleNode.dataset.collapsed = "true";
        if (toggleButton) {
            toggleButton.textContent = "+";
        }
    }
    const root = document.documentElement;
    const previousMotion = root.dataset.motion || "gentle";
    const style = getComputedStyle(dock);
    const transitionMs = Math.max(
        parseCssDuration(style.getPropertyValue("--duration-normal"), 240),
        parseCssDuration(style.getPropertyValue("--duration-slow"), 420)
    );
    button.dataset.playing = "true";
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    hint.textContent = "正在播放 AI 展开与收起动效；不会请求模型或执行 Action。";
    if (previousMotion === "none") {
        root.dataset.motion = "gentle";
    }
    root.dataset.orderlyPreviewAiPlaying = "true";

    let resultHint = "AI 动效播放完成。";
    try {
        if (dock.dataset.panel === "open") {
            document.querySelector("#ai-plan-close")?.click();
        }
        if (dock.dataset.composer === "open") {
            document.querySelector("#ai-composer-close")?.click();
        }
        await waitForCondition(
            () => dock.dataset.panel !== "open" && dock.dataset.composer !== "open",
            1400
        );
        await delay(Math.min(transitionMs, 420));

        launcher.click();
        await waitForCondition(() => dock.dataset.composer === "open", 1600);
        await delay(transitionMs + 520);

        document.querySelector("#ai-composer-close")?.click();
        await waitForCondition(() => dock.dataset.composer !== "open", 1600);
        await delay(Math.min(transitionMs, 420));
    } catch {
        resultHint = "AI 入口当前不可用，未播放动效。";
    } finally {
        delete root.dataset.orderlyPreviewAiPlaying;
        root.dataset.motion = previousMotion;
        button.dataset.playing = "false";
        button.disabled = false;
        button.setAttribute("aria-busy", "false");
        hint.textContent = resultHint;
        if (shouldRestoreConsole && consoleNode?.dataset.collapsed === "true") {
            consoleNode.dataset.collapsed = "false";
            if (toggleButton) {
                toggleButton.textContent = "−";
            }
        }
        window.setTimeout(() => {
            if (button.dataset.playing !== "true") {
                hint.textContent = defaultHint;
            }
        }, 2200);
    }
}

function element(tagName, className = "", text = "") {
    const node = document.createElement(tagName);
    if (className) {
        node.className = className;
    }
    if (text) {
        node.textContent = text;
    }
    return node;
}

function createConsole() {
    const consoleNode = element("aside", "preview-console");
    consoleNode.dataset.collapsed = "false";
    consoleNode.setAttribute("aria-label", "UI 热预览控制台");

    const brand = element("div", "preview-console__brand");
    brand.append(
        element("strong", "", "UI PREVIEW"),
        element("small", "", "源码热更新")
    );

    const themeLabel = element("label", "", "主题");
    const themeSelect = document.createElement("select");
    themeSelect.id = "preview-theme";
    themeSelect.setAttribute("aria-label", "预览主题");
    themeLabel.append(themeSelect);

    const motionLabel = element("label", "", "动效");
    const motionSelect = document.createElement("select");
    motionSelect.id = "preview-motion";
    motionSelect.setAttribute("aria-label", "预览动效档位");
    [
        ["gentle", "灵动"],
        ["minimal", "克制"],
        ["none", "关闭"]
    ].forEach(([value, label]) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        motionSelect.append(option);
    });
    motionLabel.append(motionSelect);

    const actions = element("div", "preview-console__actions");

    const aiMotionButton = element("button", "preview-console__ai-motion", "AI 动效");
    aiMotionButton.type = "button";
    aiMotionButton.dataset.playing = "false";
    aiMotionButton.title = "播放 AI 输入区展开与收起动效，仅操作预览界面";

    const scopesButton = element("button", "preview-console__scopes", "挂点");
    scopesButton.type = "button";
    scopesButton.title = "显示所有 data-motion-scope 挂点";

    const reloadButton = element("button", "preview-console__replay", "重播");
    reloadButton.type = "button";
    reloadButton.title = "刷新页面并重播进入动效";

    const toggleButton = element("button", "preview-console__toggle", "−");
    toggleButton.type = "button";
    toggleButton.title = "收起或展开预览控制台（Alt+P）";
    toggleButton.setAttribute("aria-label", "收起或展开预览控制台");

    const hint = element(
        "p",
        "preview-console__hint",
        "框架样式即时 HMR；主题与模块 CSS 修改后自动刷新。Alt+P 收起。"
    );

    actions.append(aiMotionButton, scopesButton, reloadButton);

    consoleNode.append(
        brand,
        themeLabel,
        motionLabel,
        actions,
        toggleButton,
        hint
    );

    const settings = readSettings();
    motionSelect.value = MOTION_VALUES.has(settings.motion) ? settings.motion : "gentle";

    motionSelect.addEventListener("change", () => {
        updateSettings({ motion: motionSelect.value });
        location.reload();
    });

    themeSelect.addEventListener("change", () => {
        updateSettings({ theme: themeSelect.value });
        location.reload();
    });

    const defaultHint = hint.textContent;
    aiMotionButton.addEventListener("click", () => {
        void playAiMotionSequence(aiMotionButton, hint, defaultHint);
    });

    scopesButton.addEventListener("click", () => {
        const enabled = document.documentElement.dataset.orderlyPreviewScopes !== "true";
        document.documentElement.dataset.orderlyPreviewScopes = String(enabled);
        scopesButton.setAttribute("aria-pressed", String(enabled));
    });
    scopesButton.setAttribute("aria-pressed", "false");

    reloadButton.addEventListener("click", () => location.reload());

    toggleButton.addEventListener("click", () => {
        const collapsed = consoleNode.dataset.collapsed !== "true";
        consoleNode.dataset.collapsed = String(collapsed);
        toggleButton.textContent = collapsed ? "+" : "−";
    });

    return { consoleNode, themeSelect };
}

async function hydrateThemes(themeSelect) {
    try {
        const response = await fetch("/api/themes", {
            headers: { Accept: "application/json" },
            cache: "no-store"
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const payload = await response.json();
        const themes = Array.isArray(payload?.items) ? payload.items : [];
        const settings = readSettings();
        const validThemeIds = new Set(themes.map((theme) => theme.id));
        const selected = validThemeIds.has(settings.theme)
            ? settings.theme
            : themes.find((theme) => theme.default === true)?.id || "";

        themeSelect.replaceChildren();
        if (themes.length === 0) {
            const option = document.createElement("option");
            option.value = "";
            option.textContent = "内置安全外观";
            themeSelect.append(option);
            themeSelect.disabled = true;
            return;
        }
        themes.forEach((theme) => {
            const option = document.createElement("option");
            option.value = theme.id;
            option.textContent = theme.label || theme.id;
            option.selected = theme.id === selected;
            themeSelect.append(option);
        });
    } catch {
        const option = document.createElement("option");
        option.value = "";
        option.textContent = "主题目录不可用";
        themeSelect.replaceChildren(option);
        themeSelect.disabled = true;
    }
}

function install() {
    if (document.querySelector(".preview-console")) {
        return;
    }
    const { consoleNode, themeSelect } = createConsole();
    document.body.append(consoleNode);
    hydrateThemes(themeSelect);

    document.addEventListener("keydown", (event) => {
        if (!event.altKey || event.key.toLowerCase() !== "p") {
            return;
        }
        event.preventDefault();
        const toggle = consoleNode.querySelector(".preview-console__toggle");
        toggle?.click();
    });
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", install, { once: true });
} else {
    install();
}
