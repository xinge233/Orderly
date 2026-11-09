# 02 · 结构与契约

> 读者：`guardian`（主要）、`engineer`（次要）。这份文件定义"什么放在哪里"和"一份契约长什么样"。
> 本文件是 `docs/MODULAR-CONTRACT.md` 的母本。guardian 落盘时保持字段一一对应，不要增删语义。

---

## 1. 顶层目录：只允许八个位置

```
<repo-root>/
├─ AGENTS.md      唯一入口，agent 必读
├─ prompts/       提示词与规范（只读，不得由实现方修改）
├─ tasks/         任务卡与交接记录
├─ contracts/     唯一允许定义数据结构的地方
├─ control/       控制层（Java）
├─ web/           前端（原生三件套）
├─ docs/          人读规范
└─ scripts/       门禁与启动脚本
```

规则：

1. 顶层出现第九个目录，门禁 G1 直接失败。**点开头的目录例外**（`.git`、`.codex`、`.workbuddy` 等），它们是工具自己的配置位，不参与白名单——但要新增一个点目录也得说一声，别让 `.idea`、`.vscode` 悄悄进来。
2. 想加目录，先写 RFC 到 `contracts/RFC-<编号>-<主题>.md`，`guardian` 批准后同步更新 `docs/` 与本文件，再建目录。
3. `ai/` 在 S1 不存在。S4 会作为第九个位置加入，届时同步更新本文件——**现在不要预留空目录**。

---

## 2. 模块：四件套，位置固定

一个模块由四个文件构成，少一件即为未完成。S1 不实现任何模块，但结构必须现在就定死，否则 S2 会各写各的。

```
control/src/main/java/com/orderly/module/<id_with_underscores>/
    module.json          契约，唯一真相
    <Name>Module.java    实现，标注模块 id
    README.md            写给模型的用法 + 写给人看的边界

control/src/test/java/com/orderly/module/<id_with_underscores>/
    <Name>ModuleTest.java
```

规则：

- **目录名 = id 中的点换成下划线。** `fs.list_items` → `fs_list_items`。
- 测试必须放在 `src/test`，不能和实现放在一起（否则会被打进 jar）。
- 模块自包含：删掉文件夹就等于删掉这项能力，不必去别处清扫残留。任何"模块 A 依赖模块 B 的类"都是违规。
- 模块之间的共享代码只允许放在 `kernel/`，而 `kernel/` 由 guardian 独占，模块 agent 只读。

### 2.1 共享内核与禁区

```
control/src/main/java/com/orderly/kernel/    ModuleRegistry、RiskPolicy、ModuleContract、WorkspacePath
control/src/main/java/com/orderly/exec/      执行引擎、状态机、事件记录（S3 才实现）
control/src/main/java/com/orderly/api/       REST 与 SSE
web/                                         前端
contracts/                                   构建期导出物
```

`engineer` 可以写 `kernel/`、`api/` 的骨架，但**只有 `guardian` 有权定义 `ModuleContract` 的字段**。两者的分工是：guardian 说"契约有哪几个字段、各是什么类型"，engineer 说"这个字段怎么被读取和校验"。

---

## 3. 契约字段

S1 必须把 `contracts/module.schema.json` 定下来，字段如下。**这是全项目唯一允许定义模块结构的地方**，任何一端都不许手写一份。

| 字段 | 类型 | 必填 | 约束 | 用途 |
|---|---|---|---|---|
| `id` | string | 是 | `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$`，全局唯一 | 模块与工具的调用标识，也是目录名来源 |
| `name` | string | 是 | ≤ 24 字，不含标点 | 侧边栏显示名 |
| `summary` | string | 是 | ≤ 60 字，**只描述能力，不描述效果** | 给模型看的工具说明 |
| `risk` | enum | 是 | `read_only` / `write` / `delete` | 决定授权策略与界面流程 |
| `params` | object | 是 | JSON Schema 2020-12，根类型 `object`，`additionalProperties: false`，每个字段必须有 `description` | 参数校验依据 |
| `result` | object | 是 | 键白名单固定，见 §3.2 | 返回值结构 |
| `inverse` | object \| null | 是 | null 时 `irreversible` 必须为 true | 逆操作的声明 |
| `irreversible` | boolean | 是 | 为 true 时 `risk` 强制 `delete` | 标记不可撤销 |
| `ui` | object | 是 | `icon`、`entry`、`accent` 三键，见 §3.3 | 界面插槽声明 |
| `settings` | array | 是 | 无则 `[]`，有则每项符合 §3.4 | 设置项声明 |
| `version` | string | 是 | 语义化版本 | 破坏性变更靠它显式化 |

### 3.1 三档风险

