/**
 * 文件管理器视图。
 *
 * 所有真实文件访问经模块 Query/Action 和宿主运行时完成。界面只持有根 ID、根内相对
 * 路径和短时读取令牌，不自行拼接绝对路径，也不把文件内容写入本地存储。
 */

const TEXT_EXTENSIONS = new Set([
    "txt", "md", "json", "jsonl", "csv", "tsv", "log", "xml", "yaml", "yml",
    "html", "htm", "css", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java",
    "py", "ps1", "cmd", "bat", "sh", "sql", "ini", "toml"
]);

function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value : fallback;
}

function joinPath(parent, name) {
    const parts = [parent, name]
        .flatMap((value) => String(value ?? "").split(/[\\/]+/))
        .filter(Boolean);
    return parts.join("/");
}

function parentPath(path) {
    const parts = String(path ?? "").split("/").filter(Boolean);
    parts.pop();
    return parts.join("/");
}

function extensionOf(name) {
    const value = String(name ?? "");
    const dot = value.lastIndexOf(".");
    return dot < 0 ? "" : value.slice(dot + 1).toLowerCase();
}

function isTextEntry(entry) {
    return entry && !entry.directory && TEXT_EXTENSIONS.has(extensionOf(entry.name));
}

const READABLE_DOCUMENT_EXTENSIONS = new Set([
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "png", "jpg", "jpeg", "bmp", "tif", "tiff", "webp"
]);

function isReadableEntry(entry) {
    return isTextEntry(entry)
        || (entry && !entry.directory && READABLE_DOCUMENT_EXTENSIONS.has(extensionOf(entry.name)));
}

const AI_RISK_OPTIONS = [
    {
        value: "READ_ONLY",
        label: "只读",
        description: "AI 可查询、浏览和读取文件，不能修改。"
    },
    {
        value: "WRITE",
        label: "写入",
        description: "AI 可创建、写入、移动和恢复文件，不能移入回收站。"
    },
    {
        value: "DELETE",
        label: "删除",
        description: "AI 可调用全部文件动作；删除仍只进入项目回收站。"
    }
];

function normalizeAiRiskLevel(value, fallback = "READ_ONLY") {
    const normalized = String(value ?? "").trim().toUpperCase();
    return AI_RISK_OPTIONS.some((option) => option.value === normalized)
        ? normalized
        : fallback;
}

function aiRiskOption(value) {
    const normalized = normalizeAiRiskLevel(value);
    return AI_RISK_OPTIONS.find((option) => option.value === normalized);
}

