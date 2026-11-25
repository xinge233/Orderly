# 归序 Orderly

归序是一个本地优先、可审查、可中止、可回滚的个人文件与事务工作流程序。本仓库当前提供首版垂直切片：总体 AI 可以提交结构化 Action 数组，宿主按顺序校验和执行；写入、删除必须确认；删除只会进入项目回收站；执行记录、快照和事件可以跨重启查询。计划能力保留但默认隐藏，可在设置中重新启用。

## 当前可用能力

- 本地规则计划器：把少量明确的中文指令转换为结构化计划。它不连接外部大模型，界面会如实标注这一限制。
- 自包含模块：每个 `Module/module_*` 在自身目录携带动作契约、只读查询、本地计划规则、执行器、逆操作和 UI 资源；宿主与前端不保存具体模块映射。
- 模块控制：唯一的控制模块 `module_system_control` 管理系统业务模块的启停、侧边栏顺序、管理顺序、声明式设置，以及 Action/Query 的 AI 可调用状态。
- 文件模块：列出沙箱目录、创建目录、移动文件、移入回收站、从回收站恢复。
- 待办模块：读取并创建待办。
- 日程模块：读取并修改已有日程的时间。
- 学习界面参考扩展：新增 `研习蓝` 浅色主题、只读 AI 学习助手、知识脉络与复习自测模块；文件模块同时提供原型风格的资料管理视图和自身只读示例。它们不是 V7 已交付能力；全局 AI 接入不等同于学习模块的上传、自动整理、任务记录、自动出题与自动阅卷能力，这些学习能力仍明确标注尚未接入。
- AI 直执：业务 Action 默认允许 AI 调用，用户可在模组设置中动态关闭；Action 数组顺序执行，首次失败停止并逆序回滚此前完成项；支持指向前序输出的宿主解析引用。
- 真实 AI 接入：根目录 `ai/` 的 Python 3.14 + FastAPI 服务负责模型配置、提示词、会话和结构化决策；Java 宿主只做本机 IPC 网关、最终契约校验与执行。API Key 只临时保存在 Python AI 服务进程内。
- AI Query：模型可选择模块已声明且允许 AI 调用的只读 Query，最多 4 条，由 Java 校验并执行。查询原始结果始终返回本地界面，并在受控大小上限内自动回传模型用于展示或后续决策；不提供可修改的发送开关。
- 中央控制：风险分级、服务端确认、路径边界、快照、持久化执行记录、事件时间线和逆序回滚。
- 本地界面：动态模块侧栏与总览、彼此独立的可持久化卡片和侧边栏排序、默认时间线且可切换摘要的历史记录、设置弹窗；旧版计划功能启用后恢复计划界面，独立的可拖动 AI 对话 Dock 始终可用。
- Windows 完整版：正式应用包含 Java 运行时、完整 JDK 21、Maven、本地仓库、AI 可执行文件和模块源码。终端用户不需要自行安装 JDK、Python 或 Maven；只需单独提供真实模型 API Key。

## 启动

### Windows 完整版

普通用户使用 GitHub Release 中的 `Orderly-0.1.0.exe` 安装器，或解压 `Orderly-0.1.0-full-app.zip` 后双击 `Orderly.exe`。安装器为当前用户安装，不需要管理员权限，也不需要安装 JDK、Python 或 Maven。完整版使用系统自带的 Windows PowerShell 5.1 作为启动编排层，其余构建和运行工具均来自应用目录。

完整版运行时数据默认写入 `%LOCALAPPDATA%\Orderly\data`，包括工作区、回收站、执行记录、模块治理和日志。模块源码与运行 JAR 保留在安装目录；jpackage 的当前用户安装目录对该用户可写，因此设置界面中的模块下载、源码安装和本地 Maven 构建仍可工作。可通过 `ORDERLY_DATA_DIR` 把应用数据改到另一个专用目录。

完整版安装器尚未进行代码签名，Windows SmartScreen 可能显示“未知发布者”。应用不会自动更新；升级前应保留 `%LOCALAPPDATA%\Orderly\data`，安装新版本后再次启动即可继续使用原有数据。

### 源码开发

