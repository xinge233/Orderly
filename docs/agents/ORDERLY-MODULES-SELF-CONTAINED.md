# 任务卡

## 基本信息

- 任务编号：`ORDERLY-MODULES-SELF-CONTAINED`
- 任务名称：模块自包含与动态扩展改造
- 当前阶段：首版实现阶段
- 主代理：`/root`
- 执行角色：`project_manager`、`software_engineer`
- PM 预检结论：`READY`（用户已明确要求按先前计划实施；PM 技术复核已确认主体实现）

## 目标

删除任意 `Module/module_*` 后，只需重新构建并启动，其后端、侧边栏入口、总览卡片、API 和前端视图必须一起消失且程序不报错。每个模块的动作契约、只读查询、本地计划规则、执行器、逆操作和 UI 必须位于自身目录。框架只能通过动态目录、kernel SPI、通用 API 和模块提供的 UI 元数据协作。总览卡片必须支持纵向拖动、按钮与键盘上下移动并持久化；零模块必须合法。

## 范围

- 允许读取：整个仓库及用户指定的产品资料。
- 允许修改：`AGENTS.md`、`.codex/agents/*.toml`、`docs/agents/**/*.md`、`app/kernel/**`、`app/host/**`、`app/frontend/**`、`Module/module_fs/**`、`Module/module_todo/**`、`Module/module_calendar/**`、同级自包含模块目录、`contracts/**`、`themes/**`、`scripts/**`、根目录构建与启动文件。
- 允许创建：上述范围内的模块资源、通用契约、样例、测试和必要文档。

## 非目标

- 不实现运行中热卸载、远程插件下载、签名校验或模块权限沙箱。
- 不接入真实外部大模型、云服务或远端文件操作。
- 不修改 `docs/ai 文档.md` 与 `docs/归序Orderly-项目策划书-v7.docx`。
- 不引入未批准的业务模块或伪实现页面。
- 不物理删除用户文件；文件删除只进入项目回收站。
- 不得通过删除、跳过、弱化断言或吞掉异常制造通过结果。

## 必读材料

1. `AGENTS.md`
2. `docs/agents/architecture-baseline.md`
3. `docs/agents/collaboration-workflow.md`
4. `contracts/*.schema.json` 与 `contracts/README.md`
5. `Module/module_*` 现有契约、执行器和资源
6. `app/host` 的契约、运行时、API 与测试
7. `app/frontend` 的动态模块加载、设置和排序实现
8. `scripts/build.ps1`、`scripts/gate.ps1`、`scripts/modularity.ps1`、`scripts/smoke.ps1`

## 模块与依赖约束

- 文件职责边界：框架负责发现、校验、授权、调度、快照、回滚和统一外观；模块负责自身业务契约、查询、计划规则、执行器、逆操作和 UI。
- 引用方向：`app/host` 不编译依赖任何具体模块；通过模块 JAR、kernel SPI 和通用接口协作。
- 模块 UI 资源只能来自自身 JAR 的 `orderly-ui/module.json`、`module.js`、`module.css`。
- 宿主不得把具体模块 UI 或业务契约打入 fat JAR。
- 是否允许新增依赖：否。
- 是否允许修改契约/接口/UI 插槽：是，本任务需要增加通用查询、计划规则和模块 UI 清单契约。

## 验收标准

- [x] 删除单模块后重建启动，后端、侧栏、总览、API 和前端视图消失且不报错。
- [x] 零模块可启动，模块目录和总览为空，计划器返回澄清问题。
- [x] 每个模块携带自身契约、执行器、查询、计划规则和 UI。
- [x] 通用查询接口和模块 UI 资源接口可用，非法/穿越路径被拒绝。
- [x] 框架源码无具体模块 ID、固定业务端点或固定 UI 映射。
- [x] Host fat JAR 不含具体模块 UI、业务契约或业务模块类。
- [x] 构建清理陈旧 `target/orderly-modules`，删除模块后不会继续加载旧 JAR。
- [x] 总览卡片动态生成，支持拖动、按钮和键盘排序，刷新后保留顺序。
- [x] 模块契约、UI schema、合法/非法样例和自动校验形成闭环。
- [x] 测试、构建、启动、模块化检查和 HTTP 烟测真实执行且未弱化断言。

## 验证方式

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\gate.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Port 18082 -SkipBuild
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:18082
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\modularity.ps1
```

- 短生命周期验证 `gate.ps1`、`smoke.ps1`、`modularity.ps1` 必须以退出码 `0` 完成；Host、前端和 UI 契约测试不得失败/跳过；模块化六阶段必须完成。
- `start.ps1` 是长驻服务命令，不以自行退出 `0` 作为启动成功条件。验收标准为：JDK 21 启动、只监听 `127.0.0.1`、健康检查和 HTTP 烟测通过；随后由验收流程受控终止服务。受控终止导致 `start.ps1` 外层观察非零退出码时，必须保留原始日志并明确标注为测试终止，不得伪装为应用正常退出。
- HTTP 烟测必须使用隔离数据并清理测试残留。

## 风险与阻塞

- 已知风险：外部模块 JAR 是本地受信任代码，不是安全沙箱。
- 已知风险：不实现热加载；模块变化必须重新构建启动。
- 待确认事项：无；用户已明确要求执行该计划。
- 停止条件：需要扩大产品范围、引入依赖、访问项目外个人目录或物理删除数据时停止并请求用户决定。

## PM 预检意见

- 结论：范围与首版实现阶段一致，可以开工。
- 理由：用户明确要求模块自包含、动态扩展、零模块和可持久化排序，并已授权使用 PM 与软件工程师协作。
- 需主代理裁决：无。
