/**
 * 框架内置图标目录与 `data-icon` 占位符水合工具。
 *
 * 职责：集中维护受信任的 SVG 路径和 AI 图标白名单，使模块与设置界面无需各自复制图标表。
 * 安全边界：`icon()` 只拼接本文件中的静态路径，不接受调用方传入 SVG 字符串；非法图标名
 * 回退为信息图标。调用方仍需确保 `size` 是受控数字，再使用返回值设置 `innerHTML`。
 *
 * 副作用：除 `hydrateIcons()` 会修改指定 DOM 子树外，其余函数均不读写外部状态。
 */

// 该对象是唯一的内置路径来源；新增图标时应保持路径为静态、可信的 SVG 片段。
const ICONS = {
    search: '<circle cx="11" cy="11" r="7"></circle><path d="m20 20-4-4"></path>',
    settings: '<circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.09A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.09A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.09A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.12.38.33.72.6 1 .3.28.68.42 1.1.4H21v4h-.09A1.7 1.7 0 0 0 19.4 15Z"></path>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"></path>',
    close: '<path d="m6 6 12 12M18 6 6 18"></path>',
    arrow: '<path d="m9 18 6-6-6-6"></path>',
    up: '<path d="m6 15 6-6 6 6"></path>',
    home: '<path d="m3 11 9-8 9 8"></path><path d="M5 10v10h14V10M9 20v-6h6v6"></path>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h5l2 2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"></path>',
    file: '<path d="M6 2h8l4 4v16H6Z"></path><path d="M14 2v5h5"></path>',
    check: '<rect x="3" y="3" width="18" height="18" rx="5"></rect><path d="m8 12 2.5 2.5L16 9"></path>',
    "check-plain": '<path d="m5 12 4 4L19 6"></path>',
    alert: '<path d="M10.3 3.7 2.6 17a2 2 0 0 0 1.8 3h15.2a2 2 0 0 0 1.8-3L13.7 3.7a2 2 0 0 0-3.4 0Z"></path><path d="M12 9v4M12 17h.01"></path>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="3"></rect><path d="M8 3v4M16 3v4M3 10h18"></path>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"></path><path d="M3 3v5h5M12 7v5l3 2"></path>',
    spark: '<path d="m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7Z"></path><path d="m19 16 .8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8Z"></path>',
    shield: '<path d="M12 3 4.5 6v5.3c0 4.7 3 7.9 7.5 9.7 4.5-1.8 7.5-5 7.5-9.7V6Z"></path><path d="m9 12 2 2 4-4"></path>',
    route: '<circle cx="6" cy="18" r="2"></circle><circle cx="18" cy="6" r="2"></circle><path d="M8 18h3a3 3 0 0 0 3-3V9a3 3 0 0 1 3-3"></path>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"></path><path d="M6.1 9a7 7 0 0 1 11.5-2L20 9M4 15l2.4 2A7 7 0 0 0 18 15"></path>',
    download: '<path d="M12 3v11"></path><path d="m7 10 5 5 5-5"></path><path d="M5 20h14"></path>',
    power: '<path d="M12 3v9"></path><path d="M7.1 5.7a8 8 0 1 0 9.8 0"></path>',
    trash: '<path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"></path>',
    play: '<path d="m8 5 11 7-11 7Z"></path>',
    undo: '<path d="M9 7 4 12l5 5"></path><path d="M4 12h10a6 6 0 0 1 0 12"></path>',
    info: '<circle cx="12" cy="12" r="9"></circle><path d="M12 11v6M12 7h.01"></path>',
    palette: '<path d="M12 3a9 9 0 1 0 0 18h1.5a1.5 1.5 0 0 0 0-3H12a2 2 0 0 1 0-4h5a4 4 0 0 0 0-8h-1a4 4 0 0 0-4-3Z"></path><circle cx="7.5" cy="10" r="1"></circle><circle cx="9.5" cy="6.5" r="1"></circle><circle cx="14" cy="6.5" r="1"></circle>',
    modules: '<rect x="3" y="3" width="7" height="7" rx="2"></rect><rect x="14" y="3" width="7" height="7" rx="2"></rect><rect x="3" y="14" width="7" height="7" rx="2"></rect><rect x="14" y="14" width="7" height="7" rx="2"></rect>',
    notification: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"></path>'
};

