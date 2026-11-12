# 前端外观与动画主题包模块化交接

## 0. 文档状态

| 项目 | 内容 |
|---|---|
| 状态 | 实现完成，自动化验证通过，等待 PM 最终验收 |
| 更新日期 | 2026-11-13 |
| 仓库 | `D:\code\Orderly_framework-v3` |
| 分支 | `framework-v3` |
| 交接前基线提交 | `e60e9ec docs: add frontend theme modularization handoff` |
| 当前实现 | 目录主题包、事务化多 CSS 装载、局部问题与两级回退 |
| 目标 | 可拆装目录主题包，支持框架语义动画，重启后生效 |
| 主要负责人 | 主代理最终负责；PM 只读审查；软件工程师按任务卡实施 |

### 0.1 实施结果

2026-11-13 已完成目录主题包实现：

- `themes/paper`、`themes/moss`、`themes/night` 已迁移为 `theme.json + tokens.css + motion.css` 目录包；旧扁平 JSON 保留在 `themes/_legacy-flat-json/`。
- 宿主只扫描 `orderly.themes-dir` 的直接目录，不使用 classpath 回退，不再把 `themes/` 打入 fat JAR。
- `/api/themes` 返回 `{items, issues, builtInFallbackActive}`；新增 `/api/themes/{id}/assets/{asset}` 白名单资源接口。
- 前端按候选 CSS 全量成功后再提交切换，并覆盖失败保留、并发请求代次、当前主题 -> 默认主题 -> 内置安全外观三级顺序。实际主题与请求不同时会把 `settingsStore.theme` 校正为实际 id，不重建设置面板。
- 框架公开语义挂点仅覆盖壳层；动画迁入主题 `motion.css`，`orderly.safety` cascade layer 强制处理 `motion: none` 与 `prefers-reduced-motion`。
- 设置面板改为专用 `role=status` / 显式切换失败局部 `role=alert`；静态目录问题使用普通 section/heading/list。设置导航提供 `aria-controls`、`aria-current`，切换分区后焦点进入新面板标题。
- 基础 `scroll-behavior` 为 `auto`，平滑滚动仅由主题 `motion.css` 声明；SVG 已从主题资源白名单移除，失效纹理与旧 SVG 移入 `_legacy-flat-json`，主题卡不再用当前主题令牌生成重复色块。

已实际通过：

```text
node --test app/frontend/tests/*.test.mjs                 39/39 pass
.\scripts\gate.ps1                                        exit 0
.\scripts\modularity.ps1                                  exit 0
真实启动 + .\scripts\smoke.ps1                             exit 0
fat JAR 用户主题条目检查                                  0
git diff --check                                           exit 0
```

最终验证证据：

- `gate.ps1`：Maven 32 项，0 失败、0 错误、1 项跳过；Node 39 项通过；模块 UI 契约 2 项通过。
- Edge：13/13 通过，覆盖桌面与窄屏、失败切换、焦点保持、`motion: none`、`prefers-reduced-motion`，以及无默认主题时完整卸载 active theme 并回退内置安全外观；无 console/HTTP error。
- 最终同版本真实启动 + `smoke.ps1`：退出码 `0`。
- 唯一未完全验证项：当前 Windows 权限无法创建符号链接，因此符号链接创建用例跳过；路径和链接拒绝实现仍在。

本文件记录目标、已拍板决策、实现边界、实施结果和验收矩阵。

下一会话仍必须先阅读：

1. `AGENTS.md`
2. `docs/agents/architecture-baseline.md`
3. `docs/agents/collaboration-workflow.md`
4. 本文件
5. 当前 PM 任务卡

若实际仓库、分支、提交或工作区状态与本文件不一致，应先报告差异，不得直接沿用本文件中的状态描述。

## 1. 用户目标

将归序前端的外观与动画升级为可拆装的主题系统，使用户能够：

- 在 `themes/` 中添加一个主题文件夹后，重启归序即可选择该主题。
- 从 `themes/` 中移除主题文件夹后，重启归序后该主题不再出现。
- 不重新构建宿主或模块，即可安装、替换或移除用户主题包。
- 让主题控制颜色、字体、间距、时长、缓动、关键帧和复杂 CSS 动画。
- 让侧边栏、总览卡片、视图切换、弹窗、提示和状态反馈等框架区域具备稳定动画能力。
- 在主题失效、版本不兼容或资源损坏时只影响局部，并回退到可用默认主题。
- 关闭动效或系统启用 `prefers-reduced-motion` 时，动画始终能够被安全禁用。

