export default async function mount(root, context) {
    const make = context.element;
    const shell = make("div", "study-ui-shell");
    const loading = make("section", "study-ui-loading");
    const loadingIcon = make("span", "study-ui-icon study-ui-icon--accent");
    loadingIcon.innerHTML = context.icon("spark", 20);
    loading.setAttribute("aria-live", "polite");
    loading.append(
        loadingIcon,
        make("div", "", "正在读取本模块学习数据…")
    );
    shell.append(loading);
    root.replaceChildren(shell);

    try {
        const data = await context.query("study.assistant.read");
        renderWorkbench(shell, data, context, make);
    } catch (error) {
        const failure = make("section", "study-ui-error");
        const failureIcon = make("span", "study-ui-icon");
        failureIcon.innerHTML = context.icon("info", 20);
        failure.append(
            failureIcon,
            make("strong", "", "学习工作台暂时不可用"),
            make("p", "", error?.message || "无法读取模块自有数据。")
        );
        shell.replaceChildren(failure);
    }
}

function renderWorkbench(shell, data, context, make) {
    const payload = data?.payload ?? {};
    const tasks = Array.isArray(payload.tasks) ? payload.tasks : [];
    const conversation = Array.isArray(payload.conversation) ? payload.conversation : [];
    const toolTrace = Array.isArray(payload.toolTrace) ? payload.toolTrace : [];
    const citations = Array.isArray(payload.citations) ? payload.citations : [];
    const sources = new Map(
        (Array.isArray(data?.sources) ? data.sources : [])
            .map((source) => [source.id, source.label || source.id])
    );

    const hero = make("header", "study-ui-hero");
    const heading = make("div", "study-ui-heading");
    const kicker = make("div", "study-ui-kicker");
    const kickerIcon = make("span", "study-ui-icon study-ui-icon--accent");
    kickerIcon.innerHTML = context.icon("spark", 16);
    kicker.append(kickerIcon, make("span", "", "STUDY WORKSPACE"));

    const titleRow = make("div", "study-ui-title-row");
    titleRow.append(make("h1", "", data?.title || context.module.title || "AI 学习助手"));

    const plannerBadge = make(
        "span",
        "study-ui-badge study-ui-badge--safe",
        payload.planner === "local-rules" ? "本地规则" : String(payload.planner || "规划方式未声明")
    );
    const modelBadge = make(
        "span",
        "study-ui-badge",
        payload.externalModelConnected === false ? "未接入外部模型" : "外部模型状态未验证"
    );
    titleRow.append(plannerBadge, modelBadge);
    heading.append(
        kicker,
        titleRow,
        make("p", "study-ui-description", data?.description || "只读学习数据工作台。")
    );

    const headerActions = make("div", "study-ui-header-actions");
    const recordButton = make("button", "study-ui-record-button");
    recordButton.type = "button";
    recordButton.disabled = true;
    recordButton.setAttribute("aria-disabled", "true");
    recordButton.title = "任务记录能力尚未接入";
    const recordIcon = make("span", "study-ui-icon");
    recordIcon.innerHTML = context.icon("history", 16);
    recordButton.append(recordIcon, make("span", "", "任务记录 · 尚未接入"));
    headerActions.append(
        metric(make, context, "本周任务", String(tasks.length), "check"),
        metric(make, context, "工具轨迹", String(toolTrace.length), "route"),
        recordButton
    );
    hero.append(heading, headerActions);

    const workspace = make("div", "study-ui-grid");
    const leftColumn = make("div", "study-ui-column");
    const rightColumn = make("div", "study-ui-column");
    leftColumn.append(
        taskSection(tasks, context, make),
        conversationSection(conversation, context, make)
    );
    rightColumn.append(
        toolSection(toolTrace, context, make),
        citationSection(citations, sources, context, make)
    );
    workspace.append(leftColumn, rightColumn);

    shell.replaceChildren(hero, workspace);
}

function metric(make, context, label, value, iconName) {
    const item = make("div", "study-ui-metric");
    const iconNode = make("span", "study-ui-icon");
    iconNode.innerHTML = context.icon(iconName, 16);
    const copy = make("span", "study-ui-metric-copy");
    copy.append(
        make("strong", "", value),
        make("small", "", label)
    );
    item.append(iconNode, copy);
    return item;
}

function sectionHeader(make, context, iconName, title, detail) {
    const header = make("header", "study-ui-section-header");
    const iconNode = make("span", "study-ui-icon study-ui-icon--accent");
    iconNode.innerHTML = context.icon(iconName, 18);
    const copy = make("div", "study-ui-section-copy");
    copy.append(
        make("h2", "", title),
        make("p", "", detail)
    );
    header.append(iconNode, copy);
    return header;
}

