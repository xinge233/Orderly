/**
 * 本地资料管理视图。
 *
 * 职责：查询资料元数据，提供检索、分类筛选、详情、导入、编辑、回收站和恢复入口。
 * 依赖：materials.list、materials.trash.list 以及宿主公开的模块 Action 调用能力。
 * 隔离：查询、渲染或动作失败只替换本模块视图内的状态，不修改框架和其他模块。
 */

export const MATERIAL_EVENT_REFRESH_DELAY = 180;
export const MATERIAL_ACCEPT = [
    ".pdf", ".docx", ".xlsx", ".pptx",
    ".txt", ".md", ".csv", ".json",
    ".png", ".jpg", ".jpeg", ".webp", ".mp4", ".webm"
].join(",");

const SUPPORTED_FORMATS = new Set([
    "pdf", "docx", "xlsx", "pptx",
    "txt", "md", "csv", "json",
    "png", "jpg", "jpeg", "webp", "mp4", "webm"
]);
const IMAGE_FORMATS = new Set(["png", "jpg", "jpeg", "webp"]);
const VIDEO_FORMATS = new Set(["mp4", "webm"]);
const SUMMARY_FORMATS = new Set([
    "pdf", "docx", "xlsx", "pptx",
    "txt", "md", "csv", "json",
    "png", "jpg", "jpeg", "webp"
]);
const MAX_UPLOAD_BYTES = 64 * 1024 * 1024;

/**
 * 资料摘要只保存在当前页面会话内，跨模块视图重挂载复用，刷新页面后重新生成。
 * 该缓存不写入 localStorage、模块设置、审计或执行记录。
 */
export const MATERIAL_SUMMARY_CACHE = new Map();

/**
 * 判断执行事件是否应触发资料视图刷新。
 *
 * @param {object} event 宿主执行事件
 * @param {string} moduleId 当前模块 ID
 * @returns {boolean} 是否为本模块终态事件
 */
export function materialDataEvent(event, moduleId) {
    if (!event || event.moduleId !== moduleId) {
        return false;
    }
    if (event.type !== "STEP" && event.type !== "ROLLBACK_STEP") {
        return false;
    }
    return ["COMPLETED", "FAILED", "ROLLED_BACK"].includes(event.status);
}

/**
 * 将字节数转换为紧凑可读文本。
 *
 * @param {unknown} value 字节数
 * @returns {string} 可读大小
 */
export function formatBytes(value) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes < 0) {
        return "大小未知";
    }
    if (bytes < 1024) {
        return `${Math.round(bytes)} B`;
    }
    const units = ["KB", "MB", "GB", "TB"];
    let size = bytes / 1024;
    let index = 0;
    while (size >= 1024 && index < units.length - 1) {
        size /= 1024;
        index += 1;
    }
    const digits = size >= 10 ? 0 : 1;
    return `${size.toFixed(digits)} ${units[index]}`;
}

/**
 * 判断某格式是否可通过宿主摘要通道生成本地抽取后的模型摘要。
 *
 * @param {unknown} value 文件格式
 * @returns {boolean} 是否支持摘要
 */
export function materialSummarySupported(value) {
    return SUMMARY_FORMATS.has(String(value ?? "").trim().toLowerCase());
}

/**
 * 将文件格式归入稳定的视觉分组，供缩略图或格式图块差分。
 *
 * @param {unknown} value 文件格式
 * @returns {"image" | "video" | "pdf" | "document" | "sheet" | "slide" | "text" | "file"} 视觉分组
 */
export function materialFormatGroup(value) {
    const format = String(value ?? "").trim().toLowerCase();
    if (IMAGE_FORMATS.has(format)) {
        return "image";
    }
    if (VIDEO_FORMATS.has(format)) {
        return "video";
    }
    if (format === "pdf") {
        return "pdf";
    }
    if (format === "docx") {
        return "document";
    }
    if (format === "xlsx" || format === "csv") {
        return "sheet";
    }
    if (format === "pptx") {
        return "slide";
    }
    if (format === "txt" || format === "md" || format === "json") {
        return "text";
    }
    return "file";
}

/**
 * 计算自动摘要仍需处理的资料。
 *
 * @param {Array<object>} items 资料库中的有效资料
 * @param {Map<string, object>} [summaries] 当前页面会话摘要缓存
 * @returns {Array<object>} 仍需自动生成的资料
 */
export function materialAutoSummaryCandidates(
    items,
    summaries = MATERIAL_SUMMARY_CACHE
) {
    const seen = new Set();
    return (Array.isArray(items) ? items : []).filter((item) => {
        const id = String(item?.id ?? "");
        if (!id
                || seen.has(id)
                || String(item?.status ?? "ACTIVE") !== "ACTIVE"
                || !materialSummarySupported(item?.format)) {
            return false;
        }
        seen.add(id);
        const current = summaries.get(id);
        return !current || !["ready", "error", "loading"].includes(current.status);
    });
}

