export const THEME_OPTIONS = Object.freeze([
    { value: "paper", label: "纸页", note: "温暖明亮" },
    { value: "night", label: "夜航", note: "沉稳深色" },
    { value: "moss", label: "苔原", note: "自然低饱和" }
]);

export const MOTION_OPTIONS = Object.freeze([
    { value: "gentle", label: "灵动" },
    { value: "minimal", label: "克制" },
    { value: "none", label: "关闭" }
]);

const DEFAULTS = Object.freeze({
    theme: "paper",
    motion: "gentle",
    notifications: true,
    showFileModule: true,
    showTodoModule: true,
    showCalendarModule: true
});

const THEMES = new Set(THEME_OPTIONS.map((item) => item.value));
const MOTIONS = new Set(MOTION_OPTIONS.map((item) => item.value));

/**
 * 清洗可能损坏或过期的本地设置。
 *
 * @param {unknown} input 原始值
 * @returns {object} 合法设置
 */
export function normalizeSettings(input) {
    const source = input && typeof input === "object" ? input : {};
    return {
        theme: THEMES.has(source.theme) ? source.theme : DEFAULTS.theme,
        motion: MOTIONS.has(source.motion) ? source.motion : DEFAULTS.motion,
        notifications: typeof source.notifications === "boolean"
            ? source.notifications
            : DEFAULTS.notifications,
        showFileModule: typeof source.showFileModule === "boolean"
            ? source.showFileModule
            : DEFAULTS.showFileModule,
        showTodoModule: typeof source.showTodoModule === "boolean"
            ? source.showTodoModule
            : DEFAULTS.showTodoModule,
        showCalendarModule: typeof source.showCalendarModule === "boolean"
            ? source.showCalendarModule
            : DEFAULTS.showCalendarModule
    };
}

export class SettingsStore {
    #storage;
    #storageKey;
    #listeners = new Set();
    #value;

    /**
     * @param {{storage?: Storage, storageKey?: string}} options 配置
     */
    constructor({ storage = globalThis.localStorage, storageKey = "orderly.settings.v1" } = {}) {
        this.#storage = storage;
        this.#storageKey = storageKey;
        this.#value = this.#read();
    }

    getSnapshot() {
        return Object.freeze({ ...this.#value });
    }

    /**
     * 更新设置并持久化。
     *
     * @param {object} patch 局部设置
     * @returns {object} 新快照
     */
    update(patch) {
        this.#value = normalizeSettings({ ...this.#value, ...patch });
        try {
            this.#storage?.setItem(this.#storageKey, JSON.stringify(this.#value));
        } catch {
            // 存储不可用时仍保留当前会话设置。
        }
        const snapshot = this.getSnapshot();
        this.#listeners.forEach((listener) => listener(snapshot));
        return snapshot;
    }

    reset() {
        this.#value = { ...DEFAULTS };
        try {
            this.#storage?.removeItem(this.#storageKey);
        } catch {
            // 忽略受限浏览器的存储异常。
        }
        const snapshot = this.getSnapshot();
        this.#listeners.forEach((listener) => listener(snapshot));
        return snapshot;
    }

    subscribe(listener) {
        this.#listeners.add(listener);
        return () => this.#listeners.delete(listener);
    }

    #read() {
        try {
            const raw = this.#storage?.getItem(this.#storageKey);
            return normalizeSettings(raw ? JSON.parse(raw) : DEFAULTS);
        } catch {
            return { ...DEFAULTS };
        }
    }
}

export class ThemeManager {
    #document;
    #fetch;
    #baseUrl;
    #cache = new Map();
    #current = null;

    /**
     * @param {{documentRef?: Document, fetchImpl?: typeof fetch, baseUrl?: string}} options 配置
     */
    constructor({
        documentRef = globalThis.document,
        fetchImpl = globalThis.fetch?.bind(globalThis),
        baseUrl = "../themes"
    } = {}) {
        this.#document = documentRef;
        this.#fetch = fetchImpl;
        this.#baseUrl = String(baseUrl).replace(/\/$/, "");
    }

    /**
     * 加载主题令牌并应用到根元素。
     *
     * @param {string} name 主题 id
     * @returns {Promise<object | null>} 主题定义
     */
    async apply(name) {
        const safeName = THEMES.has(name) ? name : DEFAULTS.theme;
        let theme = this.#cache.get(safeName);
        if (!theme) {
            try {
                const fileName = safeName === "paper" ? "default" : safeName;
                const response = await this.#fetch(`${this.#baseUrl}/${fileName}.json`);
                if (!response.ok) {
                    throw new Error(`主题加载失败：${response.status}`);
                }
                theme = await response.json();
                this.#cache.set(safeName, theme);
            } catch {
                return this.#current;
            }
        }
        const root = this.#document?.documentElement;
        if (!root) {
            return theme;
        }
        Object.entries(theme?.tokens ?? {}).forEach(([key, value]) => {
            if (key.startsWith("--") && typeof value === "string") {
                root.style.setProperty(key, value);
            }
        });
        root.dataset.theme = safeName;
        const meta = this.#document.querySelector?.('meta[name="color-scheme"]');
        if (meta && theme?.colorScheme) {
            meta.content = theme.colorScheme;
        }
        const browserMeta = this.#document.querySelector?.('meta[id="theme-color-meta"]');
        if (browserMeta && theme?.browserColor) {
            browserMeta.content = theme.browserColor;
        }
        this.#current = theme;
        return theme;
    }
}
