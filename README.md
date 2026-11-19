# 归序 Orderly

归序是一个本地优先、可审查、可中止、可回滚的个人文件与事务工作流程序。本仓库当前提供首版垂直切片：总体 AI 可以提交结构化 Action 数组，宿主按顺序校验和执行；写入、删除必须确认；删除只会进入项目回收站；执行记录、快照和事件可以跨重启查询。计划能力保留但默认隐藏，可在设置中重新启用。

## 当前可用能力

- 本地规则计划器：把少量明确的中文指令转换为结构化计划。它不连接外部大模型，界面会如实标注这一限制。
- 自包含模块：每个 `Module/module_*` 在自身目录携带动作契约、只读查询、本地计划规则、执行器、逆操作和 UI 资源；宿主与前端不保存具体模块映射。
- 模块控制：唯一的系统模块 `module_control` 管理系统业务模块的启停、侧边栏顺序、管理顺序、声明式设置，以及 Action/Query 的 AI 可调用状态。
- 文件模块：列出沙箱目录、创建目录、移动文件、移入回收站、从回收站恢复。
- 待办模块：读取并创建待办。
- 日程模块：读取并修改已有日程的时间。
- 学习界面参考扩展：新增 `研习蓝` 浅色主题、只读 AI 学习助手、知识脉络与复习自测模块；文件模块同时提供原型风格的资料管理视图和自身只读示例。它们不是 V7 已交付能力；全局 AI 接入不等同于学习模块的上传、自动整理、任务记录、自动出题与自动阅卷能力，这些学习能力仍明确标注尚未接入。
- AI 直执：业务 Action 默认允许 AI 调用，用户可在模组设置中动态关闭；Action 数组顺序执行，首次失败停止并逆序回滚此前完成项；支持指向前序输出的宿主解析引用。
- 真实 AI 接入：根目录 `ai/` 的 Python 3.14 + FastAPI 服务负责模型配置、提示词、会话和结构化决策；Java 宿主只做本机 IPC 网关、最终契约校验与执行。API Key 只临时保存在 Python AI 服务进程内。
- AI Query：模型可选择模块已声明且允许 AI 调用的只读 Query，最多 4 条，由 Java 校验并执行。查询原始结果默认只返回本地界面，不发送模型；只有用户显式开启设置后才按大小上限发送结构化结果。
- 中央控制：风险分级、服务端确认、路径边界、快照、持久化执行记录、事件时间线和逆序回滚。
- 本地界面：动态模块侧栏与总览、彼此独立的可持久化卡片和侧边栏排序、默认时间线且可切换摘要的历史记录、设置弹窗；旧版计划功能启用后恢复计划界面，独立的可拖动 AI 对话 Dock 始终可用。

## 启动

环境要求：支持 Windows PowerShell 5.1 与 PowerShell 7+；另需 JDK 21、Maven 3.9+、Python 3.14。脚本显式使用 `JAVA_HOME\bin\java.exe`，因此必须把 `JAVA_HOME` 指向 JDK 21；即使 PATH 中还有另一个 Java 版本，也不会用于构建或启动。启动脚本会创建或复用 `ai/.venv`，安装 FastAPI、Uvicorn 和测试依赖，然后启动仅监听回环地址的 Python AI 服务。

```powershell
.\scripts\start.ps1
```

首次启动前，`start.ps1` 会调用 `scripts/build.ps1`：自动扫描 `Module/module_*`，逐个执行 Maven `verify` 并把模块 JAR 放到 `target/orderly-modules/`，再构建宿主 fat JAR。直接运行 `build.ps1` 默认执行模块与宿主测试；`-SkipTests` 仅用于明确的快速启动或验收路径。构建会先清空运行模块暂存目录，因此删除模块源码后不会继续加载陈旧 JAR。可用以下命令单独构建并以已有产物启动：

```powershell
.\scripts\build.ps1
.\scripts\start.ps1 -SkipBuild
```

