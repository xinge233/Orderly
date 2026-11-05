/**
 * 后端模块到前端导航/视图的显式绑定。
 *
 * 契约样例中的模块只有在这里有明确绑定时，转换器才会生成导航项；
 * 缺失绑定会立即报错，避免静默落入占位页掩盖配置问题。
 */
export const moduleViewBindings = Object.freeze({
    "sample-empty": Object.freeze({
        navigationId: "lab",
        label: "模块试验台",
        caption: "空功能模块",
        icon: "flask",
        view: "lab"
    })
});
