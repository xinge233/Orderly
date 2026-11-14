# 归序 Orderly 会话交接：2026-11-14

## 0. 文档用途

本文件用于把当前 Codex 会话中已经确认的项目状态、设计决策、验证证据和风险转移到新的对话。

它不是新的实施授权，也不替代用户指令、PM 任务卡或仓库规则。新会话必须先把本文件当作历史快照，核验实际仓库后再决定是否继续工作。

新对话与旧对话的聊天记录不会自动共享。Codex 会读取当前工作树中的文件，因此可长期保留的项目信息必须写入仓库。官方 OpenAI Docs 对此的说明是：聊天保留自己的记录和启动目录，Codex 读取当前工作树，长期指导应放在 `AGENTS.md` 或已签入仓库的文档中。

## 1. 交接快照

| 项目 | 内容 |
|---|---|
| 交接日期 | 2026-11-14 |
| 仓库 | `D:\code\Orderly_framework-v3` |
| 分支 | `framework-v3` |
| 交接基线提交 | `882d2d3 feat: refine expanding sidebar` |
| 远端 | `origin/framework-v3` |
| 创建文档前工作区 | 干净，本地与 `origin/framework-v3` 同步 |
| 当前实现状态 | 侧边栏拉开幕效与相关验证完成 |
| 后续代码权限 | 未授予新的实现任务；新会话不得把本文件当作开工批准 |

本文件会在交接基线之后形成一个独立的文档提交。新会话不应假定 `HEAD` 仍然等于 `882d2d3`，而应检查提交历史中是否存在该基线及其后的交接文档提交。

## 2. 当前会话已经完成的工作

当前工作聚焦于前端壳层侧边栏的交互与视觉修正，核心结果如下：

- 桌面侧边栏默认宽度为 `64px`，只显示图标轨道。
- 指针进入后约 `40ms` 开始展开，目标宽度为 `208px`。
- 指针离开且没有焦点、搜索、拖拽或设置弹窗抑制时，约 `120ms` 后收起。
- 收起和展开使用同一套 DOM，不发生界面替换，也不缩放图标或导航行。
- 导航图标固定为 `34px`，导航、搜索、设置和工作区行固定约 `40px`。
- 展开只横向拉开侧边栏并露出文字，主内容的预留轨道保持 `64px`，不随展开重排。
- 侧边栏导航不再显示功能描述，只显示单行名称；完整说明保留在可访问名称中。
- 总览导航项不生成数量点或数量气泡。
- 动态模块只有真实正整数数量时才显示纯数字气泡。
- 数量气泡使用 `#sidebar-count-layer`，允许越过 `64px` 侧边栏边界，不裁切、不拦截鼠标，也不显示模块名称。
- 工作区 footer 使用文件夹图标并显示 `.orderly-data/workspace`，不再显示“已连接”。
- `motion: none` 和 `prefers-reduced-motion` 下立即切换，不保留过渡。
- `<=820px` 继续使用原移动端点击抽屉、`inert`、ARIA、Escape 和焦点返回路径。
- 三套主题的桌面侧边栏只对 `width` 做快速过渡；移动端单独保留抽屉 `transform`。

当前没有剩余的侧边栏代码任务。除非用户明确发出新指令，否则新会话不应继续修改侧边栏。

## 3. 关键文件

| 文件 | 当前职责 |
|---|---|
| `app/frontend/index.html` | 侧边栏初始状态、数量气泡承载层和稳定可访问名称 |
| `app/frontend/src/app.js` | 展开/收起时序、焦点与弹窗抑制、数量气泡定位和导航渲染 |
| `app/frontend/styles/app.css` | 桌面图标轨道、固定行高、移动端抽屉和气泡样式 |
| `app/frontend/styles/tokens.css` | 侧边栏宽度、展开宽度和快速时长语义变量 |
| `app/frontend/tests/expanding-sidebar.test.mjs` | 单 DOM、固定尺寸、展开时序、气泡和可访问性测试 |
| `app/frontend/tests/compact-layout.test.mjs` | 紧凑布局与主内容零重排测试 |
| `app/frontend/tests/theme-motion.test.mjs` | 主题动画、关闭动效和移动端隔离测试 |
| `themes/paper/motion.css` | 纸页主题的侧边栏拉开动画取值 |
| `themes/moss/motion.css` | 苔原主题的侧边栏拉开动画取值 |
| `themes/night/motion.css` | 夜间主题的侧边栏拉开动画取值 |
| `docs/agents/ORDERLY-FRONTEND-THEME-MODULARIZATION-HANDOFF.md` | 前端主题模块化与侧边栏完整实施记录，重点阅读第 16 节 |

## 4. 验证证据

以下结果记录于交接基线 `882d2d3` 附近。它们属于历史证据，不代表新会话在当前文件状态上自动重新通过。

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

.\scripts\modularity.ps1
  exit 0

同版本真实启动 + .\scripts\smoke.ps1
  exit 0
  5 个通用查询通过

git diff --check
  exit 0
