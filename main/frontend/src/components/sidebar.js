import { navigationSections } from "../config/navigation.js";
import { icon } from "./icons.js";

/**
 * 导航组件。
 *
 * 组件只负责渲染配置和抛出选择事件，不直接决定页面内容；
 * 这样侧栏可以独立替换，导航配置也可以从后端模块目录动态生成。
 */
export class Sidebar {
    #container;
    #onSelect;

    /**
     * @param {{container: HTMLElement, onSelect: (item: object) => void}} options
     */
    constructor({ container, onSelect }) {
        this.#container = container;
        this.#onSelect = onSelect;
        this.#render();
        this.#container.addEventListener("click", (event) => this.#handleClick(event));
    }

    /**
     * 标记当前导航项，并向辅助技术同步当前位置。
     *
     * @param {string} itemId 当前导航项 id
     */
    setActive(itemId) {
        this.#container.querySelectorAll("[data-navigation-id]").forEach((button) => {
            const active = button.dataset.navigationId === itemId;
            button.classList.toggle("is-active", active);
            if (active) {
                button.setAttribute("aria-current", "page");
            } else {
                button.removeAttribute("aria-current");
            }
        });
    }

    /** 根据稳定配置生成分组导航；所有标签来自本地静态配置。 */
    #render() {
        this.#container.innerHTML = navigationSections.map((section) => `
            <section class="nav-section" aria-labelledby="nav-section-${section.id}">
                <h2 class="nav-section__title" id="nav-section-${section.id}">
                    ${section.label}
                </h2>
                <div class="nav-list">
                    ${section.items.map((item) => `
                        <button
                            class="nav-item"
                            type="button"
                            data-navigation-id="${item.id}"
                            data-view="${item.view}"
                            title="${item.label}"
                        >
                            <span class="nav-item__icon">${icon(item.icon, 19)}</span>
                            <span class="nav-item__copy">
                                <strong>${item.label}</strong>
                                <span>${item.caption}</span>
                            </span>
                            <span class="nav-item__marker" aria-hidden="true"></span>
                        </button>
                    `).join("")}
                </div>
            </section>
        `).join("");
    }

    /**
     * 使用事件委托处理导航，避免每个按钮单独绑定监听器。
     *
     * @param {MouseEvent} event 点击事件
     */
    #handleClick(event) {
        const button = event.target.closest("[data-navigation-id]");
        if (!button) {
            return;
        }

        const item = navigationSections
            .flatMap((section) => section.items)
            .find((candidate) => candidate.id === button.dataset.navigationId);

        if (item) {
            this.setActive(item.id);
            this.#onSelect(item);
        }
    }
}