主题系统只负责框架外壳的视觉和动画。具体业务模块的动画归模块设计负责人和模块自身维护，框架不替模块设计内部动画。

## 2. 已拍板决策

以下决策已经由用户确认，不再作为待讨论项：

1. **重启后生效**
   - 首版不实现运行中扫描、热切换或热卸载。
   - 添加、替换或删除主题包后，用户重启应用即可生效。
   - 重启生效不等于重新构建；用户主题包不得要求 Maven 或宿主重新打包。

2. **不保留扁平主题格式兼容层**
   - 不继续支持现有 `themes/*.json` 作为正式主题格式。
   - 迁移完成后，只发现目录形式和其中的 `theme.json`。
   - 不需要双格式加载、自动迁移或长期兼容分支。

3. **使用“语义挂点 + 语义变量”**
   - 框架组件公开稳定的 `data-motion-*` 状态挂点和语义 CSS 变量。
   - 主题只依赖公开挂点和变量，不依赖组件内部 DOM 层级、内部类名或具体元素顺序。
   - 挂点和变量的变化视为契约变化，必须有测试和迁移说明。

4. **允许关键帧和复杂组合动画**
   - 主题 CSS 可以声明 `@keyframes`、多属性过渡、分阶段动画和组合动画。
   - 主题仍不得携带或执行 JavaScript。
   - 动画不能掩盖内容、阻止输入、破坏焦点管理或制造不可关闭的界面。

5. **模块动画归模块所有**
   - 框架和主题不定义具体模块内部动画。
   - 模块的进入、内容切换、局部加载、局部错误和业务交互动效由模块设计师在模块自身范围内设计。
   - 主题不得使用选择器侵入模块内部 DOM，也不要求框架公开模块动画选择器。
   - 模块可以按自身契约使用通用语义变量，例如时长和缓动，但框架不得替模块编排动画。

6. **明确 `schemaVersion`**
   - 每个 `theme.json` 必须声明整数 `schemaVersion`。
   - 首版实现支持 `schemaVersion: 1`。
   - 遇到不支持的版本时，只将该主题标记为不可用，并在主题目录结果中给出局部错误。
   - 当前选中主题不受支持时，回退到可用默认主题。
   - 如果没有可用默认主题，则回退到框架内置安全外观，程序仍可启动。

## 3. 交接前实现证据（历史）

交接前已核验工作区干净，并与 `origin/framework-v3` 同步。相关现状如下。

### 3.1 宿主主题目录

`app/host/src/main/java/com/orderly/host/theme/ThemeCatalog.java` 当前行为：

- 扫描 `orderly.themes-dir` 指向目录中的扁平 `*.json` 文件。
- 源目录存在时，不读取 classpath 主题。
- 只接受普通目录和普通文件，拒绝符号链接。
- 校验 id、顺序、颜色方案、令牌名和重复 id。
- 支持最多一个 `default: true`。
- 任一不合法主题可能直接阻止宿主创建主题目录。

`ThemeDefinition` 当前只有元数据与令牌映射，没有：

- `schemaVersion`
- 样式资源列表
- 主题资源接口
- 局部加载问题模型
- 内置安全外观回退状态

### 3.2 宿主接口

`app/host/src/main/java/com/orderly/host/api/OrderlyController.java` 当前提供：

- `GET /api/themes`
- `GET /api/themes/{id}`

没有主题 CSS 或静态资源接口。

### 3.3 前端主题管理

`app/frontend/src/settings.js` 的 `ThemeManager` 当前行为：

- 按主题 id 请求 `/api/themes/{id}`。
- 只应用名称符合 `--[a-z0-9-]+` 且值为字符串的令牌。
- 切换主题时移除上一主题写入的根元素令牌。
- 设置 `html[data-theme]`、`color-scheme` 和浏览器主题色。
- 没有样式表生命周期，没有装载失败后的局部问题展示。

### 3.4 当前动效基础

`app/frontend/styles/tokens.css` 已提供：