源码开发环境要求：支持 Windows PowerShell 5.1 与 PowerShell 7+；另需 JDK 21、Maven 3.9+、Python 3.14。脚本显式使用 `JAVA_HOME\bin\java.exe`，因此必须把 `JAVA_HOME` 指向 JDK 21；即使 PATH 中还有另一个 Java 版本，也不会用于构建或启动。启动脚本会创建或复用 `ai/.venv`，安装 FastAPI、Uvicorn 和测试依赖，然后启动仅监听回环地址的 Python AI 服务。

```powershell
.\scripts\start.ps1
.\scripts\start.ps1 -BackgroundModules
```

源码启动默认先读取 `target/build-manifest.json` 并校验运行输入与 JAR 哈希。全部有效时直接启动，不调用 Maven；只有对应输入变化、产物缺失或哈希不符时才构建受影响部分。`-ForceBuild` 忽略清单并完整重建，`-SkipBuild` 只用于明确知道产物有效的验证路径。`-BackgroundModules` 在后台上继续监督缺失或新增模块，Java 健康检查通过并打开界面后由 `scripts/build-module-background.ps1` 按队列处理待构建项。首轮构建结束后 `scripts/watch-module-downloads.ps1` 继续监督新下载模块，空闲时不持有仓库构建锁；发现新源码后再执行单模块构建和宿主刷新。后台默认最多同时运行 2 个模块 Maven worker，可在设置弹窗的“系统设置”中调整为 1-4；worker 只生成候选 JAR，校验、原子发布和宿主刷新始终由单一父进程依次完成。启动入口 `启动归序.cmd` 在源码目录使用该模式，在预构建运行包中直接按清单启动且不调用 Maven。

直接运行 `build.ps1` 默认执行模块与宿主测试；`-SkipTests` 仅用于明确的快速启动或验收路径。`-BootstrapOnly` 只构建控制模块与宿主，`-ModulesOnly -ModuleName <module_*>` 只构建一个业务模块且不重建宿主，`-SkipKernelInstall` 复用已安装 kernel。前台完整构建默认串行；只有显式传入大于 1 的 `-MaxParallel` 才并行，且并行范围仅限相互独立的模块 Maven 构建。后台渐进启动读取 `.orderly-data/build-settings.json`，默认并发为 2；多个 worker 使用隔离 Maven 仓库，发布仍保持单线程。

`build.ps1` 会在整个构建期间持有基于规范化 `repoRoot` 哈希命名的仓库级互斥锁，防止两个外部构建同时写共享 Maven 仓库、模块暂存或宿主 target。`modularity.ps1` 从首次移动模块源码前开始持有同一把锁，直到源码恢复、最终重建和清理结束；它嵌套调用 `build.ps1` 时使用同一线程的可重入所有权。默认等待上限为 120 秒，超时或获取失败会报告 `repoRoot`、锁名和原因。

