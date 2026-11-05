import { moduleViewBindings } from "../config/module-bindings.js";

/** 前端支持的公共模块目录契约版本。 */
export const SUPPORTED_MODULE_CATALOG_VERSION = "1.0";

const RISK_LEVELS = new Set(["READ_ONLY", "WRITE", "DESTRUCTIVE"]);
const PARAMETER_TYPES = new Set(["STRING", "INTEGER", "BOOLEAN"]);
const WORKSPACE_ACCESS = new Set(["NONE", "READ", "WRITE"]);

/**
 * 要求输入为非空字符串。
 *
 * @param {unknown} value 输入值
 * @param {string} label 字段说明
 * @returns {string} 规范化文本
 */
function readText(value, label) {
    if (typeof value !== "string" || value.trim() === "") {
        throw new TypeError(`${label}必须是非空字符串`);
    }
    return value.trim();
}

/**
 * 要求输入为对象。
 *
 * @param {unknown} value 输入值
 * @param {string} label 字段说明
 * @returns {Record<string, unknown>}
 */
function readObject(value, label) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
        throw new TypeError(`${label}必须是对象`);
    }
    return value;
}

/**
 * 要求输入为数组。
 *
 * @param {unknown} value 输入值
 * @param {string} label 字段说明
 * @returns {Array<unknown>}
 */
function readArray(value, label) {
    if (!Array.isArray(value)) {
        throw new TypeError(`${label}必须是数组`);
    }
    return value;
}

/**
 * 校验并读取参数 schema。
 *
 * @param {unknown} value 原始 schema
 * @returns {Readonly<object>} 已校验 schema
 */
function parseParameterSchema(value) {
    const schema = readObject(value, "parameterSchema");
    if (schema.type !== "object" || schema.additionalProperties !== false) {
        throw new TypeError("parameterSchema 必须禁止额外属性");
    }

    const properties = readArray(schema.properties, "parameterSchema.properties").map(
        (propertyValue) => {
            const property = readObject(propertyValue, "参数属性");
            const type = readText(property.type, "参数类型");
            if (!PARAMETER_TYPES.has(type)) {
                throw new TypeError(`未知参数类型：${type}`);
            }
            if (typeof property.required !== "boolean") {
                throw new TypeError("参数 required 必须是布尔值");
            }
            return Object.freeze({
                name: readText(property.name, "参数名"),
                description: String(property.description ?? ""),
                type,
                required: property.required
            });
        }
    );

    const propertyNames = properties.map((property) => property.name);
    if (new Set(propertyNames).size !== propertyNames.length) {
        throw new TypeError("参数 schema 存在重复参数名");
    }

    const required = readArray(schema.required, "parameterSchema.required").map(
        (name) => readText(name, "必填参数名")
    );
    const expectedRequired = properties
        .filter((property) => property.required)
        .map((property) => property.name);
    if (
        new Set(required).size !== required.length
        || required.length !== expectedRequired.length
        || required.some((name) => !expectedRequired.includes(name))
    ) {
        throw new TypeError("parameterSchema.required 与参数 required 标记不一致");
    }

    return Object.freeze({
        type: "object",
        properties: Object.freeze(properties),
        required: Object.freeze(required),
        additionalProperties: false
    });
}

/**
 * 校验并读取单个动作。
 *
 * @param {unknown} value 原始动作
 * @returns {Readonly<object>} 已校验动作
 */
function parseAction(value) {
    const action = readObject(value, "动作");
    const riskLevel = readText(action.riskLevel, "风险等级");
    const workspaceAccess = readText(action.workspaceAccess, "工作区访问范围");
    if (!RISK_LEVELS.has(riskLevel)) {
        throw new TypeError(`未知风险等级：${riskLevel}`);
    }
    if (!WORKSPACE_ACCESS.has(workspaceAccess)) {
        throw new TypeError(`未知工作区访问范围：${workspaceAccess}`);
    }
    if (
        riskLevel !== "READ_ONLY"
        && action.confirmationRequired !== true
    ) {
        throw new TypeError("写入或破坏性动作必须声明 confirmationRequired=true");
    }

    return Object.freeze({
        id: readText(action.id, "动作 id"),
        displayName: readText(action.displayName, "动作显示名称"),
        description: String(action.description ?? ""),
        parameterSchema: parseParameterSchema(action.parameterSchema),
        riskLevel,
        confirmationRequired: action.confirmationRequired === true,
        idempotent: action.idempotent === true,
        rollbackSupported: action.rollbackSupported === true,
        workspaceAccess
    });
}

/**
 * 校验固定的模块目录 JSON。
 *
 * 该解析器不执行动态代码，只接受明确字段和枚举值，供前端 Mock 与测试使用。
 *
 * @param {unknown} payload 后端目录响应或固定样例
 * @returns {Readonly<object>} 规范化模块目录
 */
export function parseModuleCatalogPayload(payload) {
    const catalog = readObject(payload, "模块目录");
    if (catalog.contractVersion !== SUPPORTED_MODULE_CATALOG_VERSION) {
        throw new TypeError(`不支持的模块目录契约版本：${catalog.contractVersion}`);
    }

    const modules = readArray(catalog.modules, "modules").map((moduleValue) => {
        const module = readObject(moduleValue, "模块");
        if (!Number.isInteger(module.order)) {
            throw new TypeError("模块 order 必须是整数");
        }

        const actions = readArray(module.actions, "模块动作").map(parseAction);
        const actionIds = actions.map((action) => action.id);
        if (new Set(actionIds).size !== actionIds.length) {
            throw new TypeError("同一模块不能声明重复动作 id");
        }

        return Object.freeze({
            id: readText(module.id, "模块 id"),
            displayName: readText(module.displayName, "模块显示名称"),
            description: String(module.description ?? ""),
            order: module.order,
            actions: Object.freeze(actions)
        });
    });

    const moduleIds = modules.map((module) => module.id);
    if (new Set(moduleIds).size !== moduleIds.length) {
        throw new TypeError("模块目录存在重复模块 id");
    }

    return Object.freeze({
        contractVersion: SUPPORTED_MODULE_CATALOG_VERSION,
        mode: readText(catalog.mode, "运行模式"),
        modules: Object.freeze(modules)
    });
}

/**
 * 将模块目录转换为前端导航项。
 *
 * 每个模块都必须有显式视图绑定；缺少绑定会抛错，不会静默使用占位视图。
 *
 * @param {unknown} payload 模块目录
 * @param {Record<string, object>} bindings 模块到视图的绑定
 * @returns {Array<Readonly<object>>} 导航项
 */
export function moduleCatalogToNavigation(payload, bindings = moduleViewBindings) {
    const catalog = parseModuleCatalogPayload(payload);
    return catalog.modules.map((module) => {
        const binding = bindings[module.id];
        if (!binding) {
            throw new TypeError(`模块 ${module.id} 缺少前端视图绑定`);
        }
        return Object.freeze({
            id: binding.navigationId,
            moduleId: module.id,
            label: binding.label ?? module.displayName,
            caption: binding.caption ?? module.description,
            icon: binding.icon,
            view: binding.view
        });
    });
}
