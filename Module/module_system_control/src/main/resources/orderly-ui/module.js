/**
 * 模组设置系统视图。
 *
 * 职责：管理业务模块启停、管理顺序、模块设置和 Action/Query 的动态 AI 调用状态。
 * 依赖：orderly-ui/module.json 只声明容器视图，不提供 Query 或 Action；
 * 宿主仅为受保护的 module_system_control 注入 context.systemAdmin，所有管理调用都经该接口完成。
 * 隔离：单个管理 API、刷新或保存失败时保留当前可用快照并在本视图内报错；
 * 视图移除时取消延迟刷新和框架事件订阅，不触碰其他模块 DOM 或内部实现。
 */

const SETTING_TYPES = new Set(["boolean", "select", "integer", "text"]);
const MODULE_CONTROL_ROLE = "MODULE_CONTROL";

function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function finiteNumber(value, fallback) {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
}

function listOf(value) {
    return Array.isArray(value) ? value : [];
}

function issueText(issue) {
    if (typeof issue === "string") return issue;
    if (!issue || typeof issue !== "object") return "未知问题";
    return text(issue.message ?? issue.title ?? issue.error ?? issue.moduleId, "未知问题");
}

function optionOf(option) {
    if (typeof option === "string") {
        return { value: option, label: option };
    }
    if (!option || typeof option !== "object") return null;
    const value = text(option.value, "");
    return value ? { value, label: text(option.label ?? option.title, value) } : null;
}

function fieldsFromSettings(settings) {
    if (Array.isArray(settings)) return settings;
    if (!settings || typeof settings !== "object") return [];
    if (Array.isArray(settings.fields)) return settings.fields;
    const entries = Object.entries(settings);
    if (!entries.length) return [];
    return entries.every(([, value]) =>
        value && typeof value === "object" && SETTING_TYPES.has(String(value.type)))
        ? entries.map(([key, value]) => ({ key, ...value }))
        : [];
}

function settingDefinitions(module) {
    const sources = [
        module?.ui?.settings,
        module?.uiManifest?.settings,
        module?.manifest?.settings,
        module?.settingDefinitions,
        module?.settingsSchema,
        module?.settings?.fields,
        module?.contract?.settings?.fields,
        module?.contract?.settings,
        module?.settings
    ];
    for (const source of sources) {
        const fields = fieldsFromSettings(source);
        if (fields.length) return fields;
    }
    return [];
}

function normalizeField(field, index) {
    if (typeof field === "string") {
        return {
            key: field,
            title: field,
            description: "",
            type: "text",
            defaultValue: ""
        };
    }
    const source = field && typeof field === "object" ? field : {};
    const key = text(source.key ?? source.id, `field${index + 1}`);
    const type = SETTING_TYPES.has(String(source.type)) ? String(source.type) : "text";
    let defaultValue = source.default ?? source.defaultValue ?? source.value;
    if (defaultValue === undefined) {
        defaultValue = type === "boolean" ? false : type === "integer" ? 0 : "";
    }
    const options = type === "select"
        ? listOf(source.options).map(optionOf).filter(Boolean)
        : [];
    if (type === "select" && !options.some((option) => option.value === String(defaultValue))) {
        defaultValue = options[0]?.value ?? "";
    }
    return {
        key,
        title: text(source.title ?? source.label, key),
        description: text(source.description),
        type,
        defaultValue,
        options,
        min: source.min,
        max: source.max,
        step: source.step,
        placeholder: text(source.placeholder),
        minLength: source.minLength,
        maxLength: source.maxLength
    };
}

function normalizeDefinitions(module) {
    return settingDefinitions(module).map(normalizeField);
}

function normalizeInterfaceDefinition(item, kind, index) {
    const source = item && typeof item === "object" ? item : {};
    return {
        id: text(source.id, `${kind}${index + 1}`),
        title: text(source.title ?? source.label, `${kind}${index + 1}`),
        description: text(source.description),
        kind
    };
}

function normalizeInterfaceState(input) {
    const source = input && typeof input === "object" && !Array.isArray(input) ? input : {};
    const normalizeMap = (value) => {
        if (!value || typeof value !== "object" || Array.isArray(value)) return {};
        return Object.fromEntries(Object.entries(value).flatMap(([moduleId, flags]) => {
            if (!moduleId || !flags || typeof flags !== "object" || Array.isArray(flags)) {
                return [];
            }
            return [[moduleId, Object.fromEntries(Object.entries(flags)
                .filter(([, enabled]) => typeof enabled === "boolean"))]];
        }));
    };
    return {
        actionAiCallableEnabled: normalizeMap(source.actionAiCallableEnabled),
        queryAiCallableEnabled: normalizeMap(source.queryAiCallableEnabled),
        inUseActions: listOf(source.inUseActions).filter((key) => typeof key === "string")
    };
}

function interfaceStateFor(interfaceState, kind, moduleId, interfaceId) {
    const field = kind === "query" ? "queryAiCallableEnabled" : "actionAiCallableEnabled";
    const value = interfaceState[field]?.[moduleId]?.[interfaceId];
    return value !== false;
}

function actionIsInUse(interfaceState, moduleId, interfaceId) {
    return interfaceState.inUseActions.includes(`${moduleId}/${interfaceId}`);
}

function normalizeCatalogItem(item) {
    const source = item && typeof item === "object" ? item : {};
    return {
        moduleId: text(source.moduleId),
        title: text(source.title, text(source.moduleId, "未命名模块")),
        description: text(source.description),
        version: text(source.version),
        size: finiteNumber(source.size, 0),
        status: text(source.status, "AVAILABLE").toUpperCase(),
        message: text(source.message),
        canInstall: source.canInstall === true,
        canRetryBuild: source.canRetryBuild === true,
        jobId: text(source.jobId),
        jobActive: source.jobActive === true
    };
}

function normalizeModuleCatalog(payload) {
    const source = payload?.state && typeof payload.state === "object"
        ? payload.state
        : payload && typeof payload === "object" ? payload : {};
    return {
        schemaVersion: finiteNumber(source.schemaVersion, 0),
        apiVersion: finiteNumber(source.apiVersion, 0),
        generatedAt: text(source.generatedAt),
        sourceCommit: text(source.sourceCommit),
        items: listOf(source.items ?? source.modules)
            .map(normalizeCatalogItem)
            .filter((item) => item.moduleId)
    };
}

function catalogStatusLabel(status, message = "") {
    return {
        AVAILABLE: "可下载",
        SOURCE_PRESENT: "源码已存在",
        QUEUED: "等待构建",
        DOWNLOADING: "正在下载",
        VERIFYING: "正在校验",
        STAGING: "正在安装",
        BUILDING: "正在构建",
        LOADING: "正在加载",
        INSTALLED: "已启用",
        RESTART_REQUIRED: "重启后生效",
        BUILD_FAILED: "构建失败",
        BUILD_TIMEOUT: "构建超时",
        INSTALL_FAILED: "安装失败",
        FAILED: "安装失败"
    }[String(status ?? "").toUpperCase()] ?? text(message, "状态未知");
}