```

交接前主代理还记录了 Edge 真实交互验收 `16/16` 通过，覆盖：

- `40ms` 展开与 `120ms` 收起的真实时序。
- `64px` 到 `208px` 的同一 DOM 拉开。
- 固定图标和固定行高。
- 主内容零重排。
- 越过 `64px` 边界的纯数字气泡。
- 总览无数量节点。
- 工作区完整相对路径。
- `motion: none`。
- 移动端行为隔离。

保存的截图存在：

```text
target/sidebar-pull-1440-collapsed.png
target/sidebar-pull-1440-expanded.png
target/sidebar-pull-1920.png
target/sidebar-pull-390.png
C:\Users\lecoo\.codex\visualizations\2026\11\12\01a1d049-c5aa-7c11-b5cd-a0beed734374\orderly-sidebar-pull-concept-16x9.png
```

最后一张概念图尺寸为 `1920x1080`。

Windows 当前权限无法创建符号链接，因此相关 Maven 用例被明确跳过。不得把该跳过项描述为通过。

## 5. 已拍板且不得静默改动的约束

- 计划先于执行，未经确认的计划不得产生真实文件变更。
- 删除只能进入项目回收站，禁止物理删除。
- 授权、快照和回滚由框架统一担保，模块不得绕过。
- 框架与具体模块分离，框架不得引用具体模块实现。
- 主题只包含取值和表现层，不支持主题 JavaScript。
- 主题颜色、字体和动效时长必须使用语义变量。
- 模块内部动画归模块自身，框架主题不得侵入模块内部 DOM。
- 设置界面保持弹窗结构。
- 当前阶段不得接入云服务、密钥或远端文件操作。
- 未实现能力必须明确标为“占位”或“尚未接入”。
- 不修改两份既有产品原始资料，除非用户明确要求。
- 测试结论必须来自实际执行；未运行项必须标为未验证。
- 完成代码或文档更新后，必须创建 Git commit 并推送到 `origin/framework-v3`；推送失败必须如实报告。

用户偏好：

- 中文沟通。
- 界面要求紧凑、快速、尺寸精确。
- 侧边栏展开必须是“横向拉开”，不能缩放内容。
- 用户说“只读”时，只能分析或生成概念图，不得修改代码。
- 发现需求不合适时，应先反驳和说明，不应机械执行。

## 6. 已知风险和未决事项

### 6.1 工作区路径显示

当前 footer 固定显示 `.orderly-data/workspace`。如果以后通过 `ORDERLY_DATA_DIR` 使用自定义数据目录，该显示可能不准确。

后续若要显示真实动态路径，必须单独设计，并继续避免向界面暴露绝对个人路径。

### 6.2 优雅停机

既有 SSE 长连接会使优雅停机等待约 30 秒，并出现 async timeout warning。这是独立既有问题，不属于本次侧边栏任务，尚未修复。

### 6.3 Windows 符号链接测试

当前 Windows 权限不能实际创建符号链接，相关测试被明确跳过。不得降低断言或伪造通过来消除跳过项。

### 6.4 历史证据时效

本文件中的测试数量和结果对应交接基线。新会话如果修改了任何文件，必须重新运行与变更相关的测试，不能继续引用旧结果。

## 7. 新对话的正确读取方式

### 7.1 Codex 桌面端

1. 确保新对话属于本地项目 `D:\code\Orderly_framework-v3`，不要选择无项目对话。
2. 打开新对话，首条消息直接写文件路径并要求先核验状态。
3. 不要只说“继续之前的工作”，因为聊天记录不会自动共享。

推荐首条消息：

```text
请继续 Orderly 项目的未完成会话。先只读执行以下步骤：

1. 确认当前目录是 D:\code\Orderly_framework-v3。
2. 读取 AGENTS.md。
3. 读取 docs/agents/architecture-baseline.md。
4. 读取 docs/agents/collaboration-workflow.md。
5. 读取 docs/agents/ORDERLY-SESSION-HANDOFF-2026-11-14.md。
6. 读取 docs/agents/ORDERLY-FRONTEND-THEME-MODULARIZATION-HANDOFF.md 的第 16 节。
7. 执行 git status --short --branch、git log -6 --oneline --decorate 和 git remote -v。

完成只读核对后，先向我报告：
- 当前分支、HEAD 和远端同步状态；
- 实际工作区是否干净；
- 交接文档与真实仓库是否一致；
- 当前是否还有已获授权的代码任务；
- 你建议的下一步。