开发构建默认使用仓库内 `.m2-repository`。需要验证已有本地仓库不含远端回退时，可使用严格 Maven 离线模式；每次 Maven 调用都会带 `-o` 和指定仓库：

```powershell
.\scripts\build.ps1 -Offline -MavenRepository .\.m2-repository
```

`gate.ps1`、`start.ps1` 与 `modularity.ps1` 接受相同的 `-Offline`、`-MavenRepository` 参数并透传给 Maven 构建。严格离线模式不会调用 `ensure-env.ps1` 联网准备 Python 环境，而是要求现有 `ai/.venv` 可用；它不代表 Python、Node 或整个应用已经支持离线安装。

Java 宿主只监听 `127.0.0.1:8080`，Python AI 服务使用每次启动分配的 `127.0.0.1` 临时端口和随机 Bearer Token。打开 <http://127.0.0.1:8080/>。宿主或 AI 服务异常退出时，启动脚本会清理本次启动的另一个子进程。

程序首次运行会在仓库根目录创建 `.orderly-data/`：

- `.orderly-data/workspace/`：隔离演示工作区。
- `.orderly-data/trash/`：应用回收站。
- `.orderly-data/state/`：各模块自己的集合状态、初始化标记和执行记录。
- `.orderly-data/executions/`：宿主统一持久化的执行、步骤、参数、输出、快照和审计结果。
- `.orderly-data/module-governance.json`：业务模块启用状态、两套排序、模块设置以及 Action/Query 的动态 AI 可调用状态；使用原子文件替换写入。
- `.orderly-data/system-features.json`：宿主功能开关；首次运行默认关闭计划功能。

不会扫描或修改其他个人目录。首版可以通过 `ORDERLY_DATA_DIR` 环境变量改用另一个应用专用目录，但仍不支持选择任意系统文件夹。

## 演示

首次启动时，当前安装的模块可以各准备一次示例文件、待办和日程；用户清空后重启不会再次播种。右下角 AI Dock 在未配置模型时用于临时输入 DeepSeek API Key；也可以进入“设置 → AI 接入”切换自定义模式和配置删除直执。独立 AI 计划不依赖旧版计划功能。若要使用本地规则计划器，先在设置中启用计划功能，然后可以尝试：

```text
创建文件夹 竞赛材料
```

```text
移动 收件箱/示例笔记.txt 到 竞赛材料
```

```text
删除 待整理/草案.txt
```

写入和删除计划都需要用户勾选确认，删除不再要求输入确认词。执行后可以在历史记录中回滚，验证文件、待办和日程的逆序恢复。

## 验证

```powershell
.\scripts\doc-gate.ps1
.\scripts\gate.ps1
.\scripts\modularity.ps1
```

`scripts/doc-gate.ps1` 是只读快速门禁，不访问网络，不启动 Java、Maven 或 Node，也不写运行数据。它解析 `scripts/*.ps1` 的 PowerShell AST，检查关键规则、契约样例、上下文配置、仓库地图脚本引用，以及 Git 跟踪和未跟踪未忽略 Markdown 的合并冲突标记和本地相对链接。已跟踪但工作区删除的普通 Markdown 会跳过正文读取，但其他文档指向它的本地链接仍会失败，关键文件仍必须存在。

快速路径只接受明确的快速白名单 Markdown 文档：根 `AGENTS.md`、`README.md`、`Module/AGENTS.md`、`Module/README.md`、`docs/**/*.md` 和 `.codex/agents/**/*.md`。白名单路径大小写精确，扩展名只接受小写 `.md`；大写或混合大小写的目录、文件名和扩展名均回落完整门禁。`themes/**`、`contracts/**`、`Module/module_*` 以及任意未知根目录中的 `.md` 即使扩展名为 Markdown，也不得使用快速路径。

