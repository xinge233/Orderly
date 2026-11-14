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

## 15. ORDERLY-FRONTEND-EXPANDING-SIDEBAR 实施交接

### 15.1 状态与范围

| 项目 | 内容 |
|---|---|
| 状态 | 实现完成，自动化与启动烟测通过，等待 PM 最终验收 |
| 任务编号 | `ORDERLY-FRONTEND-EXPANDING-SIDEBAR` |
| 实施日期 | 2026-11-13 |
| 桌面默认 | 64px 图标轨道，主内容始终只预留 64px |
| 桌面展开 | 208px 覆盖层，不改变主内容 `left`/`width`，不触发主内容重排 |
| 自动展开 | `(min-width: 821px) and (hover: hover) and (pointer: fine)`，指针停留 140ms 后展开 |
| 自动收起 | 指针离开后 400ms 收起；焦点、非空搜索、拖拽或设置弹窗会抑制收起 |
| 移动端 | `<=820px` 继续使用现有点击抽屉、`inert`、`aria-hidden`、焦点返回与 Escape 路径 |

本任务只修改前端壳层、主题动效、相关测试和本交接文档。未修改 `Module/`、`app/kernel/`、`app/host/`、`contracts/`、产品资料、脚本或依赖，未实现 Pin 持久化或设置项。

### 15.2 实施摘要

- `tokens.css` 将 `--sidebar-width` 改为 64px，并新增 `--sidebar-expanded-width: 208px`；`app-shell` 网格仍只消费 64px 轨道。
- `index.html` 的侧栏运动状态初始为 `collapsed`；搜索与设置按钮补充稳定 `aria-label`。
- `app.js` 使用单一展开状态对象和一个展开/收起定时器；即时展开入口覆盖指针点击搜索、`/`、焦点进入、Tab、拖拽激活。
- 指针离开后统一检查 `focus-within`、搜索值、拖拽状态、body 拖拽类和设置弹窗；条件解除时通过 `focusout`、输入清空、拖拽结束和弹窗关闭重新评估。
- 收起态显示 34px 图标、40px 控件命中区、状态点和非零模块数量角标；零值及 `—`/占位值不创建角标。导航按钮的 `aria-label` 同时包含名称、说明与真实数量。
- `pulsePlanModules` 只在桌面短暂设置 `attention`，2.6 秒后按当前真实展开状态恢复；移动端不写入桌面 attention 状态。
- 三个目录主题的 `sidebar` 动效增加 `width`、`padding` 与 `transform` 过渡；`app.css` 未新增 `transition`，`orderly.safety` 继续禁用全部动效。
- 命令栏继续按 64px 侧栏宽度计算可用空间；1080px 断点保留原计算。

### 15.3 实际验证

```text
npm test
  65 tests, 65 pass, 0 fail

npm run check
  exit 0

git diff --check
  exit 0（仅提示部分文件下次写回时 CRLF 行尾转换）

.\scripts\gate.ps1
  exit 0
  Maven host tests: 49 run, 0 failures, 0 errors, 1 skipped
  Frontend tests: 65 pass
  Module UI contract samples: 4 pass

.\scripts\modularity.ps1
  exit 0

.\scripts\start.ps1 -SkipBuild -Port 18082
  startup success, 127.0.0.1:18082

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18082
  exit 0

最终代码复跑：
.\scripts\start.ps1 -SkipBuild -Port 18083
  startup success, graceful shutdown complete
.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18083
  exit 0
```

验证说明：

- 完整门禁首次尝试因既有 `127.0.0.1:8080` 本地宿主锁定 `target/orderly-modules/*.jar` 而失败；确认进程属于本仓库后停止该宿主，重新执行 `gate.ps1` 通过。该次失败属于环境占用，不是代码测试失败。
- 真实 HTTP 首页返回 200，且包含初始 `collapsed` 状态、搜索 `aria-label` 与设置 `aria-label`。
- HTTP 烟测覆盖 JDK 21、业务/系统模块目录、模块偏好、模块 UI、通用查询、目录主题详情/CSS、回退字段、刷新响应和未确认计划拒绝，未产生业务数据变更。
- Edge 多尺寸与 140ms/400ms 真实交互时序验收由主代理负责，本工程师未将其表述为已通过。

