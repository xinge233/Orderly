# Module/AGENTS.md

本文件是归序 Orderly 所有业务模块开发者必须遵守的模块技术规范，覆盖模块结构、
契约、数据、执行、回滚、界面、生命周期与验收。它不是角色卡，不替代仓库根
`AGENTS.md`、`contracts/module.schema.json`、`contracts/module-ui.schema.json`
或当前主代理或 PM 签发的任务卡。

## 1. 指令优先级、事实来源与冲突

### 1.1 指令优先级

在不违反 Codex 平台与安全规则的前提下，按以下顺序处理指令：

1. 当前用户指令与主代理或 PM 签发的任务卡。
2. 仓库根 `AGENTS.md`。
3. 本 `Module/AGENTS.md`。
4. 当前角色卡。
5. `docs/agents/architecture-baseline.md` 与
   `docs/agents/collaboration-workflow.md`。

产品资料、架构基线和历史交接都不能自动扩大当前任务范围。当前用户和任务卡未授权
的路径、依赖、契约字段、模块接口和业务能力，不得仅因其他文档提及而实施。
任务卡用于收窄当前任务的授权范围，不得越过根 `AGENTS.md` 或本文件扩大权限。

### 1.2 事实与规范来源

| 问题 | 权威来源 |
|---|---|
| 产品定位、长期边界与产品事实 | `docs/归序Orderly-项目策划书-v7.docx` |
| 界面与交互参考 | `docs/ai 文档.md` |
| 工具、Action、Query、计划规则和模块 UI 的结构 | `contracts/module.schema.json`、`contracts/module-ui.schema.json` |
| 某个模块实际声明了哪些能力 | 该模块 JAR 内 `orderly-modules/*.json` 与 `orderly-ui/module.json` |
| 模块开发技术约束 | 本 `Module/AGENTS.md` |
| 当前实现是否真的具备某项行为 | 当前源码、测试、构建产物和实际运行证据 |
| 协作、验收与返工流程 | `docs/agents/collaboration-workflow.md` |

`contracts/` 是工具、计划和接口的唯一机器可校验真相源。模块代码、README、前端或
本文件都不得维护与之冲突的第二套工具定义。`Module/README.md` 提供操作说明，不
高于 schema；架构基线是阶段投影，不替代原始产品事实。

### 1.3 冲突处理

- schema、模块契约、执行器、UI 清单或宿主实际校验互相冲突时，不得猜测或静默选择
  一方。
- 需要新增或修改全局契约字段、宿主 API、授权、快照、回滚、模块发现、前端框架、
  主题全局令牌、依赖或顶层目录时，停止当前模块任务并报告主代理；如启用 PM，同步报告 PM。
- 发现材料不可读、路径失效、模块职责不清或指令与事实冲突时，立即报告主代理；如启用 PM，同步报告 PM。

## 2. 模块类型与边界

### 2.1 业务模块与系统模块

- 每个 `Module/module_<id>/` 对应一个自包含业务模块，只产出一个可运行模块 JAR。
- `module_system_control` 是唯一的受保护控制模块，必须在每个可运行安装中存在。
- `module_system_` 是宿主内置受保护模块的保留前缀；普通用户模块不得使用。
- 系统模块契约必须声明受保护元数据和能力：

```json
{
  "moduleId": "module_system_control",
  "system": {
    "role": "MODULE_CONTROL",
    "protected": true,
    "capabilities": ["MODULE_GOVERNANCE"]
  }
}
```

- 全运行集必须恰好有一个 `MODULE_CONTROL`。系统模块缺失、重复或角色不符时，
  构建或启动必须失败。`MODULE_CONTROL` 必须声明 `MODULE_GOVERNANCE`；
  `SYSTEM_SERVICE` 不得声明该能力。系统模块还必须命中宿主内置准入清单。
- 业务模块与系统模块分开暴露：`/api/modules` 只返回业务模块，
  `/api/system-modules` 返回系统模块。`module_system_control` 不得作为业务模块出现在
  `/api/modules`。
