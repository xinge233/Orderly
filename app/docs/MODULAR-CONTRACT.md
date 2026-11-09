# 归序 Orderly · 模块契约规范

> 程序根目录为 `app/`。机器可执行的唯一数据结构定义位于 `app/contracts/module.schema.json`；本文解释字段语义，不替代 Schema。

## 1. 冻结范围与字段数量

本规范按 `prompts/02-structure-and-contract.md` §3 的唯一字段表冻结 **11 个顶层字段**。

任务卡和冻结清单中的“12 个字段”与实际字段表冲突。编排者已裁决以字段表为准；不新增无依据的第 12 个字段。若未来需要新字段，必须走 RFC、提升版本并同步 Schema、样例、控制层和前端。

## 2. 仓库位置

用户已明确程序在独立目录中构建，因此 S1 程序结构映射如下：

```text
app/
├─ contracts/      契约、样例、RFC
├─ control/        Java 21 + Spring Boot 控制层
├─ web/            原生 HTML / CSS / JavaScript
├─ docs/           人读规范
└─ scripts/        门禁与启动脚本
```

根目录的 `AGENTS.md`、`prompts/`、`tasks/` 保持为协作入口，不移动、不修改。

## 3. 模块四件套

一个模块只有同时具备契约、实现、说明和测试才算存在。S2 起固定位置为：

```text
app/control/src/main/java/com/orderly/module/<id_with_underscores>/
    module.json
    <Name>Module.java
    README.md

app/control/src/test/java/com/orderly/module/<id_with_underscores>/
    <Name>ModuleTest.java
```

目录名由模块 `id` 中的点替换为下划线得到。模块必须自包含，不得直接依赖另一个模块。共享代码只能进入 `kernel/`，模块对 `kernel/` 只读。

S1 不创建任何真实模块，注册表保持 `modules: []`。

## 4. 十一个顶层字段

| 字段 | 类型 | 必填 | 约束 | 用途 |
|---|---|---|---|---|
| `id` | string | 是 | 匹配 `^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$`，全局唯一 | 调用标识与目录名来源 |
| `name` | string | 是 | 1～24 字，不含标点和符号 | 侧边栏显示名 |
| `summary` | string | 是 | 1～60 字，只描述能力，不描述效果 | 给模型看的工具说明 |
| `risk` | enum | 是 | `read_only` / `write` / `delete` | 决定授权和界面流程 |
| `params` | object | 是 | JSON Schema 2020-12；根类型 object；根 `additionalProperties` 为 false；每个参数必须有 `description` | 参数校验依据 |
| `result` | object | 是 | 顶层键只允许以下五个，`status` 必填 | 返回值结构 |
| `inverse` | object \| null | 是 | null 时 `irreversible` 必须为 true，且 `risk` 必须为 delete | 逆操作声明 |
| `irreversible` | boolean | 是 | true 时 `risk` 必须为 delete | 标记不可撤销 |
| `ui` | object | 是 | 只允许 `icon`、`entry`、`accent` 三键 | 界面插槽声明 |
| `settings` | array | 是 | 无设置为 `[]`；有设置时只允许 number / toggle / select | 设置项声明 |
| `version` | string | 是 | 语义化版本 | 显式化兼容性变化 |

根对象 `additionalProperties` 固定为 false。模块提交任何额外顶层字段都会被拒绝。

## 5. 三档风险

| 风险 | 语义 | 授权方式 | 界面流程 |
|---|---|---|---|
| `read_only` | 不改变任何对象状态 | 默认允许 | 直接执行，不弹确认 |
| `write` | 改变状态且可撤销 | 一次确认 | 计划预览 + 单次确认 |
| `delete` | 移除对象或不可逆 | 二次确认 | 计划预览 + 二次确认 + 列明影响范围 |

强制关系：

- 写/删除操作的 `inverse: null` → `irreversible: true` 且 `risk: delete`
- `irreversible: true` → `risk: delete`
- 纯只读操作是唯一例外：它不改变状态，因此允许 `inverse: null` 和 `irreversible: false`

模块不声明确认次数、确认文案、进度条或撤销按钮。流程由框架根据 `risk` 推导。

## 6. 参数 Schema

`params` 的值本身必须是一份 JSON Schema 2020-12：

- 根 `type` 固定为 `object`
- 根 `additionalProperties` 固定为 false
- `properties` 中的每个参数必须有非空 `description`
- 参数名、类型、枚举、范围和 required 关系由模块唯一真源声明
- 控制层据此校验调用参数，前端不得手写另一份参数定义