## 16. ORDERLY-FRONTEND-SIDEBAR-PULL-REFINEMENT 实施交接

### 16.1 状态与范围

| 项目 | 内容 |
|---|---|
| 状态 | 主代理真实浏览器验收与 PM 独立复核完成，结论 `PASS` |
| 任务编号 | `ORDERLY-FRONTEND-SIDEBAR-PULL-REFINEMENT` |
| 实施日期 | 2026-11-14 |
| 桌面收起 | 64px 图标轨道，主网格仍只预留 64px |
| 桌面展开 | 同一 DOM 横向拉开到 208px 覆盖层，主内容不重排 |
| 自动展开/收起 | 指针停留 40ms 展开；条件解除后 120ms 收起 |

本任务只修改前端壳层、主题动效、相关测试、主题说明和本交接文档，未修改 `Module/`、`app/kernel/`、`app/host/`、`contracts/`、`scripts/`、依赖或产品资料，也未修改 `renderModuleCards` 与 `card.badge`。

### 16.2 实施摘要

- 新增 `--duration-sidebar: 120ms`，克制动效为 80ms，关闭动效与系统减少动效为 0ms。
- 桌面侧栏保持同一 DOM，导航图标固定 34px，导航、工作区、设置与搜索行固定 40px；侧栏使用 `overflow: hidden`，文字只随 208px 宽度增长从轨道边界露出。
- 三套主题的桌面侧栏只过渡 `width var(--duration-sidebar) var(--ease-out)`，不再动画 `padding` 或桌面 `transform`；820px 以下单独保留移动抽屉 `transform` 过渡。
- `renderNavigation` 只渲染单行 `strong`，完整导航说明仍进入 `aria-label`；设置入口可见描述已移除，完整说明保留在 `aria-label`。
- 侧栏内部不再生成 `nav-count`。业务或系统模块只有在带 `moduleId`、计数为有限正整数时，才可能在收起态生成纯数字气泡；零、空值、占位符和非数字均不显示。
- 新增长期位于 `body` 下的 `#sidebar-count-layer`，使用 `aria-hidden="true"`、`pointer-events: none` 和固定定位，根据动态模块按钮的 viewport 纵坐标把数字放在 64px 轨道外。展开开始、移动端、侧栏停用时清空；滚动、导航重绘、窗口缩放与收起完成后重算。
- 工作区 footer 不再显示连接状态文案或绿色健康点，改用本地 `folder` 图标；收起态为 40px 图标区，展开态显示 `.orderly-data/workspace` 单行相对路径。`role="status"`、`aria-live` 与离线“工作区不可用 / 启动宿主”可访问名称保留，不暴露宿主返回的绝对个人路径。
- 搜索、焦点、拖动、设置弹窗抑制收起、移动抽屉 `inert`/ARIA、Escape 和焦点返回路径保持原有行为。

### 16.3 实际验证

```text
npm test
  67 tests, 67 pass, 0 fail

npm run check
  exit 0

git diff --check
  exit 0
  only LF -> CRLF conversion warnings

.\scripts\gate.ps1
  exit 0
  Maven host tests: 49 run, 0 failures, 0 errors, 1 skipped
  Frontend tests: 67 pass
  Module UI contract samples: 4 pass

.\scripts\modularity.ps1
  exit 0

.\scripts\start.ps1 -SkipBuild -Port 18087
  startup success, 127.0.0.1:18087

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18087
  exit 0

Ctrl+C
  Tomcat graceful shutdown complete
```

验证说明：