| 档位 | 语义 | 授权方式 | 界面流程 |
|---|---|---|---|
| `read_only` | 不改动任何对象状态 | 默认允许 | 直接执行，不弹确认 |
| `write` | 改动对象状态但可撤销 | 需一次确认 | 计划预览 + 单次确认 |
| `delete` | 移除对象或不可逆 | 需二次确认 | 计划预览 + 二次确认 + 列明影响范围 |

"删得再问一次"是产品承诺，不要在实现里优化掉。

### 3.2 返回值白名单

`result` 只允许下列键，**多一个键，注册器启动即拒绝**：

| 键 | 类型 | 含义 |
|---|---|---|
| `status` | enum | `ok` / `failed` / `partial` |
| `changed` | 结构化数组 | 发生了什么变更，供时间线渲染 |
| `counts` | 数值映射 | 各类计数，供汇总行使用 |
| `errorCode` | enum 字符串 | 失败原因，来自固定错误码表 |
| `inverse` | 对象 \| null | 可逆句柄，撤销时原样交回模块 |

明确禁止：`message`、`summary`、`suggestion`、`detail` 一类自由文本字段。**错误也是结构化的**——错误码 + 可选的结构化定位信息，不是一句话。

### 3.3 `ui` 三键

| 键 | 类型 | 约束 |
|---|---|---|
| `icon` | string | 取自 `docs/UI-SPEC.md` 的图标清单，不得引用外部资源或内联 SVG |
| `entry` | string | 侧边栏插槽名，固定为 `sidebar.item` |
| `accent` | string | 语义色名，取自主题变量表（如 `accent.primary`），**不得是色值** |

### 3.4 `settings` 单项

| 键 | 类型 | 约束 |
|---|---|---|
| `key` | string | 模块内唯一 |
| `label` | string | ≤ 16 字 |
| `control` | enum | `number` / `toggle` / `select` 三者之一，别的一律不支持 |
| `default` | 依控件 | `number` 需配 `min`/`max`/`step`；`select` 需配 `options` |

设置控件只允许这三种，是产品决策而不是实现偷懒：每多一种控件，就要多一套主题适配、多一套降级策略、多一套可访问性处理。

---

## 4. 注册表

`contracts/registry.json` 是构建期导出物，**不手工编辑**。

S1 的目标形态是**空注册表**：

- 注册表存在，结构合法，`modules` 为空数组
- 控制层加载它、校验它、以 0 模块正常启动
- 前端读到空数组时渲染空状态

这条要求的意义在于：如果框架在 0 模块时起不来，说明框架偷偷依赖了某个具体模块，模块化就是假的。**空注册表能跑，是模块化的最小证明。**

登记方式（S2 起生效，S1 只写规则不写实现）：宿主构建时读取模块索引文件，按登记顺序装配并生成注册表。新增模块需要改一行索引。这一行不是麻烦，是设计意图——它让"新增了一个能力"这件事在合并请求里可见、可评审。

---

## 5. 契约的四条治理规则

1. **契约也要被测试。** 每份契约配一组合法与非法样例。只写契约不写样例，等于没验证过。S1 至少要有一份非法样例，且它的报错能指出具体字段。
2. **破坏性变更必须显式。** 删字段、改类型、改必填性都算破坏性变更，必须提升 `version` 并同步两端，不允许悄悄改。
3. **变更顺序不可颠倒。** 先改契约 → 再生成产物 → 最后写实现。反过来做，契约会被实现反向塑形，最终无人敢动。
4. **契约改动需要评审。** 它是三方共用的接口，不能由一方单方面合并。S1 期间评审人就是 `guardian` 自己，但要留下评审记录。

---

## 6. 命名与编码约定

| 对象 | 约定 | 例 |
|---|---|---|
| 模块 id | 小写点分，至少两段 | `fs.list_items` |
| Java 包 | 全小写，包名不含下划线以外的分隔 | `com.orderly.module.fs_list_items` |
| Java 类 | 大驼峰，模块类以 `Module` 结尾 | `ListItemsModule` |
| 契约键 | 小驼峰 | `errorCode` |
| CSS 类 | `kebab-case`，框架类以 `o-` 前缀 | `o-sidebar`、`o-card` |
| CSS 变量 | `--语义层级-用途` | `--accent-primary`、`--surface-card` |
| 事件类型 | 大写下划线 | `STEP_STARTED` |
| 文件编码 | UTF-8，无 BOM，LF | — |

---

## 7. guardian 落盘检查表

把本文件变成 `docs/MODULAR-CONTRACT.md` 时，逐条自查：

- [ ] 顶层目录图与实际仓库一致
- [ ] 契约字段表 12 行齐全，类型与必填列没有空缺
- [ ] 三档风险与界面流程的对应关系写明
- [ ] 返回值白名单与禁止清单并列写出（只写允许的，实现方会自己加）
- [ ] 空注册表要求写明"这是模块化的最小证明"
- [ ] 命名表覆盖 id / 包 / 类 / 键 / CSS / 变量 / 事件七类
- [ ] 文档里没有出现任何具体实现代码
