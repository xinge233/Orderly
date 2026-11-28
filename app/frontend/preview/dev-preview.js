import { icon } from "../src/icons.js";

const SETTINGS_KEY = "orderly.settings.v1";
const MOTION_VALUES = new Set(["gentle", "minimal", "none"]);
const DESKTOP_SIDEBAR_QUERY = "(min-width: 821px)";
const AI_CALL_RUNNING_MS = 1200;
const AI_CALL_RESULT_HOLD_MS = 2600;
const AI_CALL_RESULT_EXIT_MS = 180;

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

function previewModuleButton() {
    const buttons = [...document.querySelectorAll(".nav-item--module[data-module-id]")];
    return buttons.find((button) => button.classList.contains("is-active"))
        || buttons.find((button) => button.getClientRects().length > 0)
        || buttons[0]
        || null;
}

function positionPreviewAiResult(result, button) {
    const sidebar = document.querySelector("#sidebar");
    if (!result || !button?.isConnected || !sidebar
        || !window.matchMedia(DESKTOP_SIDEBAR_QUERY).matches) {
        return false;
    }
    const sidebarRect = sidebar.getBoundingClientRect();
    const rootStyle = getComputedStyle(document.documentElement);
    const collapsedWidth = Number.parseFloat(rootStyle.getPropertyValue("--sidebar-width"))
        || sidebarRect.width;
    const gap = Number.parseFloat(rootStyle.getPropertyValue("--space-2")) || 8;
    const visibleRight = sidebar.classList.contains("is-expanded")
        ? sidebarRect.right
        : Math.min(sidebarRect.right, sidebarRect.left + collapsedWidth);
    const buttonRect = button.getBoundingClientRect();
    result.style.left = `${visibleRight + gap}px`;
    result.style.top = `${buttonRect.top + buttonRect.height / 2}px`;
    return true;
}

async function playAiModuleCallSequence(button, hint, defaultHint) {
    if (button.dataset.playing === "true") {
        return;
    }
    const moduleButton = previewModuleButton();
    const resultLayer = document.querySelector("#nav-ai-result-layer");
    if (!moduleButton || !resultLayer) {
        hint.textContent = "未找到可预览的模块导航，无法播放 AI 调用动效。";
        return;
    }
    if (document.querySelector(".nav-item[data-ai-activity]")) {
        hint.textContent = "侧栏已有 AI 模块调用状态，未叠加预览动效。";
        return;
    }

    const root = document.documentElement;
    const previousMotion = root.dataset.motion || "gentle";
    button.dataset.playing = "true";
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    hint.textContent = "正在预览 AI 调用模块：侧栏进度条后将显示成功标记；不会请求模型或执行 Action。";
    if (previousMotion === "none") {
        root.dataset.motion = "gentle";
    }
    root.dataset.orderlyPreviewAiCallPlaying = "true";

    let progress = null;
    let result = null;
    let openedMobileSidebar = false;
    let resultHint = "AI 调用模块动效播放完成。";
    try {
        const sidebar = document.querySelector("#sidebar");
        if (window.matchMedia("(max-width: 820px)").matches
            && sidebar && !sidebar.classList.contains("is-open")) {
            document.querySelector("#mobile-menu")?.click();
            openedMobileSidebar = sidebar.classList.contains("is-open");
            if (openedMobileSidebar) {
                await delay(220);
            }
        }
        moduleButton.dataset.aiActivity = "running";
        progress = element("span", "nav-ai-progress");
        progress.setAttribute("role", "progressbar");
        const moduleLabel = moduleButton.querySelector(".nav-copy strong")?.textContent?.trim() || "模块";
        progress.setAttribute("aria-label", `AI 正在调用${moduleLabel}`);
        moduleButton.append(progress);
        await delay(AI_CALL_RUNNING_MS);
        progress.remove();

        result = element("span", "nav-ai-result");
        const resultIcon = element("span", "nav-ai-result__icon");
        resultIcon.innerHTML = icon("check-plain", 12);
        result.append(
            resultIcon,
            element("span", "sr-only", `AI 调用${moduleLabel}成功`)
        );
        result.setAttribute("role", "status");
        result.setAttribute("aria-live", "polite");
        result.dataset.phase = "success";
        result.dataset.moduleId = moduleButton.dataset.moduleId;
        moduleButton.dataset.aiActivity = "success";
        if (window.matchMedia(DESKTOP_SIDEBAR_QUERY).matches) {
            positionPreviewAiResult(result, moduleButton);
            resultLayer.append(result);
        } else {
            moduleButton.append(result);
        }
        await delay(AI_CALL_RESULT_HOLD_MS);
        result.classList.add("is-leaving");
        await delay(AI_CALL_RESULT_EXIT_MS);
        result.remove();
    } catch {
        resultHint = "AI 调用动效预览中断，未修改其他界面状态。";
    } finally {
        progress?.remove();
        result?.remove();
        delete moduleButton.dataset.aiActivity;
        delete root.dataset.orderlyPreviewAiCallPlaying;
        root.dataset.motion = previousMotion;
        if (openedMobileSidebar) {
            document.querySelector("#mobile-scrim")?.click();
        }
        button.dataset.playing = "false";
        button.disabled = false;
        button.setAttribute("aria-busy", "false");
        hint.textContent = resultHint;
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

    const aiMotionButton = element("button", "preview-console__ai-motion", "AI 调用");
    aiMotionButton.type = "button";
    aiMotionButton.dataset.playing = "false";
    aiMotionButton.title = "播放 AI 调用模块时的侧栏进度与成功标记动效，仅操作预览界面";

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
        void playAiModuleCallSequence(aiMotionButton, hint, defaultHint);
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
