/**
 * 设置界面的声明式结构。
 *
 * 每个分组对应设置对话框中的一个分类；每个字段包含 key、类型、说明和候选项。
 * 新增设置时优先扩展本配置，由 settings-dialog.js 统一渲染、保存和恢复默认值。
 * 这样设置能力增删不会散落在多个 DOM 事件处理函数中。
 */
export const settingSections = [
    {
        id: "appearance",
        title: "外观",
        summary: "主题与界面动效",
        icon: "palette",
        fields: [
            {
                key: "theme",
                type: "theme",
                label: "界面主题",
                description: "主题通过 CSS 变量切换，模块卡片与布局会同步更新。",
                options: [
                    { value: "paper", label: "纸页", note: "温暖明亮", swatches: ["#eee8dc", "#c56f4f", "#315f57"] },
                    { value: "midnight", label: "夜航", note: "沉稳深色", swatches: ["#171c25", "#d4a85f", "#74a99c"] },
                    { value: "moss", label: "苔原", note: "自然低饱和", swatches: ["#e7eadf", "#527b63", "#b46b4c"] }
                ]
            },
            {
                key: "motion",
                type: "segmented",
                label: "交互动效",
                description: "动效由独立策略驱动，可在不修改页面逻辑的情况下替换或关闭。",
                options: [
                    { value: "gentle", label: "灵动" },
                    { value: "minimal", label: "克制" },
                    { value: "none", label: "关闭" }
                ]
            }
        ]
    },
    {
        id: "language",
        title: "语言",
        summary: "界面语言偏好",
        icon: "globe",
        fields: [
            {
                key: "language",
                type: "select",
                label: "显示语言",
                description: "当前原型仅保存偏好；完整多语言资源将在模块接入后生效。",
                options: [
                    { value: "zh-CN", label: "简体中文" },
                    { value: "en-US", label: "English" }
                ]
            }
        ]
    },
    {
        id: "notifications",
        title: "通知",
        summary: "提醒与状态反馈",
        icon: "bell",
        fields: [
            {
                key: "notifications",
                type: "toggle",
                label: "启用界面通知",
                description: "保存此偏好，不请求浏览器通知权限，也不连接外部消息服务。"
            }
        ]
    }
];

/** 设置默认值必须与设置字段保持一一对应。 */
export const settingDefaults = Object.freeze({
    theme: "paper",
    motion: "gentle",
    language: "zh-CN",
    notifications: true
});

/** 用于主题切换时同步浏览器标签栏颜色。 */
export const themeBrowserColors = Object.freeze({
    paper: "#f2efe7",
    midnight: "#171c25",
    moss: "#e7eadf"
});