`gate.ps1` 会先无条件调用 `scripts/doc-gate.ps1`。当变更集非空且每一项都属于快速白名单 Markdown 时，`gate.ps1` 自动走快速文档分支并直接成功退出；空变更集、任何非 Markdown 文件、快速白名单外 Markdown 或分类不确定的变更都继续完整门禁。完整分支使用标准 Python `jsonschema` 校验契约样例，并执行模块化 Maven 测试与打包、框架与模块 UI 语法、前端 Node 测试，同时检查物理删除调用、业务样式硬编码色值、固定模块映射和固定业务端点。`-DocumentOnly` 要求变更集必须是非空快速白名单 Markdown，否则失败且不启动构建。`modularity.ps1` 通过移动源码和重启宿主，验证全业务模块、移除单模块、零业务模块（保留 `module_control`）、恢复、临时模块自动发现与可逆移出、模块 UI/查询/计划规则、动态主题与已选主题失效回退；结束后恢复原生模块源码，并清理 `target` 验收临时目录。服务启动后的端到端 HTTP 烟测由 `scripts/smoke.ps1` 完成。

### 离线源码包

正式包默认要求 Git 工作树干净。以下命令在 `target/orderly-offline` 中从空 Maven 仓库执行一次在线预热构建和 Java/Maven 测试，再生成包含源码、`maven-repository`、`maven-manifest.json`、`files.sha256`、第三方制品清单和许可风险提示的 ZIP。源码路径只来自 Git 索引，新增文件必须先显式加入索引。产物不会自动上传：

```powershell
.\scripts\package-offline.ps1
.\scripts\verify-offline.ps1 .\target\orderly-offline\Orderly-0.1.0-SNAPSHOT-offline-source.zip
```

仅用于本地验收脏工作树时，可使用 `.\scripts\package-offline.ps1 -AllowDirtySnapshot`。该包会在 manifest 中记录 `gitDirty=true`，不属于正式干净发布；正式包的 `gitDirty` 必须为 `false`。`sourceTreeSha256` 是对排序后的源码路径、文件大小和 SHA-256 组成规范文本计算的稳定摘要，`sourceFiles` 同时记录每个源码文件的大小和哈希。

验证脚本要求 ZIP 旁存在同名 `.sha256`，会先拒绝禁止路径，再安全解压到 `target/orderly-offline/verify-*`，双向核对源码、POM、Maven 仓库和第三方 JAR/POM 集合及大小、SHA-256、重解析点，最后在包内运行 `build.ps1 -Offline -MavenRepository <包内 maven-repository>`。验证只执行 Maven 构建与 Java 测试，不要求或运行 Node/Python 测试。离线范围仍仅限 Maven，不代表整个应用可以离线安装。第三方清单中的许可状态固定为 `NOT_DETERMINED`，不是法律或许可兼容性结论。

## 低 token 读取

先读取 `docs/repo-map.md` 定位入口，再用 `scripts/context.ps1` 做只读、有界读取。工具只接受 Git 跟踪文件，默认每次读取 200 行，并拒绝仓库外路径、目录、未跟踪或忽略文件、重解析点、二进制和超限请求。

```powershell
.\scripts\context.ps1 -Mode map
.\scripts\context.ps1 -Path AGENTS.md
.\scripts\context.ps1 -Mode files -Kind ps1
.\scripts\context.ps1 -Mode search -Pattern "createPlan" -Count 20
.\scripts\context.ps1 -Mode diff
.\scripts\context.ps1 -Mode audit
```

## 架构

