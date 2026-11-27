/**
 * 模块与动效生成器系统视图。
 *
 * 界面只调用宿主生成接口，不在模块内持有文件权限或模型密钥。
 * 候选构建、镜像预览、版本应用和步骤连带回滚均由宿主服务担保。
 */

function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function listOf(value) {
    return Array.isArray(value) ? value : [];
}

function moduleIsBusiness(module) {
    return module?.source !== "system" && !module?.system;
}

export function normalizeCodegenModules(modules) {
    return listOf(modules)
        .filter(moduleIsBusiness)
        .map((module) => ({
            moduleId: text(module.moduleId),
            title: text(module.title, text(module.moduleId, "未命名模块")),
            description: text(module.description),
            enabled: module.enabled !== false,
            order: Number.isFinite(Number(module.order)) ? Number(module.order) : 0,
            actions: listOf(module.actions),
            queries: listOf(module.queries),
            plannerRules: listOf(module.plannerRules)
        }))
        .filter((module) => module.moduleId)
        .sort((left, right) =>
            Number(left.order ?? 0) - Number(right.order ?? 0)
            || left.moduleId.localeCompare(right.moduleId));
}

export function interfaceRows(module) {
    return [
        ...listOf(module?.actions).map((item) => ({
            kind: "Action",
            id: text(item.id),
            title: text(item.title, text(item.id)),
            risk: text(item.risk, "UNKNOWN"),
            detail: `${listOf(item.parameters).length} 参数`
        })),
        ...listOf(module?.queries).map((item) => ({
            kind: "Query",
            id: text(item.id),
            title: text(item.title, text(item.id)),
            risk: "READ_ONLY",
            detail: `${listOf(item.parameters).length} 参数`
        })),
        ...listOf(module?.plannerRules).map((item) => ({
            kind: "Plan",
            id: text(item.id),
            title: text(item.title, text(item.id)),
            risk: "LOCAL",
            detail: text(item.pattern, "本地规则")
        }))
    ].filter((item) => item.id);
}

export function normalizeCodegenSession(input) {
    if (!input || typeof input !== "object") return null;
    return {
        sessionId: text(input.sessionId),
        mode: text(input.mode),
        motionTarget: text(input.motionTarget),
        themeId: text(input.themeId),
        moduleId: text(input.moduleId),
        summary: text(input.summary),
        status: text(input.status, "GENERATED"),
        buildStatus: text(input.buildStatus, "PENDING"),
        buildMessage: text(input.buildMessage),
        previewStatus: text(input.previewStatus, "STOPPED"),
        previewUrl: text(input.previewUrl),
        applyVersionId: text(input.applyVersionId),
        steps: listOf(input.steps).map((step) => ({
            stepId: text(step?.stepId),
            index: Number(step?.index) || 0,
            title: text(step?.title),
            path: text(step?.path),
            status: text(step?.status, "GENERATED"),
            existedBefore: step?.existedBefore === true,
            bytes: Number(step?.bytes) || 0
        })).filter((step) => step.stepId && step.path),
        interfaces: input.interfaces && typeof input.interfaces === "object"
            ? input.interfaces
            : {},
        versions: listOf(input.versions).map((version) => ({
            versionId: text(version?.versionId),
            status: text(version?.status),
            createdAt: text(version?.createdAt)
        })).filter((version) => version.versionId),
        createdAt: text(input.createdAt),
        updatedAt: text(input.updatedAt)
    };
}

export function normalizeCodegenSessions(input) {
    return listOf(input)
        .map((session) => ({
            sessionId: text(session?.sessionId),
            mode: text(session?.mode),
            moduleId: text(session?.moduleId),
            status: text(session?.status, "GENERATED"),
            buildStatus: text(session?.buildStatus, "PENDING"),
            previewStatus: text(session?.previewStatus, "STOPPED"),
            stepCount: Number(session?.stepCount) || 0,
            createdAt: text(session?.createdAt),
            updatedAt: text(session?.updatedAt)
        }))
        .filter((session) => session.sessionId)
        .sort((left, right) =>
            sessionTimestamp(right.updatedAt) - sessionTimestamp(left.updatedAt));
}

export function selectCodegenSession(input, {
    mode = "module",
    moduleId = "",
    allowFallback = true
} = {}) {
    const normalizedMode = text(mode);
    const normalizedModuleId = text(moduleId);
    const candidates = normalizeCodegenSessions(input)
        .filter((session) => session.mode === normalizedMode);
    if (!candidates.length) return null;
    if (normalizedMode === "module") {
        if (normalizedModuleId) {
            const exact = candidates.filter(
                (session) => session.moduleId === normalizedModuleId
            );
            if (exact.length) return exact[0];
        } else if (!allowFallback) {
            return null;
        }
        if (!allowFallback) return null;
    }
    return candidates[0];
}

export function codegenPreviewAction(input) {
    const session = input && typeof input === "object" ? input : null;
    if (!session?.sessionId) {
        return { kind: "unavailable", label: "生成后可预览", url: "" };
    }
    if (session.buildStatus !== "READY") {
        return { kind: "unavailable", label: "等待构建完成", url: "" };
    }
    const url = text(session.previewUrl);
    if (session.previewStatus === "READY" && url) {
        return { kind: "open", label: "打开镜像预览", url };
    }
    return { kind: "start", label: "启动镜像预览", url: "" };
}