可用以下命令单独构建并以已有产物启动：

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -MaxParallel 3
.\scripts\build.ps1 -BootstrapOnly -SkipTests
.\scripts\build.ps1 -ModulesOnly -ModuleName module_fs -SkipKernelInstall
.\scripts\start.ps1 -SkipBuild
.\scripts\start.ps1 -ForceBuild
.\scripts\start.ps1 -BackgroundModules
```

开发构建默认使用仓库内 `.m2-repository`。前台有效并发为 1 时直接使用共享 Maven 仓库；前台并发大于 1 时，以及所有后台 worker，都在 `target/build-parallel-*` 下使用独立可写仓库，共享预热仓库只作为 Maven 3.9+ 的只读 tail。kernel 安装、模块 JAR 校验、候选暂存发布、宿主编译和宿主 JAR 检查始终由主进程串行完成。任一 worker 失败时会收尾已启动 worker，并报告模块名、Maven 原始退出码和日志路径。

`target/build-parallel-*` 会保留每次构建的 worker 原始 `stdout`、`stderr` 和运行元数据；显式并行时还会保留隔离 Maven 仓库，用于复现依赖解析与失败。受控清理只允许在该目录所属仓库没有任何构建或模块化验收运行时，按专用前缀删除旧的 `target/build-parallel-*` 运行目录；不得通配删除 `target/`、`target/orderly-modules/` 或宿主 target。

模块阶段失败不会改变既有 `target/orderly-modules/` 和宿主 JAR。宿主阶段失败会恢复上一套模块暂存和宿主 JAR；恢复失败会作为独立错误明确报告。不要只清空 `target/orderly-modules/` 来启动新版本，应用运行期间也不要重建、替换或删除其模块目录；源码增删后应重新构建并重启。

开发构建默认使用仓库内 `.m2-repository`。需要验证已有本地仓库不含远端回退时，可使用严格 Maven 离线模式；每次 Maven 调用都会带 `-o` 和指定仓库，并行 worker 也不会联网回退：

```powershell
.\scripts\build.ps1 -Offline -MavenRepository .\.m2-repository
.\scripts\build.ps1 -Offline -MavenRepository .\.m2-repository -MaxParallel 3
```

`gate.ps1`、`start.ps1`、`modularity.ps1` 与 `verify-offline.ps1` 接受相同的 `-MaxParallel` 并只透传给 `build.ps1`，不复制并行调度逻辑。`gate.ps1`、`start.ps1` 与 `modularity.ps1` 也透传 `-Offline`、`-MavenRepository`。`start.ps1 -MavenOffline` 只让 Maven 使用仓库根目录下的 `maven-repository` 严格离线构建，Python 环境仍由 `ensure-env.ps1` 正常准备；`-Offline` 则进一步要求直接复用现有 `ai/.venv`，不会联网准备 Python 环境。离线范围只覆盖 Maven 构建，不代表 Python、Node 或整个应用已经支持离线安装。

Java 宿主只监听 `127.0.0.1:8080`，Python AI 服务使用每次启动分配的 `127.0.0.1` 临时端口和随机 Bearer Token。打开 <http://127.0.0.1:8080/>。后台模式先等待 Java 健康检查，再启动模块构建；Python 环境准备与 AI 服务启动同时进行，不会阻塞框架首屏。宿主或 AI 服务异常退出时，启动脚本会清理本次启动的子进程。后台日志与模块状态分别位于 `target/background/`、`target/module-build-status.json` 和 `target/module-build-priority.json`。

侧边栏底部的“退出归序”会在二次确认后停止 Java 宿主、AI 服务和后台模块构建。Action 或计划执行期间禁止退出；退出后重新运行 `启动归序.cmd` 即可恢复，未完成的模块构建会重新进入后台队列。

待构建模块会以真实构建状态显示在侧边栏和总览。点击待构建模块会写入优先请求；正在运行的 Maven 不会被抢占，调度器会在下一个可用 worker 上选择它。存在未就绪模块时，AI 输入会先提示等待；用户确认后，AI 在全部模块完成或失败前不会提交模型回合，也不会执行部分 Action。

源码启动首次运行会在仓库根目录创建 `.orderly-data/`；完整版默认使用 `%LOCALAPPDATA%\Orderly\data`：

- `.orderly-data/workspace/`：隔离演示工作区。
- `.orderly-data/trash/`：应用回收站。
- `.orderly-data/state/`：各模块自己的集合状态、初始化标记和执行记录。
- `.orderly-data/executions/`：宿主统一持久化的执行、步骤、参数、输出、快照和审计结果。
- `.orderly-data/module-governance.json`：业务模块启用状态、两套排序、模块设置以及 Action/Query 的动态 AI 可调用状态；使用原子文件替换写入。
- `.orderly-data/module-removals.json`：等待重启清理的业务模块删除登记；启动扫描会跳过对应 JAR。
- `.orderly-data/trash/modules/`：已移除模块源码和构建产物的本地回收站。
- `.orderly-data/system-features.json`：宿主功能开关；首次运行默认关闭计划功能。
- `.orderly-data/build-settings.json`：后台模块构建并发；首次运行默认 2，允许 1-4。

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
.\scripts\test-build-parallel.ps1
.\scripts\test-build-plan.ps1
.\scripts\test-startup-cache.ps1
.\scripts\package-full.ps1 -OutputDirectory D:\Orderly-artifacts\0.1.0
.\scripts\verify-full.ps1 D:\Orderly-artifacts\0.1.0\staging\Orderly
.\scripts\verify-full-release-pair.ps1 D:\Orderly-artifacts\0.1.0\staging\Orderly <offline-source.zip>
.\scripts\package-runtime.ps1
.\scripts\verify-runtime.ps1 .\target\orderly-runtime\Orderly-0.1.0-runtime.zip
.\scripts\verify-release-pair.ps1 <runtime.zip> <offline-source.zip>
```

