# 任务卡

## 基本信息

- 任务编号：`ORDERLY-MODULES-CONTRACT-SAMPLES`
- 任务名称：模块契约与模块 UI 清单的样例及自动校验
- 当前阶段：首版实现阶段
- 主代理：`/root`
- 执行角色：`software_engineer`
- PM 预检结论：`READY`（范围来自 PM 对模块自包含改造的定向返工项）

## 目标

为已加入的 `contracts/module.schema.json` 和 `contracts/module-ui.schema.json` 补齐合法与非法样例，并把与运行时一致的契约/UI 校验纳入自动门禁，使新增能力形成“契约、样例、校验”闭环。

## 范围

- 允许读取：整个仓库，但以 `AGENTS.md`、架构基线、协作流程、`contracts/`、宿主契约加载代码与测试、`scripts/gate.ps1` 为主。
- 允许修改：`contracts/**`、`app/host/src/test/**`、`scripts/gate.ps1`。
- 允许在确有必要时最小修改：`app/host/src/main/**` 中契约/UI 校验代码；不得改变正常运行行为。
- 允许创建：`contracts/samples/valid/` 与 `contracts/samples/invalid/` 下的通用契约和 UI 样例。

## 非目标

- 不修改三个正式业务模块的行为、契约字段或 UI。
- 不修改前端行为、模块发现机制、通用查询/UI 路由或排序功能。
- 不修改 `docs/ai 文档.md` 与 `docs/归序Orderly-项目策划书-v7.docx`。
- 不修改 `AGENTS.md`、`.codex/agents/*.toml` 或产品阶段边界。
- 不引入新依赖、远程服务、云能力或伪实现。
- 不得通过删除、跳过、弱化断言或吞掉异常制造绿色结果。

## 必读材料

1. `AGENTS.md`
2. `docs/agents/architecture-baseline.md`
3. `docs/agents/collaboration-workflow.md`
4. `contracts/module.schema.json`
5. `contracts/module-ui.schema.json`
6. `app/host/src/main/java/com/orderly/host/contract/ContractCatalog.java`
7. `app/host/src/test/java/com/orderly/host/contract/ContractCatalogTest.java`
8. `scripts/gate.ps1`

## 模块与依赖约束

- 文件职责边界：schema 声明公开结构；样例表达合法/非法输入；测试验证运行时校验；门禁执行测试。
- 引用方向：门禁调用测试，测试调用宿主校验逻辑；业务模块不得被测试硬编码依赖。
- 是否允许新增目录：允许在 `contracts/samples/{valid,invalid}` 下新增样例文件。
- 是否允许新增依赖：否。
- 是否允许修改契约/接口/UI 插槽：仅限为闭环补齐样例和校验入口，不改变现有运行时公开语义。

## 验收标准

- [ ] 模块契约有至少一个合法样例和代表性非法样例。
- [ ] 模块 UI 清单有至少一个合法样例和代表性非法样例。
- [ ] 合法样例被当前运行时校验接受。
- [ ] 非法样例被当前运行时校验以明确错误拒绝。
- [ ] 样例不依赖 `module_fs`、`module_todo`、`module_calendar`。
- [ ] 校验已接入 `scripts/gate.ps1`，失败时返回非零退出码。
- [ ] 没有范围外文件、依赖、业务实现或断言弱化。
- [ ] 实际执行构建和门禁并保留退出码与测试数量。

## 验证方式

- 当前阶段命令：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\gate.ps1
```

- 预期证据：两条命令退出码均为 `0`；新增样例校验测试被实际执行，无跳过；门禁输出包含契约样例校验通过。

## 风险与阻塞

- 已知风险：JSON Schema 与 Java 运行时校验可能存在表达差异，必须用实际运行时接受/拒绝结果作为证据。
- 待确认事项：无。
- 停止条件：若需要改变公开契约语义、业务模块或新增依赖，停止并交主代理裁决。

## PM 预检意见

- 结论：定向返工可从 `contracts/samples/valid`、`contracts/samples/invalid` 和自动校验入口开始。
- 理由：运行时模块契约与 UI 校验已存在，但 schema、样例和门禁尚未形成闭环。
- 需主代理裁决：无；用户已要求按先前计划继续实施。