function taskSection(tasks, context, make) {
    const section = make("section", "study-ui-section");
    section.append(sectionHeader(make, context, "check", "本周待办", "模块自有只读任务"));
    const list = make("div", "study-ui-task-list");
    if (!tasks.length) {
        list.append(emptyState(make, "本周还没有学习任务。"));
    } else {
        tasks.forEach((task, index) => {
            const row = make("article", "study-ui-task");
            const indexNode = make("span", "study-ui-task-index", String(index + 1).padStart(2, "0"));
            const copy = make("div", "study-ui-task-copy");
            copy.append(
                make("strong", "", task.title || "未命名任务"),
                make("p", "", task.detail || "暂无说明")
            );
            row.append(indexNode, copy, make("span", "study-ui-readonly", "只读"));
            list.append(row);
        });
    }
    section.append(list);
    return section;
}

function conversationSection(conversation, context, make) {
    const section = make("section", "study-ui-section");
    section.append(sectionHeader(
        make,
        context,
        "spark",
        "示例对话",
        "静态只读样例，不生成实时回答"
    ));
    const list = make("div", "study-ui-conversation");
    if (!conversation.length) {
        list.append(emptyState(make, "当前没有示例对话。"));
    } else {
        conversation.forEach((message) => {
            const isAssistant = message.role === "assistant";
            const bubble = make(
                "article",
                `study-ui-message${isAssistant ? " study-ui-message--assistant" : ""}`
            );
            const meta = make("div", "study-ui-message-meta");
            meta.append(make(
                "span",
                "study-ui-message-role",
                isAssistant ? "只读示例" : "示例输入"
            ));
            if (isAssistant) {
                const sampleIcon = make("span", "study-ui-icon");
                sampleIcon.innerHTML = context.icon("info", 14);
                meta.append(sampleIcon);
            }
            bubble.append(meta, make("p", "", message.content || ""));
            list.append(bubble);
        });
    }

    const composer = make("form", "study-ui-composer");
    const label = make("label", "study-ui-sr-only", "学习目标");
    label.htmlFor = "study-assistant-command";
    const input = make("textarea", "study-ui-input");
    input.id = "study-assistant-command";
    input.rows = 2;
    input.maxLength = 240;
    input.placeholder = "描述学习目标，交给右下角本地规则计划器";
    const submit = make("button", "study-ui-send");
    submit.type = "submit";
    const sendIcon = make("span", "study-ui-icon");
    sendIcon.innerHTML = context.icon("route", 16);
    submit.append(sendIcon, make("span", "", "交给计划器"));
    const status = make("p", "study-ui-composer-status");
    status.setAttribute("aria-live", "polite");
    composer.append(label, input, submit, status);
    composer.addEventListener("submit", (event) => {
        event.preventDefault();
        const command = input.value.trim();
        if (!command) {
            status.textContent = "请先输入学习目标。";
            input.focus();
            return;
        }
        context.fillCommand(command);
        status.textContent = "已填入右下角本地规则计划器，请在计划预览中确认。";
    });
    section.append(list, composer);
    return section;
}

function toolSection(toolTrace, context, make) {
    const section = make("section", "study-ui-section");
    section.append(sectionHeader(
        make,
        context,
        "route",
        "工具调用",
        "本模块查询与未接入能力"
    ));
    const list = make("div", "study-ui-tool-list");
    if (!toolTrace.length) {
        list.append(emptyState(make, "当前没有工具轨迹。"));
    } else {
        toolTrace.forEach((step) => {
            const row = make("article", "study-ui-tool");
            const marker = make("span", `study-ui-state study-ui-state--${step.state || "sample"}`);
            marker.textContent = stateLabel(step.state);
            const copy = make("div", "study-ui-tool-copy");
            copy.append(
                make("strong", "", step.label || "未命名步骤"),
                make("p", "", step.detail || "")
            );
            row.append(marker, copy);
            list.append(row);
        });
    }
    section.append(list);
    return section;
}

function citationSection(citations, sources, context, make) {
    const section = make("section", "study-ui-section");
    section.append(sectionHeader(
        make,
        context,
        "info",
        "引用资料",
        "来自本模块 JAR 的只读摘录"
    ));
    const list = make("div", "study-ui-citations");
    if (!citations.length) {
        list.append(emptyState(make, "当前没有引用资料。"));
    } else {
        citations.forEach((citation) => {
            const item = make("article", "study-ui-citation");
            const source = make("div", "study-ui-citation-source");
            const iconNode = make("span", "study-ui-icon");
            iconNode.innerHTML = context.icon("file", 14);
            source.append(iconNode, make("span", "", sources.get(citation.sourceId) || citation.sourceId));
            item.append(
                source,
                make("h3", "", citation.title || "未命名资料"),
                make("p", "", citation.excerpt || "")
            );
            list.append(item);
        });
    }
    section.append(list);
    return section;
}

function emptyState(make, text) {
    return make("p", "study-ui-empty", text);
}

function stateLabel(state) {
    if (state === "available") return "可用";
    if (state === "not-connected") return "尚未接入";
    return "只读示例";
}