`scripts/doc-gate.ps1` 是只读快速门禁，不访问网络，不启动 Java、Maven 或 Node，也不写运行数据。它解析 `scripts/*.ps1` 的 PowerShell AST，检查关键规则、契约样例、上下文配置、仓库地图脚本引用，以及 Git 跟踪和未跟踪未忽略 Markdown 的合并冲突标记和本地相对链接。DOCX 会检查 OOXML ZIP 结构、必需部件、路径安全、宏部件和 XML 完整性。已跟踪但工作区删除的文档会跳过正文读取，但其他文档指向它的本地链接仍会失败，关键文件仍必须存在。`scripts/test-build-parallel.ps1` 不依赖 Pester，在 `target` 下用临时 Maven 包装器验证默认串行、显式并发、隔离仓库、失败退出码、原始日志、失败后产物不变和严格离线 `-o`。

纯文档变更需要变更集非空、分类可信且每一项都是安全路径下的小写 `.md` 或 `.docx`。Markdown 规则适用于所有 Markdown 文档，包括 `AGENTS.md` 和 `.codex/agents/**` 角色卡；它们仍必须通过关键规则和本地链接检查。

DOCX 结构校验不判断文字内容和页面排版。实际修改 DOCX 内容或排版时，仍须渲染受影响页面并核对版式，不能把包结构通过当作视觉验收通过。

`gate.ps1` 会先无条件调用 `scripts/doc-gate.ps1`。当变更是纯 Markdown/DOCX 文档时，`gate.ps1` 在文档门禁成功后直接退出，不运行 Maven、Node 或 Python 测试；空变更集、其他文件类型、混合变更或分类不确定的变更继续完整门禁。完整分支使用标准 Python `jsonschema` 校验契约样例，并执行模块化 Maven 测试与打包、框架与模块 UI 语法、前端 Node 测试，同时检查物理删除调用、业务样式硬编码色值、固定模块映射和固定业务端点。`-DocumentOnly` 要求变更集必须是非空纯 Markdown/DOCX，否则失败且不启动构建。`modularity.ps1` 通过移动源码和重启宿主，验证全业务模块、移除单模块、零业务模块（保留 `module_system_control`）、恢复、临时模块自动发现与可逆移出、模块 UI/查询/计划规则、动态主题与已选主题失效回退；结束后恢复原生模块源码，并清理 `target` 验收临时目录。服务启动后的端到端 HTTP 烟测由 `scripts/smoke.ps1` 完成。

### 完整 Windows 发布与离线源码包

正式发布提供三类资产：`Orderly-0.1.0.exe` 当前用户安装器、`Orderly-0.1.0-full-app.zip` 完整应用归档，以及 `Orderly-0.1.0-offline-source.zip` 离线源码包；每个资产都有同名 `.sha256`。安装器和完整应用归档都内置 Java 运行时、JDK 21、Maven、Maven 仓库、AI 可执行文件、模块源码与已构建模块 JAR。完整版和源码包必须来自同一个干净 Git 提交和同一源码树摘要。

完整版构建需要 WiX Toolset 3.x；普通用户不需要安装 WiX。构建脚本会复制 Git 跟踪源码，使用 PyInstaller 生成 AI 可执行文件，并使用 jpackage 生成应用镜像和当前用户安装器。正式发布要求干净 Git 工作树，构建物应放在仓库外，例如 `D:\Orderly-artifacts\0.1.0`：

```powershell
.\scripts\package-full.ps1 -OutputDirectory D:\Orderly-artifacts\0.1.0
.\scripts\verify-full.ps1 D:\Orderly-artifacts\0.1.0\staging\Orderly
```

仅验证应用目录和 ZIP、暂不生成 `setup.exe` 时可使用 `-SkipInstaller`。只用于本地脏工作树验收时，可同时使用 `-AllowDirtySnapshot`；该清单会记录 `gitDirty=true`，不属于正式发布。`package-full.ps1` 默认执行 Java/Maven 测试，`-SkipTests` 只用于已经在同一提交通过完整门禁的 CI 发布链。

`verify-full.ps1` 会核对 `full-runtime-manifest.json`、`files.sha256`、宿主 JAR、AI 可执行文件、模块 JAR、内置 JDK/Maven 和完整应用目录，然后移除外部 `JAVA_HOME` 并使用最小 PATH 实际启动 `Orderly.exe`。验证链路会检查宿主存活、Python AI 代理、业务/系统模块、目录主题和正常退出，并确认退出后没有残留进程引用应用目录。`-SkipLaunch` 只执行结构与哈希验证。

