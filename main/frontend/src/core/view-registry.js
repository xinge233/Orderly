import { labView } from "../modules/lab.js";
import { overviewView } from "../modules/overview.js";
import { placeholderView } from "../modules/placeholder.js";

/**
 * 视图注册表把导航配置中的 view 名称解析为完整生命周期定义。
 *
 * 新模块可以注册自己的 render/mount/unmount 钩子，而不需要修改主界面布局。
 */
const views = new Map([
    ["overview", overviewView],
    ["placeholder", placeholderView],
    ["lab", labView]
]);

/**
 * 注册或替换视图定义。
 *
 * @param {string} name 视图名
 * @param {Readonly<object>} view 完整视图定义
 */
export function registerView(name, view) {
    if (
        !name
        || !view
        || typeof view.render !== "function"
        || typeof view.mount !== "function"
        || typeof view.unmount !== "function"
    ) {
        throw new TypeError("注册视图需要名称和完整生命周期定义");
    }
    views.set(name, view);
}

/**
 * 判断视图是否已经注册。
 *
 * @param {string} name 视图名
 * @returns {boolean} 已注册时返回 true
 */
export function hasView(name) {
    return views.has(name);
}

/**
 * 返回已注册视图。
 *
 * 未知视图直接抛错，配置一致性测试和运行时都不会静默降级。
 *
 * @param {string} name 视图名
 * @returns {Readonly<object>} 视图定义
 */
export function getView(name) {
    const view = views.get(name);
    if (!view) {
        throw new Error(`视图尚未注册：${name}`);
    }
    return view;
}

/**
 * 返回所有已注册视图名快照。
 *
 * @returns {ReadonlyArray<string>} 视图名列表
 */
export function registeredViewNames() {
    return Object.freeze([...views.keys()]);
}
