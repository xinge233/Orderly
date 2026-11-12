# 界面外观设计角色卡包

本文件夹集中保存 `ui_design_lead` 角色卡，避免角色配置和说明散落到其他目录。

## 文件夹内容

| 文件 | 用途 |
|---|---|
| `agent.toml` | 唯一角色配置与边界声明 |
| `README.md` | 使用方式、权限摘要和维护规则 |

本角色包不包含 ZIP、临时文件或第二份配置副本。

## 角色定位

`ui_design_lead` 负责：

- 界面外观、视觉层级、布局节奏和响应式状态
- 主题语义变量、动效和可访问性策略
- 框架外观与模块自有 UI 的边界检查
- 空模块、加载失败、错误、禁用和空数据状态
- 视觉验收清单和可复现证据

该角色默认只读；只有在 PM 任务卡明确批准后才可修改授权 UI 文件，不负责业务范围裁决、后端接口、契约、授权或最终验收。

## 在新会话中使用

新会话开始时明确要求读取本角色卡，例如：

```text
请读取并严格遵循 .codex/agents/ui-design-lead/agent.toml。
先完成只读界面审查；只有 PM 任务卡明确列出的 UI 文件才允许修改。
```

仓库现有 PM 与软件工程师使用 `.codex/agents/` 顶层的直接 TOML 文件。本角色按照用户要求以文件夹形式交付，不能假定所有客户端都会自动发现嵌套 TOML；在新会话中显式读取 `agent.toml` 是当前可靠的使用方式。若未来客户端确认支持文件夹自动发现，再更新本说明，不要虚构已自动注册。

## 权限摘要

允许读取：

- 根规则、协作文档、产品与界面资料
- `app/frontend/**`、`themes/**`
- 各模块的 `orderly-ui/**`
- 与主题、静态资源和模块 UI 加载有关的宿主实现，仅用于理解边界
- 契约、内核和验证脚本，仅用于理解与验证

仅在 PM 任务卡批准时写入：

- `app/frontend/index.html`
- `app/frontend/src/app.js`
- `app/frontend/src/icons.js`
- `app/frontend/src/settings.js`
- `app/frontend/styles/**`
- `app/frontend/tests/**`
- `themes/**`
- `Module/module_*/src/main/resources/orderly-ui/**`

禁止修改后端、契约、模块 Java 业务代码、构建脚本、两份原始产品资料及 `.orderly-data` 用户内容。更精确的边界以 `agent.toml` 为准。

## 维护规则

- 只在本文件夹维护界面设计角色配置，不在其他目录创建重复角色卡。
- 角色权限、设计边界或产品结构变化时，同步更新 `agent.toml`、本说明、`AGENTS.md` 和 `docs/agents/` 中对应索引。
- 修改后执行 TOML 解析、路径引用检查、`git diff --check` 和相关仓库门禁。
- 验证通过后再按仓库规则提交并推送；不得声称未实际执行的测试通过。
