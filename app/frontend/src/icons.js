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
    calendar: '<rect x="3" y="5" width="18" height="16" rx="3"></rect><path d="M8 3v4M16 3v4M3 10h18"></path>',
    history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"></path><path d="M3 3v5h5M12 7v5l3 2"></path>',
    spark: '<path d="m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7Z"></path><path d="m19 16 .8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8Z"></path>',
    shield: '<path d="M12 3 4.5 6v5.3c0 4.7 3 7.9 7.5 9.7 4.5-1.8 7.5-5 7.5-9.7V6Z"></path><path d="m9 12 2 2 4-4"></path>',
    route: '<circle cx="6" cy="18" r="2"></circle><circle cx="18" cy="6" r="2"></circle><path d="M8 18h3a3 3 0 0 0 3-3V9a3 3 0 0 1 3-3"></path>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"></path><path d="M6.1 9a7 7 0 0 1 11.5-2L20 9M4 15l2.4 2A7 7 0 0 0 18 15"></path>',
    trash: '<path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6"></path>',
    play: '<path d="m8 5 11 7-11 7Z"></path>',
    undo: '<path d="M9 7 4 12l5 5"></path><path d="M4 12h10a6 6 0 0 1 0 12"></path>',
    info: '<circle cx="12" cy="12" r="9"></circle><path d="M12 11v6M12 7h.01"></path>',
    palette: '<path d="M12 3a9 9 0 1 0 0 18h1.5a1.5 1.5 0 0 0 0-3H12a2 2 0 0 1 0-4h5a4 4 0 0 0 0-8h-1a4 4 0 0 0-4-3Z"></path><circle cx="7.5" cy="10" r="1"></circle><circle cx="9.5" cy="6.5" r="1"></circle><circle cx="14" cy="6.5" r="1"></circle>',
    modules: '<rect x="3" y="3" width="7" height="7" rx="2"></rect><rect x="14" y="3" width="7" height="7" rx="2"></rect><rect x="3" y="14" width="7" height="7" rx="2"></rect><rect x="14" y="14" width="7" height="7" rx="2"></rect>',
    notification: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"></path>'
};

/**
 * 仅渲染内置图标路径，不接受外部 SVG 字符串。
 *
 * @param {string} name 图标名
 * @param {number} size 像素尺寸
 * @returns {string} 静态 SVG 标记
 */
export function icon(name, size = 18) {
    const paths = ICONS[name] ?? ICONS.info;
    return `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
}

/**
 * 替换页面中的静态图标占位符。
 *
 * @param {ParentNode} root 根节点
 */
export function hydrateIcons(root = document) {
    root.querySelectorAll("[data-icon]").forEach((element) => {
        element.innerHTML = icon(element.dataset.icon);
    });
}