- `--duration-fast`
- `--duration-normal`
- `--duration-slow`
- `--ease-out`
- `--ease-spring`
- `html[data-motion="minimal"]`
- `html[data-motion="none"]`
- `prefers-reduced-motion: reduce`

`app/frontend/styles/app.css` 已经使用这些变量，但仍有以下硬编码动画：

- `target-pulse`
- `view-enter`
- `spin`
- 多个组件专属 `transition`
- 全局关闭动效规则

这些动画需要逐步迁移为框架默认动画或公开语义挂点，不能一次性删除后造成功能状态不可感知。

### 3.5 当前构建风险

`app/host/pom.xml` 当前将 `../../themes` 复制进宿主 classpath 的 `themes/` 目录。若要满足用户拆装主题且不出现陈旧主题，目标实现必须调整为：

- 用户可拆装的 `themes/` 是宿主外部运行时目录。
- 宿主 fat JAR 不携带可被回退加载的用户主题包。
- 主题删除后重启，不会从旧 JAR 中重新加载。
- 框架内置安全外观仍保留在前端基础代码中，但不得伪装成用户主题。

## 4. 目标主题包结构

首版目标结构：

```text
themes/
└─ paper/
   ├─ theme.json
   ├─ tokens.css
   ├─ motion.css
   ├─ assets/
   └─ README.md
```

约束：

- 目录名必须与 `theme.json` 中的 `id` 一致。
- `theme.json`、`tokens.css` 和 `motion.css` 都必须位于主题包根目录。
- `tokens.css` 和 `motion.css` 必须在清单中显式登记。
- `assets/` 可选，只存放本地资源。
- 主题包不允许符号链接。
- 主题包不得引用宿主任意路径、远端 URL 或用户工作区文件。
- 示例主题可以提供 `README.md`，但宿主不得执行其中的任何内容。

### 4.1 `theme.json` 草案

```json
{
  "schemaVersion": 1,
  "id": "paper",
  "label": "纸页",
  "note": "温暖明亮",
  "order": 10,
  "default": true,
  "colorScheme": "light",
  "browserColor": "#f3efe7",
  "styles": [
    "tokens.css",
    "motion.css"
  ],
  "assets": [
    "assets/paper-grain.webp"
  ]
}
```

字段规则：

| 字段 | 必需 | 规则 |
|---|---|---|
| `schemaVersion` | 是 | 整数；首版只支持 `1` |
| `id` | 是 | `[a-z][a-z0-9-]{0,63}`，并与目录名一致 |
| `label` | 是 | 非空文本 |
| `note` | 否 | 简短说明 |
| `order` | 是 | 非负整数 |
| `default` | 否 | 布尔；目录内最多一个 `true` |
| `colorScheme` | 否 | `light`、`dark` 或空 |
| `browserColor` | 否 | 本地颜色文本 |
| `styles` | 是 | 非空、稳定顺序、只含当前包允许的 CSS 相对路径 |
| `assets` | 否 | 主题 CSS 需要引用的本地静态资源相对路径；每项必须显式登记 |

### 4.2 样式职责

`tokens.css` 负责：

- 颜色令牌
- 字体令牌
- 圆角、间距、阴影和层级相关取值
- 时长与缓动变量
- 主题需要覆盖的其他语义变量

`motion.css` 负责：

- 框架语义挂点对应的动画
- `@keyframes`
- 过渡、组合动画和动画降级
- 可选 `prefers-reduced-motion` 防护

即使主题忘记写减少动效规则，框架也必须保留全局胜利条件，确保 `data-motion="none"` 和系统减少动效不被主题覆盖。

## 5. 语义动画契约

### 5.1 框架公开范围

框架只公开自身组件状态。建议首版覆盖：

| 语义范围 | 建议值 | 典型状态 |
|---|---|---|
| 应用外壳 | `app-shell` | `ready`、`busy` |
| 侧边栏 | `sidebar` | `entering`、`expanded`、`collapsed`、`reordering` |
| 顶栏 | `topbar` | `entering`、`busy` |
| 主内容区 | `main` | `entering`、`view-change` |
| 总览 | `overview` | `entering`、`reordering` |
| 模块卡片容器 | `module-card` | `entering`、`reordering`、`attention` |
| 设置弹窗 | `settings-dialog` | `open`、`closing` |
| 指令面板 | `command-panel` | `open`、`closing`、`busy` |
| 状态反馈 | `feedback` | `info`、`success`、`warning`、`error` |

