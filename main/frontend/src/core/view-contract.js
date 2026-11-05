/**
 * 定义兼容静态模板与异步模块的视图生命周期契约。
 *
 * render 返回初始 HTML；mount 在 DOM 插入后绑定事件或启动异步加载；
 * unmount 必须释放事件、定时器、请求和订阅。所有钩子都可返回 Promise。
 *
 * @param {{
 *   name?: string,
 *   render: (item: object, context: object) => string | Promise<string>,
 *   mount?: (container: HTMLElement, context: object) => void | Promise<void>,
 *   unmount?: (context: object) => void | Promise<void>
 * }} definition 视图定义
 * @returns {Readonly<object>} 已校验且不可替换钩子的视图定义
 */
export function defineView({
    name = "anonymous",
    render,
    mount = () => {},
    unmount = () => {}
}) {
    if (typeof render !== "function") {
        throw new TypeError("视图必须提供 render 函数");
    }
    if (typeof mount !== "function" || typeof unmount !== "function") {
        throw new TypeError("视图 mount/unmount 必须是函数");
    }

    return Object.freeze({ name, render, mount, unmount });
}

/**
 * 把只有 render 的旧式静态视图包装成完整生命周期视图。
 *
 * @param {string} name 视图名
 * @param {(item: object) => string} render 静态渲染函数
 * @returns {Readonly<object>} 完整视图定义
 */
export function createStaticView(name, render) {
    return defineView({ name, render });
}
