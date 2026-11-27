export default async function mount(root, context) {
    const make = context.element;
    const panel = make("section", "codegen-e2e-smoke");
    panel.dataset.motionScope = "codegen-e2e-smoke";
    panel.dataset.motionState = "ready";

    let payload;
    try {
        payload = await context.query("codegen.e2e.summary");
    } catch (error) {
        panel.append(
            make("strong", "", "生成链路验收查询失败"),
            make("p", "", error?.message || "无法读取模块查询结果。")
        );
        root.replaceChildren(panel);
        return;
    }

    panel.append(
        make("strong", "", payload?.title || "生成链路验收通过"),
        make("p", "", payload?.message || "真实模型生成的模块已完成加载。")
    );
    root.replaceChildren(panel);
}
