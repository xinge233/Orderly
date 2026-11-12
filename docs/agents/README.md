# Subagent 协作配置

本目录保存归序 Orderly 的角色提示词、架构基线、协作流程和交接模板。目标是让主代理与 subagent 在同一套边界下工作，并让每项任务都有明确范围、责任人与验收证据。

## 当前阶段

自 2026-11-10 起，仓库进入用户明确授权的首版实现阶段。

- 目标是一个本地运行、可审查、可回滚的垂直切片。
- 本地规则计划器与真实外部模型必须明确区分。
- 删除只进入项目回收站，文件操作限制在应用自管沙箱。
- 构建、测试、启动和 HTTP 烟测必须提供真实证据。
- V7 的完整发行、真实模型、任意目录选择和云能力仍不在首版范围。

## 文件索引

| 文件 | 职责 |
|---|---|
| `AGENTS.md` | 仓库级常驻规则和单一入口 |
| `.codex/agents/project-manager.toml` | `project_manager` 自定义 agent |
| `.codex/agents/software-engineer.toml` | `software_engineer` 自定义 agent |
| `.codex/agents/ui-design-lead/agent.toml` | `ui_design_lead` 界面外观设计角色卡 |
| `.codex/agents/ui-design-lead/README.md` | 界面外观设计角色卡包的使用与维护说明 |
| `.codex/agents/module-design-lead/AGENTS.md` | `module_design_lead` 程序模组设计师角色规范 |
| `architecture-baseline.md` | 产品事实、阶段边界、未来目录结构和 UI 基线 |
| `collaboration-workflow.md` | 任务流转、交接、审查、返工和完成规则 |
| `templates/task-brief.md` | PM 开工任务卡模板 |
| `templates/pm-review.md` | PM 只读验收模板 |
| `templates/software-engineer-handoff.md` | 软件工程师交接模板 |
| `ORDERLY-MODULES-SELF-CONTAINED.md` | 模块自包含与动态扩展总任务卡 |
| `ORDERLY-FRONTEND-THEME-MODULARIZATION-HANDOFF.md` | 前端外观与动画主题包模块化交接，包含已拍板决策、架构边界和验收矩阵 |
| `ORDERLY-MODULES-CONTRACT-SAMPLES.md` | 模块契约与 UI 样例、校验定向返工任务卡 |
| `ORDERLY-MODULES-SELF-CONTAINED-software-engineer-handoff.md` | 总任务软件工程师交接报告 |

## 常设角色

| 角色 | 类型 | 写入权限 | 核心产出 |
|---|---|---|---|
| 主代理 | 编排者 | 最终集成与必要修改 | 任务调度、冲突裁决、最终交付 |
| `project_manager` | 常设 subagent | 只读 | 任务卡、模块化审查、测试报错检查、验收结论 |
| `software_engineer` | 常设 subagent | 仅任务卡允许路径 | 变更实施、技术自检、交接报告 |
| `ui_design_lead` | 界面设计角色卡包 | 默认只读；仅 PM 批准的 UI 路径 | 外观、布局、动画、可访问性与视觉验收 |
| `module_design_lead` | 程序模组设计角色卡 | 默认只读；仅 PM 批准的目标模块路径 | 模块自包含、契约与查询、可回滚动作、模块 UI、失败隔离和设计验收 |

`ui_design_lead` 与 PM、软件工程师分离：它负责视觉方案和界面实现边界，不负责业务范围裁决或最终验收。其配置文件与说明集中保存在 `.codex/agents/ui-design-lead/`，避免角色配置散落。

`module_design_lead` 同样与 PM、软件工程师分离：它负责模块方案和自包含边界，不负责宿主安全、业务范围裁决或最终验收。涉及 `app/kernel/**`、`app/host/**`、全局 `contracts/**`、授权、快照、回滚或前端框架的改动，必须由主代理拆成单独任务，不能混入模块设计写入。

## 调用方式

Codex 会从项目 `.codex/agents/` 读取自定义 agent。主代理可按角色名分派任务，例如：

```text
请让 project_manager 先为本任务输出范围基线和验收清单。
确认后，让 software_engineer 只在批准的文件范围内实施。
完成后，让 project_manager 基于实际文件做只读验收。
最后汇总双方结论并检查实际产出。
```

界面设计任务可在独立会话中显式读取角色卡：

```text
请读取 .codex/agents/ui-design-lead/agent.toml，严格按该角色卡先做只读设计审查。
只有 PM 任务卡明确列出的 UI 文件才允许修改。
```

程序模组设计任务可在独立会话中显式读取角色规范：

```text
请读取 .codex/agents/module-design-lead/AGENTS.md，严格按该角色先做只读模块设计审查。
只有在 PM 任务卡明确列出目标 Module/module_* 文件后，才允许修改。
```

即使当前客户端只提供通用 subagent 调度，也应把上述角色提示词作为任务的前置上下文传入。

## 必读顺序

角色提示词在 agent 启动时自动加载，不计入文件阅读顺序。所有角色随后统一按以下顺序阅读：

1. `AGENTS.md`
2. `docs/agents/architecture-baseline.md`
3. `docs/agents/collaboration-workflow.md`
4. 当前任务卡与指定资料

材料冲突或无法读取时，停止实施并报告主代理。

## 设计依据

配置采用 Codex 的项目级自定义 agent 约定：角色至少包含 `name`、`description` 和 `developer_instructions`。现有 PM 与软件工程师使用 `.codex/agents/` 顶层的直接 TOML 文件；界面设计角色按用户要求集中放在 `.codex/agents/ui-design-lead/` 文件夹中；用户明确要求的程序模组设计师角色以 `.codex/agents/module-design-lead/AGENTS.md` 保留，新会话需要显式读取。角色卡包不应被描述为已经自动注册的 subagent，除非客户端实际验证了该发现方式。subagent 继承主会话的权限、模型提供方和 MCP 配置，因此角色文件负责行为约束，不替代主会话的沙箱与审批设置。
