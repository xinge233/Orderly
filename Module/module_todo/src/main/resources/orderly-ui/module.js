/**
 * 待办模块视图。
 *
 * 职责：展示模块自有待办集合，并把创建入口交给宿主的命令输入与计划流程。
 * 依赖：orderly-ui/module.json 提供 showCompleted 设置与示例；只调用 todo.list 只读查询；
 * 宿主 mount context 提供 module、ui、settings、element、icon、query、fillCommand 与 formatDate。
 * 隔离：查询或渲染失败只替换当前模块视图内的列表，不修改框架、其他模块或业务数据。
 */

/**
 * 挂载待办列表。
 *
 * @param {HTMLElement} root 框架分配给本模块的视图根节点，脚本只管理其子节点。
 * @param {object} context 宿主 mount context；查询、设置和交互辅助能力均由宿主注入。
 * @returns {Promise<void>} 首次只读查询和局部渲染完成后结束。
 */
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
    // 视图不直接提交写入；按钮只把模块清单中的示例填入宿主命令入口。
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

    // Query 响应按不可信输入处理，缺失字段用只读空态和 OPEN 状态兜底。
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
        // 读取失败只占据本模块列表区域，框架和其他模块仍可继续使用。
        list.replaceChildren(make("p", "todo-ui-error", error?.message || "待办读取失败"));
    }
}