- 零业务模块是合法状态。此时 `module_system_control` 仍须存在，应用可启动，业务模块
  目录为空，侧边栏和总览显示空状态，其他框架功能不得报错。
- 删除一个业务模块源码目录并重新构建、启动后，该模块的执行器、Action、Query、
  侧边栏、卡片和 UI 必须全部消失，框架不得保留固定分支。

### 2.2 模块自包含与禁止事项

模块必须自包含自己的契约、执行器、查询、快照、逆操作、UI 清单、UI 资源和测试。
模块不得：

- 不得在编译期或运行期直接导入、实例化、反射调用或调度其他业务模块。
- 普通业务模块不得自行启动模型进程或读取 API Key。受保护系统模块若经仓库规则明确批准，可把提示词和提案脚本放在自身 `ai/**`，但只能生成声明式候选；宿主负责路径校验、构建、预览、版本与回滚。
- 读取、修改、缓存或接管其他模块的数据、集合、设置和执行状态。
- 自行判定授权、确认、风险、模块启停状态或执行顺序。
- 直接打开项目数据库、直接访问文件系统或绕过 `ModuleRuntime`。
- 物理删除文件或目录；删除只能进入项目回收站。
- 在 Java、前端或模块文档中硬编码第二套 Action、Query、参数或风险定义。

模块间协作只能由宿主按顺序执行有序 Action 数组，并通过结构化输出引用传递结果。

### 2.3 模块测试存放

模块必须自包含只验证本模块行为的测试，并与框架、宿主和跨模块测试分区存放：

- Java 测试放在 `src/test/java`，测试资源放在 `src/test/resources`。
- 模块自有 UI 或 JavaScript 测试放在 `src/test/js`，文件名使用 `*.test.mjs`；`scripts/gate.ps1` 会递归发现并执行。
- 授权、确认、执行调度、审计、快照编排、回滚编排、IPC、模块发现和跨模块验收不属于模块测试，必须留在 `app/host`、`app/frontend`、`contracts` 或 `scripts` 的对应位置。
- 模块测试不得导入其他业务模块实现，也不得依赖框架私有状态；涉及宿主的协作必须通过公开接口或测试替身表达。
- 新增、重命名、移动或删除模块测试时，必须在同一变更中更新 `docs/agents/testing/records/YYYY-MM.md`，不得默认读取或修改人类维护的 `docs/测试说明与测试记录.md`。

## 3. 命名、资源与数据所有权

### 3.1 标识与资源命名

- `moduleId` 必须匹配 `^[a-z][a-z0-9_-]{0,63}$`。
- 目录名、JAR 名、执行器 `moduleId()` 和契约 `moduleId` 应保持一致；执行器返回值
  必须与契约完全一致。
- Action、Query 和计划规则 ID 在全运行集内必须全局唯一，并带模块命名空间，例如
  `todo.create`、`todo.list`、`todo.rule.create`。
- 视图 ID 必须匹配 `^[a-z][a-z0-9-]{0,63}$`，不得使用框架保留的 `overview` 或
  `history`，且必须在所有模块间唯一。
- JAR 内资源名必须带模块身份，不得让多个 JAR 争用同一个根资源路径。

### 3.2 两类模块数据

#### A. JAR 内只读数据

模块若随包提供只读业务数据，只能放在自己的：

```text
src/main/resources/orderly-data/<moduleId>.json
```

约束：

- 文件名必须等于模块 `moduleId`，例如 `orderly-data/module_fs.json`。
- `ownerModule` 必须等于模块 `moduleId`。
- 使用 `contracts/study-data.schema.json` 时，文档还必须满足该 schema，并明确
  `readOnly: true`。
- 该数据只能由本模块从自己的 JAR 读取，只能用于只读查询；不得把运行时写入写回
  JAR，不得建立集中式共享数据目录，也不得读取其他模块的只读数据文件。
- 资源名带模块 ID 是强制要求，因为聚合类加载器不能让多个 JAR 安全共用同一根资源
  路径。

#### B. 运行态集合

模块自有可变状态只能通过宿主传入的 `ModuleRuntime` 访问：