在报告前不要修改文件，也不要把交接文档中的历史建议当成新的开工授权。
```

### 7.2 Codex CLI

从项目根目录启动：

```powershell
Set-Location 'D:\code\Orderly_framework-v3'
codex
```

进入会话后使用上面的推荐首条消息。OpenAI Docs 说明：`/new` 用于新对话，`/resume` 或 `codex resume` 用于继续已保存对话。

### 7.3 新会话完成读取后的最低自检

```powershell
git status --short --branch
git log -6 --oneline --decorate
git remote -v
git merge-base --is-ancestor 882d2d3 HEAD
```

预期：

- 当前分支为 `framework-v3`。
- `882d2d3` 是当前 `HEAD` 的祖先提交。
- `HEAD` 可以包含本交接文档产生的后续文档提交。
- 工作区没有未解释的改动。
- `origin` 指向项目当前 GitHub 仓库。

如果分支、远端、基线祖先关系或工作区状态不符，新会话应先报告差异并停止实施，不得静默覆盖或猜测。

## 8. 新会话的下一步原则

- 如果用户只是继续询问状态，保持只读并先核验仓库。
- 如果用户要求继续侧边栏工作，先确认具体新需求，再建立新的任务范围。
- 如果用户要求代码或文档改动，按 `AGENTS.md` 和协作流程执行：PM 任务卡、实施、独立复核、主代理检查、提交和推送。
- 如果用户再次说“只读”，不得修改文件或提交。
- 任何新的实现都不得以“之前已经同意”为由绕过当前用户的新授权。

## 9. 官方读取依据

- OpenAI Docs，Projects and chats：<https://learn.chatgpt.com/docs/projects.md>
- OpenAI Docs，ChatGPT desktop app：<https://developers.openai.com/codex/app.md>

## 10. 学习工作台扩展实施交接（2026-11-14）

本节记录交接基线 `3eceb34` 之后新增的学习界面参考扩展。第 0 至 9 节仍是侧边栏阶段的历史快照，不应覆盖本节的新事实。

### 10.1 用户已确认范围

- 新增浅色 `study`（研习蓝）目录主题，视觉取色与层级参考学习高保真原型。
- 新增三个独立只读模块：`module_study_assistant`（AI 学习助手）、`module_study_map`（知识脉络）、`module_study_review`（复习自测）。
- 将 `module_fs` 的主视图改为资料管理样式，同时保留原有文件动作、授权、确认、快照、回滚和项目回收站语义。
- 学习数据保存在各模块自身目录，通过一套只读契约校验，不建立集中式学习数据目录，也不允许模块互相读取数据。
- 上传、AI 整理、任务记录、自动出题、自动阅卷保留为明确标注“尚未接入”的界面状态；AI 助手只显示本地规则边界和只读示例。

### 10.2 模块与数据边界

| 模块 | 视图 ID | 查询 | 模块内数据 |
|---|---|---|---|
| `module_study_assistant` | `study-assistant` | `study.assistant.read` | `orderly-data/module_study_assistant.json` |
| `module_study_map` | `study-map` | `study.map.read` | `orderly-data/module_study_map.json` |
| `module_study_review` | `study-review` | `study.review.read` | `orderly-data/module_study_review.json` |
| `module_fs` | `files` | `fs.materials` | `orderly-data/module_fs.json` |

四个业务模块由同一宿主聚合类加载器加载。学习数据资源名必须带模块 ID，不能共用 `orderly-data/study-data.json`，否则 `Class.getResourceAsStream` 会首先解析到 JAR 搜索顺序中的同一份数据。该问题已在集成阶段复现并修复。

`contracts/study-data.schema.json` 定义 `assistant`、`materials`、`knowledge-map`、`review` 四种负载。`scripts/validate-study-data.mjs` 同时校验模块契约/UI 清单中的严格 JSON 重复键、模块文件、合法/非法样例、模块归属、`readOnly: true`、集合计数、来源引用、知识图边端点、AI 外部模型声明和复习阅卷模式。

### 10.3 验证证据

- `node scripts/validate-study-data.mjs`：通过。
- `.\scripts\gate.ps1`：通过；Maven 49 项、1 项既有跳过；前端 70 项；契约 10 项。
- `.\scripts\modularity.ps1`：通过全模块、单模块移除、零模块、恢复、临时模块自动发现与可逆移出、目录主题增删和主题失效回退。
- `.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18098`：通过，四个学习查询均在宿主内返回符合契约的数据；共执行 9 条无必填参数查询。
- 通用集成测试与启动烟测不限制业务模块总数，也不要求任何具体业务模块存在；唯一强制模块是受保护的系统模块 `module_control`，模块偏好必须返回 `moduleSettings`。学习模块查询仅在该模块实际安装时校验。
- Edge 实测 `devicePixelRatio=1`：820、821、1024、1440、1920 与 390px 均无横向溢出；桌面展开侧栏 272px，主网格左缘保持 72px，侧栏覆盖伪元素为 `none`。
- 四视图实测：资料管理 8 行、知识图 31 节点、复习题 6 道、AI 学习助手明确显示“本地规则”和“未接入外部模型”；设置中的主题选项包含 `paper`、`moss`、`night`、`study`，可从其他主题切换回 `study`；待接入按钮与未持久化标记提示正确。
- 四个模块 JAR 均包含各自的 SPI、模块契约、UI 三件资源与模块唯一学习数据文件。

### 10.4 当前限制

- 学习模块是界面参考扩展，不代表 V7 已承诺通用 AI 学习业务。
- 不连接外部模型，不实现上传、自动整理、任务记录持久化、自动出题、自动阅卷或掌握状态写回。
- 浏览器验收基于应用自管测试数据；真实个人目录仍不在当前授权范围。
- `docs/~$Orderly-项目策划书-v7.docx` 是用户 Word 程序产生的临时锁文件，不属于实现产物，不提交。
