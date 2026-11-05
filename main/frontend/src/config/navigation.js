/**
 * 侧边栏导航的单一配置源。
 *
 * 新增页面时只需增加一个导航项，并在 view-registry.js 注册同名视图。
 * 项目多到超出视口后，中段导航会自动滚动，底部设置区不会跟随滚动。
 */
export const navigationSections = [
    {
        id: "workspace",
        label: "工作空间",
        items: [
            {
                id: "overview",
                label: "总览",
                caption: "今日工作台",
                icon: "compass",
                view: "overview"
            },
            {
                id: "files",
                label: "文件与目录",
                caption: "整理与归档",
                icon: "folder",
                view: "placeholder"
            },
            {
                id: "todos",
                label: "待办事项",
                caption: "任务收束",
                icon: "check",
                view: "placeholder"
            },
            {
                id: "calendar",
                label: "日程安排",
                caption: "时间视图",
                icon: "calendar",
                view: "placeholder"
            }
        ]
    },
    {
        id: "activity",
        label: "活动与扩展",
        items: [
            {
                id: "history",
                label: "执行记录",
                caption: "审计与回滚",
                icon: "history",
                view: "placeholder"
            },
            {
                id: "lab",
                label: "模块试验台",
                caption: "空功能模块",
                icon: "flask",
                view: "lab"
            }
        ]
    }
];

/**
 * 将分组导航展平，便于快速查找和键盘导航。
 *
 * @returns {Array<object>} 所有导航项
 */
export function flattenNavigation() {
    return navigationSections.flatMap((section) => section.items);
}

/**
 * 返回默认选中的导航项；找不到配置时给出明确错误。
 *
 * @returns {object} 默认导航项
 */
export function getDefaultNavigationItem() {
    const item = flattenNavigation()[0];
    if (!item) {
        throw new Error("至少需要配置一个导航项");
    }
    return item;
}