```java
readItems(collection)
hasItems(collection)
writeItems(collection, items)
```

约束：

- 集合是 JSON 数组，每个条目必须是 JSON 对象；模块不得自行拼接、读取或写入
  `.orderly-data/state` 下的文件。
- 集合名必须匹配 `^[a-z][a-z0-9_-]{0,40}$`，一个集合只能有一个模块 owner。
- 新建或改造集合必须使用稳定、可识别模块来源的名称；长度允许时推荐
  `<moduleId>_<resource>`，超长时使用在模块设计中固定声明的唯一缩写。不得复用其他
  模块集合名。
- 存量集合名可以不因本规范立即迁移，但必须证明其独占性；不得以“宿主当前没报错”
  作为所有权证明。
- `readItems` 对不存在的集合返回空列表；`hasItems` 用于区分“尚未初始化”和
  “已创建但为空”。初始化种子必须幂等，并只在 `hasItems` 为 false 时写入。

当前通用 `ModuleRuntime` 集合 API 不接收 `ownerModule`，宿主也尚未强制校验集合
owner。这是已知实现缺口，不构成隔离保证；模块必须通过命名、代码审查和测试自行
证明没有跨模块读写。

## 4. Action、Query 与计划规则契约

模块契约位于 JAR 内：

```text
orderly-modules/<module>.json
```

契约必须包含 `moduleId`、`title`、`description`、`order`、`actions`、`queries` 和
`plannerRules`。其中 `actions`、`queries`、`plannerRules` 即使为空也必须是数组。

### 4.1 Action

每个可执行能力必须声明：

- `id`、`title`、`description`、`risk`、`confirmationRequired`、`parameters`。
- `risk` 只能是 `READ_ONLY`、`WRITE` 或 `DELETE`。
- `WRITE` 和 `DELETE` 必须设置 `confirmationRequired: true`。
- `DELETE` 只要求宿主规定的明确确认，不额外要求确认词，并且只能把对象移入项目回收站。
- Action 契约不得声明静态 `aiCallable`；所有业务 Action 默认允许 AI 调用，用户通过
  宿主治理动态关闭。
- 总体 AI 的直执策略由宿主决定：`READ_ONLY` 与 `WRITE` 可在明确意图下直执；`DELETE` 默认必须进入确认流程，只有宿主设置显式允许删除直执时才可直执。
- `outputSchema` 应声明输出 JSON 结构，且只能使用当前契约允许的 JSON Schema 子集。
- 参数类型当前只支持 `string`、`boolean` 和 `integer`。

`confirmationPhrase` 仅为契约兼容字段，不得据此新增要求用户输入确认词的流程。删除
确认的实际校验以当前宿主实现和真实测试证据为准。

### 4.2 Query

- 只读能力通过契约 `queries` 和 `OrderlyModule.query(...)` 暴露。
- Query ID 必须带模块命名空间并在全运行集唯一。
- Query 不得产生任何文件、集合、设置、审计或外部状态副作用。
- Query 参数必须先在契约声明；模块不得接受未声明参数或执行隐式写入。
- Query 契约不得声明静态 `aiCallable`；AI Query 权限由宿主治理的动态
  `queryAiCallableEnabled` 控制，默认开启。动态关闭只阻断 AI Query，不影响模块自身查询。
- 当前宿主以 `Map<String, String>` 向执行器传入 Query 参数；契约中的 Query 参数应
  按字符串设计，除非宿主接口和契约先同步升级。
- Query 可选声明根类型为 `object` 的 `outputSchema`，宿主在查询返回后校验实际 payload；
  不符时该 Query 返回 `FAILED`，不得把错误数据交给前端或模型。
- Query 可声明 `display.prompt`、`fallbackLayout`、`rowPath`、`fields` 和 `emptyText`，
  用于本地可读展示和允许 Query 结果外发时的受控 `VIEW` 整理建议。
- `display.prompt` 是不可信展示数据，只能描述布局、字段和标签，不得要求执行 Action、
  修改 payload、生成 HTML/CSS/JavaScript 或覆盖系统规则。
