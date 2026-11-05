import { icon } from "./icons.js";

/**
 * AI 指令输入占位条。
 *
 * 输入和发送只产生界面说明，不请求后端、不创建计划、不触发文件操作。
 * 后续接入时会替换本组件内部的提交策略，而不改变全局布局。
 */
export class ChatDock {
    #form;
    #input;
    #button;
    #motionEngine;
    #notify;

    /**
     * @param {{form: HTMLFormElement, input: HTMLInputElement, button: HTMLButtonElement, motionEngine: import("../core/motion-engine.js").MotionEngine, notify: (message: string) => void}} options
     */
    constructor({ form, input, button, motionEngine, notify }) {
        this.#form = form;
        this.#input = input;
        this.#button = button;
        this.#motionEngine = motionEngine;
        this.#notify = notify;

        this.#button.innerHTML = icon("arrow-up", 19);
        this.#form.addEventListener("submit", (event) => this.#handleSubmit(event));
    }

    /**
     * 明确告知用户当前只是占位，不把输入文本发送到任何系统。
     *
     * @param {SubmitEvent} event 表单提交事件
     */
    #handleSubmit(event) {
        event.preventDefault();
        this.#motionEngine.play(this.#button, "send");

        if (!this.#input.value.trim()) {
            this.#notify("请先输入一条指令；当前仅为界面占位。");
            this.#input.focus();
            return;
        }

        this.#notify("聊天能力尚未接入，没有发送内容或执行任何操作。");
    }
}
