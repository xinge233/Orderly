# Subagent 协作配置

本目录保存归序 Orderly 的角色提示词、架构基线、协作流程和交接模板。目标是让主代理与 subagent 在同一套边界下工作，并让每项任务都有明确范围、责任人与验收证据。

## 当前阶段

当前仓库只搭建提示词与协作框架，不包含任何应用源码。

- 本阶段只允许 Markdown 与 `.codex/agents/*.toml` 提示词配置。
- 不实现 Java、Python、AI、前端、数据库、测试或业务模块。
- 不创建 `app/`、`Module/`、`themes/`、`contracts/`、`ai/`、`scripts/`。
- 当前没有可运行程序，测试状态统一写为“不适用，未运行”。
- “最低可用界面”是未来实现约束，不是本阶段的编码任务。

## 文件索引

| 文件 | 职责 |
|---|---|
| `AGENTS.md` | 仓库级常驻规则和单一入口 |
| `.codex/agents/project-manager.toml` | `project_manager` 自定义 agent |
| `.codex/agents/software-engineer.toml` | `software_engineer` 自定义 agent |
| `architecture-baseline.md` | 产品事实、阶段边界、未来目录结构和 UI 基线 |
| `collaboration-workflow.md` | 任务流转、交接、审查、返工和完成规则 |
| `templates/task-brief.md` | PM 开工任务卡模板 |
| `templates/pm-review.md` | PM 只读验收模板 |
| `templates/software-engineer-handoff.md` | 软件工程师交接模板 |

## 常设角色

| 角色 | 类型 | 写入权限 | 核心产出 |
|---|---|---|---|
| 主代理 | 编排者 | 最终集成与必要修改 | 任务调度、冲突裁决、最终交付 |
| `project_manager` | 常设 subagent | 只读 | 任务卡、模块化审查、测试报错检查、验收结论 |
| `software_engineer` | 常设 subagent | 仅任务卡允许路径 | 变更实施、技术自检、交接报告 |

本阶段不增加第三个常设角色。PM 已承担模块化与测试审查，增加角色会造成职责重叠。未来进入真实代码阶段后，如契约、安全或 UI 审查复杂度显著上升，再评估增加独立审查员。

## 调用方式

Codex 会从项目 `.codex/agents/` 读取自定义 agent。主代理可按角色名分派任务，例如：

```text
请让 project_manager 先为本任务输出范围基线和验收清单。
确认后，让 software_engineer 只在批准的文件范围内实施。
完成后，让 project_manager 基于实际文件做只读验收。
最后汇总双方结论并检查实际产出。
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

配置采用 Codex 的项目级自定义 agent 约定：`.codex/agents/` 下每个 TOML 定义一个角色，至少包含 `name`、`description` 和 `developer_instructions`。subagent 继承主会话的权限、模型提供方和 MCP 配置，因此角色文件负责行为约束，不替代主会话的沙箱与审批设置。