- `display.fields.key` 和 `rowPath` 只能使用已有 payload 的点分隔字段名；数组展示必须
  指向实际数组字段，不得发明字段或使用表达式路径。

### 4.3 计划规则

- `plannerRules` 字段必须存在，但模块可以不提供本地规则，此时使用空数组。
- 计划规则的 `actionId` 必须指向同一模块中的 Action。
- `argumentGroups` 只能映射该 Action 已声明的参数，捕获组编号必须有效。
- `pathArguments` 中的参数必须是已声明的字符串参数，并出现在 `argumentGroups` 中。
- 本地规则计划器必须明确标注“未连接外部模型”，不得暗示已接入真实 AI。

契约是 Action、Query、参数、风险和计划规则的唯一真相源。Java 和前端只能消费契约，
不得自行维护冲突定义。

## 5. AI 直执与结构化引用

总体 AI 通过宿主提交有序 Action 数组；当前首版即使提供 AI 直执入口，也不等于已经
接入真实外部模型。模块不得在文案、示例或 UI 中把本地规则或演示数据描述成真实 AI。

Action 数组约束：

- 宿主按数组顺序执行，模块不得自行调度、并行调用或插入下一步。
- 每项必须声明 `moduleId`、`actionId` 和 `arguments`。
- 宿主在执行前校验模块启用状态、Action 归属、`actionAiCallableEnabled`、风险、
  确认、参数和输出 schema。
- 首个不可恢复失败发生后，宿主停止后续 Action，并逆序回滚此前已完成 Action。
- 模块之间不得直接互调；需要传递结果时，由宿主按顺序执行后续 Action。

后一个 Action 可以引用此前已经完成 Action 的 `output`：

```json
{
  "$fromAction": 0,
  "path": ["output", "entries", 0, "path"]
}
```

- `$fromAction` 使用零基索引，只能引用更早且已完成的 Action。
- 引用对象只能包含 `$fromAction` 和 `path`。
- `path` 必须是非空数组并以 `output` 开头。
- 宿主解析引用后必须再次校验目标 Action 的参数类型。
- 缺失、越界、循环、非法路径、输出 schema 不符或参数类型不匹配时必须失败。
- 模块不得自行解析 `$fromAction`，也不得缓存其他模块输出副本。

## 6. 快照、执行、回滚与回收站

模块执行器必须实现 `OrderlyModule`：

- `moduleId()` 返回与契约一致的稳定模块 ID。
- `initialize(ModuleRuntime)` 可选，必须幂等，只能通过 `ModuleRuntime` 初始化自有状态。
- `query(...)` 只实现契约声明的只读查询。
- `prepareSnapshot(...)` 在正向执行前运行，只能读取，不得产生副作用。
- `execute(...)` 只执行当前一个已声明 Action。
- `rollback(...)` 根据快照和正向输出执行幂等逆操作。

快照、输出和错误上下文必须是可 JSON 序列化的对象。宿主在数组首次失败时停止，
并按完成顺序的逆序调用 `rollback(...)`；模块不得自行回滚其他步骤。

删除规则：

- 禁止 `Files.delete`、`Files.deleteIfExists`、直接系统删除命令或等价物理删除。
- 删除动作必须通过 `ModuleRuntime.moveToTrash(...)` 进入项目回收站。
- 回滚或恢复只能使用宿主提供的回收站能力，且原位置存在时不得覆盖。
- 模块不得宣称已经具备未验证的回收站、快照或回滚能力。

## 7. 来源、审计与诚实性

Action 来源由宿主设置，至少区分 `PLAN`、`AI_DIRECT`、`AI_PLAN`、`MODULE_CONTROL` 和
`SYSTEM`。模块不得接受、覆盖或伪造来源。

