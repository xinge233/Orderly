import { icon } from "../components/icons.js";
import {
    moduleCatalogToNavigation,
    parseModuleCatalogPayload
} from "../contracts/module-catalog.js";
import { setText } from "../core/safe-text.js";
import { defineView } from "../core/view-contract.js";

/**
 * 空功能模块视图。
 *
 * 此页只解释后端测试模块的边界和发现接口，不提供命令执行按钮。
 *
 * @returns {string} 视图 HTML
 */
export function renderLab() {
    return `
        <div class="view-stack lab-view">
            <header class="page-heading">
                <div class="page-heading__icon page-heading__icon--accent">${icon("flask", 26)}</div>
                <div>
                    <span class="eyebrow">MODULE SANDBOX</span>
                    <h1>空功能试验台</h1>
                    <p>验证模块注册、发现与调度链路，不读取文件、不调用网络、不产生副作用。</p>
                </div>
                <span class="status-chip status-chip--safe">无副作用</span>
            </header>

            <section class="lab-grid">
                <article class="lab-card lab-card--primary">
                    <span class="lab-card__number" data-contract-order>MODULE / pending</span>
                    <div class="lab-card__icon">${icon("route", 30)}</div>
                    <h2 data-contract-module-id>正在读取契约</h2>
                    <p data-contract-module-description>
                        从固定模块目录样例读取名称、动作和安全元数据。
                    </p>
                    <div class="lab-card__footer">
                        <span><i></i> 已注册</span>
                        <span data-contract-action>action: pending</span>
                    </div>
                </article>

                <article class="lab-card">
                    <span class="lab-card__number">DISCOVERY</span>
                    <h2>模块目录</h2>
                    <p data-contract-status>
                        控制层通过 Spring 自动收集模块 Bean，并在启动时执行 id 唯一性校验。
                    </p>
                    <code class="endpoint-code">GET /api/platform/modules</code>
                </article>

                <article class="lab-card">
                    <span class="lab-card__number">EXTENSION</span>
                    <h2>松耦合扩展</h2>
                    <p>动作定义是唯一可信来源；中央管线负责校验、确认、审计和回滚。</p>
                    <div class="extension-pills">
                        <span>PlatformModule</span>
                        <span>ModuleHandler</span>
                    </div>
                </article>
            </section>

            <section class="panel lab-safety-panel">
                <div class="panel__header">
                    <div>
                        <span class="eyebrow">CONTROLLED SKELETON</span>
                        <h2>当前刻意未开放的能力</h2>
                    </div>
                    <span class="status-chip">框架阶段</span>
                </div>
                <div class="safety-list">
                    <span>${icon("close", 15)} 任意命令执行端点</span>
                    <span>${icon("close", 15)} 文件与系统操作</span>
                    <span>${icon("close", 15)} AI 模型调用</span>
                    <span>${icon("close", 15)} 任务状态写入</span>
                </div>
            </section>
        </div>
    `;
}

/**
 * 读取固定契约样例，并用 textContent 安全填充动态模块文本。
 *
 * 这里不请求真实后端，仅验证 Java 契约样例可被前端独立解析。
 *
 * @param {HTMLElement} container 当前视图容器
 * @param {{signal: AbortSignal}} context 生命周期上下文
 */
async function mountLab(container, context) {
    try {
        const contractUrl = new URL("./contracts/module-catalog.sample.json", document.baseURI);
        const response = await fetch(contractUrl, { signal: context.signal });
        if (!response.ok) {
            throw new Error(`契约样例请求失败：${response.status}`);
        }

        const catalog = parseModuleCatalogPayload(await response.json());
        const navigationItems = moduleCatalogToNavigation(catalog);
        const navigation = navigationItems.find((item) => item.view === "lab")
            ?? navigationItems[0];
        const module = catalog.modules.find(
            (candidate) => candidate.id === navigation?.moduleId
        );
        if (!module || !navigation) {
            throw new Error("固定契约中没有可展示的空功能模块");
        }

        const action = module.actions[0];
        setText(container, "[data-contract-order]", `MODULE / ${module.order}`);
        setText(container, "[data-contract-module-id]", module.id);
        setText(container, "[data-contract-module-description]", module.description);
        setText(
            container,
            "[data-contract-action]",
            action
                ? `action: ${action.id} · ${action.riskLevel}`
                : "action: none"
        );
        setText(
            container,
            "[data-contract-status]",
            `契约 v${catalog.contractVersion} 已解析 · 导航目标 ${navigation.view} · `
            + "Java 与前端共享同一固定样例。"
        );
    } catch (error) {
        // 主动取消视图加载不视为错误；其他失败只在界面内说明，不把内容当 HTML。
        if (error?.name !== "AbortError") {
            setText(container, "[data-contract-status]", "固定契约样例加载失败，请检查静态资源。");
        }
    }
}

/** 空功能试验台视图定义。 */
export const labView = defineView({
    name: "lab",
    render: renderLab,
    mount: mountLab
});
