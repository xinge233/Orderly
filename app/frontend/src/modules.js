const MODULE_ID = /^[a-z][a-z0-9_-]{0,63}$/;
const VIEW_ID = /^[a-z][a-z0-9-]{0,63}$/;
const ASSET_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const SETTING_KEY = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;

export const MODULE_SOURCE = Object.freeze({
    BUSINESS: "business",
    SYSTEM: "system"
});

export const SYSTEM_ADMIN_MODULE_ID = "module_control";

const SETTING_TYPES = new Set(["boolean", "integer", "select", "text"]);

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

function plainObject(value) {
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

function uniqueStrings(values) {
    return [...new Set((Array.isArray(values) ? values : [])
        .filter((value) => typeof value === "string" && value.trim())
        .map((value) => value.trim()))];
}

function payloadItems(payload, keys) {
    if (Array.isArray(payload)) {
        return payload;
    }
    const source = plainObject(payload);
    if (!source) {
        return [];
    }
    for (const key of [...keys, "items", "modules"]) {
        if (Array.isArray(source[key])) {
            return source[key];
        }
    }
    return [];
}

function normalizeSettingOptions(input) {
    if (!Array.isArray(input) || input.length === 0) {
        throw new TypeError("select 设置 options 必须是非空数组");
    }
    const seen = new Set();
    return Object.freeze(input.map((item) => {
        const source = plainObject(item);
        const value = source?.value;
        const label = source?.label;
        if (typeof value !== "string" || !value.trim()
            || typeof label !== "string" || !label.trim()) {
            throw new TypeError("select 设置 option 必须包含非空 value 与 label");
        }
        if (seen.has(value)) {
            throw new TypeError(`select 设置 option value 重复：${value}`);
        }
        seen.add(value);
        return Object.freeze({ value, label: label.trim() });
    }));
}

function optionalInteger(source, key, label) {
    if (source[key] === undefined) {
        return undefined;
    }
    if (!Number.isInteger(source[key])) {
        throw new TypeError(`${label}的 ${key} 必须是整数`);
    }
    return source[key];
}

function assertKnownFieldKeys(source, allowed, key) {
    const unknown = Object.keys(source).find((name) => !allowed.includes(name));
    if (unknown) {
        throw new TypeError(`模块设置 ${key} 包含未知字段：${unknown}`);
    }
}

function normalizeSettingField(input) {
    const source = plainObject(input) ?? {};
    const key = text(source.key);
    if (!SETTING_KEY.test(key)) {
        throw new TypeError(`模块设置键非法：${key || "(empty)"}`);
    }
    const type = text(source.type);
    if (!SETTING_TYPES.has(type)) {
        throw new TypeError(`模块 ${key} 的设置类型不受支持：${type || "(empty)"}`);
    }
    const label = text(source.label ?? source.title);
    if (!label) {
        throw new TypeError(`模块设置 ${key} 缺少 label`);
    }
    if (!Object.hasOwn(source, "default")) {
        throw new TypeError(`模块设置 ${key} 缺少 default`);
    }
    const common = {
        key,
        type,
        label,
        description: text(source.description),
        default: source.default
    };
    if (type === "boolean") {
        assertKnownFieldKeys(
            source,
            ["key", "type", "label", "title", "description", "default"],
            key
        );
        if (typeof source.default !== "boolean") {
            throw new TypeError(`boolean 设置默认值类型错误：${key}`);
        }
        return Object.freeze(common);
    }
    if (type === "integer") {
        assertKnownFieldKeys(
            source,
            ["key", "type", "label", "title", "description", "default", "min", "max"],
            key
        );
        if (!Number.isInteger(source.default)) {
            throw new TypeError(`integer 设置默认值类型错误：${key}`);
        }
        const min = optionalInteger(source, "min", `模块设置 ${key}`);
        const max = optionalInteger(source, "max", `模块设置 ${key}`);
        if (min !== undefined && max !== undefined && min > max) {
            throw new TypeError(`integer 设置 min 不能大于 max：${key}`);
        }
        if ((min !== undefined && source.default < min)
            || (max !== undefined && source.default > max)) {
            throw new TypeError(`integer 设置默认值超出范围：${key}`);
        }
        return Object.freeze({
            ...common,
            ...(min === undefined ? {} : { min }),
            ...(max === undefined ? {} : { max })
        });
    }
    if (type === "select") {
        assertKnownFieldKeys(
            source,
            ["key", "type", "label", "title", "description", "default", "options"],
            key
        );
        const options = normalizeSettingOptions(source.options);
        if (typeof source.default !== "string" || !source.default
            || !options.some((option) => option.value === source.default)) {
            throw new TypeError(`select 设置默认值必须存在于 options：${key}`);
        }
        return Object.freeze({ ...common, options });
    }
    assertKnownFieldKeys(
        source,
        [
            "key",
            "type",
            "label",
            "title",
            "description",
            "default",
            "placeholder",
            "minLength",
            "maxLength"
        ],
        key
    );
    if (typeof source.default !== "string") {
        throw new TypeError(`text 设置默认值类型错误：${key}`);
    }
    const minLength = optionalInteger(source, "minLength", `模块设置 ${key}`);
    const maxLength = optionalInteger(source, "maxLength", `模块设置 ${key}`);
    if (minLength !== undefined && minLength < 0) {
        throw new TypeError(`text 设置 minLength 不能小于 0：${key}`);
    }
    if (maxLength !== undefined && maxLength < 1) {
        throw new TypeError(`text 设置 maxLength 必须大于 0：${key}`);
    }
    if (minLength !== undefined && maxLength !== undefined && minLength > maxLength) {
        throw new TypeError(`text 设置 minLength 不能大于 maxLength：${key}`);
    }
    const length = source.default.length;
    if ((minLength !== undefined && length < minLength)
        || (maxLength !== undefined && length > maxLength)) {
        throw new TypeError(`text 设置默认值长度超出范围：${key}`);
    }
    return Object.freeze({
        ...common,
        description: text(source.description),
        placeholder: text(source.placeholder),
        ...(minLength === undefined ? {} : { minLength }),
        ...(maxLength === undefined ? {} : { maxLength })
    });
}

/**
 * 校验模块 UI 清单声明的设置结构。
 *
 * @param {unknown} input 合约规定的 {fields}，兼容当前模块使用的字段数组
 * @returns {{fields: ReadonlyArray<object>}} 规范化结构
 */
export function normalizeModuleSettingSchema(input) {
    if (input == null) {
        return Object.freeze({ fields: Object.freeze([]) });
    }
    const source = plainObject(input);
    if (source && Object.keys(source).some((key) => key !== "fields")) {
        throw new TypeError("模块 settings 只能包含 fields");
    }
    const fieldSource = Array.isArray(input)
        ? input
        : Array.isArray(source?.fields)
            ? source.fields
            : null;
    if (!fieldSource) {
        throw new TypeError("模块 settings 必须是字段数组或包含 fields 的对象");
    }
    const keys = new Set();
    const fields = fieldSource.map((field) => {
        const normalized = normalizeSettingField(field);
        if (keys.has(normalized.key)) {
            throw new TypeError(`模块设置键重复：${normalized.key}`);
        }
        keys.add(normalized.key);
        return normalized;
    });
    return Object.freeze({ fields: Object.freeze(fields) });
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
        settings: normalizeModuleSettingSchema(source.settings),
        script: assetName(source.script, "js"),
        style: assetName(source.style, "css")
    });
}

