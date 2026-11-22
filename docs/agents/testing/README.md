# AI 测试上下文

本文件是 AI 处理测试新增、删除、覆盖分析、验收和测试规划时的默认上下文。它只提供稳定规则和读取路由，不复制具体用例名称或历史执行记录。

## 1. 默认边界

- AI 默认不得读取或修改 `docs/测试说明与测试记录.md`。
- 该文件是人类维护的完整测试资料，只用于人类查阅、详细用例盘点、历史证据和人工整理。
- 只有用户明确要求查阅时，AI 才能按指定章节或行段有界读取该文件，不能加载全文。
- “有哪些测试用例”的最终事实来源始终是对应测试源码，不是任何 Markdown 清单。
- 不得为了读取测试信息而绕过本路由直接打开人类测试文档。

## 2. 测试位置

| 层级 | 主要位置 | 说明 |
|---|---|---|
| Java 宿主与集成测试 | `app/host/src/test/java` | JUnit 5、Spring Boot、AssertJ、Mockito |
| Java 模块测试 | `Module/*/src/test/java` | 模块自有执行器和回滚行为 |
| Python AI 测试 | `ai/tests` | pytest、FastAPI 测试客户端 |
| 前端 Node 测试 | `app/frontend/tests` | 框架状态、API、布局和交互逻辑 |
| 模块 Node 测试 | `Module/*/src/test/js` | 模块自有 UI 和 JavaScript，文件名 `*.test.mjs` |
| 宿主 JS 契约测试 | `app/host/src/test/js` | 模块 UI、学习数据等跨端契约 |
| 契约样例 | `contracts/samples/valid`、`contracts/samples/invalid` | 合法与非法样例 |
| 系统验收 | `scripts` | 门禁、模块化、烟测、构建和离线包验证 |

测试资源放在同级 `src/test/resources`。模块测试不得导入其他业务模块实现；授权、确认、调度、审计、IPC、模块发现和跨模块验收仍属于宿主或脚本层。

## 3. 默认验证

完整门禁：

```powershell
.\scripts\gate.ps1
```

纯 Markdown 或 DOCX 变更：

```powershell
.\scripts\doc-gate.ps1
```

前端测试：

```powershell
Push-Location .\app\frontend
try {
    npm run check
    npm test
}
finally {
    Pop-Location
}
```

Python 测试：

```powershell
$python = & .\ai\scripts\ensure-env.ps1
Push-Location .\ai
try {
    & $python -m compileall -q src tests
    & $python -m pytest
}
finally {
    Pop-Location
}
```

模块 Maven 测试：

```powershell
mvn -f .\Module\<module_id>\pom.xml test
```

模块 JavaScript 测试由 `scripts/gate.ps1` 自动递归发现 `Module/*/src/test/js/**/*.test.mjs`。

## 4. 测试变更记录

新增、重命名、移动或删除测试方法、测试文件、测试脚本、契约样例或端到端场景时，AI 必须在同一变更中更新当前月份记录：

```text
docs/agents/testing/records/YYYY-MM.md
```

每条记录至少包含：

- 日期。
- 测试层级和相关文件或用例。
- 覆盖的行为或风险。
- 实际执行命令与结果。
- 未运行或受环境阻塞的项目。

结果只允许使用 `PASS`、`FAIL`、`NOT RUN` 或 `BLOCKED`。不得根据历史结果推断当前通过。

## 5. 证据要求

- 定向测试只证明相关局部行为，不替代完整门禁。
- 完整门禁未取得成功退出码时，必须报告 `FAIL` 或 `BLOCKED`，并保留真实报错。
- 文件锁、运行中的应用、缺少依赖或网络问题属于环境阻塞，不得伪称测试通过。
- 允许使用隔离构建目录或临时模块目录完成等价验证，但必须说明实际命令和隔离范围。
- 测试不得通过删除、跳过、弱化断言或吞掉异常制造绿色结果。
- 用户明确要求读取人类测试文档时，只读取与当前问题直接相关的章节。

## 6. 维护规则

- 本文件只保留稳定路由和规则，不增长为第二份完整用例清单。
- 具体用例名称通过测试源码搜索获得。
- 历史执行结果进入对应月份的记录文件。
- 最近一次验证摘要可以写入当前月份记录，不在本文件累积。
- 本文件结构或读取边界变化时，同步更新 `AGENTS.md`、`docs/repo-map.md` 和 `docs/agents/README.md`。
