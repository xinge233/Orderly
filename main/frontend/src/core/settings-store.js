import { settingDefaults, settingSections } from "../config/settings.js";

const STORAGE_KEY = "orderly.ui.settings.v1";

/**
 * 读取设置配置中的允许字段，避免把任意 localStorage 内容写回页面。
 *
 * @returns {Map<string, object>} key 到字段定义的映射
 */
function createFieldIndex() {
    const fields = new Map();
    for (const section of settingSections) {
        for (const field of section.fields) {
            fields.set(field.key, field);
        }
    }
    return fields;
}

/**
 * 按字段声明值校验单个设置值。
 *
 * 遇到非法值时回退默认值，这样用户手工修改存储或旧版本设置不会破坏界面。
 *
 * @param {object} field 字段定义
 * @param {unknown} value 待校验值
 * @returns {unknown} 合法值
 */
function normalizeValue(field, value) {
    if (field.type === "toggle") {
        return typeof value === "boolean" ? value : Boolean(settingDefaults[field.key]);
    }

    const allowedValues = new Set((field.options ?? []).map((option) => option.value));
    return allowedValues.has(value) ? value : settingDefaults[field.key];
}

/**
 * 浏览器设置存储。
 *
 * 依赖注入 storage 便于在 Node 测试中使用内存实现，也便于未来替换为
 * Java 控制层提供的受控设置存储，而无需改动设置界面的渲染代码。
 */
export class SettingsStore {
    #storage;
    #fieldIndex = createFieldIndex();
    #settings;
    #listeners = new Set();

    /**
     * @param {{storage?: Storage}} options
     */
    constructor({ storage = globalThis.localStorage } = {}) {
        this.#storage = storage;
        this.#settings = this.#read();
    }

    /**
     * 返回当前设置的快照，防止调用方直接修改内部状态。
     *
     * @returns {Readonly<typeof settingDefaults>}
     */
    getSnapshot() {
        return Object.freeze({ ...this.#settings });
    }

    /**
     * 合并一组设置并通知订阅者。
     *
     * @param {Record<string, unknown>} patch 局部设置
     * @returns {Readonly<typeof settingDefaults>} 最新快照
     */
    update(patch) {
        const next = { ...this.#settings };

        for (const [key, value] of Object.entries(patch)) {
            const field = this.#fieldIndex.get(key);
            if (!field) {
                continue;
            }
            next[key] = normalizeValue(field, value);
        }

        this.#settings = next;
        this.#persist();
        const snapshot = this.getSnapshot();
        this.#listeners.forEach((listener) => listener(snapshot));
        return snapshot;
    }

    /**
     * 恢复全部默认设置。
     *
     * @returns {Readonly<typeof settingDefaults>} 默认设置快照
     */
    reset() {
        this.#settings = { ...settingDefaults };
        this.#persist();
        const snapshot = this.getSnapshot();
        this.#listeners.forEach((listener) => listener(snapshot));
        return snapshot;
    }

    /**
     * 订阅设置变化。
     *
     * @param {(settings: Readonly<typeof settingDefaults>) => void} listener 监听器
     * @returns {() => void} 取消订阅函数
     */
    subscribe(listener) {
        this.#listeners.add(listener);
        return () => this.#listeners.delete(listener);
    }

    /**
     * 从存储中读取并按当前 schema 规范化。
     *
     * @returns {typeof settingDefaults} 已校验设置
     */
    #read() {
        let stored = {};
        try {
            stored = JSON.parse(this.#storage?.getItem(STORAGE_KEY) ?? "{}");
        } catch {
            // 存储内容损坏时静默回退，不影响主界面启动。
            stored = {};
        }

        const settings = { ...settingDefaults };
        for (const [key, field] of this.#fieldIndex.entries()) {
            settings[key] = normalizeValue(field, stored?.[key]);
        }
        return settings;
    }

    /** 写入失败（如隐私模式）不阻断当前界面使用。 */
    #persist() {
        try {
            this.#storage?.setItem(STORAGE_KEY, JSON.stringify(this.#settings));
        } catch {
            // 内存中的设置仍然有效，下一次启动时回退默认值。
        }
    }
}