/**
 * 汇总模块 UI 并隔离视图 ID 冲突。系统模块优先保留管理入口。
 *
 * @param {Array<object>} modules 模块目录
 * @param {Array<{moduleId: string, ui?: object, error?: string}>} loaded 加载结果
 * @returns {{moduleUi: object, moduleErrors: object}} UI 状态
 */
export function resolveModuleUiCatalog(modules = [], loaded = []) {
    const entries = new Map(loaded.map((entry) => [entry?.moduleId, entry]));
    const moduleUi = {};
    const moduleErrors = {};
    const viewOwners = new Map();
    const ordered = [...modules].sort((left, right) =>
        Number(isBusinessModule(left)) - Number(isBusinessModule(right)));
    ordered.forEach((module) => {
        const moduleId = module.moduleId;
        const entry = entries.get(moduleId);
        if (!entry) {
            moduleErrors[moduleId] = "模块 UI 清单尚未加载";
            return;
        }
        if (entry.error) {
            moduleErrors[moduleId] = entry.error;
            return;
        }
        const ui = entry.ui;
        if (["overview", "history"].includes(ui?.view?.id)) {
            moduleErrors[moduleId] = `模块视图 ID 与框架视图冲突：${ui.view.id}`;
            return;
        }
        const owner = viewOwners.get(ui?.view?.id);
        if (owner) {
            moduleErrors[moduleId] = `模块视图 ID 与 ${owner} 冲突：${ui.view.id}`;
            return;
        }
        if (!ui?.view?.id) {
            moduleErrors[moduleId] = "模块 UI 清单缺少视图 ID";
            return;
        }
        viewOwners.set(ui.view.id, moduleId);
        moduleUi[moduleId] = ui;
    });
    return { moduleUi, moduleErrors };
}

/**
 * 规范化单个模块目录项并标记来源。来源不信任业务响应自称的 system 字段。
 *
 * @param {unknown} input 模块条目
 * @param {string} source 目录来源
 * @returns {object} 模块条目
 */
export function normalizeModuleEntry(input, source = MODULE_SOURCE.BUSINESS) {
    const item = plainObject(input);
    const moduleId = text(item?.moduleId ?? item?.id);
    if (!item || !MODULE_ID.test(moduleId)) {
        throw new TypeError("模块目录包含非法模块 ID");
    }
    const normalizedSource = source === MODULE_SOURCE.SYSTEM
        ? MODULE_SOURCE.SYSTEM
        : MODULE_SOURCE.BUSINESS;
    const system = normalizedSource === MODULE_SOURCE.SYSTEM
        ? plainObject(item.system) ?? plainObject(item.systemMetadata)
        : null;
    return {
        ...item,
        moduleId,
        title: text(item.title, moduleId),
        description: text(item.description),
        order: Number.isFinite(Number(item.order)) ? Number(item.order) : 0,
        enabled: item.enabled !== false,
        source: normalizedSource,
        system
    };
}

