# 仓库地图

本文件是 Orderly 的轻量导航入口，只记录仓库入口、职责、读取路由、生成目录、二进制资产和验证命令。产品事实、规则和实现细节仍以各自主文件为准。

## 入口

| 入口 | 用途 | 何时读取 |
|---|---|---|
| `AGENTS.md` | 仓库常驻约束与协作入口 | 每次任务开始 |
| `docs/agents/architecture-baseline.md` | 首版架构与产品事实的阶段投影 | 每次技术任务开始 |
| `docs/agents/collaboration-workflow.md` | 任务、交接、门禁和返工流程 | 每次协作任务开始 |
| `README.md` | 构建、启动、功能和验证入口 | 需要运行或说明应用时 |
| `docs/测试说明与测试记录.md` | 测试分层、覆盖范围、执行命令、已知缺口和新增测试记录 | 涉及测试新增、删除、验收结论或测试规划时 |
| `.codex/config.toml` | Codex 项目级上下文配置 | 检查上下文预算配置时 |

## 顶层目录

| 路径 | 职责 |
|---|---|
| `app/kernel/` | 模块 SPI、风险等级、调用与结果等跨端基础类型 |
| `app/host/` | 模块加载、计划、授权、执行、快照、回滚、事件与 HTTP/SSE 外壳 |
| `app/frontend/` | 本地静态界面、前端状态、设置和交互测试 |
| `Module/` | 自包含业务模块与宿主内置 `module_system_*` 受保护模块 |
| `contracts/` | 计划、模块、模块 UI、主题和学习数据的契约与样例 |
| `ai/` | Python 3.14 + FastAPI AI 服务、提示词、模型接入、会话、Query 选择和评测；凭据不进入仓库 |
| `themes/` | 目录主题包，只包含清单、语义变量、动效和登记资源 |
| `scripts/` | 构建、启动、门禁、烟测、模块化和上下文工具 |
| `docs/agents/` | 角色规则、架构基线、协作流程、模板和历史交接 |
| `.codex/agents/` | 项目级 agent 与角色卡配置 |

## 业务模块

| 目录 | 入口职责 |
|---|---|
| `Module/module_system_control/` | 唯一控制模块；模块启停、顺序和设置治理 |
| `Module/module_fs/` | 沙箱文件查询、写动作、回收站与资料视图 |
| `Module/module_todo/` | 待办查询与创建 |
| `Module/module_calendar/` | 日程查询与时间修改 |
| `Module/module_study_assistant/` | 只读学习助手参考视图 |
| `Module/module_study_map/` | 只读知识脉络参考视图 |
| `Module/module_study_review/` | 只读复习自测参考视图 |

模块入口通常从 `pom.xml`、`src/main/java/`、`src/main/resources/orderly-modules/`、`src/main/resources/orderly-ui/` 和模块测试开始定位。模块自有 Java 测试位于 `src/test/java`，模块自有 JavaScript 测试位于 `src/test/js/**/*.test.mjs`，由 Maven 或 `scripts/gate.ps1` 自动发现。

`Module/AGENTS.md` 是模块目录级规范，定义自包含、Action 契约、默认 AI 可调用、结构化输出引用和禁止模块直接互调。

## 应用入口