- `app/kernel/`：模块 SPI 与跨端数据契约。
- `app/host/`：授权、校验、调度、快照、回滚、事件、HTTP/SSE 外壳和 Python AI IPC 网关，不实现具体业务或模型调用。
- `ai/`：唯一的 Python AI 实现，负责模型配置与临时 Key、模型请求、提示词、会话、`ACTIONS / PLAN / QUERY / QUESTION` 解析和 Query 选择；不持有文件权限或执行能力。
- `app/frontend/`：静态本地界面。
- `Module/module_fs/`、`module_todo/`、`module_calendar/`：各自携带契约、查询、计划规则、执行器、逆操作和 `orderly-ui/` 资源；构建脚本会自动发现新的 `Module/module_*`。模块规范见 `Module/AGENTS.md`。
- `Module/module_study_assistant/`、`module_study_map/`、`module_study_review/`：只读学习界面参考模块，每个模块从自身 JAR 的 `orderly-data/<moduleId>.json` 返回数据，不包含动作或跨模块读取。
- `Module/module_control/`：唯一受保护的系统模块，只提供模块治理入口，不作为业务模块出现在 `/api/modules`。
- `contracts/`：计划、接口和样例的唯一真相源。
- `themes/`：目录主题包，只包含清单、语义变量、动效 CSS 与登记资源，不包含界面结构或 JavaScript。
- `scripts/`：构建、启动、门禁与烟测。

### 模块运行时边界

宿主不在编译期依赖任何具体业务模块，也不把模块打进自己的 fat JAR。启动时，宿主按文件名稳定排序扫描外部模块目录，用 `URLClassLoader`（父加载器共享 kernel SPI）读取每个 JAR 的 `orderly-modules/*.json`，再通过 `ServiceLoader` 绑定执行器；契约与执行器集合不一致、重复模块、重复动作、重复查询或无效计划规则都会阻止启动。必须恰好加载一个受保护的 `module_control` 系统模块，零业务模块仍合法。

`GET /api/modules` 只返回业务模块并附带 `enabled`，`GET /api/system-modules` 返回系统模块且恒为启用。模块治理 API：

```text
PATCH /api/modules/{moduleId}/activation
GET   /api/system/module-preferences
PUT   /api/system/module-preferences/sidebar-order
PUT   /api/system/module-preferences/management-order
GET   /api/system/module-interfaces
PATCH /api/modules/{moduleId}/interfaces/{kind}/{interfaceId}/ai-callable
PUT   /api/modules/{moduleId}/settings
POST  /api/modules/{moduleId}/settings/reset
POST  /api/system/modules/refresh
POST  /api/system/module-folder/open
```

`module-folder/open` 只接受 `X-Orderly-Action: open-module-folder`，只在 Windows 上打开配置的 `orderly.module-source-dir`，不接受客户端路径。模块查询、计划规则、计划生成、确认和执行都检查 `enabled`；执行中拒绝停用，回滚始终允许。开关、设置、排序、刷新和打开目录会写入审计事件。

业务 Action 和 Query 不在模块契约中声明静态 AI 权限。宿主在 `.orderly-data/module-governance.json` 中保存用户级 `actionAiCallableEnabled` 与 `queryAiCallableEnabled`，所有业务接口默认开启且可由 `module_control` 动态切换；系统模块 `module_control` 不参与治理。动态开关只限制 AI：Action 影响 AI 计划与 AI 直执，Query 影响已接入的 AI Query，模块自身查询和总览不会受影响。独立 AI 计划引用中的 Action 显示“使用中”，确认时原子转换为整次执行占用，并在结束或失败后释放；过期提案也会释放。旧版计划不参与。

宿主功能与直执 API：

```text
GET  /api/system/features
PUT  /api/system/features/planning
POST /api/ai/actions/execute
GET  /api/ai/settings
PUT  /api/ai/settings
DELETE /api/ai/settings
POST /api/ai/settings/test
POST /api/ai/turns
POST /api/ai/plans/{planId}/confirm
GET  /api/executions
GET  /api/executions/{executionId}
POST /api/executions/{executionId}/rollback
```

旧版计划由宿主设置控制，默认关闭。关闭时旧计划生成、确认和计划回滚接口返回 `FEATURE_DISABLED`，历史执行仍可读取和回滚。独立 AI 计划不复用该开关或接口。AI 直执请求只由宿主设置 `AI_DIRECT` 或 `AI_PLAN` 来源，客户端不能覆盖风险等级、模型返回结构或确认状态。
`POST /api/ai/turns` 必须携带 `clientRequestId`；同一设置代次、会话和请求 ID 的重复提交返回首次结果，不会再次调用模型或重复执行写入。

