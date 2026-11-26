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
        busy: false,
        apiKey: "",
        modelConfigured: false,
        modelLabel: "正在读取模型配置"
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
            state.modelLabel = state.modelConfigured
                ? `${text(settings.model, "已配置模型")} · 已就绪`
                : "未配置模型";
        } catch {
            state.modelConfigured = false;
            state.modelLabel = "AI 服务状态不可用";
        }
        render();
    }

    async function ensureModelConfigured() {
        if (state.apiKey.trim()) {
            const settings = await request("/api/ai/settings", {
                method: "PUT",
                body: {
                    mode: "DEEPSEEK",
                    apiKey: state.apiKey.trim()
                }
            });
            state.apiKey = "";
            state.modelConfigured = settings?.configured === true;
            state.modelLabel = `${text(settings?.model, "DeepSeek")} · 本次运行已配置`;
            return;
        }
        const settings = await request("/api/ai/settings");
        state.modelConfigured = settings?.configured === true;
        state.modelLabel = state.modelConfigured
            ? `${text(settings.model, "已配置模型")} · 已就绪`
            : "未配置模型";
        if (!state.modelConfigured) {
            throw new Error("请先在设置中配置模型，或在生成器内临时输入 API Key。");
        }
    }

    function applySession(payload) {
        state.session = normalizeCodegenSession(payload);
        if (state.session) {
            if (state.session.moduleId) state.moduleTarget = state.session.moduleId;
            if (state.session.motionTarget === "framework") {
                state.motionTarget = "framework";
            } else if (state.session.motionTarget === "module" && state.session.moduleId) {
                state.motionTarget = state.session.moduleId;
            }
            if (state.session.themeId) state.themeId = state.session.themeId;
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

    async function generate(kind) {
        if (state.busy) return;
        const prompt = text(kind === "module" ? state.modulePrompt : state.motionPrompt);
        if (!prompt) {
            setStatus("请先输入生成需求。");
            return;
        }
        state.busy = true;
        state.status = "正在生成候选步骤…";
        render();
        try {
            await ensureModelConfigured();
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

    async function openPreview() {
        if (!state.session?.sessionId || state.busy) return;
        state.busy = true;
        state.status = "正在启动独立镜像预览…";
        render();
        try {
            let payload = state.session;
            if (payload.previewStatus !== "READY" || !payload.previewUrl) {
                payload = await request(
                    `/api/codegen/sessions/${encodeURIComponent(payload.sessionId)}/preview`,
                    {
                        method: "POST",
                        action: "preview-codegen"
                    }
                );
                applySession(payload);
            }
            const url = state.session?.previewUrl;
            if (!url) throw new Error("镜像预览地址为空");
            globalThis.open(url, "_blank", "noopener,noreferrer");
            state.status = "独立镜像预览已打开。";
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
                state.mode = mode;
                state.status = "";
                render();
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
                state.status = "";
                render();
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

    function buildModelField() {
        const field = make("label", "module-codegen-ui-field module-codegen-ui-model-field");
        const head = make("span", "module-codegen-ui-model-head");
        head.append(
            make("span", "module-codegen-ui-label", "模型"),
            make("span", "module-codegen-ui-model-state", state.modelLabel)
        );
        const input = make("input", "module-codegen-ui-input");
        input.type = "password";
        input.autocomplete = "off";
        input.spellcheck = false;
        input.placeholder = state.modelConfigured
            ? "使用已配置模型；也可临时输入新的 API Key"
            : "临时输入 DeepSeek API Key";
        input.value = state.apiKey;
        input.addEventListener("input", () => { state.apiKey = input.value; });
        field.append(head, input);
        return field;
    }

    function buildModuleTargetPanel() {
        const panel = make("section", "module-codegen-ui-target-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        const selected = selectedModule();
        head.append(
            make("strong", "", "修改对象"),
            make("span", "", selected?.title ?? "新建模块")
        );
        const body = make("div", "module-codegen-ui-target-panel-body");
        body.append(buildModulePicker(""));
        panel.append(head, body);
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
                const risk = make("span", `module-codegen-ui-risk is-${row.risk.toLowerCase()}`);
                risk.textContent = row.risk;
                item.append(copy, risk);
                list.append(item);
            });
        }
        panel.append(list);
        return panel;
    }

    function buildModulePreview() {
        const module = selectedModule();
        const preview = make("button", "module-codegen-ui-thumb");
        preview.type = "button";
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
                state.session?.previewStatus === "READY"
                    ? `${state.session.moduleId} · 点击打开镜像预览`
                    : module ? `${module.title} · 生成后可预览` : "新建模块预览")
        );
        preview.addEventListener("click", openPreview);
        return preview;
    }

    function buildRevisionPanel() {
        const panel = make("section", "module-codegen-ui-revision-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        const session = state.session;
        head.append(
            make("strong", "", "版本与步骤"),
            make("span", "", session ? `${session.steps.length} 步 · ${session.status}` : "无记录")
        );
        const body = make("div", "module-codegen-ui-revision-body");
        if (!session) {
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
            const previewButton = button(
                session.previewStatus === "READY" ? "打开镜像预览" : "启动镜像预览",
                "route",
                "module-codegen-ui-button is-small"
            );
            previewButton.disabled = state.busy || session.buildStatus !== "READY";
            previewButton.addEventListener("click", openPreview);
            const rebuildButton = button(
                "重新构建",
                "spark",
                "module-codegen-ui-button is-small"
            );
            rebuildButton.disabled = state.busy
                || session.status === "APPLIED"
                || session.steps.every((step) => step.status === "ROLLED_BACK");
            rebuildButton.addEventListener("click", rebuildCandidate);
            actions.append(previewButton, rebuildButton);
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
        const panel = make("section", "module-codegen-ui-preview-panel");
        const head = make("header", "module-codegen-ui-panel-head");
        head.append(
            make("strong", "", "预览"),
            make(
                "span",
                "",
                state.session?.previewStatus === "READY"
                    ? `${state.session.moduleId} · 镜像已就绪`
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
        controls.append(buildModelField());
        const actions = make("footer", "module-codegen-ui-composer-foot");
        const note = make("span", "module-codegen-ui-note");
        note.append(
            icon("shield", 15),
            make("span", "", "模块专属 AI 代码将保存在目标模块目录。")
        );
        const generate = button("生成候选", "spark", "module-codegen-ui-button is-primary");
        generate.disabled = state.busy;
        generate.title = "调用模块专属 Python AI 生成候选文件与构建步骤";
        generate.addEventListener("click", () => generate("module"));
        actions.append(note, generate);
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
            ),
            buildModelField()
        );
        body.append(editor);
        const foot = make("footer", "module-codegen-ui-composer-foot");
        const note = make("span", "module-codegen-ui-note");
        note.append(
            icon("route", 15),
            make("span", "", "框架动效复用公共挂点；模块动效只能增加本模块挂点。")
        );
        const generate = button("生成动效候选", "spark", "module-codegen-ui-button is-primary");
        generate.disabled = state.busy;
        generate.title = "调用模块专属 Python AI 生成动效候选";
        generate.addEventListener("click", () => generate("motion"));
        foot.append(note, generate);
        body.append(foot);
        composer.append(head, body);
        return composer;
    }

    function buildMotionPreview() {
        const preview = make("button", "module-codegen-ui-motion-preview");
        preview.type = "button";
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
        preview.addEventListener("click", openPreview);
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
        header.append(heading, buildTabs());

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

    render();
    refreshModelState();
}
