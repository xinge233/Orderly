import { renderView } from "../core/view-registry.js";

/**
 * 主显示区协调器。
 *
 * 它只负责替换可信本地视图、更新面包屑和播放转场，不持有任何业务状态。
 */
export class MainView {
    #stage;
    #breadcrumb;
    #motionEngine;

    /**
     * @param {{stage: HTMLElement, breadcrumb: HTMLElement, motionEngine: import("../core/motion-engine.js").MotionEngine}} options
     */
    constructor({ stage, breadcrumb, motionEngine }) {
        this.#stage = stage;
        this.#breadcrumb = breadcrumb;
        this.#motionEngine = motionEngine;
    }

    /**
     * 渲染并展示一个导航项。
     *
     * @param {object} item 导航项
     */
    show(item) {
        this.#stage.innerHTML = renderView(item);
        this.#breadcrumb.innerHTML = `
            <span>工作台</span>
            <span aria-hidden="true">/</span>
            <strong>${item.label}</strong>
        `;

        // 视图切换后回到顶部，避免上一页的滚动位置误导用户。
        this.#stage.scrollTo({ top: 0, behavior: "instant" });
        this.#motionEngine.play(this.#stage, "navigate");
        this.#stage.focus({ preventScroll: true });
    }
}