export function catalogActionState(item, status, job, capabilities = {}) {
    const normalizedStatus = text(status, "AVAILABLE").toUpperCase();
    const activeJob = Boolean(job) && job.terminal !== true;
    if (normalizedStatus === "INSTALLED") {
        return { kind: "none", label: "已启用", icon: "check", disabled: true };
    }
    if (normalizedStatus === "RESTART_REQUIRED") {
        return { kind: "none", label: "重启后生效", icon: "refresh", disabled: true };
    }
    if (activeJob) {
        return {
            kind: "none",
            label: catalogStatusLabel(normalizedStatus, job?.message),
            icon: "download",
            disabled: true
        };
    }
    if (item?.canRetryBuild === true) {
        return {
            kind: "retry-build",
            label: "重试构建",
            icon: "refresh",
            disabled: capabilities.retryBuild !== true
        };
    }
    if (item?.canInstall === true) {
        return {
            kind: "install",
            label: normalizedStatus === "AVAILABLE" ? "下载" : "重试",
            icon: "download",
            disabled: capabilities.install !== true
        };
    }
    return {
        kind: "none",
        label: catalogStatusLabel(normalizedStatus),
        icon: "download",
        disabled: true
    };
}

function formatBytes(bytes) {
    const value = finiteNumber(bytes, 0);
    if (value <= 0) return "";
    if (value < 1024) return `${value} B`;
    if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
    return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function settingValues(module, sharedValues = {}) {
    const direct = [
        module?.settingValues,
        module?.settingsValues,
        module?.settings?.values,
        module?.config?.settings,
        sharedValues?.[module?.moduleId]
    ].find((value) => value && typeof value === "object" && !Array.isArray(value));
    if (direct) return { ...direct };
    const settings = module?.settings;
    if (settings
        && typeof settings === "object"
        && !Array.isArray(settings)
        && !Array.isArray(settings.fields)
        && !Object.values(settings).some((value) =>
            value && typeof value === "object" && SETTING_TYPES.has(String(value.type)))) {
        return { ...settings };
    }
    return {};
}

function normalizeModule(raw, index, sharedValues) {
    const source = raw && typeof raw === "object" ? raw : {};
    const nestedModule = source.module && typeof source.module === "object"
        ? source.module
        : {};
    const contract = source.contract && typeof source.contract === "object"
        ? source.contract
        : nestedModule;
    const ui = source.ui ?? source.uiManifest ?? source.manifest ?? nestedModule.ui ?? {};
    const system = source.system ?? contract.system ?? {};
    const moduleId = text(source.moduleId ?? contract.moduleId ?? source.id);
    return {
        moduleId,
        title: text(source.title ?? contract.title ?? ui.title, moduleId || "未命名模块"),
        description: text(source.description ?? contract.description),
        icon: text(source.icon ?? ui.view?.icon ?? ui.icon, "modules"),
        enabled: source.enabled !== false && source.active !== false,
        managementOrder: finiteNumber(source.managementOrder ?? source.order ?? contract.order, index),
        protected: source.protected === true || system.protected === true,
        role: text(source.role ?? system.role),
        canRemove: source.canRemove === true,
        removalStatus: text(source.removalStatus, "NONE").toUpperCase(),
        removalMessage: text(source.removalMessage),
        settings: normalizeDefinitions({
            ...source,
            ui,
            contract,
            settings: source.settings ?? contract.settings
        }),
        values: settingValues({ ...source, settings: source.settings ?? contract.settings }, sharedValues),
        actions: listOf(source.actions ?? contract.actions ?? nestedModule.actions)
            .map((item, actionIndex) => normalizeInterfaceDefinition(item, "action", actionIndex)),
        queries: listOf(source.queries ?? contract.queries ?? nestedModule.queries)
            .map((item, queryIndex) => normalizeInterfaceDefinition(item, "query", queryIndex)),
        raw: source
    };
}

function adminMethod(admin, ...names) {
    // 按宿主公开的兼容方法名解析能力；缺失方法由具体交互显示“尚未接入”，不猜测本地替代实现。
    for (const name of names) {
        if (typeof admin?.[name] === "function") {
            return admin[name].bind(admin);
        }
    }
    return null;
}

function runtimeMessage(error, fallback) {
    return text(error?.message, fallback);
}

/**
 * 挂载模组设置视图。
 *
 * @param {HTMLElement} root 框架分配的模块视图根节点。
 * @param {object} context 宿主 mount context；仅 module_system_control 会获得可选 systemAdmin 管理 API。
 * @returns {Promise<void>} 初始快照读取和首屏渲染完成后结束。
 */
export default async function mount(root, context) {
    const make = context.element;
    const admin = context.systemAdmin;
    const getState = adminMethod(admin, "getState", "status", "listModules", "list");
    const getInterfaceState = adminMethod(admin, "getInterfaceState", "interfaceState");
    const refreshModules = adminMethod(admin, "refreshModules", "refresh");
    const openFolder = adminMethod(
        admin,
        "openModulesFolder",
        "openModuleFolder",
        "openFolder",
        "showModulesFolder"
    );
    const getModuleCatalog = adminMethod(admin, "getModuleCatalog", "listModuleCatalog");
    const installModule = adminMethod(admin, "installModule", "downloadModule");
    const getModuleInstallStatus = adminMethod(
        admin,
        "getModuleInstallStatus",
        "moduleInstallStatus"
    );
    const retryModuleBuild = adminMethod(admin, "retryModuleBuild", "prioritizeModuleBuild");
    const removeModule = adminMethod(admin, "removeModule", "deleteModule");
    const undoModuleRemoval = adminMethod(admin, "undoModuleRemoval", "restoreModule");
    const saveOrder = adminMethod(admin, "saveManagementOrder", "saveOrder", "setManagementOrder");
    const setEnabled = adminMethod(admin, "setModuleEnabled", "setEnabled");
    const saveSettings = adminMethod(
        admin,
        "saveModuleSettings",
        "updateModuleSettings",
        "saveSettings"
    );
    const resetSettings = adminMethod(
        admin,
        "resetModuleSettings",
        "resetSettings"
    );
    const setInterfaceAiCallable = adminMethod(
        admin,
        "setInterfaceAiCallable",
        "saveInterfaceAiCallable"
    );

    const state = {
        modules: [],
        added: [],
        issues: [],
        loading: true,
        busy: false,
        needsBuild: false,
        status: "",
        statusKind: "",
        error: "",
        subpage: "list",
        selectedId: "",
        draft: {},
        expandedIds: new Set(),
        interfaceState: normalizeInterfaceState({}),
        catalog: normalizeModuleCatalog({}),
        catalogLoading: false,
        catalogError: "",
        installJobs: new Map(),
        deleteMode: false,
        pendingDeleteId: "",
        removalBusyId: ""
    };
    // 事件可能在短时间内成批到达，单一 timer 用于合并刷新并保证视图销毁时可取消。
    let interfaceRefreshTimer = null;
    let catalogPollTimer = null;
    let catalogPollBusy = false;
    let deleteConfirmTimer = null;

    function setStatus(message, kind = "") {
        state.status = message;
        state.statusKind = kind;
    }

    function clearDeleteConfirmation() {
        if (deleteConfirmTimer) {
            clearTimeout(deleteConfirmTimer);
            deleteConfirmTimer = null;
        }
        state.pendingDeleteId = "";
    }

    function beginDeleteConfirmation(moduleId) {
        clearDeleteConfirmation();
        state.pendingDeleteId = moduleId;
        render();
        deleteConfirmTimer = setTimeout(() => {
            deleteConfirmTimer = null;
            if (!root.isConnected) return;
            if (state.pendingDeleteId === moduleId) {
                state.pendingDeleteId = "";
                render();
            }
        }, 5000);
    }

    function toggleDeleteMode() {
        clearDeleteConfirmation();
        state.deleteMode = !state.deleteMode;
        setStatus(
            state.deleteMode ? "选择要删除的模组。" : "",
            state.deleteMode ? "warn" : ""
        );
        render();
    }

    root.addEventListener("keydown", (event) => {
        if (event.key !== "Escape") return;
        if (state.pendingDeleteId) {
            event.preventDefault();
            clearDeleteConfirmation();
            render();
            return;
        }
        if (state.deleteMode) {
            event.preventDefault();
            toggleDeleteMode();
        }
    });

    function applySnapshot(payload) {
        const source = payload?.state && typeof payload.state === "object" ? payload.state : payload;
        const snapshot = source && typeof source === "object" ? source : {};
        const sharedValues = snapshot.settingValues
            ?? snapshot.settingsValues
            ?? (snapshot.settings && typeof snapshot.settings === "object" ? snapshot.settings : {});
        const modules = listOf(snapshot.modules ?? snapshot.items)
            .map((module, index) => normalizeModule(module, index, sharedValues))
            .filter((module) => module.moduleId)
            .sort((left, right) =>
                left.managementOrder - right.managementOrder
                || left.moduleId.localeCompare(right.moduleId));
        state.modules = modules;
        if (state.pendingDeleteId
                && !modules.some((module) =>
                    module.moduleId === state.pendingDeleteId && module.canRemove)) {
            clearDeleteConfirmation();
        }
        state.added = listOf(snapshot.added ?? payload?.added);
        state.issues = listOf(snapshot.issues ?? payload?.issues);
        state.interfaceState = normalizeInterfaceState(
            snapshot.interfaceState ?? payload?.interfaceState
        );
        const notBuilt = snapshot.notBuilt ?? snapshot.missingBuilds;
        state.needsBuild = snapshot.built === false
            || snapshot.needsBuild === true
            || snapshot.status === "NOT_BUILT"
            || (Array.isArray(notBuilt) && notBuilt.length > 0)
            || state.issues.some((issue) =>
                String(issue?.code ?? issue ?? "").includes("NOT_BUILT"));
        if (state.selectedId && !modules.some((module) => module.moduleId === state.selectedId)) {
            state.selectedId = "";
            state.subpage = "list";
        }
    }

    function selectedModule() {
        return state.modules.find((module) => module.moduleId === state.selectedId) ?? null;
    }

    function effectiveCatalogStatus(item) {
        const itemStatus = text(item?.status, "AVAILABLE").toUpperCase();
        if (itemStatus === "INSTALLED") return itemStatus;
        const job = state.installJobs.get(item.moduleId);
        return job?.status ? text(job.status).toUpperCase() : itemStatus;
    }

    function catalogJobActive(job) {
        return Boolean(job) && job.terminal !== true;
    }

    function stopCatalogPoll() {
        if (catalogPollTimer) {
            clearInterval(catalogPollTimer);
            catalogPollTimer = null;
        }
    }

    function scheduleCatalogPoll() {
        if (catalogPollTimer || !getModuleInstallStatus
                || ![...state.installJobs.values()].some(catalogJobActive)) {
            return;
        }
        catalogPollTimer = setInterval(() => {
            void pollInstallJobs();
        }, 900);
    }

    async function pollInstallJobs() {
        if (catalogPollBusy || !root.isConnected) {
            if (!root.isConnected) stopCatalogPoll();
            return;
        }
        const active = [...state.installJobs.entries()]
            .filter(([, job]) => catalogJobActive(job));
        if (!active.length) {
            stopCatalogPoll();
            return;
        }
        catalogPollBusy = true;
        let installed = false;
        let terminalChanged = false;
        let statusChanged = false;
        try {
            await Promise.all(active.map(async ([moduleId, job]) => {
                const payload = await getModuleInstallStatus(job.jobId);
                if (!payload || typeof payload !== "object") return;
                const next = {
                    ...job,
                    status: text(payload.status, job.status).toUpperCase(),
                    message: text(payload.message, job.message),
                    terminal: payload.terminal === true
                };
                if (next.status !== job.status
                        || next.message !== job.message
                        || next.terminal !== job.terminal) {
                    statusChanged = true;
                }
                state.installJobs.set(moduleId, next);
                if (next.terminal && !job.terminal) {
                    terminalChanged = true;
                }
                if (next.status === "INSTALLED") {
                    installed = true;
                }
            }));
            if (!root.isConnected) {
                stopCatalogPoll();
                return;
            }
            if (installed && refreshModules) {
                const payload = await refreshModules();
                if (hasModuleCollection(payload)) {
                    applySnapshot(payload);
                }
                await loadModuleCatalog(false);
                setStatus("模块已构建并启用。", "safe");
            }
            else if (terminalChanged) {
                await loadModuleCatalog(false);
            }
            if (statusChanged) {
                render();
            }
        } catch (error) {
            if (!root.isConnected) {
                stopCatalogPoll();
                return;
            }
            setStatus(runtimeMessage(error, "模块安装状态读取失败"), "error");
            render();
        } finally {
            catalogPollBusy = false;
            if (![...state.installJobs.values()].some(catalogJobActive)) {
                stopCatalogPoll();
            }
        }
    }

    function iconNode(name, size = 18) {
        const node = make("span", "module-control-ui-icon");
        node.innerHTML = context.icon(name, size);
        return node;
    }

    function button(label, icon, className = "module-control-ui-button") {
        const control = make("button", className);
        control.type = "button";
        control.append(iconNode(icon, 17), make("span", "", label));
        return control;
    }

    function buildStatus() {
        const status = make("div", `module-control-ui-status${state.statusKind
            ? ` is-${state.statusKind}`
            : ""}`);
        status.setAttribute("role", "status");
        status.setAttribute("aria-live", "polite");
        if (state.status) {
            status.append(make("span", "", state.status));
        }
        if (state.added.length || state.issues.length) {
            const details = make("span", "module-control-ui-status__details");
            const parts = [];
            if (state.added.length) {
                const names = state.added.map(issueText).slice(0, 3).join("、");
                parts.push(`新增 ${state.added.length} 项${names ? `：${names}` : ""}`);
            }
            if (state.issues.length) {
                const names = state.issues.map(issueText).slice(0, 3).join("、");
                parts.push(`问题 ${state.issues.length} 项${names ? `：${names}` : ""}`);
            }
            details.textContent = parts.join("；");
            status.append(details);
        }
        if (state.needsBuild && state.status !== "请先构建") {
            status.append(make("strong", "module-control-ui-build", "请先构建"));
        }
        return status;
    }

    function snapshotSource(payload) {
        return payload?.state && typeof payload.state === "object" ? payload.state : payload;
    }

    function hasModuleCollection(payload) {
        const source = snapshotSource(payload);
        return Boolean(source && typeof source === "object"
            && (Array.isArray(source.modules) || Array.isArray(source.items)));
    }

    function refreshMetadata(payload) {
        const source = snapshotSource(payload);
        const metadata = source && typeof source === "object" ? source : {};
        return {
            added: listOf(metadata.added ?? payload?.added),
            issues: listOf(metadata.issues ?? payload?.issues),
            needsBuild: metadata.built === false
                || metadata.needsBuild === true
                || metadata.status === "NOT_BUILT"
                || (Array.isArray(metadata.notBuilt) && metadata.notBuilt.length > 0)
        };
    }

    async function readSnapshot() {
        if (!getState) {
            throw new Error("宿主尚未接入模组管理 API");
        }
        return getState();
    }

    function interfaceStateEquals(left, right) {
        return JSON.stringify(left) === JSON.stringify(right);
    }

    async function refreshInterfaceState() {
        if (!getInterfaceState || !root.isConnected || state.loading || state.busy) {
            return;
        }
        try {
            const next = normalizeInterfaceState(await getInterfaceState());
            if (!interfaceStateEquals(state.interfaceState, next)) {
                state.interfaceState = next;
                render();
            }
        } catch {
            // 事件驱动的刷新失败不覆盖当前可用快照；下一次事件或手动刷新会重试。
        }
    }

    function scheduleInterfaceRefresh() {
        // 120ms 防抖把连续框架事件合并成一次读取，避免重复重建同一管理列表。
        if (!getInterfaceState) return;
        if (interfaceRefreshTimer) {
            clearTimeout(interfaceRefreshTimer);
        }
        interfaceRefreshTimer = setTimeout(() => {
            interfaceRefreshTimer = null;
            void refreshInterfaceState();
        }, 120);
    }

    async function reload() {
        state.loading = true;
        state.error = "";
        render();
        try {
            applySnapshot(await readSnapshot());
            if (state.needsBuild) {
                setStatus("", "warning");
            }
        } catch (error) {
            setStatus(runtimeMessage(error, "模组状态读取失败"), "error");
        } finally {
            state.loading = false;
            render();
        }
    }

    async function refreshStatus() {
        if (!refreshModules) {
            setStatus("宿主尚未接入模组刷新 API", "error");
            render();
            return;
        }
        state.busy = true;
        render();
        try {
            const payload = await refreshModules();
            const metadata = refreshMetadata(payload);
            if (hasModuleCollection(payload)) {
                applySnapshot(payload);
            } else {
                applySnapshot(await readSnapshot());
                state.added = metadata.added;
                state.issues = metadata.issues;
                state.needsBuild = metadata.needsBuild;
            }
            setStatus(state.needsBuild ? "请先构建" : "已刷新模组状态。",
                state.needsBuild || state.issues.length ? "warning" : "safe");
        } catch (error) {
            setStatus(runtimeMessage(error, "模组状态刷新失败"), "error");
        } finally {
            state.busy = false;
            render();
        }
    }

    async function loadModuleCatalog(showLoading = true) {
        if (!getModuleCatalog) {
            state.catalogError = "宿主尚未接入模块下载 API";
            state.catalogLoading = false;
            render();
            return false;
        }
        if (showLoading) {
            state.catalogLoading = true;
        }
        state.catalogError = "";
        render();
        try {
            state.catalog = normalizeModuleCatalog(await getModuleCatalog());
            for (const [moduleId, job] of state.installJobs) {
                if (!job.jobId && job.terminal === true) {
                    state.installJobs.delete(moduleId);
                }
            }
            state.catalog.items.forEach((item) => {
                if (item.status === "INSTALLED") {
                    state.installJobs.delete(item.moduleId);
                    return;
                }
                if (!item.jobId) return;
                state.installJobs.set(item.moduleId, {
                    jobId: item.jobId,
                    status: item.status,
                    message: item.message,
                    terminal: !item.jobActive
                });
            });
            scheduleCatalogPoll();
            return true;
        } catch (error) {
            state.catalogError = runtimeMessage(error, "模块下载目录读取失败");
            setStatus(state.catalogError, "error");
            return false;
        } finally {
            state.catalogLoading = false;
            render();
        }
    }

    function openCatalog() {
        clearDeleteConfirmation();
        state.deleteMode = false;
        state.subpage = "catalog";
        state.selectedId = "";
        setStatus("", "");
        render();
        void loadModuleCatalog(true).then(() => {
            if (root.isConnected) {
                root.querySelector(".module-control-ui-back")?.focus();
            }
        });
    }

    async function installCatalogModule(item) {
        if (item.status === "INSTALLED" || !item.canInstall) {
            return;
        }
        if (!installModule) {
            setStatus("宿主尚未接入模块安装 API", "error");
            render();
            return;
        }
        if (state.installJobs.has(item.moduleId)
                && catalogJobActive(state.installJobs.get(item.moduleId))) {
            return;
        }
        state.installJobs.set(item.moduleId, {
            status: "DOWNLOADING",
            message: "正在创建安装任务",
            terminal: false
        });
        render();
        try {
            const accepted = await installModule(item.moduleId);
            if (!accepted || typeof accepted !== "object" || !text(accepted.jobId)) {
                throw new Error("宿主未返回模块安装任务");
            }
            state.installJobs.set(item.moduleId, {
                jobId: text(accepted.jobId),
                status: text(accepted.status, "DOWNLOADING").toUpperCase(),
                message: "安装任务已创建",
                terminal: false
            });
            setStatus(`${item.title}已加入下载队列。`, "safe");
            scheduleCatalogPoll();
        } catch (error) {
            state.installJobs.set(item.moduleId, {
                status: "INSTALL_FAILED",
                message: runtimeMessage(error, "模块安装请求失败"),
                terminal: true
            });
            setStatus(runtimeMessage(error, "模块安装请求失败"), "error");
        }
        render();
    }

    async function retryCatalogBuild(item) {
        if (item.status === "INSTALLED" || !item.canRetryBuild) {
            return;
        }
        if (!retryModuleBuild) {
            setStatus("宿主尚未接入模块重建 API", "error");
            render();
            return;
        }
        try {
            state.installJobs.set(item.moduleId, {
                status: "QUEUED",
                message: "已请求重新构建",
                terminal: false
            });
            setStatus(`${item.title}已加入重建队列。`, "safe");
            render();
            await retryModuleBuild(item.moduleId);
            setTimeout(() => {
                if (root.isConnected) void loadModuleCatalog(false);
            }, 1500);
        } catch (error) {
            state.installJobs.set(item.moduleId, {
                status: "BUILD_FAILED",
                message: runtimeMessage(error, "模块重建请求失败"),
                terminal: true
            });
            setStatus(runtimeMessage(error, "模块重建请求失败"), "error");
            render();
        }
    }

    async function markModuleRemoval(module) {
        if (!removeModule) {
            setStatus("宿主尚未接入模块删除 API", "error");
            render();
            return;
        }
        if (!module.canRemove || module.removalStatus === "PENDING_RESTART") {
            return;
        }
        clearDeleteConfirmation();
        state.busy = true;
        state.removalBusyId = module.moduleId;
        render();
        try {
            const payload = await removeModule(module.moduleId);
            if (hasModuleCollection(payload)) {
                applySnapshot(payload);
            } else {
                applySnapshot(await readSnapshot());
            }
            setStatus(`${module.title}已标记删除，重启后生效。`, "safe");
        } catch (error) {
            try {
                applySnapshot(await readSnapshot());
            } catch {
                // 保留原始删除错误；下一次手动刷新会重新同步宿主状态。
            }
            setStatus(runtimeMessage(error, "模块删除失败"), "error");
        } finally {
            state.busy = false;
            state.removalBusyId = "";
            render();
        }
    }

    async function restoreModuleRemoval(module) {
        if (!undoModuleRemoval) {
            setStatus("宿主尚未接入撤销删除 API", "error");
            render();
            return;
        }
        state.busy = true;
        state.removalBusyId = module.moduleId;
        render();
        try {
            const payload = await undoModuleRemoval(module.moduleId);
            if (hasModuleCollection(payload)) {
                applySnapshot(payload);
            } else {
                applySnapshot(await readSnapshot());
            }
            setStatus(`${module.title}已撤销删除。`, "safe");
        } catch (error) {
            setStatus(runtimeMessage(error, "撤销删除失败"), "error");
        } finally {
            state.busy = false;
            state.removalBusyId = "";
            render();
        }
    }

    async function persistOrder(moduleIds, focusId) {
        const previous = state.modules.map((module) => module.moduleId);
        if (!saveOrder) {
            setStatus("宿主尚未接入模组排序 API", "error");
            state.modules.sort((left, right) =>
                previous.indexOf(left.moduleId) - previous.indexOf(right.moduleId));
            render();
            return;
        }
        // 先乐观更新当前 DOM 顺序以保持拖动反馈，再由宿主返回值或权威快照确认。
        state.modules = moduleIds
            .map((id, index) => {
                const module = state.modules.find((item) => item.moduleId === id);
                return module ? { ...module, managementOrder: index } : null;
            })
            .filter(Boolean);
        render();
        try {
            const payload = await saveOrder(moduleIds);
            if (hasModuleCollection(payload)) {
                applySnapshot(payload);
            } else {
                applySnapshot(await readSnapshot());
            }
            setStatus("模组顺序已保存。", "safe");
        } catch (error) {
            // 保存失败恢复操作前顺序；成功和失败都在重渲染后把焦点送回发起拖动的模块。
            const byId = new Map(state.modules.map((module) => [module.moduleId, module]));
            state.modules = previous.map((id) => byId.get(id)).filter(Boolean);
            setStatus(runtimeMessage(error, "模组顺序保存失败"), "error");
        }
        render();
        root.querySelector(`[data-drag-id="${focusId}"]`)?.focus();
    }

    function finishPointerOrder(moduleId, cancelled = false) {
        // 最终顺序以当前行 DOM 为准；取消时直接重渲染，避免把预览位置误提交给宿主。
        const rows = [...root.querySelectorAll("[data-module-id]")];
        rows.forEach((row) => {
            row.classList.remove("is-dragging", "is-drop-target");
            row.removeAttribute("aria-grabbed");
        });
        if (cancelled) {
            render();
            return;
        }
        const order = rows.map((row) => row.dataset.moduleId).filter(Boolean);
        const current = state.modules.map((module) => module.moduleId);
        if (order.join("\n") === current.join("\n")) return;
        void persistOrder(order, moduleId);
    }

    function bindPointerOrder(handle, row, moduleId) {
        let pointerId = null;

        // 拖动期间临时使用文档级 pointer 监听，鼠标移出手柄后仍可排序，结束或取消时统一移除。
        const move = (event) => {
            if (pointerId !== event.pointerId) return;
            const rows = [...root.querySelectorAll("[data-module-id]")];
            rows.forEach((item) => item.classList.remove("is-drop-target"));
            const target = rows.find((item) => {
                if (item === row) return false;
                const rect = item.getBoundingClientRect();
                return event.clientY >= rect.top && event.clientY <= rect.bottom;
            });
            if (!target) return;
            target.classList.add("is-drop-target");
            const rect = target.getBoundingClientRect();
            const before = event.clientY < rect.top + rect.height / 2;
            target.parentNode.insertBefore(row, before ? target : target.nextSibling);
        };

        const end = (event, cancelled) => {
            if (pointerId !== event.pointerId) return;
            try {
                if (handle.hasPointerCapture?.(pointerId)) {
                    handle.releasePointerCapture(pointerId);
                }
            } catch {
                // 文档级监听不依赖 pointer capture。
            }
            pointerId = null;
            document.removeEventListener("pointermove", move, true);
            document.removeEventListener("pointerup", up, true);
            document.removeEventListener("pointercancel", cancel, true);
            finishPointerOrder(moduleId, cancelled);
        };

        const up = (event) => end(event, false);
        const cancel = (event) => end(event, true);

        handle.addEventListener("pointerdown", (event) => {
            if (event.button !== 0 || state.busy) return;
            event.preventDefault();
            pointerId = event.pointerId;
            try {
                handle.setPointerCapture?.(pointerId);
            } catch {
                // 指针捕获是可增强能力，文档级监听负责完成拖动。
            }
            document.addEventListener("pointermove", move, true);
            document.addEventListener("pointerup", up, true);
            document.addEventListener("pointercancel", cancel, true);
            row.classList.add("is-dragging");
            row.setAttribute("aria-grabbed", "true");
        });
    }

    function bindKeyboardOrder(handle, moduleId) {
        // Alt+方向键提供与指针拖动等价的排序能力，并复用同一持久化和焦点恢复路径。
        handle.addEventListener("keydown", (event) => {
            if (!event.altKey || !["ArrowUp", "ArrowDown"].includes(event.key)) return;
            event.preventDefault();
            const order = state.modules.map((module) => module.moduleId);
            const from = order.indexOf(moduleId);
            const to = Math.max(0, Math.min(order.length - 1,
                from + (event.key === "ArrowUp" ? -1 : 1)));
            if (from < 0 || from === to) return;
            const [moved] = order.splice(from, 1);
            order.splice(to, 0, moved);
            void persistOrder(order, moduleId);
        });
    }

    async function toggleModule(module, input) {
        if (!setEnabled) {
            input.checked = module.enabled;
            setStatus("宿主尚未接入模块启停 API", "error");
            render();
            return;
        }
        const next = input.checked;
        input.disabled = true;
        try {
            const payload = await setEnabled(module.moduleId, next);
            module.enabled = next;
            if (hasModuleCollection(payload)) {
                applySnapshot(payload);
            } else {
                applySnapshot(await readSnapshot());
            }
            setStatus(`${module.title}已${next ? "启用" : "停用"}。`, "safe");
        } catch (error) {
            input.checked = module.enabled;
            setStatus(runtimeMessage(error, "模块状态保存失败"), "error");
        }
        render();
    }

    function openSettings(module) {
        clearDeleteConfirmation();
        state.deleteMode = false;
        state.selectedId = module.moduleId;
        state.subpage = "settings";
        // 编辑从宿主持久化值复制成独立草稿，保存成功后才以宿主返回值更新模块快照。
        state.draft = {};
        module.settings.forEach((field) => {
            const current = module.values[field.key];
            state.draft[field.key] = current === undefined ? field.defaultValue : current;
        });
        setStatus("", "");
        render();
        root.querySelector(".module-control-ui-settings-scroll input, .module-control-ui-settings-scroll select")
            ?.focus();
    }

    async function toggleInterface(module, iface, input) {
        if (!setInterfaceAiCallable) {
            input.checked = interfaceStateFor(
                state.interfaceState,
                iface.kind,
                module.moduleId,
                iface.id
            );
            setStatus("宿主尚未接入接口 AI 开关 API", "error");
            render();
            return;
        }
        const previous = interfaceStateFor(
            state.interfaceState,
            iface.kind,
            module.moduleId,
            iface.id
        );
        const requested = input.checked;
        input.disabled = true;
        try {
            const payload = await setInterfaceAiCallable(
                module.moduleId,
                iface.kind,
                iface.id,
                requested
            );
            applySnapshot(payload);
            setStatus(
                `${iface.title}已${requested ? "允许" : "禁止"} AI 调用。`,
                "safe"
            );
        } catch (error) {
            input.checked = previous;
            const message = runtimeMessage(error, "接口 AI 开关保存失败");
            try {
                applySnapshot(await readSnapshot());
            } catch {
                // 保留原始保存错误；读取失败时仍由下一次手动刷新恢复。
            }
            setStatus(message, "error");
        }
        render();
    }

    function buildInterfaceRow(module, iface) {
        const row = make("div", "module-control-ui-interface");
        const dynamicEnabled = interfaceStateFor(
            state.interfaceState,
            iface.kind,
            module.moduleId,
            iface.id
        );
        const inUse = iface.kind === "action"
            && actionIsInUse(state.interfaceState, module.moduleId, iface.id);
        const checked = dynamicEnabled;
        const systemControlled = module.protected || module.role === MODULE_CONTROL_ROLE;

        const title = make("strong", "module-control-ui-interface__title", iface.title);
        const description = make(
            "span",
            "module-control-ui-interface__description",
            iface.description || "尚未提供接口说明。"
        );

        const control = make("div", "module-control-ui-interface__control");
        if (inUse) {
            control.append(make("span", "module-control-ui-interface__state", "使用中"));
        }

        if (systemControlled) {
            control.append(make(
                "span",
                "module-control-ui-interface__state module-control-ui-interface__state--fixed",
                "宿主治理接口"
            ));
        } else {
            const switchLabel = make(
                "label",
                "module-control-ui-switch module-control-ui-switch--interface"
            );
            const checkbox = make("input");
            checkbox.type = "checkbox";
            checkbox.checked = checked;
            checkbox.disabled = state.busy || inUse;
            const moduleSuffix = module.enabled ? "" : "；模块当前停用";
            checkbox.setAttribute(
                "aria-label",
                `${iface.title}${checked ? "允许" : "禁止"} AI 调用${moduleSuffix}`
            );
            const track = make("span", "module-control-ui-switch__track");
            switchLabel.append(checkbox, track);
            checkbox.addEventListener("change", () => void toggleInterface(module, iface, checkbox));
            control.append(switchLabel);
        }

        if (!module.enabled) {
            row.classList.add("is-module-disabled");
        }
        if (inUse) {
            row.classList.add("is-locked");
        }
        row.append(title, description, control);
        return row;
    }

    function buildInterfaceGroup(module, title, items, kind) {
        const group = make("section", "module-control-ui-interface-group");
        const heading = make("header", "module-control-ui-interface-group__header");
        heading.append(
            make("h3", "", title),
            make("span", "", module.protected || module.role === MODULE_CONTROL_ROLE
                ? "系统治理接口由宿主执行，不能在模组设置中关闭"
                : kind === "query"
                    ? "仅影响 AI 查询；模块自身查询不受限制"
                    : "仅影响 AI 计划与 AI 直执")
        );
        group.append(heading);
        if (!items.length) {
            group.append(make("p", "module-control-ui-empty", "尚未声明接口"));
            return group;
        }
        items.forEach((iface) => group.append(buildInterfaceRow(module, iface)));
        return group;
    }

    function buildInterfacePanel(module) {
        const panel = make("section", "module-control-ui-interfaces");
        panel.id = `module-control-interfaces-${module.moduleId}`;
        const clip = make("div", "module-control-ui-interfaces__clip");
        const content = make("div", "module-control-ui-interfaces__content");
        content.append(
            buildInterfaceGroup(module, "动作接口", module.actions, "action"),
            buildInterfaceGroup(module, "查询接口", module.queries, "query")
        );
        clip.append(content);
        panel.append(clip);
        return panel;
    }

    function buildRow(module) {
        const row = make("article", "module-control-ui-row");
        row.dataset.moduleId = module.moduleId;
        row.setAttribute("role", "listitem");
        if (module.protected || module.role === MODULE_CONTROL_ROLE) {
            row.classList.add("is-protected");
        }
        const pendingRemoval = module.removalStatus === "PENDING_RESTART";
        row.classList.toggle("is-pending-removal", pendingRemoval);

        const header = make("div", "module-control-ui-row-header");
        const handle = make("button", "module-control-ui-drag");
        handle.type = "button";
        handle.disabled = state.busy || state.deleteMode || pendingRemoval;
        handle.title = `拖动排序 ${module.title}`;
        handle.setAttribute("aria-label", `拖动排序 ${module.title}`);
        handle.setAttribute("aria-keyshortcuts", "Alt+ArrowUp Alt+ArrowDown");
        handle.append(iconNode("menu", 18));
        bindPointerOrder(handle, row, module.moduleId);
        bindKeyboardOrder(handle, module.moduleId);

        const moduleIcon = make("span", "module-control-ui-module-icon");
        moduleIcon.innerHTML = context.icon(module.icon, 20);
        const expanded = state.expandedIds.has(module.moduleId);
        const canExpandInterfaces = true;
        const interfacePanel = canExpandInterfaces ? buildInterfacePanel(module) : null;
        if (interfacePanel) {
            interfacePanel.classList.toggle("is-open", expanded);
            interfacePanel.toggleAttribute("inert", !expanded);
            interfacePanel.setAttribute("aria-hidden", String(!expanded));
        }
        if (canExpandInterfaces && expanded) {
            row.classList.add("is-expanded");
        }
        const copy = make(canExpandInterfaces ? "button" : "div", "module-control-ui-copy");
        if (canExpandInterfaces) {
            copy.type = "button";
            copy.id = `module-control-expand-${module.moduleId}`;
            copy.dataset.expandId = module.moduleId;
            copy.setAttribute("aria-expanded", String(expanded));
            copy.setAttribute("aria-controls", `module-control-interfaces-${module.moduleId}`);
        } else {
            copy.classList.add("is-static");
        }
        copy.append(
            make("strong", "module-control-ui-title", module.title),
            make("span", "", module.description || "尚无模块说明")
        );
        if (pendingRemoval) {
            copy.append(make(
                "span",
                "module-control-ui-removal-badge",
                module.removalMessage || "重启后生效"
            ));
        }
        const toggleInterfaces = () => {
            const nextExpanded = !state.expandedIds.has(module.moduleId);
            if (nextExpanded) {
                state.expandedIds.add(module.moduleId);
            } else {
                state.expandedIds.delete(module.moduleId);
            }
            row.classList.toggle("is-expanded", nextExpanded);
            copy.setAttribute("aria-expanded", String(nextExpanded));
            interfacePanel.classList.toggle("is-open", nextExpanded);
            interfacePanel.toggleAttribute("inert", !nextExpanded);
            interfacePanel.setAttribute("aria-hidden", String(!nextExpanded));
        };

        const rowActions = make("div", "module-control-ui-row-actions");
        const settingsButton = make("button", "module-control-ui-square");
        settingsButton.type = "button";
        settingsButton.title = `打开 ${module.title} 设置`;
        settingsButton.setAttribute("aria-label", `打开 ${module.title} 设置`);
        settingsButton.append(iconNode("settings", 18));
        settingsButton.disabled = state.busy || state.deleteMode || pendingRemoval;
        settingsButton.addEventListener("click", () => openSettings(module));

        const protectedModule = module.protected || module.role === MODULE_CONTROL_ROLE;
        if (pendingRemoval) {
            const undoButton = button(
                state.removalBusyId === module.moduleId ? "处理中…" : "已删除",
                "undo",
                "module-control-ui-button is-danger module-control-ui-removal-action"
            );
            undoButton.type = "button";
            undoButton.title = "点击撤销删除";
            undoButton.setAttribute("aria-label", `撤销删除 ${module.title}`);
            undoButton.disabled = state.busy || state.removalBusyId === module.moduleId;
            undoButton.addEventListener("click", () => void restoreModuleRemoval(module));
            rowActions.append(settingsButton, undoButton);
        } else if (state.deleteMode) {
            if (module.canRemove && !protectedModule) {
                const confirming = state.pendingDeleteId === module.moduleId;
                const removeButton = button(
                    state.removalBusyId === module.moduleId
                        ? "处理中…"
                        : confirming ? "确认删除？" : "删除",
                    "trash",
                    "module-control-ui-button is-danger module-control-ui-removal-action"
                );
                removeButton.type = "button";
                removeButton.setAttribute("aria-pressed", String(confirming));
                removeButton.setAttribute("aria-label", confirming
                    ? `确认删除 ${module.title}`
                    : `删除 ${module.title}`);
                removeButton.disabled = state.busy || state.removalBusyId === module.moduleId;
                removeButton.addEventListener("click", () => {
                    if (state.pendingDeleteId !== module.moduleId) {
                        beginDeleteConfirmation(module.moduleId);
                        return;
                    }
                    void markModuleRemoval(module);
                });
                rowActions.append(settingsButton, removeButton);
            } else {
                rowActions.append(
                    settingsButton,
                    make(
                        "span",
                        "module-control-ui-removal-unavailable",
                        protectedModule ? "受保护模块" : "不可删除"
                    )
                );
            }
        } else {
            const switchLabel = make("label", "module-control-ui-switch");
            const checkbox = make("input");
            checkbox.type = "checkbox";
            checkbox.checked = module.enabled;
            checkbox.disabled = state.busy || protectedModule;
            checkbox.setAttribute(
                "aria-label",
                protectedModule
                    ? `${module.title}是受保护系统模块，不能停用`
                    : `${module.title}${module.enabled ? "已启用" : "已停用"}`
            );
            const track = make("span", "module-control-ui-switch__track");
            switchLabel.append(checkbox, track);
            checkbox.addEventListener("change", () => void toggleModule(module, checkbox));
            rowActions.append(settingsButton, switchLabel);
        }

        header.append(handle, moduleIcon, copy, rowActions);
        if (canExpandInterfaces) {
            header.classList.add("is-expandable");
            // 由标题栏统一接收点击，同时让拖动、设置和启停控件保持独立。
            header.addEventListener("click", (event) => {
                if (event.target.closest?.(
                    ".module-control-ui-drag, .module-control-ui-row-actions button, " +
                    ".module-control-ui-removal-unavailable, .module-control-ui-switch"
                )) {
                    return;
                }
                toggleInterfaces();
            });
        }
        row.append(header);
        if (interfacePanel) {
            row.append(interfacePanel);
        }
        return row;
    }

    function buildListPanel() {
        const body = make("div", "module-control-ui-list-view");
        const toolbar = make("div", "module-control-ui-toolbar");
        toolbar.setAttribute("role", "toolbar");
        toolbar.setAttribute("aria-label", "模组状态操作");

        const refresh = button("刷新模组状态", "refresh");
        refresh.disabled = state.busy;
        refresh.addEventListener("click", () => void refreshStatus());
        const folder = button("打开 Module 文件夹", "folder");
        folder.disabled = state.busy;
        folder.addEventListener("click", async () => {
            if (!openFolder) {
                setStatus("宿主尚未接入打开 Module 文件夹 API", "error");
                render();
                return;
            }
            try {
                await openFolder();
                setStatus("已请求宿主打开 Module 文件夹。", "safe");
            } catch (error) {
                setStatus(runtimeMessage(error, "无法打开 Module 文件夹"), "error");
            }
            render();
        });
        const catalog = button("模块下载", "download");
        catalog.disabled = state.busy;
        catalog.dataset.catalogEntry = "true";
        catalog.addEventListener("click", openCatalog);
        const canRemoveAny = state.modules.some((module) => module.canRemove);
        const deleteMode = button(
            state.deleteMode ? "退出删除" : "删除",
            "trash",
            `module-control-ui-button module-control-ui-toolbar-delete${
                state.deleteMode ? " is-danger" : ""
            }`
        );
        deleteMode.type = "button";
        deleteMode.setAttribute("aria-pressed", String(state.deleteMode));
        deleteMode.title = state.deleteMode ? "退出删除模式" : "进入删除模式";
        deleteMode.disabled = state.busy || (!state.deleteMode && !canRemoveAny);
        deleteMode.addEventListener("click", toggleDeleteMode);
        toolbar.append(refresh, folder, catalog, deleteMode);

        const status = buildStatus();
        const scroller = make("div", "module-control-ui-list-scroll");
        const list = make("div", "module-control-ui-list");
        list.setAttribute("role", "list");
        if (state.loading) {
            list.append(make("p", "module-control-ui-empty", "正在读取模组状态…"));
        } else if (state.error) {
            list.append(make("p", "module-control-ui-empty", "模组状态暂不可用。"));
        } else if (!state.modules.length) {
            if (state.needsBuild) {
                list.append(make("p", "module-control-ui-empty", "请先构建；当前没有可显示的业务模块。"));
            }
            list.append(make("p", "module-control-ui-empty", "尚无业务模块。"));
        } else {
            if (state.needsBuild) {
                list.append(make("p", "module-control-ui-build", "请先构建；当前运行中的模块仍可继续管理。"));
            }
            state.modules.forEach((module) => list.append(buildRow(module)));
        }
        scroller.append(list);
        body.append(toolbar, status, scroller);
        return body;
    }

    function buildCatalogRow(item) {
        const row = make("article", "module-control-ui-catalog-row");
        const moduleIcon = make("span", "module-control-ui-module-icon");
        moduleIcon.innerHTML = context.icon("modules", 20);
        const copy = make("div", "module-control-ui-catalog-copy");
        const title = make("strong", "", item.title);
        const description = make("span", "", item.description || "模块未提供说明");
        const meta = make("span", "module-control-ui-catalog-meta");
        const status = effectiveCatalogStatus(item);
        const job = state.installJobs.get(item.moduleId);
        meta.textContent = [
            item.version ? `版本 ${item.version}` : "",
            formatBytes(item.size),
            catalogStatusLabel(status, job?.message ?? item.message)
        ].filter(Boolean).join(" · ");
        copy.append(title, description, meta);

        const action = make("div", "module-control-ui-catalog-action");
        const stateLabel = make(
            "span",
            `module-control-ui-catalog-state is-${status.toLowerCase()}`
        );
        stateLabel.textContent = catalogStatusLabel(status, job?.message ?? item.message);
        const actionState = catalogActionState(item, status, job, {
            install: Boolean(installModule),
            retryBuild: Boolean(retryModuleBuild)
        });
        action.append(stateLabel);
        if (actionState.kind !== "none") {
            const actionButton = button(
                actionState.label,
                actionState.icon,
                "module-control-ui-button is-primary"
            );
            actionButton.disabled = actionState.disabled;
            actionButton.addEventListener("click", () => {
                if (actionState.kind === "retry-build") {
                    void retryCatalogBuild(item);
                } else if (actionState.kind === "install") {
                    void installCatalogModule(item);
                }
            });
            action.append(actionButton);
        }
        row.append(moduleIcon, copy, action);
        return row;
    }

    function buildCatalogPanel() {
        const body = make("div", "module-control-ui-catalog");
        const toolbar = make("div", "module-control-ui-settings-toolbar");
        const back = button("返回", "arrow", "module-control-ui-button module-control-ui-back");
        back.addEventListener("click", () => {
            state.subpage = "list";
            setStatus("", "");
            render();
            root.querySelector("[data-catalog-entry='true']")?.focus();
        });
        const reloadCatalog = button("刷新目录", "refresh");
        reloadCatalog.disabled = state.catalogLoading;
        reloadCatalog.addEventListener("click", () => void loadModuleCatalog(true));
        toolbar.append(back, reloadCatalog);

        const heading = make("header", "module-control-ui-catalog-header");
        const copy = make("div");
        const source = [
            state.catalog.sourceCommit ? `来源提交 ${state.catalog.sourceCommit.slice(0, 12)}` : "",
            state.catalog.generatedAt ? `生成于 ${state.catalog.generatedAt}` : ""
        ].filter(Boolean).join(" · ");
        copy.append(
            make("h2", "", "模块下载"),
            make(
                "p",
                "",
                source || "读取固定 GitHub 分发分支；下载后由本地宿主校验、构建并追加。"
            )
        );
        heading.append(copy);

        const status = buildStatus();
        const scroller = make("div", "module-control-ui-catalog-scroll");
        const list = make("div", "module-control-ui-catalog-list");
        if (state.catalogLoading) {
            list.append(make("p", "module-control-ui-empty", "正在读取模块下载目录…"));
        } else if (state.catalogError) {
            list.append(make("p", "module-control-ui-empty", state.catalogError));
        } else if (!state.catalog.items.length) {
            list.append(make("p", "module-control-ui-empty", "分发目录暂无可下载模块。"));
        } else {
            state.catalog.items.forEach((item) => list.append(buildCatalogRow(item)));
        }
        scroller.append(list);
        body.append(toolbar, heading, status, scroller);
        return body;
    }

    function normalizeDraftValue(field) {
        const value = state.draft[field.key];
        if (field.type === "boolean") return value === true;
        if (field.type === "integer") {
            const number = Number(value);
            return Number.isFinite(number) ? Math.trunc(number) : Number(field.defaultValue) || 0;
        }
        if (field.type === "select") {
            return field.options.some((option) => option.value === String(value))
                ? String(value)
                : String(field.defaultValue);
        }
        return value == null ? "" : String(value);
    }

    function buildField(field, index) {
        const wrapper = make("div", `module-control-ui-field is-${field.type}`);
        const label = make("label", "module-control-ui-field__label");
        const inputId = `module-control-setting-${index}`;
        label.htmlFor = inputId;
        label.append(make("strong", "", field.title));
        if (field.description) {
            label.append(make("span", "", field.description));
        }

        if (field.type === "boolean") {
            const controlLabel = make("label", "module-control-ui-switch module-control-ui-switch--field");
            const input = make("input");
            input.id = inputId;
            input.type = "checkbox";
            input.checked = state.draft[field.key] === true;
            input.addEventListener("change", () => {
                state.draft[field.key] = input.checked;
            });
            controlLabel.append(input, make("span", "module-control-ui-switch__track"));
            wrapper.append(label, controlLabel);
            return wrapper;
        }

        let input;
        if (field.type === "select") {
            input = make("select");
            field.options.forEach((option) => {
                const optionNode = make("option", "", option.label);
                optionNode.value = option.value;
                input.append(optionNode);
            });
            input.value = normalizeDraftValue(field);
            input.addEventListener("change", () => {
                state.draft[field.key] = input.value;
            });
        } else {
            input = make("input");
            input.type = field.type === "integer" ? "number" : "text";
            if (field.type === "integer") {
                input.step = String(field.step ?? 1);
                if (field.min !== undefined) input.min = String(field.min);
                if (field.max !== undefined) input.max = String(field.max);
            } else {
                if (field.placeholder) input.placeholder = field.placeholder;
                if (field.minLength !== undefined) input.minLength = Number(field.minLength);
                if (field.maxLength !== undefined) input.maxLength = Number(field.maxLength);
            }
            input.value = String(normalizeDraftValue(field));
            input.addEventListener("input", () => {
                state.draft[field.key] = field.type === "integer"
                    ? input.valueAsNumber
                    : input.value;
            });
        }
        input.id = inputId;
        wrapper.append(label, input);
        return wrapper;
    }

    function defaultValues(module) {
        return Object.fromEntries(module.settings.map((field) => [field.key, field.defaultValue]));
    }

    async function persistSettings(values, message) {
        const module = selectedModule();
        if (!module || !saveSettings) {
            setStatus("宿主尚未接入模块设置保存 API", "error");
            render();
            return;
        }
        state.busy = true;
        render();
        try {
            const payload = await saveSettings(module.moduleId, values);
            const saved = payload && typeof payload === "object" && !Array.isArray(payload)
                ? payload
                : values;
            module.values = { ...values, ...saved };
            state.draft = { ...module.values };
            setStatus(message, "safe");
        } catch (error) {
            setStatus(runtimeMessage(error, "模块设置保存失败"), "error");
        } finally {
            state.busy = false;
            render();
        }
    }

    function buildSettingsPanel(module) {
        const body = make("div", "module-control-ui-settings");
        const toolbar = make("div", "module-control-ui-settings-toolbar");
        const back = button("返回", "arrow", "module-control-ui-button module-control-ui-back");
        back.addEventListener("click", () => {
            state.subpage = "list";
            state.selectedId = "";
            setStatus("", "");
            render();
            root.querySelector(`[data-module-id="${module.moduleId}"] .module-control-ui-square`)?.focus();
        });
        toolbar.append(back);

        const heading = make("header", "module-control-ui-settings-header");
        const moduleIcon = make("span", "module-control-ui-module-icon");
        moduleIcon.innerHTML = context.icon(module.icon, 22);
        const copy = make("div");
        copy.append(
            make("h2", "", module.title),
            make("p", "", module.description || "模块未提供说明")
        );
        heading.append(moduleIcon, copy);

        const status = buildStatus();
        const scroller = make("div", "module-control-ui-settings-scroll");
        if (!module.settings.length) {
            scroller.append(make("p", "module-control-ui-empty", "尚未提供设置"));
        } else {
            const form = make("form", "module-control-ui-form");
            module.settings.forEach((field, index) => form.append(buildField(field, index)));
            const actions = make("div", "module-control-ui-form-actions");
            const reset = button("恢复默认", "undo");
            reset.disabled = state.busy;
            reset.addEventListener("click", async () => {
                const defaults = defaultValues(module);
                state.draft = { ...defaults };
                if (resetSettings) {
                    state.busy = true;
                    render();
                    try {
                        const payload = await resetSettings(module.moduleId);
                        const reset = payload && typeof payload === "object" && !Array.isArray(payload)
                            ? payload
                            : {};
                        module.values = { ...defaults, ...reset };
                        state.draft = { ...module.values };
                        setStatus("已恢复默认设置。", "safe");
                    } catch (error) {
                        setStatus(runtimeMessage(error, "恢复默认设置失败"), "error");
                    } finally {
                        state.busy = false;
                        render();
                    }
                } else {
                    await persistSettings(defaults, "已恢复默认设置。");
                }
            });
            const save = button("保存设置", "check", "module-control-ui-button is-primary");
            save.disabled = state.busy;
            save.addEventListener("click", async (event) => {
                event.preventDefault();
                const values = Object.fromEntries(module.settings.map((field) => [
                    field.key,
                    normalizeDraftValue(field)
                ]));
                await persistSettings(values, "模块设置已保存。");
            });
            actions.append(reset, save);
            form.addEventListener("submit", (event) => event.preventDefault());
            form.append(actions);
            scroller.append(form);
        }

        body.append(toolbar, heading, status, scroller);
        return body;
    }

    function render() {
        // 全量重建当前视图是本地状态模型的一部分；需要连续操作的控件在调用处显式恢复焦点。
        const panel = make("section", "module-control-ui-panel");
        const header = make("header", "module-control-ui-header");
        header.append(
            make("p", "module-control-ui-eyebrow", "MODULE CONTROL"),
            make("h1", "", context.module.title || "模组设置"),
            make("p", "module-control-ui-description",
                context.module.description || "统一管理本机业务模块的启停、排序与模块设置。")
        );
        panel.append(header);

        const module = state.subpage === "settings" ? selectedModule() : null;
        if (module) {
            panel.append(buildSettingsPanel(module));
        } else if (state.subpage === "catalog") {
            panel.append(buildCatalogPanel());
        } else {
            state.subpage = "list";
            panel.append(buildListPanel());
        }
        root.replaceChildren(panel);
    }

    render();
    await reload();
    if (typeof context.subscribeEvents === "function") {
        let unsubscribe = null;
        // 框架事件只触发接口状态轻量刷新；根节点脱离文档后取消 timer 和订阅，阻止死后重渲染。
        unsubscribe = context.subscribeEvents(() => {
            if (!root.isConnected) {
                clearDeleteConfirmation();
                if (interfaceRefreshTimer) {
                    clearTimeout(interfaceRefreshTimer);
                    interfaceRefreshTimer = null;
                }
                stopCatalogPoll();
                unsubscribe?.();
                return;
            }
            scheduleInterfaceRefresh();
        });
    }
}