/**
 * 分拣业务和系统目录。重复 ID 始终以系统目录为准，防止业务条目冒充系统模块。
 *
 * @param {unknown} businessPayload /api/modules 响应
 * @param {unknown} systemPayload /api/system-modules 响应
 * @returns {{modules: Array<object>, businessModules: Array<object>, systemModules: Array<object>}} 目录
 */
export function normalizeModuleCatalogs(businessPayload, systemPayload) {
    const normalizeAll = (items, source) => {
        const seen = new Set();
        return items.flatMap((item) => {
            try {
                const module = normalizeModuleEntry(item, source);
                if (seen.has(module.moduleId)) {
                    return [];
                }
                seen.add(module.moduleId);
                return [module];
            } catch {
                return [];
            }
        });
    };
    const systemModules = normalizeAll(
        payloadItems(systemPayload, ["systemModules", "system-modules"]),
        MODULE_SOURCE.SYSTEM
    );
    const systemIds = new Set(systemModules.map((module) => module.moduleId));
    const businessModules = normalizeAll(
        payloadItems(businessPayload, ["modules", "businessModules"]),
        MODULE_SOURCE.BUSINESS
    ).filter((module) => !systemIds.has(module.moduleId));
    return {
        modules: [...businessModules, ...systemModules],
        businessModules,
        systemModules
    };
}

/**
 * 判断目录项是否为业务模块。
 *
 * @param {object} module 模块
 * @returns {boolean} 是否业务模块
 */
export function isBusinessModule(module) {
    return module?.source === MODULE_SOURCE.BUSINESS;
}

/**
 * 判断目录项是否为系统模块。
 *
 * @param {object} module 模块
 * @returns {boolean} 是否系统模块
 */
export function isSystemModule(module) {
    return module?.source === MODULE_SOURCE.SYSTEM;
}

/**
 * 系统模块始终可用；业务模块遵循宿主 enabled 状态。
 *
 * @param {object} module 模块
 * @returns {boolean} 是否可见
 */
export function isModuleVisible(module) {
    return isSystemModule(module) || module?.enabled !== false;
}

/**
 * 仅固定管理员模块可取得 systemAdmin 特权。
 *
 * @param {object} module 模块
 * @returns {boolean} 是否可注入特权
 */
export function hasSystemAdminPrivilege(module) {
    const system = plainObject(module?.system);
    return isSystemModule(module)
        && module.moduleId === SYSTEM_ADMIN_MODULE_ID
        && system?.role === "MODULE_CONTROL"
        && system?.protected === true;
}

/**
 * 构造模块 mount context，特权不会泄漏给普通业务模块。
 *
 * @param {{module: object, context?: object, settings?: object, systemAdmin?: object}} input 输入
 * @returns {object} mount context
 */
export function createModuleMountContext({
    module,
    context = {},
    settings = {},
    systemAdmin = null
} = {}) {
    const result = {
        ...context,
        settings: { ...(plainObject(settings) ?? {}) }
    };
    if (hasSystemAdminPrivilege(module) && systemAdmin && typeof systemAdmin === "object") {
        result.systemAdmin = systemAdmin;
    }
    return result;
}

/**
 * 规范化宿主模块偏好。
 *
 * @param {unknown} input 响应
 * @returns {{sidebarOrder: Array<string>, managementOrder: Array<string>, moduleSettings: object}} 偏好
 */
export function normalizeModulePreferences(input) {
    const source = plainObject(input) ?? {};
    const moduleSettings = {};
    const settingsSource = plainObject(source.moduleSettings) ?? {};
    Object.entries(settingsSource).forEach(([moduleId, values]) => {
        if (MODULE_ID.test(moduleId) && plainObject(values)) {
            moduleSettings[moduleId] = { ...values };
        }
    });
    return {
        sidebarOrder: uniqueStrings(source.sidebarOrder),
        managementOrder: uniqueStrings(source.managementOrder),
        moduleSettings
    };
}

/**
 * 目录变化后保留有效顺序，并把新模块按契约 order 追加。
 *
 * @param {Array<object>} modules 全部模块
 * @param {unknown} input 宿主偏好
 * @returns {{sidebarOrder: Array<string>, managementOrder: Array<string>, moduleSettings: object}} 偏好
 */
export function reconcileModulePreferences(modules = [], input = {}) {
    const preferences = normalizeModulePreferences(input);
    const businessModules = modules.filter(isBusinessModule);
    return {
        sidebarOrder: reconcileModuleOrder(modules, preferences.sidebarOrder),
        managementOrder: reconcileModuleOrder(
            modules,
            preferences.managementOrder
        ),
        moduleSettings: Object.fromEntries(
            Object.entries(preferences.moduleSettings)
                .filter(([moduleId]) => businessModules.some((module) => module.moduleId === moduleId))
        )
    };
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