export const AI_ICON_OPTIONS = Object.freeze([
    { value: "spark", label: "星芒" },
    { value: "route", label: "路径" },
    { value: "shield", label: "防护" },
    { value: "modules", label: "模块" }
]);

const AI_ICONS = new Set(AI_ICON_OPTIONS.map((item) => item.value));
const THEME_AI_ICON_URL = /^\/api\/themes\/[a-z][a-z0-9-]{0,63}\/assets\/assets\/ai\/icons\/(?:spark|route|shield|modules)\.png$/;

/**
 * 将 AI 图标限制为前端内置选项；非法值统一回退星芒。
 *
 * @param {unknown} value 原始值
 * @returns {"spark" | "route" | "shield" | "modules"} 合法图标名
 *
 * 异常与副作用：无。未知或非字符串值不会透传，避免设置页制造对任意图标名的隐式依赖。
 */
export function normalizeAiIcon(value) {
    return AI_ICONS.has(value) ? value : "spark";
}

/**
 * 将 AI 身份图标绘制为主题 PNG mask 或框架通用兜底图标。
 *
 * @param {HTMLElement} element 图标容器
 * @param {unknown} name AI 图标 ID
 * @param {{url?: unknown, size?: unknown}} options 主题资源 URL 与渲染尺寸
 * @returns {void}
 *
 * 主题 URL 只接受宿主生成的四类 PNG 资源路径，避免把任意字符串写入 CSS。资源缺失时
 * 使用通用信息图标，保证无主题、旧主题和加载失败场景仍显示可识别入口。副作用：改写
 * 目标元素的数据属性、CSS 变量、子节点和 innerHTML。
 */
export function paintAiIcon(element, name, { url = "", size = 18 } = {}) {
    if (!element) {
        return;
    }
    const iconName = normalizeAiIcon(name);
    const iconSize = Number.isFinite(Number(size))
        ? Math.max(8, Math.min(64, Math.round(Number(size))))
        : 18;
    const themeUrl = typeof url === "string" && THEME_AI_ICON_URL.test(url) ? url : "";
    element.dataset.aiIcon = iconName;
    element.style.setProperty("--ai-icon-size", `${iconSize}px`);
    if (themeUrl) {
        element.dataset.iconSource = "theme";
        element.style.setProperty("--ai-icon-image", `url("${themeUrl}")`);
        element.replaceChildren();
        return;
    }
    element.dataset.iconSource = "fallback";
    element.style.removeProperty("--ai-icon-image");
    element.innerHTML = icon("info", iconSize);
}

/**
 * 仅渲染内置图标路径，不接受外部 SVG 字符串。
 *
 * @param {string} name 图标名
 * @param {number} size 像素尺寸
 * @returns {string} 静态 SVG 标记
 *
 * 异常与副作用：无，也不访问 DOM。返回值包含调用方给出的尺寸属性，因此尺寸也必须受控；
 * 路径内容始终来自本文件内置表，而不是外部模块清单或网络数据。
 */
export function icon(name, size = 18) {
    const paths = ICONS[name] ?? ICONS.info;
    return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
}

/**
 * 替换页面中的静态图标占位符。
 *
 * @param {ParentNode} root 根节点
 * @returns {void}
 *
 * 副作用：查询并改写 `root` 下所有 `[data-icon]` 元素的 `innerHTML`。图标名经 `icon()`
 * 的白名单回退处理，未知名称只会显示内置信息图标。
 */
export function hydrateIcons(root = document) {
    root.querySelectorAll("[data-icon]").forEach((element) => {
        element.innerHTML = icon(element.dataset.icon);
    });
}