- 每一步的输入、输出、状态、快照和结果由宿主持久化和审计，模块不得另建跨模块历史。
- AI 删除直执即使由设置开启，也只能通过项目回收站执行，并保留快照、审计和回滚。
- 模块返回的摘要必须准确描述实际结果，不得声称未发生的操作。
- 错误应抛出带明确原因的异常，由宿主记录失败与回滚状态。
- 未实现能力必须标为“占位”或“尚未接入”，不得伪装成真实 AI、安全隔离、来源验证、
  签名校验、云服务或已完成业务能力。

## 8. JAR、SPI 与模块 UI

### 8.1 JAR 必要内容

模块 JAR 必须包含：

```text
META-INF/services/com.orderly.kernel.OrderlyModule
orderly-modules/<module>.json
orderly-ui/module.json
orderly-ui/module.js
orderly-ui/module.css
```

SPI 文件内容必须是 `OrderlyModule` 实现类的完整限定名。三项 `orderly-ui` 资源必须
位于固定路径，并由该模块 JAR 发布。模块 JAR、契约和 UI 资源不得复制进宿主 fat JAR。
模块专属图标可选放在 `orderly-ui/icons/`，并在 `module.json` 的 `icons` 中登记；
通用框架语义图标仍引用宿主内置 ID，不要求在模块内复制。

宿主通过运行时目录、`URLClassLoader` 和 `ServiceLoader` 发现模块；不得恢复
`Module/modules.index`、手工模块清单或根 POM 固定模块列表。

### 8.2 UI 清单与挂载

- `orderly-ui/module.json` 必须通过 `contracts/module-ui.schema.json`。
- 清单只声明本模块的侧边栏、总览卡片、示例、设置和视图元数据。
- `module.js` 使用默认导出挂载本模块视图：

```js
export default async function mount(root, context) {
    // root: 模块视图容器
    // context.module / context.ui: 模块契约与 UI 清单
    // context.settings: 宿主校验后的模块设置
    // context.query(...): 通用只读查询
    // context.element / context.icon: 主题一致的元素与内置图标
    // context.moduleIcon(...): 清单 icons 声明的模块本地图标元素
    // context.toast / context.fillCommand / context.formatDate: 宿主交互辅助能力
}
```

- 模块只能使用宿主公开的 mount context，不得访问其他模块 DOM、内部状态或私有接口。
- 只有 `module_system_control` 可以在满足固定 ID、`MODULE_CONTROL` 与
  `MODULE_GOVERNANCE` 校验时取得 `context.systemAdmin`；普通业务
  模块不得请求、伪造或缓存该特权。
- `module_system_control` 通过宿主 API 管理业务 Action/Query 的动态 `aiCallable` 状态，
  不治理自身接口。动态状态只限制 AI，不阻断模块自身 Query。未执行独立 AI 计划或
  执行中的 Action 必须由宿主标记为“使用中”并拒绝关闭。
- `orderly-ui/module.json` 的 `settings` 只支持 `boolean`、`integer`、`select` 和
  `text` 字段；字段值由宿主校验和持久化，模块不得自行维护第二套设置定义。
- 模块专属单色 SVG/PNG 图标必须位于 `orderly-ui/icons/`，通过 `icons` 映射登记，
  单个图标不超过 256 KiB；`view.icon` 与 `card.icon` 先查模块映射，再回退框架内置
  图标。宿主只发布清单登记的资源，不得扫描整个 `orderly-ui/` 目录。
- 不得在 `app/frontend/src/app.js` 等框架文件中增加固定视图映射。
- UI 清单无效、资源缺失、加载失败或 Query 失败只影响该模块局部，不得拖垮框架、
  侧边栏或其他模块。

### 8.3 主题与动效

- 模块 CSS 必须使用主题语义变量，不得硬编码颜色、字体或动效时长。
- 动效只受程序内 `gentle`、`minimal`、`none` 三档控制。
- 系统 `prefers-reduced-motion` 完全不参与；模块不得建立第四套或独立动效策略。
- `none` 档关闭动效后，内容、状态、焦点和可操作性仍必须完整可见。

## 9. 生命周期与信任边界

### 9.1 生命周期

- 构建脚本扫描 `Module/module_*`，逐模块构建并清空、重建
  `target/orderly-modules/`，防止已移除模块从旧 JAR 继续加载。
