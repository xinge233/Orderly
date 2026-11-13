function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value : fallback;
}

function parentPath(path) {
    const parts = String(path || "").split("/").filter(Boolean);
    parts.pop();
    return parts.join("/");
}

export default async function mount(root, context) {
    const make = context.element;
    let currentPath = "";
    let mode = context.settings?.defaultView === "trash" ? "trash" : "workspace";

    const panel = make("section", "fs-ui-panel");
    const header = make("header", "fs-ui-header");
    const heading = make("div");
    heading.append(
        make("p", "fs-ui-eyebrow", "MODULE FILES"),
        make("h1", "", context.module.title || "文件与目录"),
        make("p", "fs-ui-muted", "模块只通过宿主查询和受控计划访问工作区。")
    );
    const actions = make("div", "fs-ui-actions");
    const command = make("button", "fs-ui-button fs-ui-button--accent", "输入整理指令");
    command.type = "button";
    command.addEventListener("click", () => context.fillCommand(
        context.ui.examples?.[0]?.text || "创建目录 归档"
    ));
    actions.append(command);
    header.append(heading, actions);

    const tabs = make("div", "fs-ui-tabs");
    const workspaceTab = make("button", "fs-ui-tab", "工作区");
    const trashTab = make("button", "fs-ui-tab", "回收站");
    workspaceTab.type = "button";
    trashTab.type = "button";
    tabs.append(workspaceTab, trashTab);

    const toolbar = make("div", "fs-ui-toolbar");
    const up = make("button", "fs-ui-button", "↑ 上一级");
    up.type = "button";
    const location = make("strong", "fs-ui-location", "/");
    toolbar.append(up, location);

    const list = make("div", "fs-ui-list");
    panel.append(header, tabs, toolbar, list);
    root.replaceChildren(panel);

    function syncMode() {
        workspaceTab.classList.toggle("is-active", mode === "workspace");
        trashTab.classList.toggle("is-active", mode === "trash");
        toolbar.hidden = mode === "trash";
    }

    async function load() {
        list.replaceChildren(make("p", "fs-ui-empty", "正在读取模块数据…"));
        try {
            const payload = mode === "trash"
                ? await context.query("fs.trash")
                : await context.query("fs.list", { path: currentPath });
            const entries = Array.isArray(payload?.entries) ? payload.entries : [];
            location.textContent = mode === "trash" ? "项目回收站" : `/${text(payload?.path)}`;
            up.hidden = mode === "trash" || !currentPath;
            list.replaceChildren();
            if (!entries.length) {
                list.append(make("p", "fs-ui-empty", mode === "trash" ? "回收站为空。" : "此目录为空。"));
                return;
            }
            entries.forEach((entry) => {
                const row = make("article", "fs-ui-entry");
                const icon = make("span", "fs-ui-entry__icon");
                icon.innerHTML = context.icon(entry.directory ? "folder" : "file", 20);
                const copy = make("div", "fs-ui-entry__copy");
                copy.append(
                    make("strong", "", entry.name || entry.originalPath || "未命名条目"),
                    make("small", "", entry.directory
                        ? `目录 · ${entry.path}`
                        : entry.originalPath
                            ? `原位置 · ${entry.originalPath}`
                            : `文件 · ${entry.path}`)
                );
                const meta = make("span", "fs-ui-entry__meta", entry.deletedAt
                    ? context.formatDate(entry.deletedAt)
                    : entry.directory
                        ? "目录"
                        : `${Math.max(0, Number(entry.size) || 0)} B`);
                row.append(icon, copy, meta);
                if (entry.directory && !entry.originalPath) {
                    row.tabIndex = 0;
                    row.setAttribute("role", "button");
                    row.addEventListener("click", () => {
                        currentPath = entry.path;
                        void load();
                    });
                    row.addEventListener("keydown", (event) => {
                        if (event.key === "Enter" || event.key === " ") {
                            event.preventDefault();
                            currentPath = entry.path;
                            void load();
                        }
                    });
                }
                list.append(row);
            });
        } catch (error) {
            list.replaceChildren(make("p", "fs-ui-error", error?.message || "模块数据读取失败"));
        }
    }

    up.addEventListener("click", () => {
        currentPath = parentPath(currentPath);
        void load();
    });
    workspaceTab.addEventListener("click", () => {
        mode = "workspace";
        syncMode();
        void load();
    });
    trashTab.addEventListener("click", () => {
        mode = "trash";
        syncMode();
        void load();
    });

    syncMode();
    await load();
}
