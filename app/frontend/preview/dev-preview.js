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

    const scopesButton = element("button", "preview-console__scopes", "挂点");
    scopesButton.type = "button";
    scopesButton.title = "显示所有 data-motion-scope 挂点";

    const reloadButton = element("button", "", "重播");
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

    consoleNode.append(
        brand,
        themeLabel,
        motionLabel,
        scopesButton,
        reloadButton,
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