function formatSize(size) {
    const value = Number(size);
    if (!Number.isFinite(value) || value < 0) return "—";
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
    return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function formatTime(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN");
}

function normalizeText(value) {
    return String(value ?? "").normalize("NFKC").toLocaleLowerCase("zh-CN");
}

export default async function mount(root, context) {
    const make = context.element;
    const state = {
        roots: [],
        activeRootId: "",
        path: "",
        entries: [],
        selectedPath: "",
        search: "",
        mode: "files",
        trash: [],
        preview: null,
        previewLoading: false,
        aiRiskLevel: "READ_ONLY",
        aiRiskAvailable: false,
        aiRiskLoading: true,
        aiRiskSaving: false,
        aiRiskReminderEnabled: context.settings?.aiRiskChangeReminderEnabled !== false,
        aiRiskDialogOpen: false,
        requestVersion: 0
    };

    function iconNode(name, size = 18, className = "") {
        const node = make("span", className);
        node.innerHTML = context.icon(name, size);
        return node;
    }

    function button(label, iconName, variant = "") {
        const node = make("button", ["fs-ui-button", variant].filter(Boolean).join(" "));
        node.type = "button";
        if (iconName) node.append(iconNode(iconName));
        node.append(make("span", "", label));
        return node;
    }

    function iconButton(label, iconName) {
        const node = make("button", "fs-ui-icon-button");
        node.type = "button";
        node.title = label;
        node.setAttribute("aria-label", label);
        node.append(iconNode(iconName, 18));
        return node;
    }

    function notify(message, error = false) {
        context.toast?.(message, error ? { error: true } : undefined);
    }

    function fail(error) {
        notify(error?.message || "文件操作失败", true);
    }

    const panel = make("section", "fs-ui");
    const titleId = "fs-ui-title";
    panel.setAttribute("aria-labelledby", titleId);

    const header = make("header", "fs-ui-header");
    const heading = make("div", "fs-ui-heading");
    const headingCopy = make("div");
    const title = make("h1", "", "文件管理器");
    title.id = titleId;
    headingCopy.append(
        make("p", "fs-ui-eyebrow", "LOCAL FILES"),
        title,
        make("p", "fs-ui-muted", "用户授权目录、受控写入、项目回收站与本地文件摘要。")
    );
    heading.append(iconNode("folder", 26, "fs-ui-heading__icon"), headingCopy);

    const headerActions = make("div", "fs-ui-header__actions");
    const authButton = button("授权目录", "shield", "fs-ui-button--primary");
    const refreshButton = iconButton("刷新当前目录", "refresh");
    const aiRiskControl = make("button", "fs-ui-ai-risk");
    aiRiskControl.type = "button";
    const aiRiskTrack = make("span", "fs-ui-ai-risk__track");
    const aiRiskOptions = AI_RISK_OPTIONS.map((option) => {
        const item = make("span", "fs-ui-ai-risk__option", option.label);
        item.dataset.risk = option.value;
        aiRiskTrack.append(item);
        return item;
    });
    aiRiskControl.append(
        iconNode("shield", 16),
        make("span", "fs-ui-ai-risk__label", "AI 风险"),
        aiRiskTrack
    );
    headerActions.append(aiRiskControl, refreshButton, authButton);
    header.append(heading, headerActions);

    const notice = make("div", "fs-ui-notice");
    notice.append(
        iconNode("shield", 18, "fs-ui-notice__icon"),
        make(
            "span",
            "",
            "文件根由本机用户显式授权；系统目录禁止写入和删除；删除只进入项目回收站。"
        )
    );

    const authForm = make("form", "fs-ui-auth");
    authForm.hidden = true;
    const authTitle = make("h2", "", "授权本地目录");
    const authPath = make("input");
    authPath.placeholder = "例如 D:\\资料";
    authPath.setAttribute("aria-label", "本地绝对目录");
    const authLabel = make("input");
    authLabel.placeholder = "显示名称，可留空";
    authLabel.setAttribute("aria-label", "文件根显示名称");
    const authWrite = make("input");
    authWrite.type = "checkbox";
    const authDelete = make("input");
    authDelete.type = "checkbox";
    const authLegend = make("div", "fs-ui-auth__capabilities");
    const writeLabel = make("label", "fs-ui-check");
    writeLabel.append(authWrite, make("span", "", "允许写入普通文件"));
    const deleteLabel = make("label", "fs-ui-check");
    deleteLabel.append(authDelete, make("span", "", "允许移入项目回收站"));
    authLegend.append(writeLabel, deleteLabel);
    const authActions = make("div", "fs-ui-auth__actions");
    const cancelAuth = button("取消", "close", "fs-ui-button--secondary");
    const submitAuth = button("确认授权", "check", "fs-ui-button--primary");
    submitAuth.type = "submit";
    authActions.append(cancelAuth, submitAuth);
    authForm.append(authTitle, authPath, authLabel, authLegend, authActions);

    const riskDialogTitleId = "fs-ui-risk-dialog-title";
    const riskDialogDescriptionId = "fs-ui-risk-dialog-description";
    const riskDialog = make("dialog", "fs-ui-risk-dialog");
    riskDialog.setAttribute("aria-labelledby", riskDialogTitleId);
    riskDialog.setAttribute("aria-describedby", riskDialogDescriptionId);
    const riskDialogPanel = make("section", "fs-ui-risk-dialog__panel");
    const riskDialogHeading = make("header", "fs-ui-risk-dialog__heading");
    const riskDialogTitle = make("h2", "", "切换 AI 风险上限？");
    riskDialogTitle.id = riskDialogTitleId;
    const riskDialogLevel = make("span", "fs-ui-risk-dialog__level");
    riskDialogHeading.append(make("p", "fs-ui-eyebrow", "AI RISK"), riskDialogTitle, riskDialogLevel);
    const riskDialogMessage = make("p", "fs-ui-risk-dialog__message");
    riskDialogMessage.id = riskDialogDescriptionId;
    const riskReminderLabel = make("label", "fs-ui-risk-dialog__remember");
    const riskReminderCheckbox = make("input");
    riskReminderCheckbox.type = "checkbox";
    const riskReminderCopy = make("span", "fs-ui-risk-dialog__remember-copy");
    riskReminderCopy.append(
        make("strong", "", "以后切换 AI 风险上限时不再提醒"),
        make("small", "", "可在模组设置的“AI 风险变更提醒”中重新开启。")
    );
    riskReminderLabel.append(riskReminderCheckbox, riskReminderCopy);
    riskReminderLabel.hidden = typeof context.saveSettings !== "function";
    const riskDialogActions = make("div", "fs-ui-risk-dialog__actions");
    const riskDialogCancel = button("取消", "close", "fs-ui-button--secondary");
    const riskDialogConfirm = button("确认切换", "check", "fs-ui-button--primary");
    riskDialogActions.append(riskDialogCancel, riskDialogConfirm);
    riskDialogPanel.append(
        riskDialogHeading,
        riskDialogMessage,
        riskReminderLabel,
        riskDialogActions
    );
    riskDialog.append(riskDialogPanel);

    const layout = make("div", "fs-ui-layout");
    const rootRail = make("aside", "fs-ui-roots");
    rootRail.setAttribute("aria-label", "已授权文件根");
    const browser = make("section", "fs-ui-browser");
    const detail = make("aside", "fs-ui-detail");
    detail.setAttribute("aria-label", "文件详情");
    layout.append(rootRail, browser, detail);

    panel.append(header, notice, authForm, layout, riskDialog);
    root.replaceChildren(panel);

    const uploadInput = make("input");
    uploadInput.type = "file";
    uploadInput.hidden = true;
    panel.append(uploadInput);

    function activeRoot() {
        return state.roots.find((item) => item.id === state.activeRootId) || null;
    }

    function currentDirectory() {
        return state.mode === "trash" ? "" : state.path;
    }

    function canWrite() {
        return Boolean(activeRoot()?.write);
    }

    function canDelete() {
        return Boolean(activeRoot()?.delete);
    }

    function updateAiRiskControl() {
        const option = aiRiskOption(state.aiRiskLevel);
        const optionIndex = AI_RISK_OPTIONS.findIndex((item) => item.value === option.value);
        const nextOption = AI_RISK_OPTIONS[(optionIndex + 1) % AI_RISK_OPTIONS.length];
        aiRiskControl.style.setProperty("--fs-risk-index", String(optionIndex));
        aiRiskOptions.forEach((item, index) => {
            item.classList.toggle("is-active", index === optionIndex);
        });
        aiRiskControl.disabled = state.aiRiskLoading
            || state.aiRiskSaving
            || state.aiRiskDialogOpen
            || !state.aiRiskAvailable;
        aiRiskControl.dataset.risk = option.value;
        aiRiskControl.dataset.busy = String(state.aiRiskLoading || state.aiRiskSaving);
        aiRiskControl.setAttribute("aria-busy", aiRiskControl.dataset.busy);
        aiRiskControl.setAttribute(
            "aria-label",
            state.aiRiskAvailable
                ? `AI 文件操作风险上限：${option.label}。点击切换为${nextOption.label}。`
                : "宿主尚未接入模块 AI 风险上限。"
        );
        aiRiskControl.title = state.aiRiskAvailable
            ? `AI 文件动作上限：${option.label}。${option.description} 点击切换为${nextOption.label}；文件根能力位仍单独生效。`
            : "宿主尚未接入模块 AI 风险上限。";
    }

    async function loadAiRiskLevel() {
        if (!context.aiRiskPolicy?.get) {
            state.aiRiskAvailable = false;
            state.aiRiskLoading = false;
            updateAiRiskControl();
            return;
        }
        state.aiRiskLoading = true;
        updateAiRiskControl();
        try {
            const payload = await context.aiRiskPolicy.get();
            const nextLevel = normalizeAiRiskLevel(payload?.actionAiRiskLevel, "");
            if (!nextLevel) {
                throw new Error("宿主返回了无效的 AI 风险上限");
            }
            state.aiRiskLevel = nextLevel;
            state.aiRiskAvailable = true;
        } catch (error) {
            state.aiRiskAvailable = false;
            fail(error);
        } finally {
            state.aiRiskLoading = false;
            updateAiRiskControl();
        }
    }

    function nextAiRiskOption() {
        const currentIndex = AI_RISK_OPTIONS.findIndex(
            (option) => option.value === state.aiRiskLevel
        );
        return AI_RISK_OPTIONS[(currentIndex + 1) % AI_RISK_OPTIONS.length];
    }

    let riskDialogResolve = null;
    let riskDialogReturnFocus = null;

    function riskDialogFocusNodes() {
        return [...riskDialogPanel.querySelectorAll(
            'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )].filter((node) => !node.hidden && node.getClientRects().length > 0);
    }

    function closeAiRiskDialog(result) {
        if (!riskDialog.open || !riskDialogResolve) return;
        riskDialog.close();
        state.aiRiskDialogOpen = false;
        const resolve = riskDialogResolve;
        riskDialogResolve = null;
        const returnFocus = riskDialogReturnFocus;
        riskDialogReturnFocus = null;
        updateAiRiskControl();
        if (returnFocus?.isConnected) {
            returnFocus.focus();
        }
        resolve(result);
    }

    function openAiRiskDialog(option, message) {
        riskDialogReturnFocus = document.activeElement;
        riskDialog.dataset.risk = option.value;
        riskDialogLevel.dataset.risk = option.value;
        riskDialogLevel.textContent = option.label;
        riskDialogMessage.textContent = message;
        riskReminderCheckbox.checked = false;
        if (!riskDialog.open) {
            riskDialog.showModal();
        }
        state.aiRiskDialogOpen = true;
        updateAiRiskControl();
        requestAnimationFrame(() => riskDialogCancel.focus());
        return new Promise((resolve) => {
            riskDialogResolve = resolve;
        });
    }

    function requestAiRiskConfirmation(option, message) {
        if (option.value === "READ_ONLY" || !state.aiRiskReminderEnabled) {
            return Promise.resolve({ confirmed: true, suppressReminder: false });
        }
        return openAiRiskDialog(option, message);
    }

    async function saveAiRiskReminderPreference(enabled) {
        if (typeof context.saveSettings !== "function") {
            throw new Error("宿主尚未接入模块设置保存");
        }
        const payload = await context.saveSettings({
            aiRiskChangeReminderEnabled: enabled
        });
        const savedEnabled = payload?.aiRiskChangeReminderEnabled !== false;
        if (savedEnabled !== enabled) {
            throw new Error("宿主未保存 AI 风险变更提醒设置");
        }
        state.aiRiskReminderEnabled = savedEnabled;
    }

    riskDialogCancel.addEventListener("click", () => {
        closeAiRiskDialog({ confirmed: false, suppressReminder: false });
    });
    riskDialogConfirm.addEventListener("click", () => {
        closeAiRiskDialog({
            confirmed: true,
            suppressReminder: !riskReminderLabel.hidden && riskReminderCheckbox.checked
        });
    });
    riskDialog.addEventListener("click", (event) => {
        if (event.target === riskDialog) {
            closeAiRiskDialog({ confirmed: false, suppressReminder: false });
        }
    });
    riskDialog.addEventListener("cancel", (event) => {
        event.preventDefault();
        closeAiRiskDialog({ confirmed: false, suppressReminder: false });
    });
    riskDialog.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            event.preventDefault();
            closeAiRiskDialog({ confirmed: false, suppressReminder: false });
            return;
        }
        if (event.key !== "Tab") return;
        const focusable = riskDialogFocusNodes();
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });

    async function changeAiRiskLevel(requested) {
        const normalized = normalizeAiRiskLevel(requested, "");
        const previous = state.aiRiskLevel;
        if (!normalized || normalized === previous) {
            updateAiRiskControl();
            return;
        }
        if (!state.aiRiskAvailable || !context.aiRiskPolicy?.set) {
            notify("宿主尚未接入模块 AI 风险上限", true);
            return;
        }
        const option = aiRiskOption(normalized);
        const warning = normalized === "DELETE"
            ? "删除权限允许 AI 调用全部文件动作。删除仍只会进入项目回收站，并继续经过确认、快照和审计。"
            : normalized === "WRITE"
                ? "写入权限允许 AI 创建、写入、移动和恢复文件；文件根仍需单独授予写入能力。"
                : "只读权限关闭 AI 的写入和删除动作，查询与读取仍受文件根能力位约束。";
        const confirmation = await requestAiRiskConfirmation(option, warning);
        if (!confirmation.confirmed) {
            updateAiRiskControl();
            return;
        }
        state.aiRiskSaving = true;
        updateAiRiskControl();
        try {
            const payload = await context.aiRiskPolicy.set(normalized);
            const nextLevel = normalizeAiRiskLevel(payload?.actionAiRiskLevel, "");
            if (!nextLevel) {
                throw new Error("宿主返回了无效的 AI 风险上限");
            }
            state.aiRiskLevel = nextLevel;
            if (confirmation.suppressReminder) {
                try {
                    await saveAiRiskReminderPreference(false);
                    notify(`AI 风险上限已设为“${aiRiskOption(state.aiRiskLevel).label}”，以后不再提醒`);
                } catch (error) {
                    notify(
                        `AI 风险上限已设为“${aiRiskOption(state.aiRiskLevel).label}”，但“不再提醒”设置未保存：${error?.message || "未知错误"}`,
                        true
                    );
                }
            } else {
                notify(`AI 风险上限已设为“${aiRiskOption(state.aiRiskLevel).label}”`);
            }
        } catch (error) {
            state.aiRiskLevel = previous;
            fail(error);
        } finally {
            state.aiRiskSaving = false;
            updateAiRiskControl();
        }
    }

    function cycleAiRiskLevel() {
        void changeAiRiskLevel(nextAiRiskOption().value);
    }

    function setBusy(element, busy, label = "处理中…") {
        if (!element) return;
        element.disabled = busy;
        element.dataset.busyLabel = busy ? label : "";
    }

    function renderRoots() {
        rootRail.replaceChildren();
        const headingNode = make("header", "fs-ui-panel-heading");
        headingNode.append(make("h2", "", "文件根"));
        rootRail.append(headingNode);
        if (!state.roots.length) {
            const empty = make("div", "fs-ui-empty fs-ui-empty--compact");
            empty.append(
                iconNode("folder", 22),
                make("strong", "", "尚未授权本地目录"),
                make("p", "", "选择“授权目录”后可浏览该目录。")
            );
            rootRail.append(empty);
            return;
        }
        state.roots.forEach((fileRoot) => {
            const row = make("div", "fs-ui-root-row");
            const item = make(
                "button",
                `fs-ui-root${fileRoot.id === state.activeRootId ? " is-active" : ""}`
            );
            item.type = "button";
            item.setAttribute("aria-pressed", String(fileRoot.id === state.activeRootId));
            item.append(
                iconNode("folder", 18),
                make("span", "fs-ui-root__label", text(fileRoot.label, "未命名文件根")),
                make(
                    "small",
                    "fs-ui-root__caps",
                    [fileRoot.read ? "读" : "", fileRoot.write ? "写" : "",
                        fileRoot.delete ? "回收站" : ""].filter(Boolean).join(" · ")
                )
            );
            item.addEventListener("click", () => {
                state.activeRootId = fileRoot.id;
                state.path = "";
                state.selectedPath = "";
                state.preview = null;
                state.mode = "files";
                renderRoots();
                loadDirectory();
            });
            const revoke = iconButton("撤销该文件根授权", "close");
            revoke.classList.add("fs-ui-root__revoke");
            revoke.addEventListener("click", () => revokeRoot(fileRoot, revoke));
            row.append(item, revoke);
            rootRail.append(row);
        });
    }

    function replaceBrowser(children) {
        browser.replaceChildren(...children);
    }

    function browserHeader() {
        const root = activeRoot();
        const top = make("header", "fs-ui-browser__header");
        const crumb = make("nav", "fs-ui-breadcrumb");
        crumb.setAttribute("aria-label", "当前目录");
        const rootCrumb = make("button", "fs-ui-crumb", text(root?.label, "选择文件根"));
        rootCrumb.type = "button";
        rootCrumb.addEventListener("click", () => {
            state.path = "";
            state.selectedPath = "";
            loadDirectory();
        });
        crumb.append(rootCrumb);
        let cursor = "";
        state.path.split("/").filter(Boolean).forEach((part) => {
            crumb.append(make("span", "fs-ui-crumb__separator", "/"));
            cursor = joinPath(cursor, part);
            const partPath = cursor;
            const item = make("button", "fs-ui-crumb", part);
            item.type = "button";
            item.addEventListener("click", () => {
                state.path = partPath;
                state.selectedPath = "";
                loadDirectory();
            });
            crumb.append(item);
        });
        const toolbar = make("div", "fs-ui-toolbar");
        const up = iconButton("上一级", "up");
        up.disabled = !state.path || state.mode === "trash";
        up.addEventListener("click", () => {
            state.path = parentPath(state.path);
            state.selectedPath = "";
            loadDirectory();
        });
        const newFolder = button("新建目录", "folder", "fs-ui-button--secondary");
        newFolder.disabled = !canWrite() || state.mode === "trash";
        newFolder.addEventListener("click", createDirectory);
        const newText = button("新建文本", "file", "fs-ui-button--secondary");
        newText.disabled = !canWrite() || state.mode === "trash";
        newText.addEventListener("click", createTextFile);
        const upload = button("上传", "download", "fs-ui-button--secondary");
        upload.disabled = !canWrite() || state.mode === "trash" || !activeRoot();
        upload.addEventListener("click", () => uploadInput.click());
        const trashButton = button(
            state.mode === "trash" ? "返回文件" : "回收站",
            "trash",
            "fs-ui-button--secondary"
        );
        trashButton.addEventListener("click", () => {
            state.mode = state.mode === "trash" ? "files" : "trash";
            state.selectedPath = "";
            state.preview = null;
            if (state.mode === "trash") loadTrash();
            else loadDirectory();
        });
        toolbar.append(up, newFolder, newText, upload, trashButton);
        top.append(crumb, toolbar);
        return top;
    }

    function renderFileBrowser() {
        const headerNode = browserHeader();
        const searchWrap = make("label", "fs-ui-search");
        const search = make("input");
        search.type = "search";
        search.placeholder = "筛选当前目录";
        search.value = state.search;
        search.setAttribute("aria-label", "筛选当前目录");
        searchWrap.append(iconNode("search"), search);
        search.addEventListener("input", () => {
            state.search = search.value;
            renderFileTable(tableHost);
        });
        const tableHost = make("div", "fs-ui-table-wrap");
        const summaryLine = make(
            "p",
            "fs-ui-browser__summary",
            `${state.entries.length} 个项目`
        );
        browser.replaceChildren(headerNode, searchWrap, summaryLine, tableHost);
        renderFileTable(tableHost);
    }

    function filteredEntries() {
        const needle = normalizeText(state.search).trim();
        if (!needle) return state.entries;
        return state.entries.filter((entry) =>
            normalizeText(`${entry.name} ${entry.path}`).includes(needle)
        );
    }

    function renderFileTable(host) {
        const entries = filteredEntries();
        host.replaceChildren();
        const table = make("table", "fs-ui-table");
        const head = make("thead");
        const headRow = make("tr");
        ["名称", "类型", "大小", "修改时间"].forEach((label) => {
            const th = make("th", "", label);
            th.scope = "col";
            headRow.append(th);
        });
        head.append(headRow);
        const body = make("tbody");
        if (!entries.length) {
            const row = make("tr");
            const cell = make("td", "fs-ui-table__empty", "当前目录为空或没有匹配项。");
            cell.colSpan = 4;
            row.append(cell);
            body.append(row);
        }
        entries.forEach((entry) => {
            const row = make(
                "tr",
                `fs-ui-file-row${entry.path === state.selectedPath ? " is-selected" : ""}`
            );
            row.tabIndex = 0;
            row.setAttribute("aria-selected", String(entry.path === state.selectedPath));
            const nameCell = make("td");
            const nameButton = make("button", "fs-ui-file-name");
            nameButton.type = "button";
            nameButton.append(
                iconNode(entry.directory ? "folder" : "file", 18),
                make("span", "", text(entry.name, "未命名"))
            );
            nameButton.addEventListener("click", () => {
                if (entry.directory) openDirectory(entry.path);
                else selectEntry(entry);
            });
            nameCell.append(nameButton);
            row.append(
                nameCell,
                make("td", "", entry.directory ? "目录" : extensionOf(entry.name).toUpperCase() || "文件"),
                make("td", "", entry.directory ? "—" : formatSize(entry.size)),
                make("td", "", formatTime(entry.modifiedAt))
            );
            row.addEventListener("keydown", (event) => {
                if (event.key === "Enter") {
                    event.preventDefault();
                    if (entry.directory) openDirectory(entry.path);
                    else selectEntry(entry);
                }
            });
            body.append(row);
        });
        table.append(head, body);
        host.append(table);
    }

    function renderTrash() {
        const headerNode = browserHeader();
        const list = make("div", "fs-ui-trash-list");
        if (!state.trash.length) {
            const empty = make("div", "fs-ui-empty");
            empty.append(
                iconNode("trash", 24),
                make("strong", "", "项目回收站为空"),
                make("p", "", "删除的文件会出现在这里，且不会被物理删除。")
            );
            list.append(empty);
        }
        state.trash.forEach((entry) => {
            const item = make("article", "fs-ui-trash-item");
            const copy = make("div");
            copy.append(
                make("strong", "", text(entry.name, "未命名")),
                make("p", "", text(entry.originalPath, "原位置未知")),
                make("small", "", formatTime(entry.deletedAt))
            );
            const restore = button("恢复", "undo", "fs-ui-button--secondary");
            restore.addEventListener("click", () => restoreTrash(entry, restore));
            item.append(copy, restore);
            list.append(item);
        });
        browser.replaceChildren(headerNode, list);
        renderEmptyDetail("回收站条目不提供内容预览。");
    }

    function renderEmptyDetail(message) {
        detail.replaceChildren();
        const headingNode = make("header", "fs-ui-panel-heading");
        headingNode.append(make("h2", "", "详情"));
        const empty = make("div", "fs-ui-empty fs-ui-empty--compact");
        empty.append(
            iconNode("file", 22),
            make("strong", "", "尚未选择文件"),
            make("p", "", message)
        );
        detail.append(headingNode, empty);
    }

    function currentEntry() {
        return state.entries.find((entry) => entry.path === state.selectedPath) || null;
    }

    function selectEntry(entry) {
        state.selectedPath = entry.path;
        state.preview = null;
        renderFileTable(browser.querySelector(".fs-ui-table-wrap"));
        renderDetail(entry);
    }

    function renderDetail(entry) {
        detail.replaceChildren();
        const headingNode = make("header", "fs-ui-panel-heading");
        headingNode.append(make("h2", "", entry.directory ? "目录详情" : "文件详情"));
        const titleRow = make("div", "fs-ui-detail__title-row");
        titleRow.append(
            iconNode(entry.directory ? "folder" : "file", 24),
            make("h3", "", text(entry.name, "未命名"))
        );
        const meta = make("dl", "fs-ui-meta");
        const metadata = [
            ["根内路径", text(entry.path, "/")],
            ["类型", entry.directory ? "目录" : extensionOf(entry.name).toUpperCase() || "文件"],
            ["大小", entry.directory ? "—" : formatSize(entry.size)],
            ["修改时间", formatTime(entry.modifiedAt)]
        ];
        metadata.forEach(([label, value]) => {
            meta.append(make("dt", "", label), make("dd", "", value));
        });
        const actions = make("div", "fs-ui-detail__actions");
        if (!entry.directory) {
            const open = button("默认应用打开", "arrow", "fs-ui-button--primary");
            open.addEventListener("click", () => openEntry(entry, open));
            const preview = button("本地预览", "file", "fs-ui-button--secondary");
            preview.disabled = !isReadableEntry(entry);
            preview.title = isReadableEntry(entry) ? "" : "该文件类型暂不提供文本提取";
            preview.addEventListener("click", () => openPreview(entry, preview));
            const summary = button("AI 摘要", "spark", "fs-ui-button--secondary");
            summary.disabled = !isReadableEntry(entry);
            summary.addEventListener("click", () => requestSummary(entry, summary));
            actions.append(open, preview, summary);
        }
        const rename = button("移动或重命名", "route", "fs-ui-button--secondary");
        rename.disabled = !canWrite();
        rename.addEventListener("click", () => moveEntry(entry, rename));
        const remove = button("移入回收站", "trash", "fs-ui-button--danger");
        remove.disabled = !canDelete();
        remove.addEventListener("click", () => trashEntry(entry, remove));
        actions.append(rename, remove);

        const previewHost = make("div", "fs-ui-preview");
        detail.append(headingNode, titleRow, meta, actions, previewHost);
        if (state.preview) renderPreview(previewHost, state.preview);
    }

    function renderPreview(host, preview) {
        host.replaceChildren();
        const headerNode = make("div", "fs-ui-preview__header");
        headerNode.append(
            make("h3", "", "文本预览"),
            make("span", "fs-ui-muted", preview.eof ? "已到文件末尾" : "内容已分页读取")
        );
        const editor = make("textarea", "fs-ui-preview__editor");
        editor.value = preview.content || "";
        editor.spellcheck = false;
        editor.setAttribute("aria-label", "文本文件内容");
        const actions = make("div", "fs-ui-preview__actions");
        const next = button("读取下一段", "arrow", "fs-ui-button--secondary");
        next.disabled = preview.eof;
        next.addEventListener("click", async () => {
            const entry = currentEntry();
            if (!entry) return;
            await loadPreview(entry, preview.nextOffset, next);
        });
        const save = button("保存覆盖", "check", "fs-ui-button--primary");
        save.disabled = !canWrite() || !activeRoot() || preview.editable !== true;
        save.addEventListener("click", async () => {
            if (!window.confirm("确认覆盖当前文件？旧内容会先进入项目回收站。")) return;
            setBusy(save, true, "保存中…");
            try {
                await context.executeAction("fs.write", {
                    rootId: state.activeRootId,
                    path: preview.path,
                    content: editor.value,
                    overwrite: true
                }, true);
                notify("文件已保存");
                state.preview = { ...preview, content: editor.value };
                await loadDirectory();
                const entry = currentEntry();
                if (entry) selectEntry(entry);
            } catch (error) {
                fail(error);
            } finally {
                setBusy(save, false);
            }
        });
        actions.append(next, save);
        host.append(headerNode, editor, actions);
    }

    async function loadRoots(selectFirst = false) {
        setBusy(refreshButton, true, "刷新中…");
        try {
            const payload = await context.query("fs.roots");
            const roots = Array.isArray(payload?.roots) ? payload.roots : [];
            if (Number(payload?.count) !== roots.length) {
                throw new TypeError("文件根数量与结果不一致");
            }
            state.roots = roots;
            if (!state.roots.some((item) => item.id === state.activeRootId)) {
                state.activeRootId = state.roots[0]?.id || "";
            }
            renderRoots();
            if (selectFirst && state.activeRootId) await loadDirectory();
            else if (!state.activeRootId) {
                const empty = make("div", "fs-ui-empty");
                empty.append(
                    make("strong", "", "尚未授权本地文件根"),
                    make("p", "", "点击右上角“授权目录”，输入本机绝对路径并确认能力。")
                );
                replaceBrowser([empty]);
                renderEmptyDetail("授权文件根后即可浏览目录。");
            }
        } catch (error) {
            fail(error);
        } finally {
            setBusy(refreshButton, false);
        }
    }

    async function loadDirectory() {
        if (!state.activeRootId) {
            renderRoots();
            renderEmptyDetail("请先选择文件根。");
            return;
        }
        const version = ++state.requestVersion;
        setBusy(refreshButton, true, "读取中…");
        try {
            const payload = await context.query("fs.list", {
                rootId: state.activeRootId,
                path: state.path
            });
            if (version !== state.requestVersion) return;
            const entries = Array.isArray(payload?.entries) ? payload.entries : [];
            if (Number(payload?.count) !== entries.length) {
                throw new TypeError("目录条目数量与结果不一致");
            }
            state.entries = entries;
            state.mode = "files";
            state.search = "";
            renderRoots();
            renderFileBrowser();
            const selected = currentEntry();
            if (selected) renderDetail(selected);
            else renderEmptyDetail("选择文件后可查看详情、预览、打开或摘要。");
        } catch (error) {
            if (version === state.requestVersion) fail(error);
        } finally {
            setBusy(refreshButton, false);
        }
    }

    async function loadTrash() {
        setBusy(refreshButton, true, "读取中…");
        try {
            const payload = await context.query("fs.trash");
            state.trash = Array.isArray(payload?.entries) ? payload.entries : [];
            if (Number(payload?.count) !== state.trash.length) {
                throw new TypeError("回收站条目数量与结果不一致");
            }
            state.mode = "trash";
            renderRoots();
            renderTrash();
        } catch (error) {
            fail(error);
        } finally {
            setBusy(refreshButton, false);
        }
    }

    function openDirectory(path) {
        state.path = path;
        state.selectedPath = "";
        state.preview = null;
        loadDirectory();
    }

    async function createDirectory() {
        const name = window.prompt("请输入新目录名称或根内相对路径");
        if (!name?.trim()) return;
        const target = joinPath(state.path, name.trim());
        try {
            await context.executeAction("fs.mkdir", {
                rootId: state.activeRootId,
                path: target
            }, true);
            notify("目录已创建");
            await loadDirectory();
        } catch (error) {
            fail(error);
        }
    }

    async function createTextFile() {
        const name = window.prompt("请输入新文本文件路径，例如 笔记.txt");
        if (!name?.trim()) return;
        const content = window.prompt("请输入初始文本内容，可留空", "") ?? "";
        try {
            await context.executeAction("fs.write", {
                rootId: state.activeRootId,
                path: joinPath(state.path, name.trim()),
                content,
                overwrite: false
            }, true);
            notify("文本文件已创建");
            await loadDirectory();
        } catch (error) {
            fail(error);
        }
    }

    async function openEntry(entry, trigger) {
        setBusy(trigger, true, "打开中…");
        try {
            await context.executeAction("fs.open", {
                rootId: state.activeRootId,
                path: entry.path
            }, true);
            notify("已请求系统默认应用打开");
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function openPreview(entry, trigger) {
        setBusy(trigger, true, "读取中…");
        try {
            await loadPreview(entry, 0, trigger);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function loadPreview(entry, offset, trigger) {
        setBusy(trigger, true, "读取中…");
        try {
            const grant = await context.fileRoots.grantRead(
                state.activeRootId,
                entry.path,
                "preview"
            );
            const token = grant?.token;
            if (!token) throw new Error("宿主未返回读取令牌");
            const payload = await context.query("fs.readGrantedText", {
                token,
                offset: Math.max(0, Number(offset) || 0),
                maxChars: 8000
            });
            state.preview = {
                ...payload,
                token,
                path: entry.path,
                editable: isTextEntry(entry)
            };
            renderDetail(entry);
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function requestSummary(entry, trigger) {
        setBusy(trigger, true, "授权中…");
        try {
            const grant = await context.fileRoots.grantRead(
                state.activeRootId,
                entry.path,
                "summary"
            );
            const token = grant?.token;
            if (!token) throw new Error("宿主未返回摘要读取令牌");
            context.openAiWithText(
                `请使用 module_fs 的 fs.readGrantedText 查询读取令牌 ${token} 对应文本，`
                + "参数 offset=0、maxChars=3000，并根据实际返回内容生成简明摘要。"
                + "不要复述读取令牌，不要猜测或输出文件路径，也不要修改文件。"
            );
            notify("已生成短时读取授权，请在 AI 对话中发送摘要请求");
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function revokeRoot(fileRoot, trigger) {
        if (!window.confirm(`确认撤销文件根“${fileRoot.label}”的授权？目录中的文件不会被删除。`)) {
            return;
        }
        setBusy(trigger, true, "撤销中…");
        try {
            await context.fileRoots.revoke(fileRoot.id);
            if (state.activeRootId === fileRoot.id) {
                state.activeRootId = "";
                state.path = "";
                state.selectedPath = "";
                state.preview = null;
            }
            notify("文件根授权已撤销");
            await loadRoots(true);
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function moveEntry(entry, trigger) {
        const target = window.prompt("请输入根内目标相对路径", entry.path);
        if (!target?.trim() || target.trim() === entry.path) return;
        if (!window.confirm("确认移动或重命名该对象？目标已存在时会被拒绝。")) return;
        setBusy(trigger, true, "移动中…");
        try {
            await context.executeAction("fs.move", {
                sourceRootId: state.activeRootId,
                source: entry.path,
                targetRootId: state.activeRootId,
                target: target.trim()
            }, true);
            notify("对象已移动");
            state.selectedPath = "";
            await loadDirectory();
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function trashEntry(entry, trigger) {
        if (!window.confirm(`确认把“${entry.name}”移入项目回收站？`)) return;
        setBusy(trigger, true, "移动中…");
        try {
            await context.executeAction("fs.trash", {
                rootId: state.activeRootId,
                path: entry.path
            }, true);
            notify("已移入项目回收站");
            state.selectedPath = "";
            state.preview = null;
            await loadDirectory();
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    async function restoreTrash(entry, trigger) {
        if (!window.confirm(`确认恢复“${entry.name}”？原位置已存在时会被拒绝。`)) return;
        setBusy(trigger, true, "恢复中…");
        try {
            await context.executeAction("fs.restore", {
                trashId: entry.id
            }, true);
            notify("对象已恢复");
            await loadTrash();
        } catch (error) {
            fail(error);
        } finally {
            setBusy(trigger, false);
        }
    }

    uploadInput.addEventListener("change", async () => {
        const file = uploadInput.files?.[0];
        uploadInput.value = "";
        if (!file || !activeRoot()) return;
        const target = joinPath(state.path, file.name);
        if (!window.confirm(`确认上传“${file.name}”到当前目录？`)) return;
        const safeName = file.name.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 80) || "upload.bin";
        const uploadPath = `uploads/${Date.now()}-${safeName}`;
        const uploadBlob = new Blob([file], { type: "application/octet-stream" });
        try {
            await context.executeActionWithFile("fs.upload", {
                rootId: state.activeRootId,
                path: target,
                uploadPath,
                overwrite: false
            }, uploadBlob, true);
            notify("文件已上传");
            await loadDirectory();
        } catch (error) {
            fail(error);
        }
    });

    authButton.addEventListener("click", () => {
        authForm.hidden = !authForm.hidden;
        if (!authForm.hidden) authPath.focus();
    });
    cancelAuth.addEventListener("click", () => {
        authForm.hidden = true;
        authForm.reset();
    });
    refreshButton.addEventListener("click", () => {
        if (state.mode === "trash") loadTrash();
        else loadDirectory();
    });
    aiRiskControl.addEventListener("click", cycleAiRiskLevel);
    authForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const path = authPath.value.trim();
        if (!path) {
            authPath.focus();
            return;
        }
        if (!window.confirm("确认授权该本地目录？后续文件操作仍受能力位、确认和宿主审计约束。")) {
            return;
        }
        setBusy(submitAuth, true, "授权中…");
        try {
            const payload = await context.fileRoots.authorize(
                path,
                authLabel.value.trim(),
                authWrite.checked,
                authDelete.checked
            );
            if (payload?.root?.id) {
                state.activeRootId = payload.root.id;
            }
            notify("文件根已授权");
            authForm.reset();
            authForm.hidden = true;
            await loadRoots(true);
        } catch (error) {
            fail(error);
        } finally {
            setBusy(submitAuth, false);
        }
    });

    renderRoots();
    updateAiRiskControl();
    renderEmptyDetail("正在读取文件根…");
    try {
        await Promise.all([loadRoots(true), loadAiRiskLevel()]);
    } catch (error) {
        fail(error);
    }
}