- `npm test`、`npm run check`、`git diff --check`、完整门禁、模块化检查和真实启动烟测均已由本工程师实际执行。
- HTTP 烟测通过 JDK 21、业务/系统模块目录、模块偏好、模块 UI、通用查询、目录主题详情/CSS、安全回退字段、模块刷新响应和未确认计划拒绝。
- `modularity.ps1` 通过全模块、单模块移除、零模块、恢复、临时模块自动发现与可逆移出、目录主题增删重启生效、无重构建和已选主题失效回退场景。
- Edge 下 40ms/120ms 时序、64px 边界外气泡视觉、固定 40px 行高和移动抽屉的真实浏览器验收仍由主代理负责，本工程师未将其表述为已通过。
- 本工程师未创建 Git commit，也未推送远端；提交和推送由主代理按任务分工执行。

## 17. ORDERLY-FRONTEND-SIDEBAR-CHAT-BUBBLE-EXPANSION 实施交接

### 17.1 状态与范围

| 项目 | 内容 |
|---|---|
| 状态 | 主代理真实浏览器验收与 PM 独立复核完成，结论 `PASS` |
| 任务编号 | `ORDERLY-FRONTEND-SIDEBAR-CHAT-BUBBLE-EXPANSION` |
| 实施日期 | 2026-11-14 |
| 气泡形态 | 左侧带尾圆角聊天气泡，继续由外置 `#sidebar-count-layer` 承载 |
| 收起定位 | 图标轨道右侧 |
| 展开定位 | 模块文字右侧，展开期间按帧跟踪并在 `transitionend` 后最终同步 |
| 默认动效 | `--duration-sidebar: 200ms`；克制动效为 `110ms`；关闭动效与系统减少动效为 `0ms` |

本任务只修改前端侧栏实现、主题动效变量、相关自动化测试、主题说明和本交接文档。未修改 `Module/`、`app/kernel/`、`app/host/`、`contracts/`、`scripts/`、依赖、日期交接文档或两份产品原始资料。

### 17.2 实施摘要

- 外置数量气泡从胶囊角标改为左侧带小尾巴的圆角聊天气泡；尾巴使用固定向左的形状，不随收起或展开改变方向。
- 气泡仍只查询通用 `.nav-item--module[data-module-id]`，继续通过 `navigationCount` 只显示有限正整数。总览等非模块项不生成气泡，`#sidebar-count-layer` 继续保持 `aria-hidden="true"` 和 `pointer-events: none`，真实数量继续进入导航按钮 `aria-label`。
- 外置气泡节点按 `data-module-id` 复用，连续定位不会每帧销毁重建。收起态锚点为图标轨道右缘加 4px；展开态用 `Range.selectNodeContents()` 测量通用 `.nav-copy strong` 的实际文字矩形，将气泡放在文字右缘加 7px 处。若该位置会与 `.nav-drag-handle` 相交，则把气泡移到把手右侧的安全位置。
- 展开开始时不再清空气泡。侧栏展开期间使用 `requestAnimationFrame` 持续同步，`transitionend` 或等长兜底计时器到达后停止跟踪并做最终同步；收起、离开桌面、重复展开和重新展开都会清理旧的帧/事件监听。
- 桌面导航保留整行点击与键盘焦点范围，但悬停和选中背景只作用于固定 34px 图标块，不再铺满展开后的整行。图标仍为 34x34，行高仍为 40px，`padding` 与圆角未改变。
- 三套主题仍只对 `sidebar` 使用 `width var(--duration-sidebar) var(--ease-out)`；没有新增横向文字缩放、纵向位移、高度过渡或字体缩放。移动抽屉继续使用独立 `transform` 过渡，移动端样式规则未改为桌面模式。

### 17.3 自动化验证

```text
app/frontend: npm test
  67 tests, 67 pass, 0 fail

app/frontend: npm run check
  exit 0

.\scripts\gate.ps1
  exit 0
  Maven host tests: 49 run, 0 failures, 0 errors, 1 skipped
  Frontend tests: 67 pass
  Module UI contract samples: 4 pass

git diff --check
  exit 0
  only LF -> CRLF conversion warnings

.\scripts\start.ps1 -SkipBuild -Port 18088
  startup success, 127.0.0.1:18088

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18088
  exit 0, 5 required-free generic queries passed

Ctrl+C
  Tomcat graceful shutdown complete
```

