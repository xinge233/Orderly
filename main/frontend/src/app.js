import { getDefaultNavigationItem } from "./config/navigation.js";
import { ChatDock } from "./components/chat-dock.js";
import { icon } from "./components/icons.js";
import { MainView } from "./components/main-view.js";
import { SettingsDialog } from "./components/settings-dialog.js";
import { Sidebar } from "./components/sidebar.js";
import { MotionEngine } from "./core/motion-engine.js";
import { SettingsStore } from "./core/settings-store.js";
import { ThemeController } from "./core/theme-controller.js";

/**
 * 将 HTML 中的数据图标占位符替换为本地内联 SVG。
 *
 * 这样 index.html 保持可读，同时不引入外部图标网络依赖。
 */
function hydrateStaticIcons() {
    document.querySelectorAll("[data-icon]").forEach((element) => {
        element.innerHTML = icon(element.dataset.icon, element.classList.contains("chat-dock__spark") ? 18 : 19);
    });

    const settingsIcon = document.querySelector(".settings-trigger__icon");
    if (settingsIcon) {
        settingsIcon.innerHTML = icon("settings", 19);
    }
}

/**
 * 显示短暂的状态提示。
 *
 * @param {string} message 提示文本
 */
function showToast(message) {
    const toast = document.querySelector("#toast");
    if (!toast) {
        return;
    }

    toast.textContent = message;
    toast.classList.add("is-visible");
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => {
        toast.classList.remove("is-visible");
    }, 2800);
}

/**
 * 启动主界面。
 *
 * 启动顺序保持显式：先读取设置并应用主题，再创建视图协调器，
 * 最后接入交互入口。当前不请求业务接口，也不恢复任何执行状态。
 */
function bootstrap() {
    hydrateStaticIcons();

    const stage = document.querySelector("#main-stage");
    const breadcrumb = document.querySelector("#topbar-breadcrumb");
    const navigation = document.querySelector("#sidebar-navigation");

    if (!stage || !breadcrumb || !navigation) {
        throw new Error("主界面缺少必要的 DOM 容器");
    }

    const settingsStore = new SettingsStore();
    const motionEngine = new MotionEngine();
    const themeController = new ThemeController({ motionEngine });
    themeController.apply(settingsStore.getSnapshot());

    // 设置变化统一在这里流向主题层；各个功能模块无需知道主题实现。
    settingsStore.subscribe((settings) => themeController.apply(settings));

    const mainView = new MainView({ stage, breadcrumb, motionEngine });
    const sidebar = new Sidebar({
        container: navigation,
        onSelect: (item) => {
            void mainView.show(item).catch((error) => {
                // 视图契约错误需要被开发者看见，但界面仍保持可操作。
                showToast(`视图切换失败：${error.message}`);
            });
        }
    });

    const defaultItem = getDefaultNavigationItem();
    sidebar.setActive(defaultItem.id);
    void mainView.show(defaultItem).catch((error) => {
        showToast(`主界面加载失败：${error.message}`);
    });

    const settingsDialog = new SettingsDialog({
        dialog: document.querySelector("#settings-dialog"),
        nav: document.querySelector("#settings-nav"),
        panel: document.querySelector("#settings-panel"),
        closeButton: document.querySelector("#settings-close"),
        resetButton: document.querySelector("#settings-reset"),
        store: settingsStore,
        motionEngine
    });

    document.querySelector("#settings-trigger")?.addEventListener("click", () => {
        settingsDialog.open();
    });

    // Ctrl+, 与 macOS 的 ⌘, 都可打开设置；Escape 由原生 dialog 自动处理。
    document.addEventListener("keydown", (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === ",") {
            event.preventDefault();
            settingsDialog.open();
        }
    });

    new ChatDock({
        form: document.querySelector("#chat-form"),
        input: document.querySelector("#chat-input"),
        button: document.querySelector("#send-button"),
        motionEngine,
        notify: showToast
    });

    // 页面卸载时释放当前视图的请求、事件和定时器。
    window.addEventListener("pagehide", () => {
        void mainView.destroy();
    }, { once: true });
}

// 模块脚本在 DOM 解析后执行；显式等待仍能兼容未来改为 defer 的加载方式。
if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bootstrap, { once: true });
} else {
    bootstrap();
}
