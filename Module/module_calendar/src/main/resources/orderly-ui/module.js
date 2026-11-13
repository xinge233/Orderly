export default async function mount(root, context) {
    const make = context.element;
    const panel = make("section", "calendar-ui-panel");
    const header = make("header", "calendar-ui-header");
    const heading = make("div");
    heading.append(
        make("p", "calendar-ui-eyebrow", "MODULE CALENDAR"),
        make("h1", "", context.module.title || "日程安排"),
        make("p", "calendar-ui-muted", "日程内容属于当前模块；改期操作必须经计划确认。")
    );
    const command = make("button", "calendar-ui-button", "输入改期指令");
    command.type = "button";
    command.addEventListener("click", () => context.fillCommand(
        context.ui.examples?.[0]?.text || "把日程 项目评审 改到 2026-11-20 15:00"
    ));
    header.append(heading, command);

    const list = make("div", "calendar-ui-list");
    panel.append(header, list);
    root.replaceChildren(panel);
    list.append(make("p", "calendar-ui-empty", "正在读取模块数据…"));

    try {
        const payload = await context.query("calendar.list");
        const items = Array.isArray(payload?.items) ? payload.items : [];
        const showEndTime = context.settings?.showEndTime !== false;
        list.replaceChildren();
        if (!items.length) {
            list.append(make("p", "calendar-ui-empty", "当前没有日程。"));
            return;
        }
        items.forEach((item) => {
            const row = make("article", "calendar-ui-item");
            const date = make("time", "calendar-ui-time", context.formatDate(item.startAt));
            const copy = make("div", "calendar-ui-copy");
            copy.append(make("strong", "", item.title || "未命名日程"));
            if (showEndTime) {
                copy.append(make("small", "", item.endAt
                    ? `结束于 ${context.formatDate(item.endAt)}`
                    : "结束时间未设置"));
            }
            row.append(date, copy);
            list.append(row);
        });
    } catch (error) {
        list.replaceChildren(make("p", "calendar-ui-error", error?.message || "日程读取失败"));
    }
}