新增或更新的测试覆盖：

- 气泡左侧尾巴、圆角外形及外置非交互层语义。
- 收起态轨道锚点、展开态真实文字 Range 锚点、拖拽把手避让、气泡节点复用和 `requestAnimationFrame` 跟踪。
- `transitionend` 最终同步、重复展开清理和收起恢复。
- 固定 34x34 图标、40px 行高、桌面悬停/选中视觉不铺满整行。
- 默认 200ms、克制 110ms、`motion: none` 与减少动效 0ms。
- 三套主题侧栏规则只使用变量，不包含缩放、字体缩放或内边距/高度时长过渡。

真实浏览器下的展开过程视觉、气泡与拖拽把手间距、三套主题切换观感由主代理验收，最终结果见 17.4；本工程师未将未执行的浏览器视觉检查表述为已通过。

### 17.4 主代理与 PM 最终验收

```text
Edge 真实浏览器，1440px / 1920px
  收起 64px，展开 208px
  全部导航行 40px，全部图标 34x34px
  展开气泡定位到真实文字右缘，首个气泡约为 textRight + 6.6px
  气泡右缘 156px，拖拽把手左缘 180px，无交叠
  paper / moss / night 均显示 3 个模块气泡

Edge CDP 模拟 prefers-reduced-motion: no-preference
  sidebar transition: width 0.2s
  展开 70ms 中间态采样宽度约 197.4px

390px 移动端
  sidebar-count-layer display: none
  气泡数量 0
  移动菜单 aria-expanded=true，抽屉 left:0、transform:0

motion: none 与系统减少动效
  transition: none / 0s

PM 最终结论
  PASS，无阻塞，无返工项
```

当前 Windows 系统实际启用 `prefers-reduced-motion: reduce`，因此普通运行时动画会按安全层设计立即完成；上述 `200ms` 过渡通过 Edge CDP 模拟无减少动效后验证。既有 SSE 活动连接导致的 30 秒优雅停机超时仍属于独立既有问题，本次未修改。

## 18. ORDERLY-FRONTEND-SIDEBAR-CLIP-REVEAL 实施交接

### 18.1 状态与范围

| 项目 | 内容 |
|---|---|
| 状态 | 范围内实现与自动化验证完成，等待 PM 最终验收 |
| 任务编号 | `ORDERLY-FRONTEND-SIDEBAR-CLIP-REVEAL` |
| 实施日期 | 2026-11-14 |
| 桌面覆盖层 | 固定 208px 宽，主网格始终只预留 64px |
| 收起/展开 | 仅通过 `clip-path: inset(...)` 从 64px 边界揭示到完整 208px |
| 移动端 | `clip-path: none`，继续使用独立 `transform` 抽屉 |

本任务只修改前端侧栏样式与计数同步、三套主题动效、相关测试、主题说明、架构基线和本交接文档。未修改 `tokens.css`、`index.html`、`Module/`、`app/kernel/`、`app/host/`、`contracts/`、`scripts/`、依赖、日期交接文档或产品原始资料。

### 18.2 实施摘要

