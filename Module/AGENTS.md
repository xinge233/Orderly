# Module/AGENTS.md

本文件是 `Module/` 目录内所有业务模块的常驻规范。模块实现还必须遵守仓库根
`AGENTS.md`、`contracts/module.schema.json` 和宿主实际校验；本文件不替代根规则。

## 1. 模块边界

- 每个 `Module/module_<id>/` 必须自包含契约、执行器、查询、快照、逆操作、UI、
  测试和自有数据。
- 模块不得在编译期或运行期直接导入、实例化、反射调用其他业务模块。
- 模块不得读取、修改或缓存其他模块的数据、集合、设置或执行状态。
- 模块间协作只能由宿主接收有序 Action 数组，逐项校验后调用。
- 模块不得自行判定授权、确认、风险、模块启停状态或执行顺序。
- 模块只能通过宿主传入的 `ModuleRuntime` 访问受控工作区和模块自有状态。

## 2. Action 契约

每个可执行能力必须在 `orderly-modules/*.json` 的 `actions` 中声明：

- `id`、`title`、`description`、`risk`、`confirmationRequired` 和 `parameters`。
- `risk` 只能是 `READ_ONLY`、`WRITE` 或 `DELETE`。
- `WRITE` 和 `DELETE` 必须设置 `confirmationRequired: true`。
- `DELETE` 不要求确认词，但必须明确勾选确认，并且只能把对象移入项目回收站。
- `aiCallable` 缺省为 `true`；仅当 Action 不能被总体 AI 调用时才显式设为 `false`。
- `outputSchema` 应声明 Action 返回值的 JSON 结构。首版只接受模块契约允许的
  JSON Schema 子集。
- Action 参数目前支持 `string`、`boolean` 和 `integer`。
- Action 的全部副作用必须限定在模块自身职责和宿主沙箱内。

## 3. 顺序执行与输出引用

总体 AI 通过宿主提交 Action 数组，宿主严格按数组顺序执行。模块不得自行调度下一项。

后一个 Action 可以引用此前已经完成 Action 的 JSON 输出：

```json
{
  "$fromAction": 0,
  "path": ["output", "entries", 0, "path"]
}
```

- `$fromAction` 只能引用数组中更早且已完成的 Action。
- `path` 必须是非空数组，并以 `output` 开头。
- 宿主解析引用并再次校验目标 Action 参数类型后才执行。
- 引用缺失、越界、循环、输出不符合 schema 或参数类型不匹配时必须失败。
- 模块不得自行解析跨模块引用，也不得持有其他模块的上一步输出副本。

## 4. 快照与回滚

- `prepareSnapshot(...)` 必须在正向执行前运行，只能读取，不得产生副作用。
- 快照和正向输出必须是可 JSON 序列化的对象。
- `execute(...)` 只执行一个已声明 Action。
- `rollback(...)` 必须根据快照和正向输出执行幂等逆操作。
- 宿主在数组首次失败时停止后续 Action，并逆序回滚此前完成的 Action。
- 删除只能进入项目回收站；回滚可以恢复对象，禁止物理删除。
- 执行记录、快照和事件由宿主持久化，模块不得另建跨模块历史。

## 5. 来源与审计

- Action 来源由宿主设置，模块不得接受或伪造 `PLAN`、`AI_DIRECT`、`MODULE_CONTROL`
  或 `SYSTEM` 来源。
- 模块返回的摘要必须准确描述实际结果，不得声称未发生的操作。
- 模块错误应抛出带明确原因的异常，由宿主记录失败和回滚状态。

## 6. 模块验收

新增或修改 Action 时至少验证：

- 契约 schema、合法样例和非法样例。
- Action 归属、模块启用、参数白名单和类型校验。
- 风险等级、确认要求、`aiCallable` 和输出 schema。
- 顺序执行、失败停止、逆序回滚和幂等性。
- 结构化输出引用的成功、越界、缺失和类型错误。
- 删除只进入回收站，不出现物理删除调用。
- 模块不读取其他模块数据，不硬编码其他模块行为。

