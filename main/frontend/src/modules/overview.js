import { icon } from "../components/icons.js";

/**
 * 总览视图。
 *
 * 当前只展示产品结构、模块边界和安全原则。所有卡片均明确标注为界面占位，
 * 不展示虚构文件数量、任务数量或“已完成”状态。
 *
 * @returns {string} 视图 HTML
 */
export function renderOverview() {
    return `
        <div class="view-stack overview-view">
            <section class="hero-panel">
                <div class="hero-panel__halo" aria-hidden="true"></div>
                <div class="hero-panel__content">
                    <span class="eyebrow">LOCAL-FIRST WORKFLOW</span>
                    <h1>把零散事务，<br><em>归拢成一条清晰的路。</em></h1>
                    <p>
                        文件、待办与日程将在这里形成连续工作流。
                        当前为模块化界面骨架，尚未连接真实文件或 AI 操作。
                    </p>
                    <div class="hero-tags" aria-label="产品边界">
                        <span>${icon("shield", 15)} 本地优先</span>
                        <span>${icon("route", 15)} 确认后执行</span>
                        <span>${icon("history", 15)} 可审计回滚</span>
                    </div>
                </div>

                <div class="hero-orbit" aria-label="归序工作流示意">
                    <div class="hero-orbit__ring hero-orbit__ring--outer"></div>
                    <div class="hero-orbit__ring hero-orbit__ring--inner"></div>
                    <div class="hero-orbit__core">
                        <span>归序</span>
                        <small>工作流中枢</small>
                    </div>
                    <span class="orbit-node orbit-node--files">${icon("folder", 17)}<small>文件</small></span>
                    <span class="orbit-node orbit-node--todos">${icon("check", 17)}<small>待办</small></span>
                    <span class="orbit-node orbit-node--calendar">${icon("calendar", 17)}<small>日程</small></span>
                </div>
            </section>

            <section class="surface-grid" aria-label="模块占位">
                <article class="module-tile module-tile--wide">
                    <div class="module-tile__icon">${icon("layers", 22)}</div>
                    <div>
                        <span class="module-tile__label">模块化控制层</span>
                        <h2>每个能力独立生长，<br>通过清晰契约彼此连接。</h2>
                    </div>
                    <span class="status-chip">框架已就绪</span>
                </article>

                <article class="module-tile">
                    <div class="module-tile__top">
                        <span class="module-tile__index">01</span>
                        <span class="placeholder-mark">界面占位</span>
                    </div>
                    <h3>文件与目录</h3>
                    <p>为浏览、整理、移动和归档保留的空间。</p>
                    <div class="skeleton-lines" aria-hidden="true">
                        <span></span><span></span><span></span>
                    </div>
                </article>

                <article class="module-tile">
                    <div class="module-tile__top">
                        <span class="module-tile__index">02</span>
                        <span class="placeholder-mark">界面占位</span>
                    </div>
                    <h3>待办与日程</h3>
                    <p>让事项和时间进入同一套可控流程。</p>
                    <div class="mini-timeline" aria-hidden="true">
                        <span></span><span></span><span></span>
                    </div>
                </article>
            </section>

            <div class="overview-lower-grid">
                <section class="panel workflow-panel">
                    <header class="panel__header">
                        <div>
                            <span class="eyebrow">WORKFLOW BLUEPRINT</span>
                            <h2>一条可控制的执行路径</h2>
                        </div>
                        <span class="status-chip status-chip--quiet">结构示意</span>
                    </header>

                    <ol class="workflow-route">
                        <li class="is-current">
                            <span class="workflow-route__number">1</span>
                            <div><strong>表达目标</strong><p>自然语言或传统手动入口。</p></div>
                            <span class="workflow-route__state">当前界面</span>
                        </li>
                        <li>
                            <span class="workflow-route__number">2</span>
                            <div><strong>检查计划</strong><p>复述目标、步骤、风险与影响范围。</p></div>
                            <span class="workflow-route__state">待接入</span>
                        </li>
                        <li>
                            <span class="workflow-route__number">3</span>
                            <div><strong>确认执行</strong><p>由控制层统一校验并执行。</p></div>
                            <span class="workflow-route__state">待接入</span>
                        </li>
                        <li>
                            <span class="workflow-route__number">4</span>
                            <div><strong>审计与回滚</strong><p>保留操作记录和可恢复路径。</p></div>
                            <span class="workflow-route__state">待接入</span>
                        </li>
                    </ol>
                </section>

                <aside class="panel guardrail-panel">
                    <span class="guardrail-panel__icon">${icon("shield", 24)}</span>
                    <span class="eyebrow">BOUNDARY</span>
                    <h2>能力有边界，<br>操作有回路。</h2>
                    <p>
                        AI 只能提出结构化的计划或澄清问题；
                        文件与系统动作必须由 Java 控制层在确认后执行。
                    </p>
                    <div class="guardrail-note">
                        <span class="guardrail-note__dot"></span>
                        当前版本不包含任何真实文件操作
                    </div>
                </aside>
            </div>

            <p class="prototype-note">
                当前页面为模拟界面。模块状态与工作流内容仅用于展示结构，不代表真实任务已执行。
            </p>
        </div>
    `;
}
