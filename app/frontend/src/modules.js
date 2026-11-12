const MODULE_ID = /^[a-z][a-z0-9_-]{0,63}$/;
const VIEW_ID = /^[a-z][a-z0-9-]{0,63}$/;
const ASSET_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function assetName(value, extension) {
    const name = text(value, `module.${extension}`);
    if (!ASSET_NAME.test(name) || !name.toLowerCase().endsWith(`.${extension}`)) {
        throw new TypeError(`模块资源名非法：${name}`);
    }
    return name;
}

/**
 * 校验模块自带的 UI 清单。无效清单只影响当前模块。
 *
 * @param {string} moduleId 模块 ID
 * @param {unknown} input 原始清单
 * @returns {object} 规范化清单
 */
export function normalizeModuleUi(moduleId, input) {
    if (!MODULE_ID.test(String(moduleId ?? ""))) {
        throw new TypeError("模块 ID 非法");
    }
    const source = input && typeof input === "object" ? input : {};
    if (Number(source.version ?? 1) !== 1) {
        throw new TypeError(`不支持的模块 UI 版本：${source.version}`);
    }
    const rawView = source.view && typeof source.view === "object" ? source.view : {};
    const viewId = text(rawView.id);
    if (!VIEW_ID.test(viewId)) {
        throw new TypeError(`模块 ${moduleId} 缺少合法 view.id`);
    }

    const rawCard = source.card && typeof source.card === "object" ? source.card : null;
    const card = rawCard ? {
        title: text(rawCard.title, "模块摘要"),
        description: text(rawCard.description, "由模块提供的数据摘要。"),
        badge: text(rawCard.badge, "模块数据"),
        icon: text(rawCard.icon, "modules"),
        queryId: text(rawCard.queryId),
        countKey: text(rawCard.countKey, "count"),
        unit: text(rawCard.unit, "项")
    } : null;

    return Object.freeze({
        version: 1,
        view: Object.freeze({
            id: viewId,
            caption: text(rawView.caption, "扩展模块"),
            icon: text(rawView.icon, "modules")
        }),
        card: card ? Object.freeze(card) : null,
        examples: Object.freeze((Array.isArray(source.examples) ? source.examples : [])
            .filter((item) => item && typeof item === "object" && text(item.text))
            .map((item) => Object.freeze({
                label: text(item.label, "示例"),
                text: text(item.text)
            }))),
        script: assetName(source.script, "js"),
        style: assetName(source.style, "css")
    });
}

/**
 * 按用户保存顺序排列模块，未排序的新模块按契约 order 追加。
 *
 * @param {Array<object>} modules 模块契约
 * @param {Array<string>} orderIds 已保存顺序
 * @returns {Array<object>} 已排序副本
 */
export function sortModules(modules = [], orderIds = []) {
    const positions = new Map(orderIds.map((id, index) => [id, index]));
    return [...modules].sort((left, right) => {
        const leftIndex = positions.get(left?.moduleId);
        const rightIndex = positions.get(right?.moduleId);
        if (leftIndex != null || rightIndex != null) {
            if (leftIndex == null) return 1;
            if (rightIndex == null) return -1;
            if (leftIndex !== rightIndex) return leftIndex - rightIndex;
        }
        return Number(left?.order ?? 0) - Number(right?.order ?? 0)
            || String(left?.moduleId ?? "").localeCompare(String(right?.moduleId ?? ""));
    });
}

/**
 * 校正目录变化后的模块顺序：移除失效 ID，按默认顺序追加新模块。
 *
 * @param {Array<object>} modules 当前模块
 * @param {Array<string>} orderIds 已保存顺序
 * @returns {Array<string>} 新顺序
 */
export function reconcileModuleOrder(modules = [], orderIds = []) {
    const catalogIds = new Set(modules.map((module) => module?.moduleId).filter(Boolean));
    const kept = [...new Set(orderIds)].filter((id) => catalogIds.has(id));
    const defaults = [...modules]
        .sort((left, right) => Number(left?.order ?? 0) - Number(right?.order ?? 0)
            || String(left?.moduleId ?? "").localeCompare(String(right?.moduleId ?? "")))
        .map((module) => module.moduleId)
        .filter((id) => id && !kept.includes(id));
    return [...kept, ...defaults];
}

export function moveModuleOrder(orderIds = [], moduleId, offset) {
    const ids = [...orderIds];
    const from = ids.indexOf(moduleId);
    if (from < 0) return ids;
    const to = Math.max(0, Math.min(ids.length - 1, from + Number(offset || 0)));
    if (to === from) return ids;
    const [moved] = ids.splice(from, 1);
    ids.splice(to, 0, moved);
    return ids;
}

export function reorderBefore(orderIds = [], movingId, targetId) {
    return reorderRelative(orderIds, movingId, targetId, false);
}

export function reorderRelative(orderIds = [], movingId, targetId, after = false) {
    if (!movingId || movingId === targetId) return [...orderIds];
    const ids = orderIds.filter((id) => id !== movingId);
    const targetIndex = ids.indexOf(targetId);
    const insertIndex = targetIndex < 0 ? ids.length : targetIndex + (after ? 1 : 0);
    ids.splice(insertIndex, 0, movingId);
    return ids;
}

/**
 * 按当前可见模块移动到相邻项，同时保留被过滤模块的相对位置。
 *
 * @param {Array<string>} orderIds 完整模块顺序
 * @param {Array<string>} visibleIds 当前可见模块顺序
 * @param {string} moduleId 移动模块
 * @param {number} offset -1 上移，1 下移
 * @returns {Array<string>} 新完整顺序
 */
export function moveVisibleModuleOrder(orderIds = [], visibleIds = [], moduleId, offset) {
    const visible = [...new Set(visibleIds)];
    const currentIndex = visible.indexOf(moduleId);
    if (currentIndex < 0) return [...orderIds];
    const targetIndex = currentIndex + Math.sign(Number(offset) || 0);
    if (targetIndex < 0 || targetIndex >= visible.length) return [...orderIds];
    return reorderRelative(orderIds, moduleId, visible[targetIndex], offset > 0);
}

export function readCount(payload, key = "count") {
    const parts = String(key || "count").split(".").filter(Boolean);
    let value = payload;
    for (const part of parts) {
        if (value == null || typeof value !== "object") return "—";
        value = value[part];
    }
    if (Array.isArray(value)) return value.length;
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value?.length === "number") return value.length;
    return value == null ? "—" : String(value);
}