- 宿主启动时按稳定顺序扫描运行模块 JAR，读取契约和 UI 清单，通过 `ServiceLoader`
  绑定执行器，并在通用运行时就绪后对每个模块调用一次幂等 `initialize(...)`。
- 启动时必须校验唯一 `module_system_control`、系统模块角色与能力、宿主准入清单、
  契约与执行器集合一致，以及模块、Action、
  Query、计划规则和视图 ID 唯一。
- 运行中只允许显式刷新追加已经构建的新业务模块 JAR；未构建源码返回
  `MODULE_NOT_BUILT`。
- 已加载模块的更新、替换或删除返回 `RESTART_REQUIRED`，必须重新构建并重启。删除
  请求只停用模块、把源码移入 `.orderly-data/trash/modules` 并登记重启清理；启动扫描
  在加载 JAR 前跳过待删除模块，重启后再移入 JAR。重启前允许撤销，禁止物理删除。
  不得热替换、热卸载或要求框架清理具体业务分支。
- 模块 ClassLoader 在宿主关闭时关闭。应用运行期间不得替换已加载 JAR。

### 9.2 信任边界

外部模块 JAR 是本地受信任的可执行代码，不是安全沙箱。当前不提供、也不得宣称提供：

- 远程插件下载或分发。
- 来源验证、签名校验或供应链认证。
- JVM 级权限隔离、恶意代码隔离或插件权限沙箱。
- 任意个人目录访问能力。

模块只能通过 `ModuleRuntime` 使用应用自管工作区。工作区边界是应用行为约束，不等于
恶意代码安全隔离。

## 10. 模块验收矩阵

模块新增、修改或移除时，至少按任务影响范围验证：

| 检查项 | 最低证据 |
|---|---|
| 结构完整 | JAR 内含 SPI、模块契约、`orderly-data/<moduleId>.json`（如有）和三项 `orderly-ui` 资源 |
| 自动发现 | 不修改框架固定列表即可加载；宿主 fat JAR 不含模块 JAR、契约或 UI |
| 系统模块 | 恰好一个受保护的 `module_system_control`；仅 `module_system_` 宿主内置模块可声明系统身份；业务 API 不返回系统模块 |
| 零业务模块 | 保留控制模块时可启动，业务模块列表为空，侧边栏和总览显示空状态 |
| 契约合法 | 模块契约通过 `module.schema.json`，UI 清单通过 `module-ui.schema.json`；合法与非法样例均有实际测试 |
| 标识唯一 | 模块、Action、Query、计划规则和视图 ID 的重复与越界被拒绝 |
| 数据所有权 | `ownerModule` 与 `moduleId` 一致；资源名带模块 ID；运行态集合无跨模块复用并有测试 |
| Action/Query | 动态 `aiCallable`、风险、确认、参数和输出 schema 生效；Query 无副作用 |
| 顺序与引用 | 有序 Action 数组按序执行；`$fromAction` 的成功、越界、缺失和类型错误均有测试 |
| 快照与回滚 | 快照只读且可 JSON 化；失败停止；已完成 Action 逆序回滚；逆操作幂等 |
| 删除 | 仅进入项目回收站；源码和测试中不存在物理删除调用 |
| 来源与审计 | 来源由宿主设置；输入、输出、快照、状态和回滚事件可追溯 |
| UI 与主题 | mount context 正常；三资源可访问；无硬编码颜色、字体或时长；三档动效可验证 |
| 失败隔离 | UI、资源或 Query 单模块失败不影响框架和其他模块 |
| 生命周期 | 新增可刷新；更新或删除要求重启；移除模块重构建后旧能力全部消失 |
| 诚实性 | 未接入 AI、隔离、签名、来源验证等能力均有明确占位或未接入标注 |

验证命令和结果必须来自实际执行。构建、测试、启动、HTTP 烟测或环境检查未运行时，
必须标为“未验证”，不得用设计意图或历史结果代替当前证据。测试不得通过删除、跳过、
弱化断言或吞掉异常来制造通过结果。
