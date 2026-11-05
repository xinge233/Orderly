import { themeBrowserColors } from "../config/settings.js";

/**
 * 把设置快照映射为根元素上的稳定主题状态。
 *
 * 具体颜色全部由 tokens.css 定义，控制器不拼接具体组件样式，
 * 因此新增主题只需增加令牌块和设置选项。
 */
export class ThemeController {
    #root;
    #meta;
    #motionEngine;

    /**
     * @param {{rootElement?: HTMLElement, themeColorMeta?: HTMLMetaElement | null, motionEngine: import("./motion-engine.js").MotionEngine}} options
     */
    constructor({
        rootElement = document.documentElement,
        themeColorMeta = document.querySelector("#theme-color-meta"),
        motionEngine
    }) {
        this.#root = rootElement;
        this.#meta = themeColorMeta;
        this.#motionEngine = motionEngine;
    }

    /**
     * 应用主题、动效与语言属性。
     *
     * @param {{theme: string, motion: string, language: string}} settings 设置快照
     */
    apply(settings) {
        this.#root.dataset.theme = settings.theme;
        this.#root.lang = settings.language;
        this.#motionEngine.setStrategy(settings.motion);

        if (this.#meta) {
            this.#meta.content = themeBrowserColors[settings.theme] ?? themeBrowserColors.paper;
        }
    }
}