轻量 `Orderly-0.1.0-runtime.zip` 打包脚本仍保留用于内部兼容和运行包回归，但它要求外部 JDK 21 与 Python 3.14，不再是普通用户的主发布资产。完整版构建与离线源码包完成后，使用以下命令核对版本、提交和源码树摘要：

```powershell
.\scripts\verify-full-release-pair.ps1 `
  D:\Orderly-artifacts\0.1.0\staging\Orderly `
  .\target\orderly-offline\Orderly-0.1.0-offline-source.zip
```

离线源码包默认要求 Git 工作树干净。维护者可通过以下命令在 `target/orderly-offline` 中从空 Maven 仓库执行一次在线预热构建和 Java/Maven 测试，再生成包含源码、`maven-repository`、`maven-manifest.json`、`files.sha256`、第三方制品清单和许可风险提示的 ZIP。源码路径只来自 Git 索引，新增文件必须先显式加入索引。打包脚本不代替发布步骤，正式 ZIP 必须由维护者上传到对应 Git Tag 的 GitHub Release：

```powershell
.\scripts\package-offline.ps1
.\scripts\verify-offline.ps1 .\target\orderly-offline\Orderly-0.1.0-offline-source.zip
```

仅用于本地验收脏工作树时，可使用 `.\scripts\package-offline.ps1 -AllowDirtySnapshot`。该包会在 manifest 中记录 `gitDirty=true`，不属于正式干净发布；正式包的 `gitDirty` 必须为 `false`。`sourceTreeSha256` 是对排序后的源码路径、文件大小和 SHA-256 组成规范文本计算的稳定摘要，`sourceFiles` 同时记录每个源码文件的大小和哈希。

解压正式离线包后，直接运行根目录的 `启动归序.cmd`。该入口发现同目录存在 `maven-repository` 时，会无参数调用 `start.ps1 -MavenOffline -BackgroundModules`，因此 Maven 依赖和构建插件只从包内仓库读取，并先打开框架、再逐个准备业务模块。需要自定义端口等启动参数时，可显式执行：

```powershell
.\scripts\start.ps1 -MavenOffline -BackgroundModules -Port 8081
```

验证脚本要求 ZIP 旁存在同名 `.sha256`，会先拒绝禁止路径，再安全解压到 `target/orderly-offline/verify-*`，双向核对源码、POM、Maven 仓库和第三方 JAR/POM 集合及大小、SHA-256、重解析点，最后在包内运行 `build.ps1 -Offline -MavenRepository <包内 maven-repository>`；可通过 `verify-offline.ps1 -MaxParallel <int>` 显式启用模块并行。验证只执行 Maven 构建与 Java 测试，不要求或运行 Node/Python 测试。离线范围仍仅限 Maven，不代表整个应用可以离线安装。

正式离线包的空仓库预热仍由 `package-offline.ps1` 以默认串行方式执行，因此模块依赖会写入同一个共享 Maven 仓库，随后被原样收集进包的 `maven-repository/`。只有在验证阶段显式传入 `-MaxParallel > 1` 时，包内构建才在解压目录的 `target/build-parallel-*` 下创建 worker 隔离仓库，并以包内共享仓库作为只读 tail；该临时目录随验证目录一起清理。第三方清单中的许可状态固定为 `NOT_DETERMINED`，不是法律或许可兼容性结论。

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
- `ai/`：唯一的 Python AI 实现，负责模型配置与临时 Key、模型请求、提示词、会话、`ACTIONS / PLAN / QUERY / QUESTION / VIEW` 解析和 Query 展示整理；不持有文件权限或执行能力。
- `app/frontend/`：静态本地界面。
- `Module/module_fs/`、`module_todo/`、`module_calendar/`：各自携带契约、查询、计划规则、执行器、逆操作和 `orderly-ui/` 资源；构建脚本会自动发现新的 `Module/module_*`。模块规范见 `Module/AGENTS.md`。
- `Module/module_study_assistant/`、`module_study_map/`、`module_study_review/`：只读学习界面参考模块，每个模块从自身 JAR 的 `orderly-data/<moduleId>.json` 返回数据，不包含动作或跨模块读取。
- `Module/module_system_control/`：唯一的受保护控制模块，只提供模块治理入口，不作为业务模块出现在 `/api/modules`。
- `contracts/`：计划、接口和样例的唯一真相源。
- `themes/`：目录主题包，只包含清单、语义变量、动效 CSS 与登记资源，不包含界面结构或 JavaScript。
- `scripts/`：构建、启动、门禁与烟测。