| 区域 | 优先入口 |
|---|---|
| 宿主生命周期 | `app/host/src/main/java/com/orderly/host/OrderlyHostApplication.java` |
| HTTP API | `app/host/src/main/java/com/orderly/host/api/OrderlyController.java` |
| 计划服务 | `app/host/src/main/java/com/orderly/host/plan/PlanService.java` |
| 本地规则计划器 | `app/host/src/main/java/com/orderly/host/planner/LocalRulePlanner.java` |
| 执行与回滚 | `app/host/src/main/java/com/orderly/host/execution/ExecutionService.java` |
| 共享 Action 执行核心 | `app/host/src/main/java/com/orderly/host/execution/ActionExecutionCore.java` |
| AI 直执入口 | `app/host/src/main/java/com/orderly/host/execution/AiActionService.java` |
| Python AI 服务 | `ai/src/orderly_ai/` |
| Java AI 网关与编排 | `app/host/src/main/java/com/orderly/host/ai/` |
| 持久化执行记录 | `app/host/src/main/java/com/orderly/host/execution/ExecutionRecordStore.java` |
| 系统功能开关 | `app/host/src/main/java/com/orderly/host/system/SystemFeatureService.java` |
| 模块运行时 | `app/host/src/main/java/com/orderly/host/runtime/ModuleBootstrap.java` |
| 模块治理 | `app/host/src/main/java/com/orderly/host/modulecontrol/` |
| 主题目录 | `app/host/src/main/java/com/orderly/host/theme/` |
| 前端页面 | `app/frontend/index.html` |
| 前端状态与交互 | `app/frontend/src/app.js`、`ai-dialog.js`、`ai-settings.js`、`modules.js`、`settings.js` |
| 前端样式 | `app/frontend/styles/app.css`、`tokens.css` |

## 契约入口

| 契约 | 主要消费者 |
|---|---|
| `contracts/plan.schema.json` | 宿主计划校验、计划样例 |
| `contracts/action-execution.schema.json` | 总体 AI 直执 Action 数组请求 |
| `contracts/ai-settings.schema.json` | AI 模式、临时 API Key 与删除直执设置 |
| `contracts/ai-turn-request.schema.json` | 自然语言 AI 回合请求 |
| `contracts/ai-turn-response.schema.json` | ACTIONS、PLAN、QUERY、QUESTION 响应 |
| `contracts/ai-service-turn-request.schema.json` | Java 到 Python AI 服务的版本化 IPC 请求 |
| `contracts/ai-query-continuation-request.schema.json` | Query 结果回传 Python 的受控续聊请求 |
| `contracts/ai-service-decision.schema.json` | Python 到 Java 的受控决策响应 |
| `contracts/module.schema.json` | 模块 JAR 契约与模块测试 |
| `contracts/module-ui.schema.json` | 模块 UI 清单与前端契约测试 |
| `contracts/theme.schema.json` | 宿主主题加载与主题样例 |
| `contracts/study-data.schema.json` | 学习数据校验脚本与模块数据 |
| `contracts/samples/valid/` | 合法样例与契约测试 |
| `contracts/samples/invalid/` | 非法样例与失败路径测试 |

## 读取路由

| 任务类型 | 最小读取顺序 |
|---|---|
| 规则或流程变更 | `AGENTS.md`、架构基线、协作流程、目标规则文件 |
| 宿主或 API 变更 | 目标 Java 文件、直接调用方、相关契约、定向集成测试 |
| 前端交互变更 | `app/frontend/index.html`、对应 `src/*.js`、对应测试、语义样式变量 |
| 模块动作变更 | 模块契约、模块 Java、模块 UI 清单、模块测试、宿主相关接口 |
| 主题变更 | `themes/README.md`、目标 `theme.json`、`tokens.css`、`motion.css` |
| 契约变更 | Schema、合法样例、非法样例、所有直接消费者 |
| 构建或启动问题 | `README.md`、`启动归序.cmd`、`scripts/build.ps1`、`build-module-background.ps1`、`start.ps1`、`target/background/` 与 `target/build-parallel-*` 中的目标日志行段 |
| 历史决策追溯 | `docs/agents/` 中相关交接文档的指定章节，按需读取 |

历史交接文档只用于追溯事实、验证证据和已知风险，不单独构成实施授权。

## 上下文工具

