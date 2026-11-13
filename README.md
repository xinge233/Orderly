# 归序 Orderly

归序是一个本地优先、可审查、可中止、可回滚的个人文件与事务工作流程序。本仓库当前提供首版垂直切片：用户先用自然语言描述目标，系统只生成结构化计划；计划确认后才执行真实模块动作；删除只会进入项目回收站；每个已执行步骤都可撤销。

## 当前可用能力

- 本地规则计划器：把少量明确的中文指令转换为结构化计划。它不连接外部大模型，界面会如实标注这一限制。
- 自包含模块：每个 `Module/module_*` 在自身目录携带动作契约、只读查询、本地计划规则、执行器、逆操作和 UI 资源；宿主与前端不保存具体模块映射。
- 模块控制：唯一的系统模块 `module_control` 管理系统业务模块的启停、侧边栏顺序、管理顺序和声明式设置。
- 文件模块：列出沙箱目录、创建目录、移动文件、移入回收站、从回收站恢复。
- 待办模块：读取并创建待办。
- 日程模块：读取并修改已有日程的时间。
- 中央控制：计划先行、风险分级、服务端确认、路径边界、快照、事件时间线、逆序整单回滚。
- 本地界面：动态模块侧栏与总览、彼此独立的可持久化卡片和侧边栏排序、计划预览、执行时间线、结果摘要、设置弹窗和右下角指令入口。

## 启动

环境要求：JDK 21、Maven 3.9+。脚本显式使用 `JAVA_HOME\bin\java.exe`，因此必须把 `JAVA_HOME` 指向 JDK 21；即使 PATH 中还有另一个 Java 版本，也不会用于构建或启动。

```powershell
.\scripts\start.ps1
```

首次启动前，`start.ps1` 会调用 `scripts/build.ps1`：自动扫描 `Module/module_*`，逐个构建模块 JAR 到 `target/orderly-modules/`，再构建宿主 fat JAR。构建会先清空运行模块暂存目录，因此删除模块源码后不会继续加载陈旧 JAR。可用以下命令单独构建并以已有产物启动：

```powershell
.\scripts\build.ps1
.\scripts\start.ps1 -SkipBuild
```

应用只监听 `127.0.0.1:8080`，打开 <http://127.0.0.1:8080/>。

程序首次运行会在仓库根目录创建 `.orderly-data/`：

- `.orderly-data/workspace/`：隔离演示工作区。
- `.orderly-data/trash/`：应用回收站。
- `.orderly-data/state/`：各模块自己的集合状态、初始化标记和执行记录。
- `.orderly-data/module-governance.json`：业务模块启用状态、两套排序和模块设置；使用原子文件替换写入。

不会扫描或修改其他个人目录。首版可以通过 `ORDERLY_DATA_DIR` 环境变量改用另一个应用专用目录，但仍不支持选择任意系统文件夹。

## 演示

首次启动时，当前安装的模块可以各准备一次示例文件、待办和日程；用户清空后重启不会再次播种。可以尝试：

```text
创建文件夹 竞赛材料
```

```text
移动 收件箱/示例笔记.txt 到 竞赛材料
```

```text
删除 待整理/草案.txt
```

删除计划需要在界面中键入确认词“删除”。执行后可以点击“撤销本次任务”，验证文件、待办和日程的逆序恢复。

## 验证

```powershell
.\scripts\gate.ps1
.\scripts\modularity.ps1
```

`gate.ps1` 执行模块化 Maven 测试与打包、框架与模块 UI 语法、前端 Node 测试，并检查物理删除调用、业务样式硬编码色值、固定模块映射和固定业务端点。`modularity.ps1` 通过移动源码和重启宿主，验证全业务模块、移除单模块、零业务模块（保留 `module_control`）、恢复、临时模块自动发现与可逆移出、模块 UI/查询/计划规则、动态主题与已选主题失效回退；结束后恢复原生模块源码，并清理 `target` 验收临时目录。服务启动后的端到端 HTTP 烟测由 `scripts/smoke.ps1` 完成。

## 架构

