# 契约目录

**`plan.schema.json`** 定义计划与澄清问题的公开结构，**`execution-history.schema.json`** 定义可跨重启读取的执行历史摘要及宿主回滚结论，**`module.schema.json`** 定义模块契约，**`module-ui.schema.json`** 定义模块自带 UI 清单，**`module-summary.schema.json`** 定义模块资料摘要的相对路径请求与安全响应，**`module-catalog.schema.json`** 定义 GitHub 模块分发目录，**`module-build-status.schema.json`** 定义后台模块构建状态，**`module-build-priority-request.schema.json`** 定义受限优先构建请求，**`build-settings.schema.json`** 定义构建设置并发及允许范围，**`host-shutdown-response.schema.json`** 定义本地宿主安全退出的稳定响应，**`theme.schema.json`** 定义目录主题包的 `theme.json`，**`study-data.schema.json`** 定义学习相关模块的只读数据边界，**`samples/`** 提供前端和测试共享的示例。

**`samples/valid/module.json`、`module-system-control.json`、`module-system-service.json` 与模块 UI 样例**是宿主和前端实际校验逻辑接受的合法样例；`samples/invalid/` 中的模块契约和 UI 清单会被对应逻辑明确拒绝。模块 JAR 契约不得声明静态 `aiCallable`，其属性在 `module.schema.json` 中因 `additionalProperties: false` 被拒绝；动态用户开关不属于模块 JAR 契约。`gate.ps1` 同时执行 Java 模块契约样例测试与 Node 模块 UI 样例测试，防止 schema、样例和运行时规则漂移。

**模块契约的可选 `system` 元数据**支持 `MODULE_CONTROL` 与 `SYSTEM_SERVICE`，并要求
`protected: true` 与 `capabilities`。控制模块必须声明 `MODULE_GOVERNANCE`，系统服务
不得声明该能力。全运行集必须恰好有一个 `module_system_control` 控制模块；所有系统
模块必须使用 `module_system_` 前缀并命中宿主内置准入清单，普通业务模块不得占用该
前缀。零业务模块合法，但缺少控制模块或重复控制角色必须阻止构建或启动。

**模块 UI 的可选 `settings`** 支持对象形式 `{ "fields": [...] }` 或字段数组，字段类型为 `boolean`、`integer`、`select`、`text`。宿主会拒绝重复 key、类型错误、select 默认值不在 options、重复 option value，以及 integer/text 范围非法。模块设置的当前值由 `/api/system/module-preferences` 返回，并保存在宿主单一治理 JSON 中。

**模块 UI 的可选 `icons` 映射**用于声明模块自带单色图标，键必须是小写图标 ID，值必须是 `icons/<安全文件名>.svg` 或 `.png`。宿主只发布清单中登记且 JAR 内实际存在的图标，单枚上限 256 KiB；未声明的路径、目录穿越和任意 classpath 资源均被拒绝。未提供本地图标时，`view.icon` 与 `card.icon` 继续使用框架内置语义图标。

**执行历史摘要**只由宿主生成。`canRollback` 是宿主对持久化步骤、执行状态和回滚策略的唯一结论；合法摘要必须能展示目标、来源、步骤数和时间，允许回滚时 `rollbackDisabledReason` 必须为空，禁止回滚时必须给出具体原因。

**`samples/valid/theme.json` 与 `samples/invalid/theme-*.json`** 覆盖目录主题的合法清单、不支持的 `schemaVersion`、非法 id、越界样式路径、非 CSS 样式路径和不可接受的 SVG 主动内容。宿主运行时必须同时登记 `tokens.css` 与 `motion.css`，并且只能读取清单登记的资源。

---

**模块契约由各模块 JAR 内的 `orderly-modules/*.json` 提供**，宿主在启动时通过统一模式聚合。动作风险、参数与确认词、只读查询和本地计划规则只允许在这些契约中声明，Java 执行器不保存第二套元数据。Query 可选声明根类型为 `object` 的 `outputSchema` 和用于布局、字段标签及空状态的 `display`；展示提示不得修改 payload 或执行动作。`GET /api/modules` 只返回业务模块；`GET /api/system-modules` 返回系统模块。查询通过通用接口 `GET /api/modules/{moduleId}/query/{queryId}` 调用，框架不包含具体业务端点。

**模块 UI 清单位于同一 JAR 的 `orderly-ui/module.json`**，并把脚本、样式与图标限制为同目录内的安全资源名。宿主仅通过 `GET /api/modules/{moduleId}/ui/{asset}` 提供 `module.json`、清单声明的脚本、样式和 `icons/` 资源，不接受目录穿越或任意 classpath 资源。删除业务模块源码并重新构建后，模块 JAR、契约、查询、图标、侧栏、总览卡片和前端视图必须一起消失；零业务模块是合法状态，但系统模块 `module_system_control` 必须保留。

**运行中刷新只允许显式追加新模块。**新源码目录必须已有同名 JAR，宿主不会自动编译；未构建返回 `MODULE_NOT_BUILT`。已有模块更新或删除返回 `RESTART_REQUIRED`，不会替换当前运行快照。API 约定见根 README。

---

**模块分发目录**只包含经发布脚本生成的业务模块源码包。`moduleId` 必须以 `module_` 开头且禁止全部 `module_system_*` 保留名称，包路径只能是分发目录下的相对 `.zip` 路径，包大小不得超过 20 MiB，并必须提供 SHA-256。宿主读取目录时仍会重新检查模块 ID、包路径、摘要、ZIP 结构和模块契约，不能因为目录是官方分支就跳过校验。

**后台模块构建状态**只由宿主构建链路写入，模块和前端不能自行声明 `READY`。状态中的源码名必须直接对应 `Module/module_*` 目录，禁止路径、空段和保留名称。优先构建请求只能引用已发现的业务模块源码名；构建脚本仍按自身队列决定何时生效，不承诺抢占已经运行的 Maven 任务。

**模块构建设置**只由宿主持久化和裁决。后台默认同时运行 2 个 Maven worker，设置范围为 1-4；worker 只产出候选 JAR，单一线程负责状态文件、原子发布和模块刷新。修改并发不会终止已经运行的 worker。

**宿主退出接口**只接受带 `X-Orderly-Action: shutdown` 的本地 POST 请求。存在执行中的 Action 或计划时拒绝退出；接受后先返回 `STOPPING`，再异步停止 Java 宿主，由启动脚本清理 AI 与后台模块构建进程。

---

**`trash` 是文件模块唯一的删除语义：**对象从工作区移动到 `.orderly-data/trash`，从不执行物理删除。

---

**学习数据**由 `module_study_assistant`、`module_study_map`、`module_study_review` 与 `module_fs` 各自保存在模块 JAR 的 `orderly-data/<moduleId>.json` 中，不建立集中式学习数据目录。资源名必须使用模块 ID，避免宿主聚合类加载器把多个 JAR 的同名资源解析到同一份数据。每条数据必须声明自身 `ownerModule`、`readOnly: true` 和以下四种 `kind` 之一：`assistant`、`materials`、`knowledge-map`、`review`。模块只能通过自己声明的只读查询访问自身资源；知识图的边端点必须存在于同一份数据的节点集合，AI 助手数据不得声称连接外部模型，复习数据不得包含自动阅卷能力。

---

**主题**不使用扁平 JSON，也不回退 classpath。`GET /api/themes` 返回可用项、局部问题与默认主题配置状态；`GET /api/themes/{id}` 返回完整清单元数据；`GET /api/themes/{id}/assets/{asset}` 只提供清单白名单内的 CSS、栅格图与字体，不提供 SVG。
