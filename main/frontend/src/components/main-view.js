import { getView } from "../core/view-registry.js";

/**
 * 主显示区生命周期协调器。
 *
 * 它保证旧视图先 unmount，再渲染并 mount 新视图；同时使用 AbortSignal、
 * 清理回调和切换代次标识防止异步请求、定时器或快速切换造成资源泄漏。
 */
export class MainView {
    #stage;
    #breadcrumb;
    #motionEngine;
    #document;
    #active = null;
    #generation = 0;

    /**
     * @param {{
     *   stage: HTMLElement,
     *   breadcrumb: HTMLElement,
     *   motionEngine: import("../core/motion-engine.js").MotionEngine,
     *   documentRef?: Document
     * }} options
     */
    constructor({
        stage,
        breadcrumb,
        motionEngine,
        documentRef = globalThis.document
    }) {
        this.#stage = stage;
        this.#breadcrumb = breadcrumb;
        this.#motionEngine = motionEngine;
        this.#document = documentRef;
    }

    /**
     * 切换并挂载视图。
     *
     * @param {object} item 导航项
     * @returns {Promise<void>} 新视图 mount 完成或切换被更新请求取代
     */
    async show(item) {
        const generation = ++this.#generation;

        // 先释放旧视图，再开始新视图渲染，避免两个视图的资源同时存活。
        await this.#deactivateActive();
        if (generation !== this.#generation) {
            return;
        }

        const view = getView(item.view);
        const initialContext = {
            item,
            viewName: item.view
        };
        const html = await Promise.resolve(view.render(item, initialContext));
        if (generation !== this.#generation) {
            return;
        }

        this.#stage.innerHTML = html;
        this.#updateBreadcrumb(item);

        const controller = new AbortController();
        const cleanupCallbacks = [];
        const active = {
            view,
            item,
            controller,
            cleanupCallbacks,
            state: "mounting",
            context: null
        };
        const context = Object.freeze({
            item,
            viewName: item.view,
            signal: controller.signal,
            /**
             * 注册视图切换时必须执行的同步或异步清理函数。
             *
             * @param {() => void | Promise<void>} cleanup 清理函数
             */
            registerCleanup: (cleanup) => {
                if (typeof cleanup !== "function") {
                    throw new TypeError("视图清理回调必须是函数");
                }
                if (active.state === "destroyed") {
                    void Promise.resolve(cleanup());
                    return;
                }
                cleanupCallbacks.push(cleanup);
            }
        });
        active.context = context;
        this.#active = active;

        try {
            await Promise.resolve(view.mount(this.#stage, context));
            if (active.state === "destroyed") {
                return;
            }
            active.state = "mounted";

            if (generation !== this.#generation) {
                await this.#destroy(active);
                return;
            }

            // 视图切换后才播放转场，确保 mount 失败不会被伪装成成功切换。
            this.#stage.scrollTo({ top: 0, behavior: "instant" });
            this.#motionEngine.play(this.#stage, "navigate");
            this.#stage.focus({ preventScroll: true });
        } catch (error) {
            await this.#destroy(active);
            if (generation === this.#generation) {
                this.#stage.textContent = "";
                const message = this.#document.createElement("p");
                message.className = "prototype-note";
                message.textContent = "视图加载失败，请检查控制台和模块契约。";
                this.#stage.append(message);
            }
            throw error;
        }
    }

    /**
     * 销毁当前视图；重复调用不会重复执行 unmount。
     *
     * @returns {Promise<void>} 清理完成
     */
    async destroy() {
        this.#generation++;
        await this.#deactivateActive();
    }

    /**
     * 停止当前视图并等待全部释放完成。
     *
     * @returns {Promise<void>} 清理完成
     */
    async #deactivateActive() {
        const active = this.#active;
        this.#active = null;
        if (active) {
            await this.#destroy(active);
        }
    }

    /**
     * 幂等销毁一个视图实例。
     *
     * @param {object} active 活动视图状态
     * @returns {Promise<void>} 清理完成
     */
    async #destroy(active) {
        if (active.state === "destroyed") {
            return;
        }
        active.state = "destroyed";
        active.controller.abort();

        try {
            await Promise.resolve(active.view.unmount(active.context));
        } finally {
            const cleanups = active.cleanupCallbacks.splice(0).reverse();
            for (const cleanup of cleanups) {
                try {
                    await Promise.resolve(cleanup());
                } catch {
                    // 单个清理失败不能阻止其余资源释放。
                }
            }
        }
    }

    /**
     * 使用 DOM 节点和 textContent 更新面包屑，兼容未来动态模块名称。
     *
     * @param {object} item 当前导航项
     */
    #updateBreadcrumb(item) {
        const rootLabel = this.#document.createElement("span");
        rootLabel.textContent = "工作台";
        const separator = this.#document.createElement("span");
        separator.setAttribute("aria-hidden", "true");
        separator.textContent = "/";
        const current = this.#document.createElement("strong");
        current.textContent = item.label;
        this.#breadcrumb.replaceChildren(rootLabel, separator, current);
    }
}