## 7. 返回值

### 7.1 白名单

`result` 顶层只允许：

| 键 | 类型 | 含义 |
|---|---|---|
| `status` | `ok` / `failed` / `partial` | 执行状态，必填 |
| `changed` | 结构化对象数组 | 供执行时间线渲染的变更 |
| `counts` | 非负整数映射 | 供汇总行使用 |
| `errorCode` | 大写错误码字符串 | 固定错误码表中的失败原因 |
| `inverse` | object \| null | 撤销时交回模块的句柄 |

### 7.2 禁止清单

明确禁止以下自由文本字段：

- `message`
- `summary`
- `suggestion`
- `detail`
- 其他任何自然语言说明字段

错误必须用结构化错误码和可选定位数据表达，不能返回一句话。

## 8. `ui` 三键

| 键 | 允许值 |
|---|---|
| `icon` | `archive` / `calendar` / `file` / `folder` / `search` / `settings` / `spark` / `todo` |
| `entry` | 固定为 `sidebar.item` |
| `accent` | `accent.primary` / `accent.secondary` |

模块不得提供色值、DOM、CSS、弹窗、进度条或动画时长。完整插槽定义见 `app/docs/UI-SPEC.md`。

## 9. 设置项

| 控件 | 必需字段 | 约束 |
|---|---|---|
| `number` | `default`、`min`、`max`、`step` | `step > 0`；人工复核默认值位于范围内 |
| `toggle` | 布尔型 `default` | 无额外字段 |
| `select` | 字符串型 `default`、`options` | `options` 非空、元素唯一 |

所有控件都有模块内唯一的 `key` 和最多 16 字的 `label`。

## 10. 注册表

`app/contracts/registry.json` 是构建期导出物，不手工拼接模块定义。S1 的唯一合法形态是：

```json
{
  "modules": []
}
```

控制层必须读取、校验并以 0 模块正常启动；前端必须渲染空状态。空注册表能跑是模块化成立的最小证明。

注册表结构由 `app/contracts/registry.schema.json` 定义，模块项必须匹配 `module.schema.json`。

## 11. 契约治理

1. 契约必须由合法和非法样例共同验证。
2. 非法样例每份只错一处，校验错误必须定位到字段。
3. 删除字段、改变类型、改变必填性都属于破坏性变更，必须提升 `version` 并同步所有端。
4. 变更顺序固定：契约 → 生成产物 → 实现。
5. 契约变更必须走 RFC 并由 guardian 裁决。
6. 控制层、前端和文档不得各自复制一套字段表；字段语义以 Schema 为准。

## 12. 命名与编码

| 对象 | 约定 | 示例 |
|---|---|---|
| 模块 id | 小写点分，至少两段，段内不含下划线 | `fs.list.items` |
| Java 包 | 全小写，目录使用下划线 | `com.orderly.module.fs_list_items` |
| Java 类 | 大驼峰，以 `Module` 结尾 | `ListItemsModule` |
| 契约键 | 小驼峰 | `errorCode` |
| CSS 类 | kebab-case，框架前缀 `o-` | `o-sidebar` |
| CSS 变量 | 双连字符语义命名 | `--accent-primary` |
| 事件类型 | 大写蛇形 | `STEP_STARTED` |
| 文件编码 | UTF-8 无 BOM，LF | — |

## 13. 冻结检查

- [x] 字段表列出的 11 个字段均有类型、必填性和约束
- [x] 三档风险与非逆操作强制关系已写入 Schema 和文档
- [x] 返回值白名单与禁止清单并列
- [x] 四个插槽已登记且标注不可改名
- [x] 语义变量最小集与默认值已在 `UI-SPEC.md` 登记
- [x] 提供 1 份覆盖全部顶层字段的合法样例
- [x] 提供 5 份各只错一处的非法样例
- [x] 字段数量冲突已由编排者裁决并以字段表为准

## 14. 样例预期错误

非法样例必须由校验器拒绝，并把错误定位到下列 JSON Pointer：

| 样例 | 预期定位 |
|---|---|
| `missing-risk.json` | `/risk` |
| `type-error.json` | `/name` |
| `extra-field.json` | `/confirmCount` |
| `invalid-risk.json` | `/risk` |
| `irreversible-risk-conflict.json` | `/irreversible` 或 `/risk` |
| `inverse-null-reversible.json` | `/inverse` 或 `/irreversible` |