- 桌面侧栏固定为 208px 覆盖层，`app-shell` 仍只预留 64px。收起和展开仅切换 `clip-path`，不再改变 `width`。
- 品牌、搜索框、导航、图标、文字、拖拽把手、footer、sandbox 与设置入口在收起、展开和中间帧使用同一套几何；删除 `.is-expanded` 下的子元素布局切换。文字起点布置在 64px 边界之外，由外层裁切决定是否可见。
- 使用 `sidebar::before` 在 63/64px 处提供固定轨道边缘线，不占用主题可能使用的 `sidebar::after`；桌面不再依赖被裁切的侧栏阴影。
- 三套主题只过渡 `clip-path var(--duration-sidebar) var(--ease-out)`；现有 `200ms`、`110ms`、`0ms` 时长继续来自 tokens。
- 移动端显式设置 `clip-path: none`，继续使用原有 `transform` 抽屉、`inert`、ARIA、Escape 与焦点返回路径。
- 计数气泡读取 `getComputedStyle(sidebar).clipPath`，解析 `inset(...)` 得到当前可见宽度，并按进度在 `railRight + 4px` 与真实文字右缘 `+7px` 之间连续插值。所有测量完成后才写气泡样式，并继续避让拖拽把手。
- 在插值和拖拽把手避让完成后，若 `clip-path` 动画仍在跟踪、当前不是稳定全收起状态，且气泡右缘超过当前可见裁切边界，则将气泡左缘钳制为 `currentVisibleRight - bubbleWidth`；稳定收起态继续使用 `railRight + 4px`。
- 展开和收起都启动同一帧跟踪生命周期；两处 `transitionend` 处理器均只接受 `clip-path`。动效时长 `<= 0` 时直接渲染端点，不启动持续 `requestAnimationFrame`；重复状态切换会清理旧帧、事件监听和兜底计时器。

### 18.3 自动化验证

```text
app/frontend: npm test
  67 tests, 67 pass, 0 fail

app/frontend: npm run check
  exit 0

.\scripts\gate.ps1
  exit 0
  Maven host tests: 49 run, 0 failures, 0 errors, 1 skipped
  Frontend tests: 67 pass
  Module UI contract samples: 4 pass

git diff --check
  exit 0
  only LF -> CRLF conversion warnings

.\scripts\start.ps1 -SkipBuild -Port 18090
  startup success, 127.0.0.1:18090

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18090
  exit 0, 5 required-free generic queries passed

Ctrl+C
  Tomcat graceful shutdown complete
```

新增或更新的静态契约覆盖：

- 桌面固定 208px、64px 网格、clip-path inset、固定 63/64px 边缘线。
- `.is-expanded` 不再包含子元素几何切换，导航、搜索、footer 与文字位置固定。
- 三套主题只使用 `clip-path` 过渡，不再出现 `width` 过渡。
- 两个 `transitionend` 处理器只识别 `clip-path`。
- clip-path inset 解析、当前可见宽度、收起/展开气泡插值、拖拽把手避让及“先读取后写样式”。
- 动画中间帧气泡右缘钳制发生在拖拽把手避让之后、样式写入之前，稳定收起态不受钳制影响。
- `duration <= 0` 端点直出，不启动持续帧循环。
- 移动端 `clip-path: none`、独立 transform 与既有 inert/ARIA 行为。

Playwright 工程自检因本机缺少其配置的 Chrome 可执行文件而未启动；真实浏览器下的中间帧几何、裁切命中、焦点轮廓、气泡连续移动和三主题观感由主代理验收，最终结果见 18.4。本工程师未将未执行的浏览器视觉检查表述为已通过。

### 18.4 主代理与 PM 最终验收

```text
Edge CDP：prefers-reduced-motion: no-preference

约 10.8ms
  仍处于稳定收起，visibleRight=64px

约 14.4ms
  visibleRight=113.36px
  module_fs 气泡右缘=113px
  越界=0px

约 100ms
  visibleRight=202.43px
  气泡右缘=165px
  越界=0px

终态
  visibleRight=208px
  气泡右缘=168px
  文字右缘=139.41px，间距约 6.59px
  拖拽把手间距=12px
  气泡左缘序列：68px -> 91px -> 143px -> 146px

23 帧采样
  动画中间帧气泡越界数量=0
  sidebar/main/导航行/图标/搜索/footer/sandbox/settings 最大 rect 偏差=0px
  clip-path transitionend=1 次

PM 最终结论
  PASS，无阻塞，无返工项
```

主代理完整 `gate.ps1` 退出码 `0`：Maven 49 项、0 失败、0 错误、1 跳过；前端 67 项通过；契约样例 4 项通过。真实启动 `127.0.0.1:18094` 后烟测退出码 `0`，5 个通用查询通过。既有 SSE 活动连接导致的 30 秒优雅停机超时仍属于独立既有问题，本次未修改。