/**
 * 判断资料是否匹配当前本地检索条件。查询结果被当作不可信输入逐字段兜底。
 *
 * @param {object} item 资料记录
 * @param {{keyword?: string, category?: string, format?: string}} filters 过滤条件
 * @returns {boolean} 是否匹配
 */
export function materialMatches(item, filters = {}) {
    if (!item || typeof item !== "object") {
        return false;
    }
    const keyword = String(filters.keyword ?? "").trim().toLocaleLowerCase();
    const category = String(filters.category ?? "ALL");
    const format = String(filters.format ?? "ALL").toUpperCase();
    if (category !== "ALL" && String(item.category ?? "") !== category) {
        return false;
    }
    if (format !== "ALL" && String(item.format ?? "").toUpperCase() !== format) {
        return false;
    }
    if (!keyword) {
        return true;
    }
    const tags = Array.isArray(item.tags) ? item.tags.join(" ") : "";
    const haystack = [
        item.title,
        item.fileName,
        item.category,
        item.description,
        item.format,
        tags
    ].map((value) => String(value ?? "")).join("\n").toLocaleLowerCase();
    return haystack.includes(keyword);
}

/**
 * 挂载资料管理视图。
 *
 * @param {HTMLElement} root 框架分配给模块的视图根节点
 * @param {object} context 宿主模块上下文
 * @returns {Promise<void>} 首次查询和渲染完成后结束
 */