function sessionTimestamp(value) {
    const parsed = Date.parse(text(value));
    return Number.isFinite(parsed) ? parsed : 0;
}

function normalizeThemes(themes) {
    return listOf(themes)
        .map((theme) => ({
            id: text(theme.id),
            label: text(theme.label, text(theme.id)),
            default: theme.default === true
        }))
        .filter((theme) => theme.id);
}

export default async function mount(root, context) {
    const make = context.element;
    const modules = normalizeCodegenModules(context.moduleCatalog);
    const themes = normalizeThemes(context.themeCatalog);
    const initialThemeId = context.themeId
        ?? themes.find((theme) => theme.default)?.id
        ?? themes[0]?.id
        ?? "";
    root.dataset.moduleCatalogSize = String(listOf(context.moduleCatalog).length);
    root.dataset.themeCatalogSize = String(listOf(context.themeCatalog).length);
    const state = {
        mode: "module",
        moduleTarget: modules[0]?.moduleId ?? "",
        motionTarget: "framework",
        themeId: initialThemeId,
        modulePrompt: "",
        motionPrompt: "",
        status: "",
        session: null,
        sessionLoading: false,
        sessionRequestId: 0,
        busy: false,
        modelConfigured: false,
        moduleTargetExpanded: false,
        pendingGenerationKind: ""
    };

    function icon(name, size = 17) {
        const holder = make("span", "module-codegen-ui-icon");
        holder.innerHTML = context.icon(name, size);
        return holder;
    }

    function button(label, iconName, className = "module-codegen-ui-button") {
        const node = make("button", className);
        node.type = "button";
        if (iconName) node.append(icon(iconName));
        node.append(make("span", "", label));
        return node;
    }

    function previewLink(url, label, className = "module-codegen-ui-button is-small") {
        const node = make("a", `${className} module-codegen-ui-preview-link`);
        node.href = url;
        node.target = "_blank";
        node.rel = "noopener noreferrer";
        node.append(icon("route"), make("span", "", label));
        return node;
    }

    function selectedModule() {
        return modules.find((module) => module.moduleId === state.moduleTarget) ?? null;
    }

    function setStatus(message) {
        state.status = message;
        render();
    }

    async function request(path, { method = "GET", body, action } = {}) {
        const response = await fetch(path, {
            method,
            cache: "no-store",
            headers: {
                Accept: "application/json",
                ...(body === undefined ? {} : { "Content-Type": "application/json" }),
                ...(action ? { "X-Orderly-Action": action } : {})
            },
            ...(body === undefined ? {} : { body: JSON.stringify(body) })
        });
        const text = await response.text();
        let payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch {
                payload = text;
            }
        }
        if (!response.ok) {
            const message = payload && typeof payload === "object"
                ? payload.message ?? payload.error
                : payload;
            throw new Error(text(message, `请求失败（${response.status}）`));
        }
        return payload;
    }

    async function refreshModelState() {
        try {
            const settings = await request("/api/ai/settings");
            state.modelConfigured = settings?.configured === true;
        } catch {
            state.modelConfigured = false;
        }
    }

    async function ensureModelConfigured() {
        const settings = await request("/api/ai/settings");
        state.modelConfigured = settings?.configured === true;
        return state.modelConfigured;
    }

    function openApiDialog(kind) {
        root.querySelector(".module-codegen-ui-api-dialog")?.remove();
        state.pendingGenerationKind = kind;
        const dialog = make("dialog", "module-codegen-ui-api-dialog");
        dialog.setAttribute("aria-labelledby", "module-codegen-api-title");
        const form = make("form", "module-codegen-ui-api-dialog-form");
        const title = make("h2", "", "配置 API");
        title.id = "module-codegen-api-title";
        const description = make(
            "p",
            "module-codegen-ui-api-dialog-copy",
            "首次生成需要 DeepSeek API Key；凭据只发送到本机 AI 服务。"
        );
        const field = make("label", "module-codegen-ui-field");
        field.append(make("span", "module-codegen-ui-label", "API Key"));
        const input = make("input", "module-codegen-ui-input");
        input.type = "password";
        input.autocomplete = "off";
        input.spellcheck = false;
        input.placeholder = "输入 DeepSeek API Key";
        field.append(input);
        const error = make("p", "module-codegen-ui-api-dialog-error");
        error.setAttribute("role", "alert");
        error.hidden = true;
        const actions = make("footer", "module-codegen-ui-api-dialog-actions");
        const cancel = make("button", "module-codegen-ui-button", "取消");
        cancel.type = "button";
        const submit = make("button", "module-codegen-ui-button is-primary", "保存并继续");
        submit.type = "submit";
        actions.append(cancel, submit);
        form.append(title, description, field, error, actions);
        dialog.append(form);

        const cleanup = () => {
            input.value = "";
            dialog.remove();
        };
        cancel.addEventListener("click", () => dialog.close());
        dialog.addEventListener("click", (event) => {
            if (event.target === dialog) dialog.close();
        });
        dialog.addEventListener("close", cleanup, { once: true });
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            const apiKey = input.value.trim();
            if (!apiKey) {
                error.textContent = "请输入 API Key。";
                error.hidden = false;
                input.focus();
                return;
            }
            submit.disabled = true;
            cancel.disabled = true;
            error.hidden = true;
            try {
                const settings = await request("/api/ai/settings", {
                    method: "PUT",
                    body: { mode: "DEEPSEEK", apiKey }
                });
                input.value = "";
                state.modelConfigured = settings?.configured === true;
                const pendingKind = state.pendingGenerationKind;
                state.pendingGenerationKind = "";
                dialog.close();
                if (pendingKind) {
                    await startGeneration(pendingKind);
                }
            } catch (requestError) {
                error.textContent = requestError.message;
                error.hidden = false;
                submit.disabled = false;
                cancel.disabled = false;
                input.focus();
            }
        });
        root.append(dialog);
        dialog.showModal();
        input.focus();
    }

    function applySession(payload) {
        state.session = normalizeCodegenSession(payload);
        if (state.session) {
            if (state.session.mode) state.mode = state.session.mode;
            if (state.session.moduleId) state.moduleTarget = state.session.moduleId;
            if (state.session.motionTarget === "framework") {
                state.motionTarget = "framework";
            } else if (state.session.motionTarget === "module" && state.session.moduleId) {
                state.motionTarget = state.session.moduleId;
            }
            if (state.session.themeId) state.themeId = state.session.themeId;
        }
    }

    async function restoreSession({
        mode = state.mode,
        moduleId = state.moduleTarget,
        allowFallback = true
    } = {}) {
        const requestId = ++state.sessionRequestId;
        state.sessionLoading = true;
        state.status = "";
        render();
        try {
            const sessions = await request("/api/codegen/sessions");
            if (requestId !== state.sessionRequestId || state.mode !== mode) return;
            const selected = selectCodegenSession(sessions, {
                mode,
                moduleId,
                allowFallback
            });
            if (!selected) {
                state.session = null;
                return;
            }
            const payload = await request(
                `/api/codegen/sessions/${encodeURIComponent(selected.sessionId)}`
            );
            if (requestId !== state.sessionRequestId || state.mode !== mode) return;
            applySession(payload);
        } catch (error) {
            if (requestId !== state.sessionRequestId || state.mode !== mode) return;
            state.status = `恢复生成记录失败：${error.message}`;
        } finally {
            if (requestId === state.sessionRequestId && state.mode === mode) {
                state.sessionLoading = false;
                render();
            }
        }
    }

    async function refreshSession(sessionId = state.session?.sessionId) {
        if (!sessionId) return;
        const payload = await request(`/api/codegen/sessions/${encodeURIComponent(sessionId)}`);
        applySession(payload);
    }

    function pollSession(sessionId) {
        globalThis.setTimeout(async () => {
            if (!root.isConnected || state.session?.sessionId !== sessionId) return;
            try {
                await refreshSession(sessionId);
                const buildStatus = state.session?.buildStatus;
                if (["QUEUED", "BUILDING", "PENDING"].includes(buildStatus)) {
                    render();
                    pollSession(sessionId);
                    return;
                }
                state.busy = false;
                state.status = state.session?.buildMessage
                    || (buildStatus === "READY" ? "候选已就绪，可打开镜像预览。" : "候选构建结束。");
                render();
            } catch (error) {
                state.busy = false;
                state.status = error.message;
                render();
            }
        }, 900);
    }

    async function startGeneration(kind) {
        const prompt = text(kind === "module" ? state.modulePrompt : state.motionPrompt);
        if (!prompt || state.busy) return;
        state.busy = true;
        state.status = "正在生成候选步骤…";
        render();
        try {
            const payload = await request("/api/codegen/sessions", {
                method: "POST",
                action: "generate-codegen",
                body: {
                    mode: kind,
                    prompt,
                    targetModuleId: kind === "module"
                        ? state.moduleTarget
                        : state.motionTarget !== "framework" ? state.motionTarget : "",
                    motionTarget: kind === "motion"
                        ? state.motionTarget === "framework" ? "framework" : "module"
                        : "",
                    themeId: state.themeId,
                    moduleCatalog: context.moduleCatalog ?? [],
                    motionScopes: currentMotionScopes()
                }
            });
            applySession(payload);
            state.status = "候选步骤已生成，正在自动构建。";
            render();
            if (state.session?.sessionId) pollSession(state.session.sessionId);
        } catch (error) {
            state.busy = false;
            state.status = error.message;
            render();
        }
    }

    async function generate(kind) {
        if (state.busy) return;
        const prompt = text(kind === "module" ? state.modulePrompt : state.motionPrompt);
        if (!prompt) {
            setStatus("请先输入生成需求。");
            return;
        }
        state.status = "正在检查模型配置…";
        render();
        try {
            const configured = await ensureModelConfigured();
            if (!configured) {
                state.status = "";
                render();
                openApiDialog(kind);
                return;
            }
            state.status = "";
            await startGeneration(kind);
        } catch (error) {
            state.status = error.message;
            render();
        }
    }

    async function startPreview({ restart = false } = {}) {
        if (!state.session?.sessionId || state.busy) return;
        const action = codegenPreviewAction(state.session);
        if (!restart && action.kind !== "start") return;
        const sessionId = state.session.sessionId;
        state.busy = true;
        state.status = "正在启动独立镜像宿主，最长约 45 秒…";
        render();
        try {
            applySession(await request(
                `/api/codegen/sessions/${encodeURIComponent(sessionId)}/preview`,
                {
                    method: "POST",
                    action: "preview-codegen"
                }
            ));
            const updatedAction = codegenPreviewAction(state.session);
            if (updatedAction.kind !== "open" || !updatedAction.url) {
                throw new Error("镜像预览已启动，但宿主未返回有效地址");
            }
            state.status = "镜像预览已就绪，请点击“打开镜像预览”。";
        } catch (error) {
            state.status = error.message;
        } finally {
            state.busy = false;
            render();
        }
    }

    async function rebuildCandidate() {
        if (!state.session?.sessionId || state.busy) return;
        state.busy = true;
        state.status = "正在重新构建候选…";
        render();
        try {
            applySession(await request(
                `/api/codegen/sessions/${encodeURIComponent(state.session.sessionId)}/rebuild`,
                { method: "POST", action: "rebuild-codegen" }
            ));
            pollSession(state.session.sessionId);
        } catch (error) {
            state.busy = false;
            state.status = error.message;
            render();
        }
    }

    async function applyVersion() {
        if (!state.session?.sessionId || state.busy) return;
        const accepted = typeof globalThis.confirm === "function"
            ? globalThis.confirm("应用后当前运行实例仍保持旧版本，新版本将在下次启动生效。是否继续？")
            : false;
        if (!accepted) return;
        state.busy = true;
        state.status = "正在应用候选版本…";
        render();
        try {
            applySession(await request(
                `/api/codegen/sessions/${encodeURIComponent(state.session.sessionId)}/apply`,
                { method: "POST", action: "apply-codegen-version" }
            ));
            state.status = "版本已应用；当前程序继续运行旧版本，下次启动使用新版本。";
        } catch (error) {
            state.status = error.message;
        } finally {
            state.busy = false;
            render();
        }
    }

    async function rollbackStep(step) {
        if (!state.session?.sessionId || state.busy) return;
        const accepted = typeof globalThis.confirm === "function"
            ? globalThis.confirm(`回滚“${step.title}”会同时回滚其后的所有步骤。是否继续？`)
            : false;
        if (!accepted) return;
        state.busy = true;
        state.status = `正在回滚 ${step.title} 及其后续步骤…`;
        render();
        try {
            applySession(await request(
                `/api/codegen/sessions/${encodeURIComponent(state.session.sessionId)}`
                    + `/steps/${encodeURIComponent(step.stepId)}/rollback`,
                { method: "POST", action: "rollback-codegen-step" }
            ));
            state.status = "步骤已连带回滚。";
            if (["QUEUED", "BUILDING"].includes(state.session?.buildStatus)) {
                pollSession(state.session.sessionId);
            }
        } catch (error) {
            state.status = error.message;
        } finally {
            if (!["QUEUED", "BUILDING"].includes(state.session?.buildStatus)) {
                state.busy = false;
            }
            render();
        }
    }

    function buildTabs() {
        const tabs = make("div", "module-codegen-ui-tabs");
        tabs.setAttribute("role", "tablist");
        tabs.setAttribute("aria-label", "生成模式");
        [
            ["module", "模块生成", "modules"],
            ["motion", "动效生成", "spark"]
        ].forEach(([mode, label, iconName]) => {
            const tab = button(label, iconName, "module-codegen-ui-tab");
            tab.setAttribute("role", "tab");
            tab.setAttribute("aria-selected", String(state.mode === mode));
            tab.addEventListener("click", () => {
                if (state.mode === mode) return;
                state.mode = mode;
                state.session = null;
                state.status = "";
                render();
                restoreSession({
                    mode,
                    moduleId: mode === "module" ? state.moduleTarget : "",
                    allowFallback: true
                });
            });
            tabs.append(tab);
        });
        return tabs;
    }

    function buildModulePicker(label = "生成目标") {
        const wrapper = make("div", "module-codegen-ui-field module-codegen-ui-target-field");
        if (label) wrapper.append(make("span", "module-codegen-ui-label", label));
        const list = make("div", "module-codegen-ui-picker");
        list.setAttribute("role", "listbox");
        list.setAttribute("aria-label", "模块生成目标");

        const addTarget = (id, title, description, iconName) => {
            const item = make("button", "module-codegen-ui-picker-item");
            item.type = "button";
            item.setAttribute("role", "option");
            item.setAttribute("aria-selected", String(state.moduleTarget === id));
            const iconNode = make("span", "module-codegen-ui-picker-icon");
            iconNode.innerHTML = context.icon(iconName, 16);
            const copy = make("span", "module-codegen-ui-picker-copy");
            copy.append(
                make("strong", "", title),
                make("span", "", description)
            );
            item.append(iconNode, copy);
            item.addEventListener("click", () => {
                state.moduleTarget = id;
                state.moduleTargetExpanded = false;
                state.session = null;
                state.status = "";
                render();
                restoreSession({
                    mode: "module",
                    moduleId: id,
                    allowFallback: false
                });
            });
            list.append(item);
        };

        addTarget("", "新建模块", "从空白契约开始创建", "spark");
        modules.forEach((module) => addTarget(
            module.moduleId,
            module.title,
            module.moduleId,
            "modules"
        ));
        wrapper.append(list);
        return wrapper;
    }

    function buildPrompt(label, value, placeholder, onInput) {
        const field = make("label", "module-codegen-ui-field module-codegen-ui-prompt");
        field.append(make("span", "module-codegen-ui-label", label));
        const input = make("textarea", "module-codegen-ui-textarea");
        input.value = value;
        input.placeholder = placeholder;
        input.addEventListener("input", () => onInput(input.value));
        field.append(input);
        return field;
    }

    function buildModuleTargetPanel() {
        const panel = make("section", "module-codegen-ui-target-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        const selected = selectedModule();
        const trigger = make("button", "module-codegen-ui-target-trigger");
        trigger.type = "button";
        trigger.setAttribute("aria-expanded", String(state.moduleTargetExpanded));
        trigger.setAttribute("aria-controls", "module-codegen-target-menu");
        trigger.append(
            make("span", "", selected?.title ?? "新建模块"),
            make("span", "module-codegen-ui-target-chevron", state.moduleTargetExpanded ? "⌃" : "⌄")
        );
        trigger.addEventListener("click", () => {
            state.moduleTargetExpanded = !state.moduleTargetExpanded;
            render();
        });
        head.append(make("strong", "", "修改对象"), trigger);
        panel.append(head);
        if (state.moduleTargetExpanded) {
            const body = make("div", "module-codegen-ui-target-panel-body");
            body.id = "module-codegen-target-menu";
            body.append(buildModulePicker(""));
            panel.append(body);
        }
        return panel;
    }

    function buildInterfacePanel() {
        const module = selectedModule();
        const candidate = state.session?.mode === "module" ? state.session.interfaces : null;
        const rows = candidate
            ? interfaceRows(candidate)
            : interfaceRows(module);
        const panel = make("section", "module-codegen-ui-interface-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        head.append(
            make("strong", "", "全部接口"),
            make(
                "span",
                "",
                candidate
                    ? `${rows.length} 项候选声明`
                    : module ? `${rows.length} 项声明` : "等待模块契约"
            )
        );
        panel.append(head);

        const list = make("div", "module-codegen-ui-interface-list");
        if (!module && !candidate) {
            list.append(make(
                "p",
                "module-codegen-ui-empty",
                "新建模块尚未声明接口。生成候选后会在这里展示完整接口。"
            ));
        } else if (!rows.length) {
            list.append(make("p", "module-codegen-ui-empty", "该模块当前没有声明接口。"));
        } else {
            rows.forEach((row) => {
                const item = make("div", "module-codegen-ui-interface-row");
                const copy = make("div", "module-codegen-ui-interface-copy");
                copy.append(
                    make("strong", "", row.id),
                    make("span", "", `${row.kind} · ${row.title} · ${row.detail}`)
                );
                item.append(copy);
                list.append(item);
            });
        }
        panel.append(list);
        return panel;
    }

    function buildModulePreview() {
        const module = selectedModule();
        const action = codegenPreviewAction(state.session);
        const preview = make(
            action.kind === "open" ? "a" : "button",
            "module-codegen-ui-thumb"
        );
        if (action.kind === "open") {
            preview.href = action.url;
            preview.target = "_blank";
            preview.rel = "noopener noreferrer";
        } else {
            preview.type = "button";
            preview.disabled = state.busy || action.kind !== "start";
            preview.addEventListener("click", () => startPreview());
        }
        preview.setAttribute("aria-label", "打开独立模块候选预览");
        const mock = make("span", "module-codegen-ui-thumb-app");
        const rail = make("span", "module-codegen-ui-thumb-rail");
        rail.append(make("span"), make("span"), make("span"), make("span"));
        const main = make("span", "module-codegen-ui-thumb-main");
        main.append(
            make("span", "module-codegen-ui-thumb-title"),
            make("span", "module-codegen-ui-thumb-grid")
        );
        mock.append(rail, main);
        preview.append(
            mock,
            make("span", "module-codegen-ui-thumb-caption",
                action.kind === "open"
                    ? `${state.session.moduleId} · 点击打开镜像预览`
                    : action.kind === "start"
                        ? `${state.session.moduleId} · 点击启动镜像预览`
                        : module ? `${module.title} · 生成后可预览` : "新建模块预览")
        );
        return preview;
    }

    function buildRevisionPanel() {
        const panel = make("section", "module-codegen-ui-revision-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        const session = state.session;
        head.append(
            make("strong", "", "版本与步骤"),
            make(
                "span",
                "",
                state.sessionLoading
                    ? "读取记录中"
                    : session ? `${session.steps.length} 步 · ${session.status}` : "无记录"
            )
        );
        const body = make("div", "module-codegen-ui-revision-body");
        if (state.sessionLoading) {
            body.append(make("p", "module-codegen-ui-empty", "正在恢复最近一次生成步骤…"));
        } else if (!session) {
            body.append(
                make("p", "module-codegen-ui-empty", "尚无候选版本或生成步骤记录。"),
                make("p", "module-codegen-ui-empty-small",
                    "生成后每一步文件变更都会单独记录，并支持连带回滚。")
            );
        } else {
            const summary = make("div", "module-codegen-ui-build-summary");
            summary.append(
                make("strong", "", `构建：${session.buildStatus}`),
                make("span", "", session.buildMessage || "等待宿主返回构建结果"),
                make("small", "", session.summary)
            );
            body.append(summary);

            const actions = make("div", "module-codegen-ui-inline-actions");
            const previewAction = codegenPreviewAction(session);
            if (previewAction.kind === "open") {
                actions.append(previewLink(previewAction.url, previewAction.label));
                const restartPreview = button(
                    "重启预览",
                    "route",
                    "module-codegen-ui-button is-small"
                );
                restartPreview.disabled = state.busy;
                restartPreview.addEventListener("click", () => startPreview({ restart: true }));
                actions.append(restartPreview);
            } else {
                const startPreviewButton = button(
                    state.busy && previewAction.kind === "start"
                        ? "正在启动镜像…"
                        : previewAction.label,
                    "route",
                    "module-codegen-ui-button is-small"
                );
                startPreviewButton.disabled = state.busy || previewAction.kind !== "start";
                startPreviewButton.addEventListener("click", () => startPreview());
                actions.append(startPreviewButton);
            }
            const rebuildButton = button(
                "重新构建",
                "spark",
                "module-codegen-ui-button is-small"
            );
            rebuildButton.disabled = state.busy
                || session.status === "APPLIED"
                || session.steps.every((step) => step.status === "ROLLED_BACK");
            rebuildButton.addEventListener("click", rebuildCandidate);
            actions.append(rebuildButton);
            if (session.buildStatus === "READY"
                    && session.previewStatus === "READY"
                    && !session.applyVersionId) {
                const applyButton = button(
                    "应用版本",
                    "shield",
                    "module-codegen-ui-button is-primary is-small"
                );
                applyButton.disabled = state.busy;
                applyButton.addEventListener("click", applyVersion);
                actions.append(applyButton);
            }
            body.append(actions);

            const list = make("div", "module-codegen-ui-step-list");
            session.steps.forEach((step) => {
                const row = make("div", "module-codegen-ui-step-row");
                const copy = make("div", "module-codegen-ui-step-copy");
                copy.append(
                    make("strong", "", step.title),
                    make("span", "", `${step.path} · ${step.bytes} B`)
                );
                const status = make(
                    "span",
                    `module-codegen-ui-step-status is-${step.status.toLowerCase()}`
                );
                status.textContent = step.status;
                const rollback = button(
                    "回滚",
                    "route",
                    "module-codegen-ui-icon-button"
                );
                rollback.title = "回滚此步及后续步骤";
                rollback.setAttribute("aria-label", `回滚 ${step.title} 及后续步骤`);
                rollback.disabled = state.busy || step.status === "ROLLED_BACK";
                rollback.addEventListener("click", () => rollbackStep(step));
                row.append(copy, status, rollback);
                list.append(row);
            });
            body.append(list);

            if (session.versions.length) {
                const history = make("div", "module-codegen-ui-version-list");
                history.append(make("span", "module-codegen-ui-label", "历史版本"));
                session.versions.slice(0, 5).forEach((version) => {
                    history.append(make(
                        "span",
                        "module-codegen-ui-version-chip",
                        `${version.status} · ${version.versionId.slice(0, 8)}`
                    ));
                });
                body.append(history);
            }
        }
        panel.append(head, body);
        return panel;
    }

    function buildModulePreviewPanel() {
        const module = selectedModule();
        const action = codegenPreviewAction(state.session);
        const panel = make("section", "module-codegen-ui-preview-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        head.append(
            make("strong", "", "预览"),
            make(
                "span",
                "",
                action.kind === "open"
                    ? `${state.session.moduleId} · 镜像已就绪`
                    : action.kind === "start"
                        ? `${state.session.moduleId} · 候选已构建`
                        : module ? "生成后可打开候选镜像" : "新建模块预览"
            )
        );
        const body = make("div", "module-codegen-ui-preview-panel-body");
        body.append(buildModulePreview());
        panel.append(head, body);
        return panel;
    }

    function buildModulePromptPanel() {
        const panel = make("section", "module-codegen-ui-module-prompt-panel");
        const head = make("header", "module-codegen-ui-composer-head");
        const title = make("div", "module-codegen-ui-composer-title");
        const selected = selectedModule();
        title.append(
            make("h2", "", "生成需求"),
            make("span", "", selected?.title ?? "新建模块")
        );
        head.append(
            title,
            make(
                "span",
                `module-codegen-ui-badge${state.session?.buildStatus === "READY" ? " is-ready" : ""}`,
                state.session ? state.session.buildStatus : "等待生成"
            )
        );

        const body = make("div", "module-codegen-ui-module-prompt-body");
        body.append(buildPrompt(
            "需求描述",
            state.modulePrompt,
            "描述模块功能、接口、风险确认和界面要求",
            (value) => { state.modulePrompt = value; }
        ));

        const controls = make("div", "module-codegen-ui-module-prompt-controls");
        const actions = make("footer", "module-codegen-ui-composer-foot");
        const note = make("span", "module-codegen-ui-note");
        note.append(
            icon("shield", 15),
            make("span", "", "模块专属 AI 代码将保存在目标模块目录。")
        );
        const generateButton = button(
            "生成候选",
            "spark",
            "module-codegen-ui-button is-primary"
        );
        generateButton.disabled = state.busy;
        generateButton.title = "调用模块专属 Python AI 生成候选文件与构建步骤";
        generateButton.addEventListener("click", () => generate("module"));
        actions.append(note, generateButton);
        controls.append(actions);
        body.append(controls);
        panel.append(head, body);
        return panel;
    }

    function buildModuleWorkspace() {
        const workspace = make("div", "module-codegen-ui-layout is-module-workspace");
        const left = make("div", "module-codegen-ui-module-left");
        left.append(buildModuleTargetPanel(), buildInterfacePanel());
        workspace.append(
            left,
            buildModulePreviewPanel(),
            buildRevisionPanel(),
            buildModulePromptPanel()
        );
        return workspace;
    }

    function buildMotionPicker() {
        const wrapper = make("div", "module-codegen-ui-field module-codegen-ui-target-field");
        wrapper.append(make("span", "module-codegen-ui-label", "修改目标"));
        const list = make("div", "module-codegen-ui-picker");
        list.setAttribute("role", "listbox");
        list.setAttribute("aria-label", "动效修改目标");

        const addTarget = (id, title, description, iconName) => {
            const item = make("button", "module-codegen-ui-picker-item");
            item.type = "button";
            item.setAttribute("role", "option");
            item.setAttribute("aria-selected", String(state.motionTarget === id));
            const iconNode = make("span", "module-codegen-ui-picker-icon");
            iconNode.innerHTML = context.icon(iconName, 16);
            const copy = make("span", "module-codegen-ui-picker-copy");
            copy.append(make("strong", "", title), make("span", "", description));
            item.append(iconNode, copy);
            item.addEventListener("click", () => {
                state.motionTarget = id;
                if (id !== "framework") state.moduleTarget = id;
                state.status = "";
                render();
            });
            list.append(item);
        };

        addTarget("framework", "框架动效", "复用现有公共挂点", "modules");
        modules.forEach((module) => addTarget(
            module.moduleId,
            module.title,
            `${module.moduleId} · 模块挂点`,
            "spark"
        ));
        wrapper.append(list);
        return wrapper;
    }

    function buildThemeSelect() {
        const field = make("label", "module-codegen-ui-field module-codegen-ui-theme-field");
        field.append(make("span", "module-codegen-ui-label", "目标主题"));
        const select = make("select", "module-codegen-ui-select");
        themes.forEach((theme) => {
            const option = make("option", "", theme.label);
            option.value = theme.id;
            option.selected = theme.id === state.themeId;
            select.append(option);
        });
        if (!themes.length) {
            const option = make("option", "", "当前无可用主题");
            option.value = "";
            select.append(option);
            select.disabled = true;
        }
        select.addEventListener("change", () => {
            state.themeId = select.value;
        });
        field.append(select);
        return field;
    }

    function currentMotionScopes() {
        const values = typeof context.listMotionScopes === "function"
            ? context.listMotionScopes()
            : [];
        const candidate = state.session?.mode === "motion"
            ? listOf(state.session?.interfaces?.motionHooks)
            : [];
        return [...candidate, ...listOf(values)]
            .map((entry) => ({
                scope: text(entry.scope),
                state: text(entry.state),
                phase: text(entry.phase)
            }))
            .filter((entry) => entry.scope)
            .filter((entry, index, entries) =>
                entries.findIndex((candidateEntry) =>
                    candidateEntry.scope === entry.scope
                    && candidateEntry.state === entry.state) === index)
            .slice(0, 8);
    }

    function buildMotionComposer() {
        const composer = make("section", "module-codegen-ui-composer");
        const head = make("header", "module-codegen-ui-composer-head");
        const targetLabel = state.motionTarget === "framework"
            ? "框架动效"
            : modules.find((module) => module.moduleId === state.motionTarget)?.title
                ?? state.motionTarget;
        const title = make("div", "module-codegen-ui-composer-title");
        title.append(make("h2", "", "动效生成"), make("span", "", targetLabel));
        head.append(
            title,
            make(
                "span",
                `module-codegen-ui-badge${state.session?.buildStatus === "READY" ? " is-ready" : ""}`,
                state.session ? state.session.buildStatus : "等待生成"
            )
        );

        const body = make("div", "module-codegen-ui-composer-body");
        const editor = make("div", "module-codegen-ui-editor is-motion");
        editor.append(
            buildMotionPicker(),
            buildThemeSelect(),
            buildPrompt(
                "需求描述",
                state.motionPrompt,
                "描述进入、切换、反馈和关闭动效时的表现",
                (value) => { state.motionPrompt = value; }
            )
        );
        body.append(editor);
        const foot = make("footer", "module-codegen-ui-composer-foot");
        const note = make("span", "module-codegen-ui-note");
        note.append(
            icon("route", 15),
            make("span", "", "框架动效复用公共挂点；模块动效只能增加本模块挂点。")
        );
        const generateButton = button(
            "生成动效候选",
            "spark",
            "module-codegen-ui-button is-primary"
        );
        generateButton.disabled = state.busy;
        generateButton.title = "调用模块专属 Python AI 生成动效候选";
        generateButton.addEventListener("click", () => generate("motion"));
        foot.append(note, generateButton);
        body.append(foot);
        composer.append(head, body);
        return composer;
    }

    function buildMotionPreview() {
        const action = codegenPreviewAction(state.session);
        const preview = make(
            action.kind === "open" ? "a" : "button",
            "module-codegen-ui-motion-preview"
        );
        if (action.kind === "open") {
            preview.href = action.url;
            preview.target = "_blank";
            preview.rel = "noopener noreferrer";
        } else {
            preview.type = "button";
            preview.disabled = state.busy || action.kind !== "start";
            preview.addEventListener("click", () => startPreview());
        }
        preview.setAttribute("aria-label", "打开独立动效候选预览");
        const stage = make("span", "module-codegen-ui-motion-stage");
        const sidebar = make("span", "module-codegen-ui-motion-sidebar");
        const canvas = make("span", "module-codegen-ui-motion-canvas");
        const heading = make("span", "module-codegen-ui-motion-heading");
        const tiles = make("span", "module-codegen-ui-motion-tiles");
        tiles.append(make("span"), make("span"), make("span"), make("span"));
        canvas.append(heading, tiles);
        stage.append(sidebar, canvas);

        const scopes = currentMotionScopes();
        scopes.slice(0, 5).forEach((entry, index) => {
            const hook = make("span", `module-codegen-ui-hook is-hook-${index + 1}`);
            hook.textContent = [entry.scope, entry.state].filter(Boolean).join(" · ");
            stage.append(hook);
        });
        preview.append(stage);
        return preview;
    }

    function buildMotionSummary() {
        const panel = make("section", "module-codegen-ui-motion-summary");
        const head = make("header", "module-codegen-ui-panel-head");
        const scopes = currentMotionScopes();
        head.append(
            make("strong", "", "可视化挂点"),
            make("span", "", `${scopes.length} 个当前挂点`)
        );
        const list = make("div", "module-codegen-ui-hook-list");
        if (!scopes.length) {
            list.append(make("p", "module-codegen-ui-empty", "当前视图未暴露公共动效挂点。"));
        } else {
            scopes.forEach((scope) => {
                const item = make("span", "module-codegen-ui-hook-chip");
                item.append(
                    make("strong", "", scope.scope),
                    make("span", "", [scope.state, scope.phase].filter(Boolean).join(" / "))
                );
                list.append(item);
            });
        }
        panel.append(head, list);
        return panel;
    }

    function buildMotionSide() {
        const side = make("aside", "module-codegen-ui-side");
        side.append(buildMotionPreview(), buildMotionSummary(), buildRevisionPanel());
        return side;
    }

    function buildHeaderActions() {
        const actions = make("div", "module-codegen-ui-header-actions");
        const settings = button(
            "模组设置",
            "settings",
            "module-codegen-ui-button is-small"
        );
        settings.disabled = typeof context.openSettings !== "function";
        settings.title = "打开 AI 接入设置";
        settings.addEventListener("click", () => context.openSettings("ai"));
        actions.append(settings, buildTabs());
        return actions;
    }

    function render() {
        const shell = make("section", "module-codegen-ui-shell");
        const header = make("header", "module-codegen-ui-header");
        const heading = make("div", "module-codegen-ui-heading");
        heading.append(
            make("p", "module-codegen-ui-eyebrow", "GENERATION STUDIO"),
            make("h1", "", state.mode === "module" ? "模块生成" : "动效生成"),
            make("p", "module-codegen-ui-description",
                state.mode === "module"
                    ? "描述要生成或修改的模块。"
                    : "描述框架或模块需要呈现的动效。")
        );
        header.append(heading, buildHeaderActions());

        let layout;
        if (state.mode === "module") {
            layout = buildModuleWorkspace();
        } else {
            layout = make("div", "module-codegen-ui-layout is-motion-workspace");
            layout.append(buildMotionComposer(), buildMotionSide());
        }
        shell.append(header, layout);

        if (state.status) {
            const status = make("p", "module-codegen-ui-status", state.status);
            status.setAttribute("role", "status");
            shell.append(status);
        }
        root.replaceChildren(shell);
    }

    root.addEventListener("pointerdown", (event) => {
        if (!state.moduleTargetExpanded) return;
        const target = event.target;
        if (target && typeof target.closest === "function"
                && target.closest(".module-codegen-ui-target-panel")) {
            return;
        }
        state.moduleTargetExpanded = false;
        render();
    });
    root.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && state.moduleTargetExpanded) {
            state.moduleTargetExpanded = false;
            render();
        }
    });
    render();
    refreshModelState();
    restoreSession({
        mode: state.mode,
        moduleId: state.moduleTarget,
        allowFallback: true
    });
}