## 19. ORDERLY-FRONTEND-SIDEBAR-LAYOUT-TYPOGRAPHY-REFINEMENT 实施交接

### 19.1 状态与范围

| 项目 | 内容 |
|---|---|
| 状态 | 实现、仓库门禁、真实启动、HTTP 烟测、真实浏览器验收、界面设计复核与 PM 最终只读审核均完成；允许提交 |
| 任务编号 | `ORDERLY-FRONTEND-SIDEBAR-LAYOUT-TYPOGRAPHY-REFINEMENT` |
| 实施日期 | 2026-11-14 |
| 桌面覆盖层 | 固定 272px，主网格始终只预留 72px |
| 桌面功能行 | 搜索、导航、工作区与设置统一 48px 高 |
| 图标尺寸 | 导航 40px、品牌 44px、工作区 36px |
| 内容宽度 | `--content-max: 1520px`，主内容居中并保持对称内边距 |

本任务只修改前端 tokens、基础样式、侧栏轨道宽度回退、三份相关测试、主题说明、架构基线和本交接文档。未修改 `index.html`、`Module/`、`app/kernel/`、`app/host/`、`contracts/`、`scripts/`、依赖、产品资料、日期交接文档、`themes/*/motion.css` 或第 16、17、18 节历史记录。

### 19.2 实施摘要

- 将 `--sidebar-width` 调整为 72px、`--sidebar-expanded-width` 调整为 272px，并新增 48px 行高、40px 图标与 caption/small/body/control/panel-title 语义字号。
- 桌面侧栏继续固定 272px 覆盖层并只过渡 `clip-path`。搜索、导航、工作区与设置行统一 48px；导航图标 40px、品牌图标 44px、工作区图标 36px。展开状态不再切换子元素几何。
- 搜索框使用 `40px minmax(120px, 1fr) auto` 三列、12px 间距和 48px 高输入区，输入框 `min-width: 0`，快捷键列保留且不挤压输入区。
- 工作区路径使用 12px caption 字号、`max-width: 100%`、`min-width: 0`、单行省略和文本溢出 ellipsis；完整工作区状态仍通过既有 `aria-label` 暴露。
- 删除覆盖内容的 `sidebar::before` 边线，改由侧栏背景层绘制 71/72px 轨道边线；展开态清除背景线并继续使用覆盖层自身 272px 右边框。
- 主内容改为 `width: min(100%, --content-max)`、`max-width: 1520px`、`margin-inline: auto` 和 `padding-inline: clamp(24px, 4vw, 48px)`，使 1920px 下左右留白对称且展开侧栏不改变主体几何。
- 821-1080px 宽度下命令栏使用 `clamp(320px, calc(100vw - --sidebar-expanded-width - 48px), 580px)`，避免与展开后的 272px 侧栏相交。
- 导航与控件采用语义字号，正文和辅助信息统一到 caption/small/body/control；为卡片、标题和长文本补充 `min-width: 0` 与 `overflow-wrap: anywhere`，保持页面无水平滚动。
- `app.js` 仅将侧栏轨道宽度回退从 64px 同步为 72px；未新增模块特判，也未改动计数的外置层、ARIA 或 clip-path 跟踪逻辑。

### 19.3 自动化验证

```text
app/frontend: npm test
  69 tests, 69 pass, 0 fail

app/frontend: npm run check
  exit 0

git diff --check
  exit 0
  only LF -> CRLF conversion warnings

.\scripts\gate.ps1（初次运行，环境阻塞）
  exit 1
  scanning modules: module_calendar, module_control, module_fs, module_todo
  Remove-Item: scripts/build.ps1:109
  target/orderly-modules/module_calendar.jar is being used by another process

.\scripts\gate.ps1（隔离 worktree）
  exit 0
  Maven: 49 tests, 0 failures, 0 errors, 1 skipped
  frontend: 69 tests, 69 pass, 0 fail
  module UI contract samples: 4 tests, 4 pass, 0 fail

.\scripts\start.ps1 -SkipBuild -Port 18095（隔离 worktree）
  started: exit not applicable (long-running process)

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18095
  exit 0
  5 generic queries passed

.\scripts\gate.ps1
  exit 0
  Maven: 49 tests, 0 failures, 0 errors, 1 skipped
  frontend: 69 tests, 69 pass, 0 fail
  module UI contract samples: 4 tests, 4 pass, 0 fail

.\scripts\start.ps1 -SkipBuild -Port 18096
  started: exit not applicable (long-running process)

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18096
  exit 0
  5 generic queries passed
```