实现时至少提供：

- 稳定的 `data-motion-scope` 标识组件语义区域。
- 稳定的 `data-motion-state` 标识当前状态。
- 可选的 `data-motion-phase` 标识进入、活动、退出阶段。
- 必要的语义 CSS 变量，避免主题硬编码固定时长和缓动。

示例，仅为契约示意：

```html
<aside data-motion-scope="sidebar" data-motion-state="entering">
```

```css
html[data-theme] [data-motion-scope="sidebar"][data-motion-state="entering"] {
    animation: sidebar-reveal var(--duration-normal) var(--ease-out) both;
}
```

### 5.2 稳定与私有边界

公开挂点属于主题契约，必须稳定。以下内容属于框架私有实现，不承诺给主题使用：

- 组件内部 DOM 层级
- 仅为布局服务的类名
- 数据绑定节点 id
- 具体按钮和文本节点选择器
- 组件事件处理细节

主题选择器应尽量只使用：

- `[data-motion-scope]`
- `[data-motion-state]`
- `[data-motion-phase]`
- 框架明确公开的语义状态类

### 5.3 删除动效优先级

优先级固定为：

1. 用户设置 `motion: none`
2. 系统 `prefers-reduced-motion: reduce`
3. 主题动画
4. 框架默认动画

主题 CSS 不得通过更高特异性或 `!important` 绕过前两项。测试必须实际检查关闭动效后，元素仍保持可见、可交互和可聚焦。

### 5.4 模块边界

框架不向主题公开模块内部动画挂点：

- 不增加 `module_fs`、`module_todo` 或 `module_calendar` 特判。
- 不为模块内部按钮、列表和表单设计主题专用选择器。
- 不在主题样例中演示侵入模块 DOM 的做法。
- 模块自身动画继续位于 `Module/module_*/src/main/resources/orderly-ui/module.css`。
- 模块缺失或模块 UI 加载失败不能阻止框架主题加载。

## 6. 目标宿主模型与接口

### 6.1 主题目录模型

建议新增或调整以下宿主模型，名称可按最小改动原则微调，但职责不得混淆：

- `ThemeCatalog`：发现可用目录主题并加载清单。
- `ThemeDefinition`：当前可用主题的完整定义。
- `ThemeSummary`：设置列表使用的稳定摘要。
- `ThemeIssue`：某个主题包的局部错误。
- `ThemeCatalogSnapshot`：可用摘要、局部错误和内置回退状态。

`ThemeDefinition` 至少需要：

- `schemaVersion`
- `id`
- `label`
- `note`
- `order`
- `defaultTheme`
- `colorScheme`
- `browserColor`
- `styles`
- 主题包根路径的宿主内部表示

内部根路径不得序列化为 API 返回给前端。

### 6.2 发现时机

- 只在宿主启动时扫描 `orderly.themes-dir`。
- 不监听文件系统。
- 不实现运行中重新扫描。
- 目录不存在、为空或不含有效主题时，应用仍必须启动。
- 不使用 classpath 扁平主题回退来掩盖已删除的用户主题。

### 6.3 错误隔离

- 单个主题的不支持 `schemaVersion` 必须生成局部 `ThemeIssue`，不能阻止其他主题加载。
- 当前选中主题不可用时，前端回退可用默认主题。
- 可用默认主题不存在时，前端使用框架内置安全外观。
- 现有重复 id、多个默认项等目录级冲突规则，在未获新的明确批准前不得弱化。
- 若目录级规则阻止主题目录建立，必须明确报告，不得悄悄忽略冲突。

### 6.4 API 建议

实现时可以保留路径并在响应结构上升级，但必须一次完成前后端同步：

- `GET /api/themes`
  - 返回可用主题摘要、局部问题和是否启用内置回退。
- `GET /api/themes/{id}`
  - 只返回可用主题的完整定义。
- `GET /api/themes/{id}/assets/{asset}`
  - 返回该主题清单允许的本地资源。

资源接口要求：

- 只允许主题清单登记过的资源。
- 默认只允许 CSS 和主题目录允许的本地静态资源类型。
- 严格拒绝绝对路径、`..`、反斜杠逃逸、符号链接和目录访问。
- 设置正确 `Content-Type`。
- 使用 `Cache-Control: no-cache` 或等效策略，避免主题替换后继续使用旧资源。
- 不允许读取主题包之外的任何文件。

