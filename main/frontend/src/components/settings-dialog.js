import { settingSections } from "../config/settings.js";
import { icon } from "./icons.js";
import { MotionEngine } from "../core/motion-engine.js";

/**
 * 配置驱动的设置对话框。
 *
 * 设置分类、字段和候选项全部来自 settings.js。新增字段时只需扩展配置，
 * 本组件会负责输入渲染、存储更新和恢复默认值。
 */
export class SettingsDialog {
    #dialog;
    #nav;
    #panel;
    #store;
    #closeButton;
    #resetButton;
    #activeSectionId = settingSections[0]?.id;
    #motionEngine;

    /**
     * @param {{dialog: HTMLDialogElement, nav: HTMLElement, panel: HTMLElement, closeButton: HTMLButtonElement, resetButton: HTMLButtonElement, store: import("../core/settings-store.js").SettingsStore, motionEngine: MotionEngine}} options
     */
    constructor({ dialog, nav, panel, closeButton, resetButton, store, motionEngine }) {
        this.#dialog = dialog;
        this.#nav = nav;
        this.#panel = panel;
        this.#closeButton = closeButton;
        this.#resetButton = resetButton;
        this.#store = store;
        this.#motionEngine = motionEngine;

        this.#nav.addEventListener("click", (event) => this.#handleNavClick(event));
        this.#panel.addEventListener("change", (event) => this.#handleSettingChange(event));
        this.#closeButton.addEventListener("click", () => this.close());
        this.#resetButton.addEventListener("click", () => this.#handleReset());

        // 点击 dialog 外部区域可关闭；点击内容区域不会冒泡为关闭。
        this.#dialog.addEventListener("click", (event) => {
            if (event.target === this.#dialog) {
                this.close();
            }
        });
    }

    /** 打开设置并渲染最新快照。 */
    open() {
        this.#renderNav();
        this.#renderPanel();
        if (!this.#dialog.open) {
            this.#dialog.showModal();
        }
        this.#motionEngine.play(this.#dialog.querySelector(".settings-frame"), "open");
        window.setTimeout(() => {
            this.#panel.querySelector("input, select, button")?.focus();
        }, 80);
    }

    /** 关闭设置对话框。 */
    close() {
        if (this.#dialog.open) {
            this.#dialog.close();
        }
    }

    /** 创建设置分类导航。 */
    #renderNav() {
        this.#nav.innerHTML = settingSections.map((section) => `
            <button
                class="settings-nav__item ${section.id === this.#activeSectionId ? "is-active" : ""}"
                type="button"
                data-settings-section="${section.id}"
                ${section.id === this.#activeSectionId ? 'aria-current="true"' : ""}
            >
                <span class="settings-nav__icon">${icon(section.icon, 18)}</span>
                <span>
                    <strong>${section.title}</strong>
                    <small>${section.summary}</small>
                </span>
            </button>
        `).join("");
    }

    /** 渲染当前分类中的字段。 */
    #renderPanel() {
        const section = settingSections.find(
            (candidate) => candidate.id === this.#activeSectionId
        ) ?? settingSections[0];
        const settings = this.#store.getSnapshot();

        if (!section) {
            this.#panel.innerHTML = "<p>尚未配置设置分类。</p>";
            return;
        }

        this.#panel.innerHTML = `
            <header class="settings-panel__header">
                <span class="eyebrow">PREFERENCE GROUP</span>
                <h3>${section.title}</h3>
                <p>${section.summary}</p>
            </header>
            <div class="settings-fields">
                ${section.fields.map((field) => this.#renderField(field, settings[field.key])).join("")}
            </div>
        `;
    }

    /**
     * 渲染单个设置字段。
     *
     * @param {object} field 字段定义
     * @param {unknown} value 当前值
     * @returns {string} 字段 HTML
     */
    #renderField(field, value) {
        if (field.type === "theme") {
            return `
                <fieldset class="setting-field setting-field--themes" data-setting-field="${field.key}">
                    <legend>${field.label}</legend>
                    <p>${field.description}</p>
                    <div class="theme-options">
                        ${field.options.map((option) => `
                            <label class="theme-option ${value === option.value ? "is-selected" : ""}">
                                <input
                                    type="radio"
                                    name="${field.key}"
                                    value="${option.value}"
                                    ${value === option.value ? "checked" : ""}
                                >
                                <span class="theme-option__preview" style="--preview-bg:${option.swatches[0]};--preview-accent:${option.swatches[1]};--preview-second:${option.swatches[2]}">
                                    <i></i><i></i><i></i>
                                </span>
                                <span class="theme-option__copy">
                                    <strong>${option.label}</strong>
                                    <small>${option.note}</small>
                                </span>
                            </label>
                        `).join("")}
                    </div>
                </fieldset>
            `;
        }

        if (field.type === "segmented") {
            return `
                <fieldset class="setting-field" data-setting-field="${field.key}">
                    <legend>${field.label}</legend>
                    <p>${field.description}</p>
                    <div class="segmented-control">
                        ${field.options.map((option) => `
                            <label>
                                <input
                                    type="radio"
                                    name="${field.key}"
                                    value="${option.value}"
                                    ${value === option.value ? "checked" : ""}
                                >
                                <span>${option.label}</span>
                            </label>
                        `).join("")}
                    </div>
                </fieldset>
            `;
        }

        if (field.type === "select") {
            return `
                <div class="setting-field" data-setting-field="${field.key}">
                    <label for="setting-${field.key}">${field.label}</label>
                    <p>${field.description}</p>
                    <select class="setting-select" id="setting-${field.key}" data-setting-key="${field.key}">
                        ${field.options.map((option) => `
                            <option value="${option.value}" ${value === option.value ? "selected" : ""}>
                                ${option.label}
                            </option>
                        `).join("")}
                    </select>
                </div>
            `;
        }

        return `
            <div class="setting-field setting-field--row" data-setting-field="${field.key}">
                <div>
                    <label for="setting-${field.key}">${field.label}</label>
                    <p>${field.description}</p>
                </div>
                <label class="switch">
                    <input
                        id="setting-${field.key}"
                        type="checkbox"
                        data-setting-key="${field.key}"
                        ${value ? "checked" : ""}
                    >
                    <span aria-hidden="true"></span>
                </label>
            </div>
        `;
    }

    /**
     * 切换设置分类。
     *
     * @param {MouseEvent} event 点击事件
     */
    #handleNavClick(event) {
        const button = event.target.closest("[data-settings-section]");
        if (!button) {
            return;
        }
        this.#activeSectionId = button.dataset.settingsSection;
        this.#renderNav();
        this.#renderPanel();
    }

    /**
     * 将用户修改写入统一设置存储。
     *
     * @param {Event} event 表单变化事件
     */
    #handleSettingChange(event) {
        const input = event.target.closest("[name], [data-setting-key]");
        if (!input) {
            return;
        }

        const key = input.dataset.settingKey ?? input.name;
        const value = input.type === "checkbox" ? input.checked : input.value;
        this.#store.update({ [key]: value });

        // 主题与动效立即应用；主题卡片选中描边同步刷新，不重建整块表单。
        this.#panel.querySelectorAll(".theme-option").forEach((label) => {
            label.classList.toggle("is-selected", label.querySelector("input")?.checked);
        });
    }

    /** 恢复默认值并刷新当前字段。 */
    #handleReset() {
        this.#store.reset();
        this.#renderPanel();
    }
}