初次门禁时，端口 8080 由既有 Java 进程 PID 2784 占用，其命令行指向本仓库的 `target/orderly-modules` 和运行中 host JAR。该进程不是本次任务启动，因此未停止或干扰。后续复核确认 PID 2784 已退出，8080 当前无监听。

主代理先保存初次阻塞证据，再将同一份 9 文件改动应用到独立临时 worktree `D:\code\Orderly_framework-v3-sidebar-verify`。临时 worktree 与主工作区的 `git diff` 哈希一致，并在其中完成完整门禁、构建、18095 真实启动和烟测。PID 2784 退出后，主工作区又独立完成最终门禁、18096 真实启动和烟测，结果同样通过。18095 与 18096 均为本次任务启动的验证实例，验收结束后关闭。

新增静态契约覆盖：

- 72px 网格轨道、固定 272px 覆盖层、背景边线与展开态清除背景线。
- 48px 搜索/导航/工作区/设置行、40px 导航图标、44px 品牌图标、36px 工作区图标。
- 搜索输入 `min-width: 0` 与至少 120px 轨道，工作区路径 caption 字号与 ellipsis。
- 1520px 主内容宽度、居中边距和 72px 轨道外文字起点。
- 821-1024px 命令栏按展开侧栏宽度避让。
- 200ms/110ms/0ms 侧栏时长，以及禁止 width、height、font-size、transform 动画和 `zoom`。
- 主题 `sidebar` 仍只过渡 `clip-path`，移动端仍为 `clip-path: none` 与独立 transform。

### 19.4 真实浏览器验收

主代理使用系统 Microsoft Edge，`deviceScaleFactor=1`、`visualViewport.scale=1`，在 18095 实例上执行 111 项浏览器断言并全部通过。覆盖宽度为 821、1024、1440、1920、2560 和 820px，视口高度 900px。

- 821/1024/1440/1920/2560px：侧栏外框固定 272px，收起态 `clip-path` 只露出 72px；`.app-main` 左缘始终为 72px；展开前后搜索、导航、工作区与设置行始终为 48px，导航/搜索图标 40px、品牌图标 44px、工作区图标 36px。
- 展开动画实测存在中间 `clip-path` 帧，最终为 `inset(0px)`；主题只对 `clip-path` 应用 200ms 过渡。展开态背景线清除，`sidebar::before` 与 `sidebar::after` 均为 `none`，没有边线覆盖侧栏内容。
- 所有桌面宽度下搜索输入宽度约 140px，`.orderly-data/workspace` 完整显示且右缘不超过侧栏内容边界；数量气泡与导航文字实际 Range 不重叠，也不与拖拽把手重叠。
- 821/1024px 展开侧栏下命令栏左缘分别为 295px/413px，均位于 272px 侧栏之外；页面 `scrollWidth` 始终等于 `clientWidth`，无水平滚动。
- 1920/2560px 下主内容宽度为 1520px，主区域内左右留白分别为 164/164px 和 484/484px，居中且对称。
- 820px 仍为移动抽屉：工具栏可见，侧栏关闭态使用独立 transform，抽屉打开后左缘 0、视觉宽度 300px、scrim 与 `aria-expanded=true` 同步；移动端不渲染外置计数层，且无水平滚动。
- 界面设计负责人只读复核 821/1024/1440/1920/2560 展开态、820 抽屉关闭/打开态，结论为 `PASS`，未发现层级、间距、裁切、气泡、路径、留白或边界交叠问题。
- PM 最终只读复核确认 9 文件范围、模块边界、主题语义变量、测试与文档事实一致，结论为 `PASS`，无阻断项、无返工项，允许提交。

