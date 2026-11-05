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
    #sections;
    #items = new Map();

    /**
     * @param {{container: HTMLElement, onSelect: (item: object) => void}} options
     */
    constructor({ container, onSelect, sections = navigationSections }) {
        this.#container = container;
        this.#onSelect = onSelect;
        this.#sections = sections;
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

    /**
     * 根据配置建立 DOM 节点。
     *
     * 模块名称和说明通过 textContent 写入，因此未来即使导航配置来自后端，
     * 也不会把外部字符串解释为 HTML。
     */
    #render() {
        this.#container.replaceChildren();
        this.#items.clear();

        for (const section of this.#sections) {
            const sectionElement = document.createElement("section");
            sectionElement.className = "nav-section";
            sectionElement.setAttribute("aria-labelledby", `nav-section-${section.id}`);

            const title = document.createElement("h2");
            title.className = "nav-section__title";
            title.id = `nav-section-${section.id}`;
            title.textContent = section.label;

            const list = document.createElement("div");
            list.className = "nav-list";

            for (const item of section.items) {
                this.#items.set(item.id, item);

                const button = document.createElement("button");
                button.className = "nav-item";
                button.type = "button";
                button.dataset.navigationId = item.id;
                button.dataset.view = item.view;
                button.title = item.label;

                const iconElement = document.createElement("span");
                iconElement.className = "nav-item__icon";
                // 图标路径来自本地受控图标表，不接受后端文本。
                iconElement.innerHTML = icon(item.icon, 19);

                const copy = document.createElement("span");
                copy.className = "nav-item__copy";
                const label = document.createElement("strong");
                label.textContent = item.label;
                const caption = document.createElement("span");
                caption.textContent = item.caption;
                copy.append(label, caption);

                const marker = document.createElement("span");
                marker.className = "nav-item__marker";
                marker.setAttribute("aria-hidden", "true");
                button.append(iconElement, copy, marker);
                list.append(button);
            }

            sectionElement.append(title, list);
            this.#container.append(sectionElement);
        }
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

        const item = this.#items.get(button.dataset.navigationId);

        if (item) {
            this.setActive(item.id);
            this.#onSelect(item);
        }
    }
}
