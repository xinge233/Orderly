import { icon } from "../components/icons.js";

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
                    <span class="lab-card__number">MODULE / 999</span>
                    <div class="lab-card__icon">${icon("route", 30)}</div>
                    <h2>sample-empty</h2>
                    <p>只声明一个 <code>ping</code> 测试动作。命令无法命中未知模块或未声明动作。</p>
                    <div class="lab-card__footer">
                        <span><i></i> 已注册</span>
                        <span>sideEffects: false</span>
                    </div>
                </article>

                <article class="lab-card">
                    <span class="lab-card__number">DISCOVERY</span>
                    <h2>模块目录</h2>
                    <p>控制层通过 Spring 自动收集模块 Bean，并在启动时执行 id 唯一性校验。</p>
                    <code class="endpoint-code">GET /api/platform/modules</code>
                </article>

                <article class="lab-card">
                    <span class="lab-card__number">EXTENSION</span>
                    <h2>松耦合扩展</h2>
                    <p>元数据与命令处理分成两个接口；仅有展示需求的模块无需获得执行能力。</p>
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