| 目的 | 命令 |
|---|---|
| 查看仓库地图 | `.\scripts\context.ps1 -Mode map` |
| 读取单文件前 200 行 | `.\scripts\context.ps1 -Path AGENTS.md` |
| 读取指定行段 | `.\scripts\context.ps1 -Path README.md -Start 20 -Count 40` |
| 列出 PowerShell 文件 | `.\scripts\context.ps1 -Mode files -Kind ps1` |
| 有界搜索 | `.\scripts\context.ps1 -Mode search -Pattern "PlanService" -Count 20` |
| 查看差异统计 | `.\scripts\context.ps1 -Mode diff` |
| 检查上下文配置 | `.\scripts\context.ps1 -Mode audit` |

工具只读取 Git 跟踪文件，不接受仓库外路径、目录、未跟踪或忽略文件、重解析点、二进制和超限请求。

## 忽略目录与二进制资产

| 路径或类型 | 处理方式 |
|---|---|
| `target/`、`**/target/` | Maven 与测试生成物，不读取、不搜索、不提交 |
| `.m2-repository/` | 本地 Maven 缓存，不读取、不搜索、不提交 |
| `.orderly-data/` | 本地运行数据、回收站和状态，不读取、不搜索、不提交 |
| `~$*` | Office 临时锁文件，不读取、不搜索、不提交 |
| `*.log`、`*.pid` | 诊断和进程文件，只按报错行段定向查看 |
| `docs/归序Orderly-项目策划书-v7.docx` | 产品事实二进制资料，仅在任务需要且用户授权时读取 |
| `output/归序模块化架构图.png` | 已跟踪的二进制图片资产，不用文本读取 |
| `output/归序模块化协作说明.pptx` | 已跟踪的二进制演示资产，不用文本读取 |

上下文工具遇到二进制文件时只返回拒绝原因，不输出文件正文。

## 验证命令

| 目的 | 命令 |
|---|---|
| 上下文脚本语法 | PowerShell 解析 `scripts/context.ps1` |
| 上下文配置审计 | `.\scripts\context.ps1 -Mode audit` |
| Codex 严格配置 | `codex --strict-config doctor --summary --no-color --ascii` |
| 纯 Markdown/DOCX 文档门禁 | `.\scripts\doc-gate.ps1` |
| 完整门禁 | `.\scripts\gate.ps1` |
| 模块化验收 | `.\scripts\modularity.ps1` |
| 构建与分层启动 | `.\scripts\build.ps1 -MaxParallel 3` 或 `.\scripts\test-build-parallel.ps1`；分层启动用 `.\scripts\start.ps1 -BackgroundModules` 先启动控制模块与宿主，再按默认两路、可调 1-4 的队列并行构建并由单线程发布业务模块 |
| 生成离线源码包 | 默认要求干净 Git 和索引化源码；仅本地脏快照使用 `.\scripts\package-offline.ps1 -AllowDirtySnapshot` |
| 验证离线源码包 | `.\scripts\verify-offline.ps1 .\target\orderly-offline\Orderly-0.1.0-SNAPSHOT-offline-source.zip`；可加 `-MaxParallel <int>` |
| 启动 | 开发路径用 `.\scripts\start.ps1 -BackgroundModules`；解压 GitHub Release 后运行 `.\启动归序.cmd`，自动使用包内 `maven-repository` 和后台模块队列 |
| HTTP 烟测 | `.\scripts\smoke.ps1` |

构建与门禁产生真实退出码。未实际运行的命令应标记为未验证，不能引用历史结果代替当前结果。

## 维护规则

| 触发条件 | 更新内容 |
|---|---|
| 新增或移除顶层入口 | 更新入口、顶层目录和读取路由 |
| 新增或移除模块 | 更新业务模块表与模块入口说明 |
| 新增契约或样例目录 | 更新契约入口与消费者 |
| 新增生成目录或二进制资产 | 更新忽略目录与二进制资产表 |
| 验证命令或测试资产变化 | 更新验证命令表；新增、重命名或删除测试时同步更新 `docs/测试说明与测试记录.md` |

维护本文件时只写导航事实，不复制源码、完整规则、日志、交接正文或产品策划全文。