`sidebarOrder` 和 `managementOrder` 都必须完整包含当前业务模块和 `module_control` 的 ID。`module_control` 默认位于最后，但用户可以在侧边栏和模块设置列表中拖动它；两份排序独立持久化且不得重复。

运行中刷新只允许显式追加新模块：扫描 `Module/module_*` 源码，并要求 `target/orderly-modules/` 已有对应 JAR。未构建源码返回 `MODULE_NOT_BUILT`；已有模块的源码或 JAR 更新、删除返回 `RESTART_REQUIRED`，当前运行快照保持不变。刷新不会自动编译，也不会替换或卸载已有模块。

模块查询统一通过 `GET /api/modules/{moduleId}/query/{queryId}`，模块 UI 只从 JAR 内 `orderly-ui/module.json`、`module.js` 和 `module.css` 提供，资源接口为 `GET /api/modules/{moduleId}/ui/{asset}` 且只接受白名单文件名。前端按模块 UI 清单生成侧栏、总览卡片和视图；单个模块的 UI 或查询失败只影响该模块。

学习相关只读数据由 `contracts/study-data.schema.json` 约束，并通过 `scripts/validate-study-data.mjs` 校验。公共字段声明 `ownerModule`、`readOnly: true`、来源和计数，负载按 `assistant`、`materials`、`knowledge-map`、`review` 四类校验引用完整性。数据分别位于模块自身 JAR 的模块唯一资源名下，不建立集中数据目录；模块只能调用自身声明的查询。

模块 JAR 是应用有权执行的本地文件，属于**本地受信任可执行代码**，不是安全沙箱。当前不提供远程插件下载、签名校验或权限隔离；只应放入自己信任的模块。零业务模块时应用仍可启动，`/api/modules` 返回空数组，侧栏与总览保持空状态；`module_control` 缺失时构建和启动都会失败。

主题从配置的 `themes/` 目录按直接子目录加载，不使用 classpath 回退，也不进入宿主 fat JAR。每个包包含 `theme.json`、清单登记的 `tokens.css` 与 `motion.css`，可选 `assets/`；添加或移除目录后重启宿主生效，不需要重新构建，首版不支持热加载或主题 JavaScript。

`GET /api/themes` 返回 `{items, issues, builtInFallbackActive}`。单个主题版本不支持、资源缺失或路径非法只产生局部问题；重复 id 和多个默认主题仍阻止启动。`GET /api/themes/{id}` 返回完整清单元数据，`GET /api/themes/{id}/assets/{asset}` 只提供登记白名单内的 CSS 和安全静态资源。前端回退顺序固定为当前可用主题、可用默认主题、内置安全外观，不会选择排序第一项兜底。主题制作说明见 `themes/README.md`。

## 首版边界

这是可运行的受控工作流切片，不是 V7 的最终发行版。真实模型需要用户临时输入 API Key；没有可用 Key 时 DeepSeek 实际连通性属于未验证项。尚未支持任意个人目录选择、重复文件哈希清理、提醒调度、云备份、安装包和自动更新。学习相关模块是界面参考扩展，不代表 V7 已承诺通用 AI 学习业务；其中上传、AI 整理、任务记录、自动出题和自动阅卷仍未接入。计划定义与 AI 会话保存在进程内存中；执行记录、步骤快照、输出和事件会持久化，已完成执行可跨重启查询和回滚。目录边界、离线行为和功能完成度必须在界面中如实展示，不能把规则匹配或演示数据描述为完整 AI 能力。

模块注册完全由 `Module/module_*` 源码扫描和 `target/orderly-modules/` 中的 JAR 发现完成，不维护单独的模块索引文件，也不会把模块变成 Maven reactor 成员。当前扩展性风险位于模块 JAR 的本地信任模型：仓库不校验来源或签名，且不提供远程分发、自动热加载、替换或卸载；运行中只允许显式追加已构建的新模块。