### 模块运行时边界

宿主不在编译期依赖任何具体业务模块，也不把模块打进自己的 fat JAR。启动时，宿主按文件名稳定排序扫描外部模块目录，用 `URLClassLoader`（父加载器共享 kernel SPI）读取每个 JAR 的 `orderly-modules/*.json`，再通过 `ServiceLoader` 绑定执行器；契约与执行器集合不一致、重复模块、重复动作、重复查询或无效计划规则都会阻止启动。`module_system_` 仅供宿主内置受保护模块使用，必须是 `system.protected=true` 且命中宿主准入清单；当前必须恰好加载一个 `MODULE_CONTROL`，即 `module_system_control`。零业务模块仍合法。

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
GET   /api/system/module-catalog
POST  /api/system/module-catalog/install
GET   /api/system/module-catalog/install/{jobId}
GET   /api/system/module-removals
POST  /api/system/module-removals
POST  /api/system/module-removals/undo
```

`module-folder/open` 只接受 `X-Orderly-Action: open-module-folder`，只在 Windows 上打开配置的 `orderly.module-source-dir`，不接受客户端路径。模块查询、计划规则、计划生成、确认和执行都检查 `enabled`；执行中拒绝停用，回滚始终允许。开关、设置、排序、刷新和打开目录会写入审计事件。

模块下载读取固定官方 `raw.githubusercontent.com` 分发分支的 `catalog.json`。安装请求只提交 `moduleId` 并携带 `X-Orderly-Action: install-module`；宿主拒绝全部 `module_system_*`、已存在模块、不安全路径、摘要不符、超限 ZIP、多根目录和非法契约。校验通过后源码原子写入 `Module/`，后台监督器完成本地 Maven 构建并调用刷新接口追加。新模块无需重启；已有模块更新、替换和卸载仍要求重启。

删除只作用于非系统业务模块，并要求 `X-Orderly-Action: remove-module`。宿主先把源码移入 `.orderly-data/trash/modules`、停用模块并登记 `PENDING_RESTART`；当前 JVM 不热卸载已加载 JAR，下一次启动会跳过并清理该 JAR。重启前可用 `X-Orderly-Action: undo-module-removal` 撤销并恢复原源码与启用状态。删除不会物理清除模块回收站中的内容，也不会删除模块工作区数据。

`GET /api/system/build-settings` 返回后台模块构建并发和允许范围。`PUT /api/system/build-settings` 只接受带 `X-Orderly-Action: update-build-settings` 的请求，`maxParallel` 必须是 1-4 的整数；改变只影响后续 worker 调度，不终止已经在构建的模块。

`POST /api/system/shutdown` 只接受 `X-Orderly-Action: shutdown`。存在执行中的 Action 或计划时返回 `HOST_EXECUTION_IN_PROGRESS`；接受后返回 `202` 和 `STOPPING`，随后由启动脚本清理相关子进程。

业务 Action 和 Query 不在模块契约中声明静态 AI 权限。宿主在 `.orderly-data/module-governance.json` 中保存用户级 `actionAiCallableEnabled` 与 `queryAiCallableEnabled`，所有业务接口默认开启且可由 `module_system_control` 动态切换；系统模块自身不参与业务接口治理。动态开关只限制 AI：Action 影响 AI 计划与 AI 直执，Query 影响已接入的 AI Query，模块自身查询和总览不会受影响。独立 AI 计划引用中的 Action 显示“使用中”，确认时原子转换为整次执行占用，并在结束或失败后释放；过期提案也会释放。旧版计划不参与。

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
`POST /api/ai/turns` 必须携带 `clientRequestId`；同一设置代次、会话和请求 ID 的重复提交返回首次结果，不会再次调用模型或重复执行写入。模块 Query 可声明 `outputSchema` 与 `display`；受限 Query 结果会始终回传 Python，Python 可返回只描述展示的 `VIEW`，非法或缺失时前端回退到模块声明或通用可读视图。

`sidebarOrder` 和 `managementOrder` 都必须完整包含当前业务模块和 `module_system_control` 的 ID。`module_system_control` 默认位于最后，但用户可以在侧边栏和模块设置列表中拖动它；旧治理文件中的 `module_control` 顺序 ID 会在读取时迁移为新 ID。两份排序独立持久化且不得重复。

运行中刷新只允许显式追加新模块：扫描 `Module/module_*` 源码，并要求 `target/orderly-modules/` 已有对应 JAR。未构建源码返回 `MODULE_NOT_BUILT`；已有模块的源码或 JAR 更新、删除返回 `RESTART_REQUIRED`，当前运行快照保持不变。刷新接口本身不编译；模块下载链路由独立监督器调用 `build.ps1 -ModulesOnly` 完成构建后再请求刷新，不会替换或卸载已有模块。

模块查询统一通过 `GET /api/modules/{moduleId}/query/{queryId}`，模块 UI 只从 JAR 内 `orderly-ui/module.json`、声明的脚本与样式，以及 `icons` 白名单中的单色 SVG/PNG 提供，资源接口为 `GET /api/modules/{moduleId}/ui/{asset}`。前端按模块 UI 清单生成侧栏、总览卡片和视图；单个模块的 UI、图标或查询失败只影响该模块。

学习相关只读数据由 `contracts/study-data.schema.json` 约束，并通过 `scripts/validate-study-data.mjs` 校验。公共字段声明 `ownerModule`、`readOnly: true`、来源和计数，负载按 `assistant`、`materials`、`knowledge-map`、`review` 四类校验引用完整性。数据分别位于模块自身 JAR 的模块唯一资源名下，不建立集中数据目录；模块只能调用自身声明的查询。

模块 JAR 是应用有权执行的本地文件，属于**本地受信任可执行代码**，不是安全沙箱。远程模块只允许从配置中的官方 GitHub 分发分支读取源码 ZIP，并校验 SHA-256、路径和模块契约；当前仍不提供发布者签名、证书链或代码隔离，只应安装自己信任的模块。零业务模块时应用仍可启动，`/api/modules` 返回空数组，侧栏与总览保持空状态；`module_system_control` 缺失时构建和启动都会失败。

主题从配置的 `themes/` 目录按直接子目录加载，不使用 classpath 回退，也不进入宿主 fat JAR。每个包包含 `theme.json`、清单登记的 `tokens.css` 与 `motion.css`，可选 `assets/`；添加或移除目录后重启宿主生效，不需要重新构建，首版不支持热加载或主题 JavaScript。

`GET /api/themes` 返回 `{items, issues, builtInFallbackActive}`。单个主题版本不支持、资源缺失或路径非法只产生局部问题；重复 id 和多个默认主题仍阻止启动。`GET /api/themes/{id}` 返回完整清单元数据，`GET /api/themes/{id}/assets/{asset}` 只提供登记白名单内的 CSS 和安全静态资源。前端回退顺序固定为当前可用主题、可用默认主题、内置安全外观，不会选择排序第一项兜底。主题制作说明见 `themes/README.md`。

## 首版边界

这是可运行的受控工作流切片，不是 V7 的最终发行版。真实模型需要用户临时输入 API Key；没有可用 Key 时 DeepSeek 实际连通性属于未验证项。完整版已经支持 Windows 当前用户安装，但尚未支持任意个人目录选择、重复文件哈希清理、提醒调度、云备份、自动更新和代码签名。学习相关模块是界面参考扩展，不代表 V7 已承诺通用 AI 学习业务；其中上传、AI 整理、任务记录、自动出题和自动阅卷仍未接入。计划定义与 AI 会话保存在进程内存中；执行记录、步骤快照、输出和事件会持久化，已完成执行可跨重启查询和回滚。目录边界、离线行为和功能完成度必须在界面中如实展示，不能把规则匹配或演示数据描述为完整 AI 能力。

模块注册完全由 `Module/module_*` 源码扫描和 `target/orderly-modules/` 中的 JAR 发现完成，不维护单独的模块索引文件，也不会把模块变成 Maven reactor 成员。当前模块分发风险仍位于可执行代码信任模型：仓库没有发布者签名或权限隔离，不支持覆盖更新、替换或卸载；运行中只允许通过受控下载与本地构建追加全新业务模块。
