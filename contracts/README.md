# 契约目录

`plan.schema.json` 定义计划与澄清问题的公开结构，`module.schema.json` 定义模块契约，`module-ui.schema.json` 定义模块自带 UI 清单，`theme.schema.json` 定义目录主题包的 `theme.json`，`samples/` 提供前端和测试共享的示例。

`samples/valid/module.json` 与 `samples/valid/module-ui.json` 是宿主和前端实际校验逻辑接受的合法样例；`samples/invalid/` 中的模块契约和 UI 清单会被对应逻辑明确拒绝。`gate.ps1` 同时执行 Java 模块契约样例测试与 Node 模块 UI 样例测试，防止 schema、样例和运行时规则漂移。

`samples/valid/theme.json` 与 `samples/invalid/theme-*.json` 覆盖目录主题的合法清单、不支持的 `schemaVersion`、非法 id、越界样式路径、非 CSS 样式路径和不可接受的 SVG 主动内容。宿主运行时必须同时登记 `tokens.css` 与 `motion.css`，并且只能读取清单登记的资源。

模块契约由各模块 JAR 内的 `orderly-modules/*.json` 提供，宿主在启动时通过统一模式聚合。动作风险、参数与确认词、只读查询和本地计划规则只允许在这些契约中声明，Java 执行器不保存第二套元数据。查询通过通用接口 `GET /api/modules/{moduleId}/query/{queryId}` 调用，框架不包含具体业务端点。

模块 UI 清单位于同一 JAR 的 `orderly-ui/module.json`，并把脚本与样式限制为同目录内的安全资源名。宿主仅通过 `GET /api/modules/{moduleId}/ui/{asset}` 提供 `module.json`、`module.js` 和 `module.css`，不接受目录穿越或任意 classpath 资源。删除模块源码并重新构建后，模块 JAR、契约、查询、侧栏、总览卡片和前端视图必须一起消失；零模块是合法状态。

`trash` 是文件模块唯一的删除语义：对象从工作区移动到 `.orderly-data/trash`，从不执行物理删除。

主题不使用扁平 JSON，也不回退 classpath。`GET /api/themes` 返回可用项、局部问题与默认主题配置状态；`GET /api/themes/{id}` 返回完整清单元数据；`GET /api/themes/{id}/assets/{asset}` 只提供清单白名单内的 CSS、栅格图与字体，不提供 SVG。
