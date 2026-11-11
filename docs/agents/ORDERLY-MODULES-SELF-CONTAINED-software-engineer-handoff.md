# 软件工程师交接报告

## 状态

- 状态：`DONE_WITH_CONCERNS`
- 任务编号：`ORDERLY-MODULES-SELF-CONTAINED` 与定向返工 `ORDERLY-MODULES-CONTRACT-SAMPLES`
- 当前阶段：首版实现阶段

## 修改文件

完整文件清单以提交中的 `git show --name-status` 为准。主要文件和职责如下：

| 文件或路径 | 变更类型 | 责任说明 |
|---|---|---|
| `app/kernel/src/main/java/com/orderly/kernel/OrderlyModule.java`、`ModuleRuntime.java` | 修改 | 模块生命周期与只读查询 SPI |
| `app/host/src/main/java/com/orderly/host/contract/**` | 修改/新增 | 动态契约、查询、计划规则、模块 UI 资源与严格校验 |
| `app/host/src/main/java/com/orderly/host/runtime/ModuleBootstrap.java` | 新增 | 宿主统一调用模块初始化 |
| `app/host/src/main/java/com/orderly/host/api/**` | 修改 | 通用模块查询/UI 路由，移除固定业务端点并修正 404 |
| `app/host/src/main/java/com/orderly/host/planner/LocalRulePlanner.java` | 修改 | 完全由模块契约驱动计划生成 |
| `app/host/src/main/java/com/orderly/host/runtime/WorkspaceRuntime.java` | 修改 | 移除具体模块种子数据 |
| `app/frontend/src/app.js`、`modules.js`、`api.js`、`settings.js` | 修改/新增 | 动态模块 UI、侧栏、总览、排序和持久化 |
| `app/frontend/styles/app.css`、`app/frontend/index.html` | 修改 | 动态模块样式与载入入口 |
| `Module/module_fs/**`、`module_todo/**`、`module_calendar/**` | 修改/新增 | 契约、查询、计划规则、初始化、执行器和 `orderly-ui/` |
| `contracts/module.schema.json`、`module-ui.schema.json` | 新增 | 模块契约与 UI 公开结构 |
| `contracts/samples/valid/*`、`contracts/samples/invalid/*` | 新增 | 合法/非法模块契约与 UI 样例 |
| `app/host/src/test/**` | 修改/新增 | 通用 API、初始化、零模块、契约和样例测试 |
| `app/frontend/tests/**` | 修改/新增 | 动态模块、顺序、设置与 API 测试 |
| `scripts/build.ps1`、`gate.ps1`、`modularity.ps1`、`smoke.ps1` | 修改 | 清理陈旧 JAR、完整门禁、模块化和烟测 |
| `AGENTS.md`、`docs/agents/**`、`README.md`、`contracts/README.md` | 修改/新增 | 当前实现口径、任务卡、交接和运行说明 |
| `.codex/agents/software-engineer.toml` | 修改 | 对齐已批准的动态模块发现方式 |

## 变更摘要

- 模块 JAR 成为业务契约、只读查询、计划规则、执行器、初始化和 UI 的交付单元。
- 宿主通过外部目录、`URLClassLoader`、`ServiceLoader` 和通用 API 工作，不再保存具体模块映射。
- 前端通过 `/api/modules` 和模块 UI 元数据动态生成侧栏、总览和视图；支持拖动、按钮、键盘排序及刷新持久化。
- 构建前清理 `target/orderly-modules`，删除模块源码后不会加载陈旧 JAR。
- 新增契约/UI schema、合法/非法样例与运行时一致性自动校验，`gate.ps1` 已执行 Java 与 Node 样例校验。

## 边界检查

- 是否新增依赖：否。
- 是否在框架中新增具体业务能力：否。
- 是否将具体模块打进 host fat JAR：否。
- 是否修改两份产品资料：否。
- 是否覆盖无关修改：否。
- 是否物理删除用户文件：否。
- 文件职责是否单一：是；契约、运行时、模块业务和前端各有明确边界。

## 验证

| 检查项 | 命令或方法 | 实际结果 |
|---|---|---|
| 完整门禁 | `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\gate.ps1` | 退出码 `0`；Host `26/26`，前端 `26/26`，UI 契约样例 `2/2`，全部无失败/错误/跳过 |
| 模块化 | `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\modularity.ps1` | 退出码 `0`；全模块、单模块移除、零模块、恢复、临时模块发现/query/planner/可逆移出、主题回退六阶段通过 |
| README 启动脚本 | `start.ps1 -Port 18082 -SkipBuild` | 启动成功，JDK 21，仅监听 `127.0.0.1:18082` |
| HTTP 烟测 | `smoke.ps1 -BaseUrl http://127.0.0.1:18082` | 退出码 `0`；模块目录/UI、5 个通用查询、主题和未执行计划 409 通过 |
| JAR 资源归属 | `jar tf` 审计 | 三个模块 JAR 均含各自 UI、契约和 ServiceLoader；Host 对应条目均为 `0` |
| Git 差异格式 | `git diff --check` | 通过 |
| 测试残留 | 检查端口与 `target` 临时目录 | 实例已停止；`18081/18082` 无监听；模块化临时路径为 `0` |

原始报错或异常：

```text
无未解决报错。Mockito/Java agent 输出为 JDK 21 警告，不影响测试结果。
```

## 风险与未决事项

- 已知风险：模块 JAR 是本地受信任代码，不是安全沙箱。
- 已知风险：不支持热加载；模块变化必须重新构建启动。
- 未验证项：没有在用户真实工作区上执行破坏性删除；这是产品约束，不是测试遗漏。
- 需要 PM 决策：由 PM 只读确认当前任务卡、规则对齐和实际证据。
- 需要主代理处理：最终检查、Git commit 和推送 `origin/framework-v3`。

## 验收状态

尚未获得 `project_manager` 对提交后版本的最终验收。本报告只代表软件工程师与主代理技术自检结果。
