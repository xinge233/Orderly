/**
 * 模块目录、UI 清单、设置 schema、治理偏好与顺序的前端规范化层。
 *
 * 职责：区分业务模块和系统模块，校验模块自带的视图、资源名、设置字段与特权元数据，并在
 * 目录变化后校正两套宿主顺序和本地总览卡片顺序。
 *
 * 安全边界：资源名校验、视图 ID 去重和 `systemAdmin` 收窄属于前端防御，不能代替宿主对
 * 模块启用状态、接口调用和特权的授权。宿主响应仍是模块目录事实来源，业务条目自称的
 * system 字段不会被信任。
 *
 * 除函数注释另有说明外，本文件函数均为纯函数；需要防误改的契约对象会被冻结，其他结果
 * 使用新数组或浅副本返回，不改变原始 API 响应。
 */
const MODULE_ID = /^[a-z][a-z0-9_-]{0,63}$/;
const VIEW_ID = /^[a-z][a-z0-9-]{0,63}$/;
const ASSET_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const SETTING_KEY = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const MODULE_SOURCE_NAME = /^module_[a-z0-9_]+$/;
const MODULE_BUILD_STATUSES = new Set(["QUEUED", "BUILDING", "READY", "FAILED"]);

export const MODULE_SOURCE = Object.freeze({
    BUSINESS: "business",
    SYSTEM: "system"
});

export const SYSTEM_ADMIN_MODULE_ID = "module_control";

const SETTING_TYPES = new Set(["boolean", "integer", "select", "text"]);

/**
 * 将可展示字符串去除首尾空白并提供兜底。
 *
 * @param {unknown} value 候选值
 * @param {string} [fallback=""] 空值兜底
 * @returns {string} 规范化文本
 *
 * 异常与副作用：无；非字符串值不会通过隐式 `String()` 变成意外内容。
 */
