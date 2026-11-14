# 契约目录

`plan.schema.json` 定义计划与澄清问题的公开结构，`module.schema.json` 定义模块契约，`module-ui.schema.json` 定义模块自带 UI 清单，`theme.schema.json` 定义目录主题包的 `theme.json`，`study-data.schema.json` 定义学习相关模块的只读数据边界，`samples/` 提供前端和测试共享的示例。

`samples/valid/module.json`、`module-control.json` 与模块 UI 样例是宿主和前端实际校验逻辑接受的合法样例；`samples/invalid/` 中的模块契约和 UI 清单会被对应逻辑明确拒绝。`gate.ps1` 同时执行 Java 模块契约样例测试与 Node 模块 UI 样例测试，防止 schema、样例和运行时规则漂移。

模块契约的可选 `system` 元数据只允许 `role: "MODULE_CONTROL"` 与 `protected: true`。全运行集必须恰好有一个 `module_control` 系统模块；零业务模块合法，但缺少控制模块或重复系统角色必须阻止构建或启动。

模块 UI 的可选 `settings` 支持对象形式 `{ "fields": [...] }` 或字段数组，字段类型为 `boolean`、`integer`、`select`、`text`。宿主会拒绝重复 key、类型错误、select 默认值不在 options、重复 option value，以及 integer/text 范围非法。模块设置的当前值由 `/api/system/module-preferences` 返回，并保存在宿主单一治理 JSON 中。

`samples/valid/theme.json` 与 `samples/invalid/theme-*.json` 覆盖目录主题的合法清单、不支持的 `schemaVersion`、非法 id、越界样式路径、非 CSS 样式路径和不可接受的 SVG 主动内容。宿主运行时必须同时登记 `tokens.css` 与 `motion.css`，并且只能读取清单登记的资源。

模块契约由各模块 JAR 内的 `orderly-modules/*.json` 提供，宿主在启动时通过统一模式聚合。动作风险、参数与确认词、只读查询和本地计划规则只允许在这些契约中声明，Java 执行器不保存第二套元数据。`GET /api/modules` 只返回业务模块；`GET /api/system-modules` 返回系统模块。查询通过通用接口 `GET /api/modules/{moduleId}/query/{queryId}` 调用，框架不包含具体业务端点。

模块 UI 清单位于同一 JAR 的 `orderly-ui/module.json`，并把脚本与样式限制为同目录内的安全资源名。宿主仅通过 `GET /api/modules/{moduleId}/ui/{asset}` 提供 `module.json`、`module.js` 和 `module.css`，不接受目录穿越或任意 classpath 资源。删除业务模块源码并重新构建后，模块 JAR、契约、查询、侧栏、总览卡片和前端视图必须一起消失；零业务模块是合法状态，但系统模块 `module_control` 必须保留。

运行中刷新只允许显式追加新模块。新源码目录必须已有同名 JAR，宿主不会自动编译；未构建返回 `MODULE_NOT_BUILT`。已有模块更新或删除返回 `RESTART_REQUIRED`，不会替换当前运行快照。API 约定见根 README。

`trash` 是文件模块唯一的删除语义：对象从工作区移动到 `.orderly-data/trash`，从不执行物理删除。

学习数据由 `module_study_assistant`、`module_study_map`、`module_study_review` 与 `module_fs` 各自保存在模块 JAR 的 `orderly-data/<moduleId>.json` 中，不建立集中式学习数据目录。资源名必须使用模块 ID，避免宿主聚合类加载器把多个 JAR 的同名资源解析到同一份数据。每条数据必须声明自身 `ownerModule`、`readOnly: true` 和以下四种 `kind` 之一：`assistant`、`materials`、`knowledge-map`、`review`。模块只能通过自己声明的只读查询访问自身资源；知识图的边端点必须存在于同一份数据的节点集合，AI 助手数据不得声称连接外部模型，复习数据不得包含自动阅卷能力。

主题不使用扁平 JSON，也不回退 classpath。`GET /api/themes` 返回可用项、局部问题与默认主题配置状态；`GET /api/themes/{id}` 返回完整清单元数据；`GET /api/themes/{id}/assets/{asset}` 只提供清单白名单内的 CSS、栅格图与字体，不提供 SVG。
