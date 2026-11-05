/**
 * 本地内联图标集合。
 *
 * 图标不依赖网络字体或外部资源，避免离线使用时出现空白和额外请求。
 * 每个条目只包含 SVG 内部路径，统一由 icon() 包装尺寸和无障碍属性。
 */
const iconPaths = {
    compass: '<circle cx="12" cy="12" r="8.5"/><path d="m14.8 9.2-1.5 4.1-4.1 1.5 1.5-4.1 4.1-1.5Z"/>',
    folder: '<path d="M3.5 7.5h6l1.8 2h9.2v8.2a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7.5Z"/><path d="M3.5 7.5V6a2 2 0 0 1 2-2h4l1.8 2h5.2a2 2 0 0 1 2 2v1.5"/>',
    check: '<rect x="3.5" y="4.5" width="17" height="15" rx="3"/><path d="m8 12 2.6 2.6L16.5 9"/>',
    calendar: '<rect x="3.5" y="5.5" width="17" height="15" rx="3"/><path d="M7.5 3.5v4M16.5 3.5v4M3.5 10h17M8 14h.01M12 14h.01M16 14h.01M8 17.5h.01M12 17.5h.01"/>',
    history: '<path d="M4 7.2V3.8M4 3.8h3.4M4.4 4.2A9 9 0 1 1 3.5 15"/><path d="M12 7.5V12l3 1.7"/>',
    flask: '<path d="M9 3.5h6M10 3.5v5.2L5.2 17a2.1 2.1 0 0 0 1.8 3.2h10a2.1 2.1 0 0 0 1.8-3.2L14 8.7V3.5"/><path d="M7.6 15h8.8"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19 13.3a7.9 7.9 0 0 0 0-2.6l2-1.5-2-3.4-2.4 1a8.1 8.1 0 0 0-2.3-1.3L14 3h-4l-.4 2.5a8.1 8.1 0 0 0-2.3 1.3l-2.4-1-2 3.4 2 1.5a7.9 7.9 0 0 0 0 2.6l-2 1.5 2 3.4 2.4-1a8.1 8.1 0 0 0 2.3 1.3L10 21h4l.4-2.5a8.1 8.1 0 0 0 2.3-1.3l2.4 1 2-3.4-2.1-1.5Z"/>',
    sparkles: '<path d="m12 3 1.4 4.1L17.5 8.5l-4.1 1.4L12 14l-1.4-4.1-4.1-1.4 4.1-1.4L12 3Z"/><path d="m18.5 14 .8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2ZM5.5 15l.6 1.6 1.6.6-1.6.6-.6 1.6L4.9 18l-1.6-.6 1.6-.6.6-1.8Z"/>',
    bell: '<path d="M18 9.5a6 6 0 0 0-12 0c0 7-2.2 7-2.2 8.5h16.4C20.2 16.5 18 16.5 18 9.5Z"/><path d="M9.7 21h4.6"/>',
    palette: '<path d="M12 3a9 9 0 0 0 0 18h1.2a1.8 1.8 0 0 0 1.1-3.2 1.8 1.8 0 0 1 1.1-3.2H18a3 3 0 0 0 3-3A8.5 8.5 0 0 0 12 3Z"/><path d="M7.5 10h.01M10 6.8h.01M14 6.8h.01M17 9.6h.01"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3.5 9h17M3.5 15h17M12 3c2.4 2.5 3.5 5.5 3.5 9s-1.1 6.5-3.5 9c-2.4-2.5-3.5-5.5-3.5-9S9.6 5.5 12 3Z"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    "arrow-up": '<path d="m6 11 6-6 6 6M12 5v14"/>',
    layers: '<path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5M3 16l9 5 9-5"/>',
    shield: '<path d="M12 3 5 6v5c0 4.7 2.8 8 7 10 4.2-2 7-5.3 7-10V6l-7-3Z"/><path d="m9 12 2 2 4-4"/>',
    route: '<circle cx="6" cy="6" r="2.5"/><circle cx="18" cy="18" r="2.5"/><path d="M8.5 6h4A3.5 3.5 0 0 1 16 9.5v3A3.5 3.5 0 0 0 19.5 16h.5M16 18h-5a3 3 0 0 1-3-3V9"/>',
    moon: '<path d="M20.2 15.2A8.5 8.5 0 0 1 8.8 3.8 8.8 8.8 0 1 0 20.2 15.2Z"/>',
    command: '<path d="M9 6V5a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v14a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6Z"/>'
};

/**
 * 生成可访问的内联 SVG。
 *
 * 名称来自受控的本地配置，不接受用户输入，因此不会形成 HTML 注入通道。
 *
 * @param {string} name 图标名称
 * @param {number} size 像素尺寸
 * @returns {string} SVG 标记
 */
export function icon(name, size = 20) {
    const paths = iconPaths[name] ?? iconPaths.layers;
    return `
        <svg class="icon" width="${size}" height="${size}" viewBox="0 0 24 24"
             fill="none" stroke="currentColor" stroke-width="1.7"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            ${paths}
        </svg>
    `;
}