`GET /api/themes` 的示例结构：

```json
{
  "items": [
    {
      "id": "paper",
      "label": "纸页",
      "note": "温暖明亮",
      "order": 10,
      "default": true,
      "schemaVersion": 1
    }
  ],
  "issues": [
    {
      "id": "future-theme",
      "code": "UNSUPPORTED_SCHEMA_VERSION",
      "message": "主题需要 schemaVersion 2，当前程序仅支持 1",
      "supportedVersions": [1],
      "actualVersion": 2
    }
  ],
  "builtInFallbackActive": false
}
```

具体 JSON 名称允许在契约评审时微调，但不得丢失局部问题与回退状态。

## 7. 前端目标行为

### 7.1 主题目录读取

- 设置页主题列表完全来自 `/api/themes`。
- 前端不得保存静态主题 id 列表。
- 有效主题可以切换。
- 不可用主题显示局部错误，但不能导致整个设置页失败。

### 7.2 主题装载

`ThemeManager` 需要扩展为完整生命周期管理：

1. 获取主题定义。
2. 校验 `schemaVersion`。
3. 按清单顺序装载 CSS 资源。
4. 等待样式可用或使用明确的可重复应用策略。
5. 设置 `html[data-theme="<id>"]` 和相关元数据。
6. 切换时移除上一主题的 `<link>`、令牌和根属性。
7. 失败时清理不完整状态。
8. 回退到默认主题或框架内置安全外观。

禁止使用主题自带 JavaScript。主题 CSS 是唯一可执行的表现层输入；宿主仍必须把它视为本地但格式不受信任的数据。

### 7.3 设置持久化

- 保持当前本地设置中的主题 id 语义。
- 主题不存在或不可用时，继续使用现有目录校正和回退逻辑。
- 不把无效主题 id 永久写回为新的固定默认。
- 主题顺序由 `order`、id 和默认规则稳定决定。

### 7.4 动画挂点接入

- 在框架组件渲染时添加公开 `data-motion-*` 属性。
- 不要求模块 UI 改造才能完成框架主题切换。
- 为拖拽排序、视图切换、弹窗、设置、加载、成功和错误状态提供可观察挂点。
- 删除动效后，状态文字、焦点、选中样式和内容变化仍必须明确。

## 8. 目录与文件边界

允许修改路径应按后续 PM 任务卡逐项列出。预计涉及：

| 路径 | 预计职责 |
|---|---|
| `app/host/src/main/java/com/orderly/host/theme/**` | 目录主题发现、清单、问题与资源模型 |
| `app/host/src/main/java/com/orderly/host/api/OrderlyController.java` | 主题列表、详情和资源接口 |
| `app/host/src/test/java/com/orderly/host/theme/**` | 主题目录、schema、安全与回退测试 |
| `app/host/src/test/java/com/orderly/host/OrderlyIntegrationTest.java` | 主题 HTTP 契约测试 |
| `app/frontend/src/settings.js` | 主题目录校正、装载、卸载和回退 |
| `app/frontend/src/app.js` | 框架语义挂点和设置页局部错误展示 |
| `app/frontend/styles/tokens.css` | 框架内置安全变量与动效优先级 |
| `app/frontend/styles/app.css` | 默认动画和公开挂点适配 |
| `app/frontend/index.html` | 必要的初始语义状态 |
| `app/frontend/tests/**` | 主题管理、挂点、回退和动效测试 |
| `themes/**` | 迁移为目录主题包 |
| `app/host/pom.xml` | 不把用户主题打进 fat JAR |
| `scripts/**` | 构建、门禁、模块化或烟测适配 |
| `README.md`、`docs/agents/**` | 使用说明和架构同步 |

默认禁止修改：

- 两份既有原始产品资料。
- 与本任务无关的具体业务模块。
- 模块内部动画和 `Module/module_*/src/main/resources/orderly-ui/**`。
- 未经批准修改模块契约、查询、执行器、授权、快照或回滚逻辑。

如实现必须修改模块代码，应停止并拆分独立任务，不得借主题改造顺带扩大模块能力。

