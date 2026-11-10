# 契约目录

`plan.schema.json` 定义计划与澄清问题的公开结构，`samples/` 提供前端和测试共享的示例。

工具契约由各模块 JAR 内的 `orderly-modules/*.json` 提供，宿主在启动时通过统一模式聚合。工具风险、参数与确认词只允许在这些契约中声明，Java 执行器不保存第二套元数据。

`trash` 是文件模块唯一的删除语义：对象从工作区移动到 `.orderly-data/trash`，从不执行物理删除。
