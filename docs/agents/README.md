# 按需 Subagent 协作配置

本目录保存归序 Orderly 的角色提示词、架构基线、协作流程和交接模板。主代理默认直接完成任务；只有命中 `AGENTS.md` 的按需条件时，才加载最少必要的 subagent 和交接模板。

## 当前阶段

自 2026-11-10 起，仓库进入用户明确授权的首版实现阶段。

- 目标是一个本地运行、可审查、可回滚的垂直切片。
- 本地规则计划器与真实外部模型必须明确区分。
- 删除只进入项目回收站；文件操作可使用应用自管工作区和用户通过宿主专用接口显式授权的本地文件根。
- 构建、测试、启动和 HTTP 烟测必须提供真实证据。
- V7 的完整发行、真实模型、自动枚举全盘或在缺少本机用户显式授权时访问个人目录，以及云能力仍不在首版范围。

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
| `release-runbook.md` | AI 发布预检、Dry Run、冻结、正式打包、安装验收、时间预算和止损规则 |
| `testing/README.md` | AI 测试上下文、默认命令、证据要求和人类测试文档读取边界 |
| `testing/records/YYYY-MM.md` | AI 按月维护的测试资产变更和真实执行结果 |
| `templates/task-brief.md` | 主代理或 PM 的按需任务卡模板 |
| `templates/pm-review.md` | 按需 PM 只读验收模板 |
| `templates/software-engineer-handoff.md` | 按需软件工程师交接模板 |
| `ORDERLY-MODULES-SELF-CONTAINED.md` | 模块自包含与动态扩展总任务卡 |
| `ORDERLY-FRONTEND-THEME-MODULARIZATION-HANDOFF.md` | 前端目录主题包实施记录，包含已拍板决策、架构边界、验证证据和后续验收矩阵 |
| `ORDERLY-SESSION-HANDOFF-2026-11-14.md` | 2026-11-14 会话状态交接，记录侧边栏完成状态、验证证据、风险和新对话读取方式 |
| `ORDERLY-MODULES-CONTRACT-SAMPLES.md` | 模块契约与 UI 样例、校验定向返工任务卡 |
| `ORDERLY-MODULES-SELF-CONTAINED-software-engineer-handoff.md` | 总任务软件工程师交接报告 |

## 按需角色

| 角色 | 类型 | 写入权限 | 核心产出 | 启用条件 |
|---|---|---|---|---|
| 主代理 | 默认执行者与编排者 | 最终集成与必要修改 | 任务范围、实施、验证、冲突裁决、最终交付 | 默认 |
| `project_manager` | 按需 subagent | 只读 | 任务卡、模块化审查、测试报错检查、验收结论 | 需要独立范围判断、高风险独立验收或用户明确要求 |
| `software_engineer` | 按需 subagent | 仅任务卡允许路径 | 变更实施、技术自检、交接报告 | 用户明确要求，或隔离实施能带来明确收益 |
| `ui_design_lead` | 按需设计角色卡包 | 默认只读；仅任务卡批准的 UI 路径 | 外观、布局、动画、可访问性与视觉验收 | 需要专门视觉设计审查或用户明确要求 |
| `module_design_lead` | 按需设计角色卡 | 默认只读；仅任务卡批准的目标模块路径 | 模块自包含、契约与查询、可回滚动作、模块 UI、失败隔离和设计验收 | 需要模块边界、契约或回滚模型设计审查 |

`ui_design_lead` 负责视觉方案和界面实现边界，不负责业务范围裁决或最终验收。其配置文件与说明集中保存在 `.codex/agents/ui-design-lead/`，避免角色配置散落。

`module_design_lead` 负责模块方案和自包含边界，不负责宿主安全、业务范围裁决或最终验收。涉及 `app/kernel/**`、`app/host/**`、全局 `contracts/**`、授权、快照、回滚或前端框架的改动，必须由主代理拆成单独任务，不能混入模块设计写入。

## 调用方式

Codex 会从项目 `.codex/agents/` 读取自定义 agent。默认无需分派角色，例如：

```text
由主代理直接完成任务，执行相关门禁，并核对实际文件后交付。
```

命中按需触发条件时，只启动最少必要的角色。例如需要独立验收时：

```text
请让 project_manager 基于实际文件复核本任务的范围、质量和验证证据，
并给出 PASS、CONDITIONAL 或 FAIL；主代理仍负责最终交付。
```

界面设计任务需要专门审查时，可在独立会话中显式读取角色卡：

```text
请读取 .codex/agents/ui-design-lead/agent.toml，严格按该角色卡先做只读设计审查。
只有任务卡明确列出的 UI 文件才允许修改。
```

程序模组设计任务可在独立会话中显式读取角色规范：

```text
请读取 .codex/agents/module-design-lead/AGENTS.md，严格按该角色先做只读模块设计审查。
只有在任务卡明确列出目标 Module/module_* 文件后，才允许修改。
```

即使当前客户端只提供通用 subagent 调度，也应把上述角色提示词作为任务的前置上下文传入。

## 必读顺序

角色提示词在 agent 启动时自动加载，不计入文件阅读顺序。启用 subagent 后按以下最小路由阅读：

1. `AGENTS.md`
2. 当前角色卡与任务卡
3. `docs/repo-map.md`
4. 当前任务直接相关的文件、测试、契约和资料

完整架构基线只在本任务涉及架构、产品事实、依赖边界或验收口径时读取；本协作流程只在确实进入按需协作、交接、返工或验收时读取相关章节。不得以“已启动角色”为由无条件全文加载。

测试任务默认读取 `docs/agents/testing/README.md` 和相关测试源码。AI 默认不得读取或修改 `docs/测试说明与测试记录.md`；只有用户明确要求查阅时，才能按指定章节或行段有界读取。

材料冲突或无法读取时，停止实施并报告主代理。

## 设计依据

配置采用 Codex 的项目级自定义 agent 约定：角色至少包含 `name`、`description` 和 `developer_instructions`。现有 PM 与软件工程师使用 `.codex/agents/` 顶层的直接 TOML 文件；界面设计角色按用户要求集中放在 `.codex/agents/ui-design-lead/` 文件夹中；用户明确要求的程序模组设计师角色以 `.codex/agents/module-design-lead/AGENTS.md` 保留，新会话需要显式读取。角色卡包不应被描述为已经自动注册的 subagent，除非客户端实际验证了该发现方式。subagent 继承主会话的权限、模型提供方和 MCP 配置，因此角色文件负责行为约束，不替代主会话的沙箱与审批设置。