export default async function mount(root, context) {
    const make = context.element;
    const moduleId = context.module?.moduleId || "module_materials";
    const settings = context.settings ?? {};
    const defaultSort = ["updated-desc", "title-asc", "size-desc"].includes(settings.defaultSort)
        ? settings.defaultSort
        : "updated-desc";
    const state = {
        activeItems: [],
        trashItems: [],
        view: "active",
        category: "ALL",
        format: "ALL",
        keyword: "",
        sort: defaultSort,
        selectedId: "",
        autoSummarizeAll: settings.autoSummarizeAll === true,
        summaries: MATERIAL_SUMMARY_CACHE,
        loading: true,
        error: ""
    };
    let refreshTimer = null;
    let refreshInFlight = null;
    let refreshRequested = false;
    let unsubscribeEvents = null;
    let lastEventSequence = 0;
    let autoSummaryRunning = false;
    let autoSummaryRunVersion = 0;
    let aiConfigured = null;

    const workspace = make("section", "materials-ui");
    workspace.setAttribute("aria-label", "本地资料库");

    const header = make("header", "materials-ui-header");
    const heading = make("div", "materials-ui-heading");
    const eyebrow = make("p", "materials-ui-eyebrow", "LOCAL MATERIALS");
    const title = make("h1", "materials-ui-title", context.module?.title || "资料管理");
    const summary = make("p", "materials-ui-summary");
    heading.append(eyebrow, title, summary);

    const headerActions = make("div", "materials-ui-header-actions");
    const importButton = iconTextButton(context, "file", "导入资料", "materials-ui-primary");
    const refreshButton = iconButton(context, "refresh", "刷新资料");
    headerActions.append(importButton, refreshButton);
    header.append(heading, headerActions);

    const controls = make("div", "materials-ui-controls");
    const searchField = make("label", "materials-ui-search");
    searchField.append(iconNode(context, "search", 18));
    const searchInput = make("input", "materials-ui-search-input");
    searchInput.type = "search";
    searchInput.placeholder = "搜索标题、文件名、分类或标签";
    searchInput.setAttribute("aria-label", "搜索资料");
    searchField.append(searchInput);

    const formatSelect = make("select", "materials-ui-select");
    formatSelect.setAttribute("aria-label", "按文件格式筛选");

    const sortSelect = make("select", "materials-ui-select");
    sortSelect.setAttribute("aria-label", "资料排序");
    [
        ["updated-desc", "最近更新"],
        ["title-asc", "标题顺序"],
        ["size-desc", "文件大小"]
    ].forEach(([value, label]) => {
        const option = make("option", "", label);
        option.value = value;
        sortSelect.append(option);
    });
    sortSelect.value = defaultSort;

    const autoSummaryLabel = make("label", "materials-ui-auto-summary");
    const autoSummaryInput = make("input", "materials-ui-auto-summary-input");
    autoSummaryInput.type = "checkbox";
    autoSummaryInput.checked = state.autoSummarizeAll;
    autoSummaryLabel.append(
        autoSummaryInput,
        make("span", "materials-ui-auto-summary-track"),
        make("span", "materials-ui-auto-summary-copy", "自动生成全部摘要")
    );

    const viewSwitch = make("div", "materials-ui-view-switch");
    const activeTab = make("button", "materials-ui-tab", "资料库");
    const trashTab = make("button", "materials-ui-tab", "回收站");
    activeTab.type = "button";
    trashTab.type = "button";
    viewSwitch.append(activeTab, trashTab);
    controls.append(searchField, formatSelect, sortSelect, autoSummaryLabel, viewSwitch);

    const body = make("div", "materials-ui-body");
    const categoryRail = make("aside", "materials-ui-categories");
    const listPanel = make("section", "materials-ui-list-panel");
    const listHeader = make("header", "materials-ui-list-header");
    const listTitle = make("h2", "materials-ui-list-title", "资料");
    const listCount = make("span", "materials-ui-count");
    const list = make("div", "materials-ui-list");
    listHeader.append(listTitle, listCount);
    listPanel.append(listHeader, list);

    const detailPanel = make("aside", "materials-ui-detail");
    body.append(categoryRail, listPanel, detailPanel);
    workspace.append(header, controls, body);

    const importDialog = createImportDialog();
    const editDialog = createEditDialog();
    const confirmDialog = createConfirmDialog();
    workspace.append(importDialog.dialog, editDialog.dialog, confirmDialog.dialog);
    root.replaceChildren(workspace);

    importButton.addEventListener("click", importDialog.open);
    refreshButton.addEventListener("click", () => void refreshData());
    searchInput.addEventListener("input", () => {
        state.keyword = searchInput.value;
        renderView();
    });
    formatSelect.addEventListener("change", () => {
        state.format = formatSelect.value;
        renderView();
    });
    sortSelect.addEventListener("change", () => {
        state.sort = sortSelect.value;
        renderView();
    });
    autoSummaryInput.addEventListener("change", () => {
        void setAutoSummarize(autoSummaryInput.checked);
    });
    activeTab.addEventListener("click", () => selectView("active"));
    trashTab.addEventListener("click", () => selectView("trash"));

    renderView();

    const disconnectObserver = new MutationObserver(() => {
        if (!workspace.isConnected) {
            cleanupRefresh();
            disconnectObserver.disconnect();
        }
    });
    disconnectObserver.observe(document.body, { childList: true, subtree: true });

    if (typeof context.subscribeEvents === "function") {
        try {
            unsubscribeEvents = context.subscribeEvents(handleFrameworkEvent);
        } catch (error) {
            showError(error);
        }
    }

    await refreshData();

    function handleFrameworkEvent(event) {
        if (!workspace.isConnected) {
            cleanupRefresh();
            return;
        }
        if (!materialDataEvent(event, moduleId)) {
            return;
        }
        const sequence = Number(event.seq);
        if (Number.isFinite(sequence)) {
            if (sequence <= lastEventSequence) {
                return;
            }
            lastEventSequence = sequence;
        }
        scheduleRefresh();
    }

    function scheduleRefresh() {
        if (refreshTimer !== null) {
            window.clearTimeout(refreshTimer);
        }
        refreshTimer = window.setTimeout(() => {
            refreshTimer = null;
            if (workspace.isConnected) {
                void refreshData();
            }
        }, MATERIAL_EVENT_REFRESH_DELAY);
    }

    async function refreshData() {
        if (refreshInFlight) {
            refreshRequested = true;
            return refreshInFlight;
        }
        refreshInFlight = loadData();
        try {
            await refreshInFlight;
        } finally {
            refreshInFlight = null;
            if (refreshRequested && workspace.isConnected) {
                refreshRequested = false;
                scheduleRefresh();
            }
        }
    }

    async function loadData() {
        try {
            const [active, trash] = await Promise.all([
                context.query("materials.list"),
                context.query("materials.trash.list")
            ]);
            if (!workspace.isConnected) {
                return;
            }
            state.activeItems = Array.isArray(active?.items) ? active.items : [];
            state.trashItems = Array.isArray(trash?.items) ? trash.items : [];
            state.loading = false;
            state.error = "";
            ensureSelection();
            renderView();
            void maybeRunAutoSummaries();
        } catch (error) {
            state.loading = false;
            state.error = error?.message || "资料读取失败";
            renderView();
        }
    }

    function selectView(view) {
        if (state.view === view) {
            return;
        }
        state.view = view;
        state.category = "ALL";
        state.selectedId = "";
        ensureSelection();
        renderView();
    }

    function ensureSelection() {
        const visible = sortedFilteredItems();
        if (!visible.some((item) => item.id === state.selectedId)) {
            state.selectedId = visible[0]?.id || "";
        }
    }

    function sortedFilteredItems() {
        const source = state.view === "active" ? state.activeItems : state.trashItems;
        const visible = source.filter((item) => materialMatches(item, {
            keyword: state.keyword,
            category: state.category,
            format: state.format
        }));
        return visible.sort((left, right) => {
            if (state.sort === "title-asc") {
                return String(left.title ?? "").localeCompare(
                    String(right.title ?? ""),
                    "zh-CN"
                );
            }
            if (state.sort === "size-desc") {
                return Number(right.sizeBytes || 0) - Number(left.sizeBytes || 0);
            }
            return String(right.updatedAt ?? "").localeCompare(String(left.updatedAt ?? ""));
        });
    }

    function renderView() {
        summary.textContent = `${state.activeItems.length} 份资料 · ${state.trashItems.length} 份在回收站`;
        autoSummaryInput.checked = state.autoSummarizeAll;
        activeTab.textContent = `资料库 ${state.activeItems.length}`;
        trashTab.textContent = `回收站 ${state.trashItems.length}`;
        activeTab.setAttribute("aria-pressed", String(state.view === "active"));
        trashTab.setAttribute("aria-pressed", String(state.view === "trash"));
        importButton.hidden = state.view === "trash";
        renderFormatOptions();
        renderCategories();

        const visible = sortedFilteredItems();
        listCount.textContent = `${visible.length} 项`;
        listTitle.textContent = state.view === "active" ? "资料目录" : "回收站";
        if (state.loading) {
            list.replaceChildren(make("p", "materials-ui-state", "正在读取资料…"));
        } else if (state.error) {
            list.replaceChildren(make("p", "materials-ui-state is-error", state.error));
        } else if (!visible.length) {
            list.replaceChildren(make(
                "p",
                "materials-ui-state",
                state.view === "active" ? "当前没有资料。" : "回收站为空。"
            ));
        } else {
            list.replaceChildren(...visible.map(materialRow));
        }
        renderDetail(visible.find((item) => item.id === state.selectedId) || null);
    }

    function renderFormatOptions() {
        const source = state.view === "active" ? state.activeItems : state.trashItems;
        const formats = [...new Set(source.map((item) => String(item.format ?? "").toUpperCase()))]
            .filter(Boolean)
            .sort();
        const options = [["ALL", "全部格式"], ...formats.map((format) => [format, format])];
        const signature = options.map(([value]) => value).join("|");
        if (formatSelect.dataset.signature === signature) {
            return;
        }
        formatSelect.dataset.signature = signature;
        formatSelect.replaceChildren(...options.map(([value, label]) => {
            const option = make("option", "", label);
            option.value = value;
            return option;
        }));
        if (!options.some(([value]) => value === state.format)) {
            state.format = "ALL";
        }
        formatSelect.value = state.format;
    }

    function renderCategories() {
        const source = state.view === "active" ? state.activeItems : state.trashItems;
        const counts = new Map();
        source.forEach((item) => {
            const category = String(item.category ?? "").trim();
            if (category) {
                counts.set(category, (counts.get(category) || 0) + 1);
            }
        });
        const categories = [...counts.entries()].sort(([left], [right]) =>
            left.localeCompare(right, "zh-CN")
        );
        const nodes = [categoryButton("ALL", "全部资料", source.length)];
        categories.forEach(([category, count]) => {
            nodes.push(categoryButton(category, category, count));
        });
        categoryRail.replaceChildren(
            make("h2", "materials-ui-category-title", "分类"),
            ...nodes
        );
    }

    function categoryButton(value, label, count) {
        const button = make("button", "materials-ui-category");
        button.type = "button";
        button.setAttribute("aria-pressed", String(state.category === value));
        button.append(
            make("span", "", label),
            make("span", "materials-ui-category-count", String(count))
        );
        button.addEventListener("click", () => {
            state.category = value;
            state.selectedId = "";
            ensureSelection();
            renderView();
        });
        return button;
    }

    function materialRow(item) {
        const row = make("div", "materials-ui-row");
        row.dataset.selected = String(item.id === state.selectedId);

        const select = make("button", "materials-ui-row-main");
        select.type = "button";
        select.setAttribute("aria-pressed", String(item.id === state.selectedId));

        const copy = make("span", "materials-ui-row-copy");
        const rowSummary = summaryLine(item);
        copy.append(
            make("strong", "", item.title || item.fileName || "未命名资料"),
            make(
                "small",
                "",
                [
                    item.category || "未分类",
                    item.format || "未知格式",
                    formatBytes(item.sizeBytes)
                ].join(" · ")
            ),
            rowSummary
        );
        select.append(
            materialThumbnail(item),
            copy,
            make("span", "materials-ui-row-date", context.formatDate?.(item.updatedAt) || "")
        );
        select.addEventListener("click", () => {
            state.selectedId = item.id;
            renderView();
        });
        row.append(select);

        if (state.view === "active") {
            const supported = materialSummarySupported(item.format);
            const summaryState = state.summaries.get(item.id);
            const loading = summaryState?.status === "loading";
            const summaryButton = iconButton(
                context,
                "spark",
                loading
                    ? "摘要生成中"
                    : supported
                        ? summaryState?.status === "ready" ? "重新生成摘要" : "生成摘要"
                        : "该格式不支持摘要"
            );
            summaryButton.classList.add("materials-ui-row-summary-button");
            summaryButton.disabled = loading || !supported;
            summaryButton.addEventListener("click", () => {
                void generateSummary(item, { manual: true });
            });
            row.append(summaryButton);
        }
        return row;
    }

    function materialThumbnail(item) {
        const format = String(item.format ?? "").toLowerCase();
        const thumbnail = make("span", "materials-ui-thumbnail");
        thumbnail.dataset.format = materialFormatGroup(format);
        thumbnail.setAttribute("aria-hidden", "true");
        const url = state.view === "active"
            ? context.moduleDataUrl?.(item.storagePath)
            : "";
        if (url && IMAGE_FORMATS.has(format)) {
            const image = make("img", "materials-ui-thumbnail-image");
            image.src = url;
            image.alt = "";
            image.loading = "lazy";
            image.addEventListener("error", () => {
                image.remove();
                paintFormatTile(thumbnail, format);
            }, { once: true });
            thumbnail.append(image);
            return thumbnail;
        }
        paintFormatTile(thumbnail, format);
        return thumbnail;
    }

    function paintFormatTile(thumbnail, format) {
        const group = materialFormatGroup(format);
        const iconName = group === "video" ? "play" : "file";
        const label = format === "jpeg" ? "JPG" : String(format || "FILE").toUpperCase();
        thumbnail.replaceChildren(
            iconNode(context, iconName, 16),
            make("span", "materials-ui-thumbnail-label", label)
        );
    }

    function summaryLine(item) {
        const current = state.summaries.get(item.id);
        if (state.view !== "active") {
            return make("small", "materials-ui-row-summary is-muted", "回收站资料不生成摘要");
        }
        if (!materialSummarySupported(item.format)) {
            return make("small", "materials-ui-row-summary is-muted", "该格式暂不支持摘要");
        }
        if (current?.status === "loading") {
            return make("small", "materials-ui-row-summary is-loading", "正在生成摘要…");
        }
        if (current?.status === "ready" && current.text) {
            return make("small", "materials-ui-row-summary", current.text);
        }
        if (current?.status === "error") {
            return make(
                "small",
                "materials-ui-row-summary is-error",
                current.message || "摘要生成失败"
            );
        }
        return make("small", "materials-ui-row-summary is-muted", "暂无摘要");
    }

    function renderDetail(item) {
        if (!item) {
            detailPanel.replaceChildren(
                iconNode(context, "file", 28),
                make("h2", "", "选择一份资料"),
                make("p", "materials-ui-muted", "查看资料详情与可用操作。")
            );
            return;
        }
        const headerNode = make("header", "materials-ui-detail-header");
        const formatChip = make("span", "materials-ui-format", item.format || "FILE");
        const actions = make("div", "materials-ui-detail-actions");
        if (state.view === "active") {
            const edit = iconButton(context, "file", "编辑资料信息");
            const trash = iconButton(context, "trash", "移入回收站");
            trash.classList.add("is-danger");
            edit.addEventListener("click", () => editDialog.open(item));
            trash.addEventListener("click", () => void moveToTrash(item));
            actions.append(edit, trash);
        } else {
            const restore = iconTextButton(context, "undo", "恢复资料", "materials-ui-secondary");
            restore.addEventListener("click", () => void restoreItem(item));
            actions.append(restore);
        }
        headerNode.append(formatChip, actions);

        const titleNode = make("h2", "materials-ui-detail-title", item.title || "未命名资料");
        const fileMeta = make(
            "p",
            "materials-ui-detail-file",
            `${item.fileName || "未记录文件名"} · ${formatBytes(item.sizeBytes)}`
        );
        const tags = make("div", "materials-ui-tags");
        const tagValues = Array.isArray(item.tags) ? item.tags : [];
        if (tagValues.length) {
            tagValues.forEach((tag) => tags.append(make("span", "materials-ui-tag", tag)));
        } else {
            tags.append(make("span", "materials-ui-tag is-muted", "无标签"));
        }

        const metadata = make("dl", "materials-ui-metadata");
        metadata.append(
            metaRow("分类", item.category || "未分类"),
            metaRow("导入时间", context.formatDate?.(item.createdAt) || item.createdAt || "未知"),
            metaRow("最近更新", context.formatDate?.(item.updatedAt) || item.updatedAt || "未知")
        );
        if (state.view === "trash") {
            metadata.append(
                metaRow("删除时间", context.formatDate?.(item.deletedAt) || item.deletedAt || "未知")
            );
        }

        const description = make(
            "p",
            "materials-ui-description",
            item.description || "暂无资料说明。"
        );
        detailPanel.replaceChildren(
            headerNode,
            titleNode,
            fileMeta,
            tags,
            metadata,
            description,
            renderPreview(item)
        );
    }

    function metaRow(label, value) {
        const wrapper = make("div");
        wrapper.append(make("dt", "", label), make("dd", "", String(value ?? "")));
        return wrapper;
    }

    function renderPreview(item) {
        const preview = make("section", "materials-ui-preview");
        const format = String(item.format ?? "").toLowerCase();
        const url = context.moduleDataUrl?.(item.storagePath);
        if (state.view === "active" && url && IMAGE_FORMATS.has(format)) {
            const image = make("img", "materials-ui-preview-image");
            image.src = url;
            image.alt = item.title || item.fileName || "资料图片";
            image.loading = "lazy";
            preview.append(image);
            return preview;
        }
        if (state.view === "active" && url && VIDEO_FORMATS.has(format)) {
            const video = make("video", "materials-ui-preview-video");
            video.src = url;
            video.controls = true;
            video.preload = "metadata";
            preview.append(video);
            return preview;
        }
        if (state.view === "active" && url && format === "pdf") {
            const frame = make("iframe", "materials-ui-preview-pdf");
            frame.src = url;
            frame.title = `${item.title || "资料"} PDF 预览`;
            preview.append(frame);
            return preview;
        }
        preview.append(
            iconNode(context, "file", 24),
            make("p", "materials-ui-muted", `${item.format || "该格式"} 暂无内嵌预览`)
        );
        return preview;
    }

    async function moveToTrash(item) {
        const confirmed = await confirmDialog.ask(
            "移入回收站",
            `确认将“${item.title || item.fileName}”移入项目回收站？`,
            "移入回收站"
        );
        if (!confirmed) {
            return;
        }
        await runAction("materials.trash", { id: item.id }, "资料已移入回收站");
    }

    async function restoreItem(item) {
        await runAction("materials.restore", { id: item.id }, "资料已恢复");
    }

    async function runAction(actionId, argumentsValue, successText) {
        try {
            await context.executeAction(actionId, argumentsValue, true);
            context.toast?.(successText, "success");
            await refreshData();
        } catch (error) {
            showError(error);
        }
    }

    async function setAutoSummarize(enabled) {
        const previous = state.autoSummarizeAll;
        state.autoSummarizeAll = enabled;
        autoSummaryInput.checked = enabled;
        renderView();
        try {
            if (typeof context.saveSettings === "function") {
                const saved = await context.saveSettings({
                    ...context.settings,
                    autoSummarizeAll: enabled
                });
                context.settings = {
                    ...context.settings,
                    ...(saved && typeof saved === "object" ? saved : {}),
                    autoSummarizeAll: enabled
                };
            }
        } catch (error) {
            state.autoSummarizeAll = previous;
            autoSummaryInput.checked = previous;
            renderView();
            showError(error);
            return;
        }
        aiConfigured = null;
        if (!enabled) {
            autoSummaryRunVersion += 1;
            return;
        }
        await maybeRunAutoSummaries({ notifyUnavailable: true });
    }

    async function maybeRunAutoSummaries({ notifyUnavailable = false } = {}) {
        if (!state.autoSummarizeAll || autoSummaryRunning || !workspace.isConnected) {
            return;
        }
        if (state.activeItems.length === 0) {
            return;
        }
        if (aiConfigured === null && typeof context.isAiConfigured === "function") {
            try {
                aiConfigured = (await context.isAiConfigured()) === true;
            } catch {
                aiConfigured = null;
            }
        }
        if (aiConfigured === false) {
            if (notifyUnavailable) {
                context.toast?.("请先在设置中配置 AI API；开启状态会保留", "error");
            }
            return;
        }

        const runVersion = ++autoSummaryRunVersion;
        autoSummaryRunning = true;
        try {
            for (const item of materialAutoSummaryCandidates(
                state.activeItems,
                state.summaries
            )) {
                if (runVersion !== autoSummaryRunVersion
                        || !state.autoSummarizeAll
                        || !workspace.isConnected) {
                    break;
                }
                await generateSummary(item);
            }
        } finally {
            autoSummaryRunning = false;
            if (runVersion === autoSummaryRunVersion) {
                renderView();
            }
        }
    }

    async function generateSummary(item, { manual = false } = {}) {
        if (!materialSummarySupported(item.format)) {
            if (manual) {
                context.toast?.("该文件格式暂不支持摘要", "error");
            }
            return;
        }
        const current = state.summaries.get(item.id);
        if (current?.status === "loading") {
            return;
        }
        if (typeof context.summarizeModuleData !== "function") {
            state.summaries.set(item.id, {
                status: "error",
                message: "当前宿主未提供资料摘要接口"
            });
            renderView();
            if (manual) {
                context.toast?.("当前宿主未提供资料摘要接口", "error");
            }
            return;
        }

        state.summaries.set(item.id, {
            status: "loading",
            text: current?.status === "ready" ? current.text : ""
        });
        renderView();
        try {
            const result = await context.summarizeModuleData(item.storagePath);
            const text = String(result?.summary ?? "").trim();
            if (!text) {
                throw new Error("模型没有返回可用摘要");
            }
            state.summaries.set(item.id, { status: "ready", text });
        } catch (error) {
            const message = String(error?.message || "摘要生成失败").trim();
            state.summaries.set(item.id, {
                status: "error",
                message: message || "摘要生成失败"
            });
            if (manual) {
                showError(error);
            }
        } finally {
            if (workspace.isConnected) {
                renderView();
            }
        }
    }

    function createImportDialog() {
        const dialog = make("dialog", "materials-ui-dialog");
        const form = make("form", "materials-ui-dialog-form");
        const dialogTitle = make("h2", "materials-ui-dialog-title", "导入资料");
        const fileInput = field("文件", "input").control;
        fileInput.type = "file";
        fileInput.accept = MATERIAL_ACCEPT;
        fileInput.required = true;
        const titleInput = field("标题", "input").control;
        titleInput.required = true;
        titleInput.maxLength = 200;
        const categoryInput = field("分类", "input").control;
        categoryInput.maxLength = 80;
        const tagsInput = field("标签", "input").control;
        tagsInput.placeholder = "多个标签用逗号分隔";
        tagsInput.maxLength = 1000;
        const descriptionInput = field("说明", "textarea").control;
        descriptionInput.maxLength = 2000;
        const error = make("p", "materials-ui-dialog-error");
        const footer = make("footer", "materials-ui-dialog-footer");
        const cancel = make("button", "materials-ui-secondary", "取消");
        cancel.type = "button";
        const submit = make("button", "materials-ui-primary", "导入");
        submit.type = "submit";
        footer.append(cancel, submit);
        form.append(
            dialogTitle,
            fileInput.closest("label"),
            titleInput.closest("label"),
            categoryInput.closest("label"),
            tagsInput.closest("label"),
            descriptionInput.closest("label"),
            error,
            footer
        );
        dialog.append(form);
        cancel.addEventListener("click", closeDialog);
        fileInput.addEventListener("change", () => {
            const file = fileInput.files?.[0];
            if (file && !titleInput.value.trim()) {
                titleInput.value = file.name.replace(/\.[^.]+$/, "");
            }
        });
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            error.textContent = "";
            const file = fileInput.files?.[0];
            if (!file) {
                error.textContent = "请选择资料文件。";
                return;
            }
            if (file.size > MAX_UPLOAD_BYTES) {
                error.textContent = "文件不能超过 64 MB。";
                return;
            }
            const extension = file.name.split(".").pop()?.toLowerCase() || "";
            if (!SUPPORTED_FORMATS.has(extension)) {
                error.textContent = "该文件格式暂不支持。";
                return;
            }
            submit.disabled = true;
            try {
                const uploadPath = `uploads/${createUploadId()}.${extension}`;
                const uploadFile = normalizeFileType(file, extension);
                await context.executeActionWithFile(
                    "materials.import",
                    {
                        uploadPath,
                        fileName: file.name,
                        title: titleInput.value.trim(),
                        category: categoryInput.value.trim(),
                        tags: tagsInput.value.trim(),
                        description: descriptionInput.value.trim(),
                        contentType: uploadFile.type
                    },
                    uploadFile,
                    true
                );
                closeDialog();
                context.toast?.("资料已导入", "success");
                await refreshData();
            } catch (uploadError) {
                error.textContent = uploadError?.message || "资料导入失败";
            } finally {
                submit.disabled = false;
            }
        });
        return {
            dialog,
            open() {
                form.reset();
                error.textContent = "";
                showDialog(dialog);
                window.setTimeout(() => fileInput.focus(), 0);
            }
        };
    }

    function createEditDialog() {
        const dialog = make("dialog", "materials-ui-dialog");
        const form = make("form", "materials-ui-dialog-form");
        const dialogTitle = make("h2", "materials-ui-dialog-title", "编辑资料");
        const titleInput = field("标题", "input").control;
        titleInput.required = true;
        titleInput.maxLength = 200;
        const categoryInput = field("分类", "input").control;
        categoryInput.maxLength = 80;
        const tagsInput = field("标签", "input").control;
        tagsInput.maxLength = 1000;
        const descriptionInput = field("说明", "textarea").control;
        descriptionInput.maxLength = 2000;
        const error = make("p", "materials-ui-dialog-error");
        const footer = make("footer", "materials-ui-dialog-footer");
        const cancel = make("button", "materials-ui-secondary", "取消");
        cancel.type = "button";
        const submit = make("button", "materials-ui-primary", "保存");
        submit.type = "submit";
        footer.append(cancel, submit);
        form.append(
            dialogTitle,
            titleInput.closest("label"),
            categoryInput.closest("label"),
            tagsInput.closest("label"),
            descriptionInput.closest("label"),
            error,
            footer
        );
        dialog.append(form);
        let editingId = "";
        cancel.addEventListener("click", closeDialog);
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            error.textContent = "";
            submit.disabled = true;
            try {
                await context.executeAction("materials.update", {
                    id: editingId,
                    title: titleInput.value.trim(),
                    category: categoryInput.value.trim(),
                    tags: tagsInput.value.trim(),
                    description: descriptionInput.value.trim()
                }, true);
                closeDialog();
                context.toast?.("资料信息已更新", "success");
                await refreshData();
            } catch (updateError) {
                error.textContent = updateError?.message || "资料更新失败";
            } finally {
                submit.disabled = false;
            }
        });
        return {
            dialog,
            open(item) {
                editingId = item.id;
                error.textContent = "";
                titleInput.value = item.title || "";
                categoryInput.value = item.category || "";
                tagsInput.value = Array.isArray(item.tags) ? item.tags.join(", ") : "";
                descriptionInput.value = item.description || "";
                showDialog(dialog);
                window.setTimeout(() => titleInput.focus(), 0);
            }
        };
    }

    function createConfirmDialog() {
        const dialog = make("dialog", "materials-ui-dialog materials-ui-confirm-dialog");
        const wrapper = make("div", "materials-ui-dialog-form");
        const dialogTitle = make("h2", "materials-ui-dialog-title", "确认操作");
        const message = make("p", "materials-ui-confirm-message");
        const footer = make("footer", "materials-ui-dialog-footer");
        const cancel = make("button", "materials-ui-secondary", "取消");
        cancel.type = "button";
        const confirm = make("button", "materials-ui-danger", "确认");
        confirm.type = "button";
        footer.append(cancel, confirm);
        wrapper.append(dialogTitle, message, footer);
        dialog.append(wrapper);
        let resolveResult = null;
        cancel.addEventListener("click", () => finish(false));
        confirm.addEventListener("click", () => finish(true));
        dialog.addEventListener("cancel", (event) => {
            event.preventDefault();
            finish(false);
        });
        function finish(result) {
            if (dialog.open) {
                dialog.close();
            }
            resolveResult?.(result);
            resolveResult = null;
        }
        return {
            dialog,
            ask(title, copy, confirmText) {
                dialogTitle.textContent = title;
                message.textContent = copy;
                confirm.textContent = confirmText;
                showDialog(dialog);
                return new Promise((resolve) => {
                    resolveResult = resolve;
                });
            }
        };
    }

    function field(label, tagName) {
        const wrapper = make("label", "materials-ui-field");
        const caption = make("span", "materials-ui-field-label", label);
        const control = make(tagName, "materials-ui-field-control");
        wrapper.append(caption, control);
        return { field: wrapper, control };
    }

    function showError(error) {
        context.toast?.(error?.message || "资料操作失败", "error");
    }

    function cleanupRefresh() {
        if (refreshTimer !== null) {
            window.clearTimeout(refreshTimer);
            refreshTimer = null;
        }
        autoSummaryRunVersion += 1;
        refreshRequested = false;
        unsubscribeEvents?.();
        unsubscribeEvents = null;
    }

    function iconNode(appContext, name, size) {
        const marker = make("span", "materials-ui-icon");
        marker.innerHTML = appContext.icon(name, size);
        return marker;
    }

    function iconButton(appContext, name, label) {
        const button = make("button", "materials-ui-icon-button");
        button.type = "button";
        button.title = label;
        button.setAttribute("aria-label", label);
        button.append(iconNode(appContext, name, 18));
        return button;
    }

    function iconTextButton(appContext, name, label, className) {
        const button = make("button", className);
        button.type = "button";
        button.append(iconNode(appContext, name, 18), make("span", "", label));
        return button;
    }

    function showDialog(dialog) {
        if (typeof dialog.showModal === "function") {
            dialog.showModal();
        } else {
            dialog.setAttribute("open", "");
        }
    }

    function closeDialog() {
        const dialog = root.querySelector("dialog[open]");
        if (!dialog) {
            return;
        }
        if (typeof dialog.close === "function") {
            dialog.close();
        } else {
            dialog.removeAttribute("open");
        }
    }
}

function createUploadId() {
    if (typeof globalThis.crypto?.randomUUID === "function") {
        return globalThis.crypto.randomUUID();
    }
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function normalizeFileType(file, extension) {
    if (file.type) {
        return file;
    }
    const type = {
        pdf: "application/pdf",
        docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        txt: "text/plain",
        md: "text/markdown",
        csv: "text/csv",
        json: "application/json",
        png: "image/png",
        jpg: "image/jpeg",
        jpeg: "image/jpeg",
        webp: "image/webp",
        mp4: "video/mp4",
        webm: "video/webm"
    }[extension] || "application/octet-stream";
    try {
        return new File([file], file.name, { type });
    } catch {
        return file;
    }
}
