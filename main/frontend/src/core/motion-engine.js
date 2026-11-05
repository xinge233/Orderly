/**
 * 可替换动效策略。
 *
 * 每种策略只负责添加短暂的状态类，具体动画由 CSS 的 data-motion 规则实现。
 * 如需更换视觉特效，可新增策略而不改动导航、设置或数据模块。
 */
const strategies = {
    gentle: {
        className: "motion-gentle",
        duration: 420
    },
    minimal: {
        className: "motion-minimal",
        duration: 220
    },
    none: {
        className: "",
        duration: 0
    }
};

export class MotionEngine {
    #strategy = strategies.gentle;
    #root;

    /**
     * @param {{rootElement?: HTMLElement}} options 根元素
     */
    constructor({ rootElement = document.documentElement } = {}) {
        this.#root = rootElement;
    }

    /**
     * 切换动效策略。
     *
     * @param {keyof strategies} name 策略名称
     */
    setStrategy(name) {
        this.#strategy = strategies[name] ?? strategies.gentle;
        this.#root.dataset.motion = name in strategies ? name : "gentle";
    }

    /**
     * 对目标元素播放一次可替换的状态反馈。
     *
     * @param {HTMLElement | null} target 目标元素
     * @param {"navigate" | "open" | "send"} effect 效果语义
     */
    play(target, effect = "navigate") {
        if (!target || !this.#strategy.className) {
            return;
        }

        target.dataset.effect = effect;
        target.classList.remove(this.#strategy.className);
        // 强制一次布局，确保连续点击同一入口时仍会重新播放动画。
        void target.offsetWidth;
        target.classList.add(this.#strategy.className);

        window.setTimeout(() => {
            target.classList.remove(this.#strategy.className);
            delete target.dataset.effect;
        }, this.#strategy.duration);
    }
}
