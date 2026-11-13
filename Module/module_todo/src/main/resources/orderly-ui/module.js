export default async function mount(root, context) {
    const make = context.element;
    const panel = make("section", "todo-ui-panel");
    const header = make("header", "todo-ui-header");
    const heading = make("div");
    heading.append(
        make("p", "todo-ui-eyebrow", "MODULE TODO"),
        make("h1", "", context.module.title || "待办事项"),
        make("p", "todo-ui-muted", "这里只读取模块自有集合；写入必须先生成计划并确认。")
    );
    const create = make("button", "todo-ui-button", "输入创建指令");
    create.type = "button";
    create.addEventListener("click", () => context.fillCommand(
        context.ui.examples?.[0]?.text || "创建待办"
    ));
    header.append(heading, create);

    const summary = make("div", "todo-ui-summary");
    const list = make("div", "todo-ui-list");
    panel.append(header, summary, list);
    root.replaceChildren(panel);
    list.append(make("p", "todo-ui-empty", "正在读取模块数据…"));

    try {
        const payload = await context.query("todo.list");
        const allItems = Array.isArray(payload?.items) ? payload.items : [];
        const showCompleted = context.settings?.showCompleted !== false;
        const items = showCompleted
            ? allItems
            : allItems.filter((item) => String(item?.status ?? "OPEN") === "OPEN");
        summary.replaceChildren(
            make("strong", "", String(items.length)),
            make("span", "", "条待办")
        );
        list.replaceChildren();
        if (!items.length) {
            list.append(make("p", "todo-ui-empty",
                showCompleted ? "当前没有待办。" : "当前没有未完成待办。"));
            return;
        }
        items.forEach((item) => {
            const row = make("article", "todo-ui-item");
            const marker = make("span", "todo-ui-marker");
            marker.innerHTML = context.icon("check", 18);
            const copy = make("div", "todo-ui-copy");
            copy.append(
                make("strong", "", item.title || "未命名待办"),
                make("small", "", item.dueAt
                    ? `截止 ${context.formatDate(item.dueAt)}`
                    : "未设置截止时间")
            );
            row.append(marker, copy, make("span", "todo-ui-status", item.status || "OPEN"));
            list.append(row);
        });
    } catch (error) {
        list.replaceChildren(make("p", "todo-ui-error", error?.message || "待办读取失败"));
    }
}
