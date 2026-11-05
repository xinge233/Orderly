import { icon } from "../components/icons.js";
import { createStaticView } from "../core/view-contract.js";

/**
 * 通用模块占位视图。
 *
 * 通过同一渲染器承载多个模块，避免为尚未实现的功能复制页面结构。
 * 真实模块接入时，可在 view-registry.js 中用专用渲染器替换它。
 *
 * @param {object} item 当前导航项
 * @returns {string} 视图 HTML
 */
export function renderPlaceholder(item) {
    return `
        <div class="view-stack placeholder-view">
            <header class="page-heading">
                <div class="page-heading__icon">${icon(item.icon, 26)}</div>
                <div>
                    <span class="eyebrow">MODULE PLACEHOLDER</span>
                    <h1>${item.label}</h1>
                    <p>${item.caption} · 此区域已经为独立模块预留，当前不执行任何业务操作。</p>
                </div>
                <span class="status-chip">界面占位</span>
            </header>

            <section class="placeholder-canvas">
                <div class="placeholder-canvas__message">
                    <span class="placeholder-canvas__signal">${icon("route", 25)}</span>
                    <h2>等待模块契约接入</h2>
                    <p>
                        页面布局、导航位置和视觉主题已准备完成。
                        后续只接入已评审的数据结构与受控接口。
                    </p>
                </div>

                <div class="placeholder-layout" aria-hidden="true">
                    <aside>
                        <span class="skeleton-block skeleton-block--short"></span>
                        <span class="skeleton-block"></span>
                        <span class="skeleton-block"></span>
                        <span class="skeleton-block skeleton-block--short"></span>
                    </aside>
                    <div>
                        <span class="skeleton-block skeleton-block--hero"></span>
                        <div class="skeleton-row">
                            <span class="skeleton-block"></span>
                            <span class="skeleton-block"></span>
                            <span class="skeleton-block"></span>
                        </div>
                    </div>
                </div>
            </section>

            <section class="contract-strip">
                <span>${icon("layers", 20)}</span>
                <div>
                    <strong>模块接入原则</strong>
                    <p>先定义契约与 Mock，再实现界面和 Java 控制层行为。</p>
                </div>
                <span class="status-chip status-chip--quiet">尚未接入</span>
            </section>
        </div>
    `;
}

/** 通用占位视图定义。 */
export const placeholderView = createStaticView("placeholder", renderPlaceholder);