## 9. 分阶段实施建议

### 阶段 A：契约和失败策略

1. PM 只读输出任务卡。
2. 确定 `schemaVersion: 1` 清单字段和 API 响应。
3. 确定局部 `ThemeIssue` 模型。
4. 确定重复 id、多个默认项等现有全局失败规则。
5. 先写失败测试，再修改实现。

### 阶段 B：宿主目录主题和资源接口

1. `ThemeCatalog` 从扫描扁平 JSON 改为扫描主题目录。
2. 解析并验证 `theme.json`。
3. 支持不支持版本的局部错误。
4. 实现受控资源读取。
5. 移除 classpath 扁平主题回退和宿主 fat JAR 内的用户主题资源。

### 阶段 C：前端装载生命周期

1. 适配新的主题目录响应。
2. 装载和卸载多份主题 CSS。
3. 显示局部主题问题。
4. 实现默认主题和内置安全外观两级回退。
5. 保留 `motion: none` 和 `prefers-reduced-motion` 的绝对优先级。

### 阶段 D：框架语义挂点与动画迁移

1. 定义并测试公开 `data-motion-*` 契约。
2. 为框架外壳组件增加挂点。
3. 将可主题化动画从硬编码规则迁入默认主题 `motion.css` 或明确默认层。
4. 保留必要的框架状态动画，避免主题切换时失去反馈。
5. 不修改模块内部 UI 和动画。

### 阶段 E：主题迁移与用户说明

1. 将 `default.json`、`night.json`、`moss.json` 转为目录包。
2. 删除扁平主题格式，不保留兼容分支。
   - 旧文件不得由代理物理删除；应迁移到项目回收站或保留为用户确认后的归档副本。
3. 编写用户主题制作说明，包括：
   - 目录结构
   - `theme.json` 字段
   - 语义变量
   - 语义挂点
   - 动效关闭要求
   - 重启生效方式
   - 常见局部错误
4. 提供最小可运行示例主题。

### 阶段 F：完整验证与交付

1. 运行定向测试。
2. 运行 `scripts/gate.ps1`。
3. 运行 `scripts/modularity.ps1`。
4. 运行真实启动和 HTTP 烟测。
5. 检查 fat JAR 不包含用户主题。
6. PM 只读复核实际文件和证据。
7. 主代理检查工作区和差异。
8. 创建 Git commit 并推送到 `origin/framework-v3`。

## 10. 验收矩阵

### 10.1 宿主单元测试

- 发现合法目录主题并稳定排序。
- 忽略主题目录中的 README 和非主题目录。
- 目录名与清单 id 不一致时按既定规则拒绝或标记。
- 不支持的 `schemaVersion` 产生局部问题，其他主题仍可用。
- 无有效默认主题时返回空默认选择并启用内置回退。
- 多个 `default: true` 继续按批准的目录级规则处理。
- 重复 id 继续按批准的目录级规则处理。
- 缺少清单、样式或样式路径非法时不读取包外文件。
- 绝对路径、`..`、编码绕过和符号链接被拒绝。
- `GET /api/themes` 包含可用项与问题。
- 主题 CSS 接口返回正确类型和禁止缓存策略。
- 主题删除后重启，不出现 classpath 陈旧主题。

### 10.2 前端单元测试

- `/api/themes` 新结构被正确解析。
- 不支持版本出现在局部错误中，不导致整页失败。
- 当前主题缺失时选择默认主题。
- 默认主题不可用时启用内置安全外观。
- 切换主题时旧 `<link>`、旧令牌和旧根属性被移除。
- 装载中途失败时清理未完成资源。
- `data-motion="none"` 优先于任何主题动画。
- `prefers-reduced-motion` 优先于任何主题动画。
- 主题管理器不执行或注入主题 JavaScript。
- 前端不存在静态主题 id 列表。

### 10.3 浏览器与可访问性测试

- 侧栏、总览、视图、弹窗和指令面板挂点稳定存在。
- 拖拽排序时有可观察语义状态，但键盘替代操作仍可用。
- 关闭动效后，内容完整可见，焦点不丢失。
- 弹窗可关闭，动画不能阻止点击或键盘操作。
- 复杂动画结束后状态样式仍正确，不依赖动画最后一帧“伪装”最终状态。
- 主题加载失败不影响模块 UI 或业务操作。