## 20. ORDERLY-FRONTEND-COLLAPSED-SIDEBAR-ROW-CLIPPING 实施交接

### 20.1 状态与范围

| 项目 | 内容 |
|---|---|
| 状态 | 实现、仓库门禁、真实启动、HTTP 烟测、真实浏览器验收、界面设计复核与 PM 最终审核均完成；允许提交 |
| 任务编号 | `ORDERLY-FRONTEND-COLLAPSED-SIDEBAR-ROW-CLIPPING` |
| 实施日期 | 2026-11-14 |
| 桌面收起态 | 搜索行与工作区行不显示会被 72px 边界横切的整行背景和边框 |
| 图标底座 | 搜索图标 40x40，工作区图标 36x36 |
| 展开态 | 原有搜索行与工作区行背景、边框规则恢复生效 |

本任务只修改 `app/frontend/styles/app.css`、`app/frontend/tests/expanding-sidebar.test.mjs` 和本节交接记录。未修改 272px 外框、72px 轨道、48px 行高、图标尺寸、`clip-path` 属性或时长、移动抽屉、主题、依赖、宿主、模块、契约和脚本。

### 20.2 实施摘要

- 在桌面 `@media (min-width: 821px)` 中新增只匹配 `.sidebar:not(.is-expanded)` 的行状态规则，将 `.search-box` 与 `.sandbox-status` 的边框和背景设为透明。
- 搜索图标使用现有 `--sidebar-icon-size`、`--radius-sm` 和 `--color-surface-muted` 形成完整 40x40 圆角底座；工作区继续沿用原有 36x36 语义底座。
- 收起规则不改变宽高、内边距、位移或动效。展开后 `:not(.is-expanded)` 不再匹配，搜索框原有 66% surface 背景及 focus 边框恢复，工作区原有 line 边框和 54% surface 背景恢复。
- 新规则位于桌面媒体查询内，`<=820px` 移动抽屉不匹配该状态选择器，仍使用独立宽度、`clip-path: none` 和 `transform` 抽屉行为。

### 20.3 自动化验证

```text
app/frontend: npm test
  70 tests, 70 pass, 0 fail

app/frontend: npm run check
  exit 0

git diff --check
  exit 0
  only LF -> CRLF conversion warnings

.\scripts\gate.ps1
  exit 0
  Maven: 49 tests, 0 failures, 0 errors, 1 skipped
  frontend: 70 tests, 70 pass, 0 fail
  module UI contract samples: 4 tests, 4 pass, 0 fail

.\scripts\start.ps1 -SkipBuild -Port 18097
  started: exit not applicable (long-running process)

.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18097
  exit 0
  5 generic queries passed
```

主代理使用系统 Microsoft Edge、night 主题、`deviceScaleFactor=1` 和 `visualViewport.scale=1` 执行 51 项浏览器断言并全部通过，覆盖 821、1024、1440、1920、2560 和 820px：

- 桌面收起态 `.search-box` 与 `.sandbox-status` 计算背景和边框均为 `rgba(0, 0, 0, 0)`，不再绘制会被 72px 裁切线横切的整行外框。
- 搜索图标底座为 40x40px、右缘 62px；工作区图标底座为 36x36px、右缘 56px，均完整位于 72px 轨道内。
- 展开态搜索行恢复 66% surface 背景，工作区行恢复 line 边框和 54% surface 背景；272px 覆盖层、72px 主网格和 48px 行高保持不变。
- 820px 移动抽屉打开后搜索和工作区背景/边框完整，未匹配桌面收起态透明规则，页面无水平滚动。
- 界面设计负责人只读复核收起态、展开态和移动抽屉截图，确认两处裁切残片消失，结论为 `PASS`，未发现新增残线、重叠或层级问题。
- PM 最终只读复核确认范围、模块边界、测试、浏览器证据与文档事实一致，结论为 `PASS`，无阻断项、无返工项，允许提交并推送。