- `app/kernel/`：模块 SPI 与跨端数据契约。
- `app/host/`：授权、校验、调度、快照、回滚、事件和 HTTP/SSE 外壳，不实现具体业务。
- `app/frontend/`：静态本地界面。
- `Module/module_fs/`、`module_todo/`、`module_calendar/`：各自携带契约、查询、计划规则、执行器、逆操作和 `orderly-ui/` 资源；构建脚本会自动发现新的 `Module/module_*`。
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
PUT   /api/modules/{moduleId}/settings
POST  /api/modules/{moduleId}/settings/reset
POST  /api/system/modules/refresh
POST  /api/system/module-folder/open
```

`module-folder/open` 只接受 `X-Orderly-Action: open-module-folder`，只在 Windows 上打开配置的 `orderly.module-source-dir`，不接受客户端路径。模块查询、计划规则、计划生成、确认和执行都检查 `enabled`；执行中拒绝停用，回滚始终允许。开关、设置、排序、刷新和打开目录会写入审计事件。

`sidebarOrder` 和 `managementOrder` 都必须完整包含当前业务模块和 `module_control` 的 ID。`module_control` 默认位于最后，但用户可以在侧边栏和模块设置列表中拖动它；两份排序独立持久化且不得重复。

运行中刷新只允许显式追加新模块：扫描 `Module/module_*` 源码，并要求 `target/orderly-modules/` 已有对应 JAR。未构建源码返回 `MODULE_NOT_BUILT`；已有模块的源码或 JAR 更新、删除返回 `RESTART_REQUIRED`，当前运行快照保持不变。刷新不会自动编译，也不会替换或卸载已有模块。

模块查询统一通过 `GET /api/modules/{moduleId}/query/{queryId}`，模块 UI 只从 JAR 内 `orderly-ui/module.json`、`module.js` 和 `module.css` 提供，资源接口为 `GET /api/modules/{moduleId}/ui/{asset}` 且只接受白名单文件名。前端按模块 UI 清单生成侧栏、总览卡片和视图；单个模块的 UI 或查询失败只影响该模块。

模块 JAR 是应用有权执行的本地文件，属于**本地受信任可执行代码**，不是安全沙箱。当前不提供远程插件下载、签名校验或权限隔离；只应放入自己信任的模块。零业务模块时应用仍可启动，`/api/modules` 返回空数组，侧栏与总览保持空状态；`module_control` 缺失时构建和启动都会失败。

主题从配置的 `themes/` 目录按直接子目录加载，不使用 classpath 回退，也不进入宿主 fat JAR。每个包包含 `theme.json`、清单登记的 `tokens.css` 与 `motion.css`，可选 `assets/`；添加或移除目录后重启宿主生效，不需要重新构建，首版不支持热加载或主题 JavaScript。

`GET /api/themes` 返回 `{items, issues, builtInFallbackActive}`。单个主题版本不支持、资源缺失或路径非法只产生局部问题；重复 id 和多个默认主题仍阻止启动。`GET /api/themes/{id}` 返回完整清单元数据，`GET /api/themes/{id}/assets/{asset}` 只提供登记白名单内的 CSS 和安全静态资源。前端回退顺序固定为当前可用主题、可用默认主题、内置安全外观，不会选择排序第一项兜底。主题制作说明见 `themes/README.md`。

## 首版边界

这是可运行的受控工作流切片，不是 V7 的最终发行版。尚未接入真实大模型、任意个人目录选择、重复文件哈希清理、提醒调度、云备份、安装包和自动更新。计划与快照目前保存在进程内存中，重启后不能继续操作旧计划；审计事件会持久化。目录边界、离线行为和功能完成度必须在界面中如实展示，不能把演示数据或规则匹配描述为完整 AI 能力。

模块注册完全由 `Module/module_*` 源码扫描和 `target/orderly-modules/` 中的 JAR 发现完成，不维护单独的模块索引文件，也不会把模块变成 Maven reactor 成员。当前扩展性风险位于模块 JAR 的本地信任模型：仓库不校验来源或签名，且不提供远程分发、自动热加载、替换或卸载；运行中只允许显式追加已构建的新模块。