function text(value, fallback = "") {
    return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

/**
 * 校验模块生态内的单文件资源名。
 *
 * @param {unknown} value 清单中的资源名
 * @param {"js" | "css"} extension 期望扩展名
 * @returns {string} 安全的文件名
 * @throws {TypeError} 名称含目录分隔符、非法字符或扩展名不匹配时抛出
 *
 * 副作用：无。只允许单段文件名，从源头阻止 `../`、绝对路径和伪装扩展名进入 UI URL。
 */
function assetName(value, extension) {
    const name = text(value, `module.${extension}`);
    if (!ASSET_NAME.test(name) || !name.toLowerCase().endsWith(`.${extension}`)) {
        throw new TypeError(`模块资源名非法：${name}`);
    }
    return name;
}

/**
 * 判断值是否为可展开的普通对象候选。
 *
 * @param {unknown} value 候选值
 * @returns {object | null} 对象本身或 null
 *
 * 异常与副作用：无；数组和 null 被视为不合法。
 */
function plainObject(value) {
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
}

/**
 * 去除字符串数组中的非字符串、空值和重复项。
 *
 * @param {unknown} values 候选数组
 * @returns {string[]} 去重且已 trim 的数组
 *
 * 异常与副作用：无；输入不是数组时返回空数组。
 */
function uniqueStrings(values) {
    return [...new Set((Array.isArray(values) ? values : [])
        .filter((value) => typeof value === "string" && value.trim())
        .map((value) => value.trim()))];
}

/**
 * 从直接数组或常见 API 包裹对象中提取列表。
 *
 * @param {unknown} payload API 响应
 * @param {string[]} keys 调用方指定的优先字段
 * @returns {Array<unknown>} 列表
 *
 * 异常与副作用：无。只检查顶层字段，不递归猜测数据，避免把嵌套内容误识别为模块目录。
 */
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

/**
 * 校验并冻结 select 设置选项。
 *
 * @param {unknown} input options 数组
 * @returns {ReadonlyArray<{value: string, label: string}>} 规范化选项
 * @throws {TypeError} options 为空、字段缺失或 value 重复时抛出
 *
 * 副作用：无。value 保持原样以匹配持久化值，label 去除首尾空白后用于展示。
 */
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

/**
 * 读取可选整数约束。
 *
 * @param {object} source 设置字段
 * @param {string} key 约束字段名
 * @param {string} label 错误消息中的字段描述
 * @returns {number | undefined} 整数或 undefined
 * @throws {TypeError} 字段存在但不是整数时抛出
 *
 * 异常与副作用：参数非法时抛出 `TypeError`，不修改 source。
 */
function optionalInteger(source, key, label) {
    if (source[key] === undefined) {
        return undefined;
    }
    if (!Number.isInteger(source[key])) {
        throw new TypeError(`${label}的 ${key} 必须是整数`);
    }
    return source[key];
}

/**
 * 拒绝设置字段中的未声明属性。
 *
 * @param {object} source 原始字段
 * @param {string[]} allowed 当前类型允许的键
 * @param {string} key 设置键
 * @returns {void}
 * @throws {TypeError} 发现未知字段时抛出
 *
 * 副作用：无。严格白名单让 schema 演进必须显式完成，避免未知属性被静默带入后续逻辑。
 */
function assertKnownFieldKeys(source, allowed, key) {
    const unknown = Object.keys(source).find((name) => !allowed.includes(name));
    if (unknown) {
        throw new TypeError(`模块设置 ${key} 包含未知字段：${unknown}`);
    }
}

/**
 * 根据设置类型校验并冻结单个字段定义。
 *
 * @param {unknown} input 原始字段
 * @returns {Readonly<object>} 规范化字段
 * @throws {TypeError} 键、类型、默认值、范围或选项不合法时抛出
 *
 * 不变量：boolean 的 default 必须是布尔值；integer 的 default 必须在可选 min/max 内；
 * select 的 default 必须属于非空且唯一的 options；text 的默认长度必须满足长度约束。
 * 副作用：无，除冻结新建对象外不修改输入。
 */
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
 * @throws {TypeError} 顶层结构、字段定义或键唯一性不合法时抛出
 *
 * `null`/`undefined` 表示模块未声明设置；一旦提供设置结构，就必须完整通过字段白名单校验。
 * 副作用：无。
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
 * @throws {TypeError} 模块 ID、UI 版本、视图 ID、资源名或设置 schema 不合法时抛出
 *
 * 调用方按模块捕获异常，使单个坏清单只隔离当前模块。清单中的默认文案只用于展示，不能
 * 用于推断模块能力；`script` 和 `style` 被收窄为单文件资源名。副作用：无。
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
 *
 * 系统模块先处理，因此视图 ID 冲突时保留系统入口，业务条目只记录局部错误。缺少清单、
 * 加载失败或占用框架保留视图的模块不会进入 `moduleUi`。副作用：无。
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
 * @throws {TypeError} 条目不是对象或模块 ID 不合法时抛出
 *
 * `source` 由调用方根据请求端点决定，而不是读取条目自称的 system 标记。副作用：无。
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
 *
 * 每个目录内先出现者保留；随后从业务目录剔除所有系统 ID，确保重复 ID 始终以系统目录为准。
 * 单条非法模块被丢弃，不阻断其余目录。副作用：无。
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
 *
 * `context` 与 `settings` 做成浅副本；只有满足固定管理员条件时才由本函数主动注入
 * `systemAdmin`。由于 `context` 是展开复制的，调用方必须保证传入的 context 自身可信，
 * 不能把任意模块数据混入其中。副作用：无，且 systemAdmin 对象本身不会深拷贝。
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
 *
 * 仅接受合法模块 ID 对应的普通设置对象；字段值保留原样，交由声明 schema 的消费方理解。
 * 副作用：无。
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
 * 规范化后台模块构建快照。
 *
 * @param {unknown} input `/api/system/module-builds` 响应
 * @returns {{mode: string, updatedAt: string, complete: boolean, modules: ReadonlyArray<object>}}
 *
 * 构建状态只用于框架导航与等待提示；条目被限制为已发现源码名和四种状态，不能借此
 * 注入模块契约、动作或任意视图 ID。副作用：无。
 */
export function normalizeModuleBuildSnapshot(input) {
    const source = plainObject(input) ?? {};
    const modules = (Array.isArray(source.modules) ? source.modules : [])
        .flatMap((item) => {
            const entry = plainObject(item);
            const sourceName = text(entry?.sourceName);
            const status = text(entry?.status).toUpperCase();
            if (!MODULE_SOURCE_NAME.test(sourceName)
                || sourceName === "module_control"
                || !MODULE_BUILD_STATUSES.has(status)) {
                return [];
            }
            return [Object.freeze({
                sourceName,
                moduleId: sourceName,
                title: text(entry.title, sourceName),
                caption: text(entry.caption, "模块正在准备"),
                icon: text(entry.icon, "modules"),
                status,
                message: text(entry.message),
                priority: Number.isInteger(Number(entry.priority))
                    ? Math.max(0, Number(entry.priority))
                    : 0,
                startedAt: text(entry.startedAt),
                finishedAt: text(entry.finishedAt)
            })];
        });
    return Object.freeze({
        mode: source.mode === "background" ? "background" : "eager",
        updatedAt: text(source.updatedAt),
        complete: source.complete === true,
        modules: Object.freeze(modules)
    });
}

/**
 * 返回尚未进入已加载目录的构建条目。
 *
 * @param {unknown} snapshot 构建快照
 * @param {Array<object>} loadedModules 已加载模块目录
 * @returns {Array<object>} 待构建条目
 *
 * READY 条目通常会在下一次目录重载后消失；在目录尚未刷新时仍保留为等待态，避免
 * 用户点击后短暂回到不存在的旧视图。副作用：无。
 */
export function pendingModuleBuilds(snapshot, loadedModules = []) {
    const loadedIds = new Set(
        (Array.isArray(loadedModules) ? loadedModules : [])
            .map((module) => module?.moduleId)
            .filter(Boolean)
    );
    const normalized = normalizeModuleBuildSnapshot(snapshot);
    return normalized.modules.filter((entry) => !loadedIds.has(entry.moduleId));
}

/**
 * 返回构建状态的用户可见文案。
 *
 * @param {string} status 构建状态
 * @returns {string} 文案
 */
export function moduleBuildStatusLabel(status) {
    return {
        QUEUED: "等待构建",
        BUILDING: "正在构建",
        READY: "正在启用",
        FAILED: "构建失败"
    }[String(status ?? "").toUpperCase()] ?? "状态未知";
}

/**
 * 目录变化后保留有效顺序，并把新模块按契约 order 追加。
 *
 * @param {Array<object>} modules 全部模块
 * @param {unknown} input 宿主偏好
 * @returns {{sidebarOrder: Array<string>, managementOrder: Array<string>, moduleSettings: object}} 偏好
 *
 * 两套顺序都会移除失效 ID 并追加新模块；模块设置只保留仍存在的业务模块，系统模块设置
 * 即使出现在响应中也会被剔除。副作用：无。
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
 *
 * 已保存 ID 优先；未保存模块再按契约 `order` 和模块 ID 稳定追加，避免目录响应顺序泄漏到
 * 界面。副作用：无，不修改输入数组。
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
 *
 * 保留有效旧顺序、去重并移除失效 ID，再按契约顺序追加新模块。副作用：无。
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

/**
 * 将模块在完整顺序中移动指定偏移。
 *
 * @param {Array<string>} [orderIds=[]] 完整模块顺序
 * @param {string} moduleId 待移动模块
 * @param {number} offset 负值上移、正值下移
 * @returns {Array<string>} 新顺序
 *
 * 目标位置被夹紧到数组边界；模块不存在或位置未变时仍返回新数组，便于调用方统一持久化。
 * 异常与副作用：无。
 */
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

/**
 * 将模块移动到目标模块之前。
 *
 * @param {Array<string>} [orderIds=[]] 完整模块顺序
 * @param {string} movingId 待移动模块
 * @param {string} targetId 目标模块
 * @returns {Array<string>} 新顺序
 *
 * 异常与副作用：无；移动 ID 为空或与目标相同时返回副本，目标不存在时追加到末尾。此函数
 * 不校验 movingId 是否原本就在数组中，界面调用方应先用当前目录顺序约束输入。
 */
export function reorderBefore(orderIds = [], movingId, targetId) {
    return reorderRelative(orderIds, movingId, targetId, false);
}

/**
 * 将模块移动到目标模块之前或之后。
 *
 * @param {Array<string>} [orderIds=[]] 完整模块顺序
 * @param {string} movingId 待移动模块
 * @param {string} targetId 目标模块
 * @param {boolean} [after=false] 是否插入目标之后
 * @returns {Array<string>} 新顺序
 *
 * 先移除待移动 ID 再定位目标，避免原位置影响插入下标；目标缺失时按追加处理。
 * 异常与副作用：无。
 */
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
 *
 * 用当前可见序列确定相邻目标，再把移动映射回完整顺序，因此隐藏模块会随相邻项保持相对
 * 位置。异常与副作用：无。
 */
export function moveVisibleModuleOrder(orderIds = [], visibleIds = [], moduleId, offset) {
    const visible = [...new Set(visibleIds)];
    const currentIndex = visible.indexOf(moduleId);
    if (currentIndex < 0) return [...orderIds];
    const targetIndex = currentIndex + Math.sign(Number(offset) || 0);
    if (targetIndex < 0 || targetIndex >= visible.length) return [...orderIds];
    return reorderRelative(orderIds, moduleId, visible[targetIndex], offset > 0);
}

/**
 * 从查询结果中读取卡片计数。
 *
 * @param {unknown} payload 查询结果
 * @param {string} [key="count"] 支持点号分隔的嵌套键
 * @returns {number | string} 数组/字符串长度、有限数字、原始展示值或破折号
 *
 * 路径中断时返回破折号，不让单个模块的数据形状破坏总览渲染。异常与副作用：无。
 */
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