### 10.4 集成与构建测试

- 添加主题目录，重启后 `/api/themes` 出现新主题。
- 删除主题目录，重启后主题不再出现。
- 删除当前主题，重启后 UI 回退可用默认主题。
- 修改主题 CSS，重启后新样式生效。
- 安装或删除主题不需要 Maven 重新构建。
- 宿主 fat JAR 不包含用户可拆装主题。
- 零主题启动合法，框架内置外观可用。
- `gate.ps1`、`modularity.ps1`、启动和 HTTP 烟测按实际结果记录。
- 测试创建的临时主题目录、资源和进程在成功和失败后都被清理。

## 11. 风险与控制

| 风险 | 控制 |
|---|---|
| 主题 CSS 选择器侵入模块内部 | 只公开框架语义挂点；验收时扫描示例和实现中的模块选择器 |
| 用户主题导致程序无法启动 | 不支持版本和可局部化错误不升级为全局失败；内置外观兜底 |
| 主题删除后旧样式残留 | 外部主题目录、无 classpath 回退、重启重新扫描、切换时完整卸载 |
| 关闭动效被主题覆盖 | 全局高优先级禁用规则和真实浏览器测试 |
| 复杂动画破坏可访问性 | 限制为 CSS；焦点、可见性、输入和弹窗关闭必须测试 |
| 资源路径穿越 | 清单白名单、规范化路径、禁止符号链接、前缀边界检查 |
| 模块与框架边界被打破 | 禁止修改模块 UI；模块动画拆给模块设计任务 |
| 预览和最终状态不一致 | 最终状态不得依赖动画保持；动画结束后验证静态状态 |
| 主题 id 与目录不一致 | 明确一致性和稳定排序规则 |
| 构建仍打包旧主题 | 检查 host POM、fat JAR 内容和删除/重启行为 |

## 12. 非目标

- 不实现运行中热加载、热切换或热卸载。
- 不保留扁平 `themes/*.json` 兼容层。
- 不执行主题 JavaScript。
- 不提供远端主题商店、下载、签名或权限沙箱。
- 不让主题改变业务逻辑、模块契约、授权、快照或回滚。
- 不由框架设计师设计模块内部动画。
- 不修改两份原始产品资料。
- 不进行物理删除。
- 不为了主题系统顺带重构无关模块。

## 13. 下一会话启动提示

可将以下内容作为下一会话首条任务的一部分：

```text
请先只读核验 D:\code\Orderly_framework-v3 的当前分支、提交和工作区状态，并依次读取：
AGENTS.md、
docs/agents/architecture-baseline.md、
docs/agents/collaboration-workflow.md、
docs/agents/ORDERLY-FRONTEND-THEME-MODULARIZATION-HANDOFF.md。

严格按交接文档实施前端外观与动画主题包模块化。先让 project_manager 输出只读任务卡、
允许修改路径和验收矩阵；确认后再让 software_engineer 实施。

必须保留以下已拍板决策：
重启后生效；不保留扁平 JSON；语义挂点加语义变量；允许关键帧和复杂 CSS；
模块动画归模块自身；使用 schemaVersion，并在不支持时局部报错和回退默认主题。

不得修改具体模块的内部动画，不得执行主题 JavaScript，不得修改两份原始产品资料，
不得物理删除文件。测试必须来自真实执行，未运行项必须如实标注。

完成实际验证后创建 Git commit 并推送到 origin/framework-v3；失败如实报告。
```

## 14. 完成定义

只有同时满足以下条件，本任务才算完成：

- 用户主题以目录包形式添加和移除。
- 增删主题后重启即可生效，不需要重新构建宿主。
- 主题包支持语义变量、关键帧和复杂 CSS 动画。
- 框架语义挂点只覆盖框架外壳，不侵入模块内部。
- `schemaVersion` 不受支持时局部报错并回退默认主题。
- 默认主题不可用时内置安全外观仍可运行。
- 主题切换不残留旧样式，删除后不出现陈旧主题。
- 关闭动效和系统减少动效始终有效。
- 定向测试、门禁、模块化和实际启动验证结果真实可追溯。
- PM 已完成只读验收，主代理已检查实际文件。
- 更新已提交并推送到 `origin/framework-v3`；若推送失败，交付说明明确写出。
