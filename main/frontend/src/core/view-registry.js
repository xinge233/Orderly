import { renderLab } from "../modules/lab.js";
import { renderOverview } from "../modules/overview.js";
import { renderPlaceholder } from "../modules/placeholder.js";

/**
 * 视图注册表把导航配置中的 view 名称解析为渲染函数。
 *
 * 新模块可以注册自己的渲染器，而不需要修改主界面布局或侧边栏实现。
 * 渲染函数始终接收导航项，便于不同模块读取自己的标题与说明。
 */
const views = new Map([
    ["overview", () => renderOverview()],
    ["placeholder", (item) => renderPlaceholder(item)],
    ["lab", () => renderLab()]
]);

/**
 * 注册或替换视图渲染器，为后续模块插件式接入预留入口。
 *
 * @param {string} name 视图名
 * @param {(item: object) => string} renderer 渲染函数
 */
export function registerView(name, renderer) {
    if (!name || typeof renderer !== "function") {
        throw new TypeError("注册视图需要名称和渲染函数");
    }
    views.set(name, renderer);
}

/**
 * 渲染指定导航项。
 *
 * @param {object} item 导航项
 * @returns {string} 视图 HTML
 */
export function renderView(item) {
    const renderer = views.get(item.view);
    if (!renderer) {
        return renderPlaceholder({
            ...item,
            caption: "视图尚未注册"
        });
    }
    return renderer(item);
}
