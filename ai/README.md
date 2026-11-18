# Orderly AI

`ai/` 是归序 Orderly 的本机 AI 决策服务。它只把用户目标、工具卡和短时会话发送给
DeepSeek 或用户配置的 HTTPS OpenAI 兼容服务；它不访问文件、模块、数据库或工作区。
API Key 只保存在 Python 进程内存中。

## 环境与启动

环境脚本可在仓库根目录或其他目录调用：

```powershell
$python = & ".\ai\scripts\ensure-env.ps1"
Write-Output $python
```

脚本会创建或复用 `ai/.venv`，安装 `ai/pyproject.toml` 声明的依赖，并只向管道输出
venv 中 `python.exe` 的绝对路径。启动服务前设置临时 IPC 令牌：

```powershell
$env:ORDERLY_AI_IPC_TOKEN = "<由宿主进程生成并注入的临时令牌>"
.\ai\scripts\start.ps1
```

`start.ps1` 复用 `ensure-env.ps1`。服务固定监听 `127.0.0.1`，默认端口 `8091`，可通过
`ORDERLY_AI_PORT` 或 `-Port` 覆盖端口。`GET /v1/health` 仅返回存活状态且不鉴权；其余
端点都要求 `Authorization: Bearer <ORDERLY_AI_IPC_TOKEN>`。令牌缺失时保护端点失败关闭。

## 接口

- `GET /v1/settings`：返回内存设置；永远包含 `allowSendQueryResultsToModel`，默认 `false`。
- `PUT /v1/settings`：更新 provider、删除直执策略与查询结果隐私开关；省略或留空
  `apiKey` 时保留进程内旧 Key。
- `DELETE /v1/settings`：清除 Key 并恢复 DeepSeek 默认设置。
- `POST /v1/settings/test`：使用当前内存 Key 做最小模型连通性测试。
- `POST /v1/turns`：返回 `ACTIONS|PLAN|QUESTION|QUERY`；`QUERY` 只含 1 至 4 条查询。
- `POST /v1/turns/query-results`：仅在
  `allowSendQueryResultsToModel=true` 时接受结果；最多 4 条，单条 16 KiB、总计 64 KiB；
  只返回 `ACTIONS|PLAN|QUESTION`，禁止循环发起 `QUERY`。

错误统一为 `{code, message, timestamp, details?}`。模型输出不符合契约时只修复一次；
第二次仍非法时返回 `AI_INVALID_RESPONSE`。删除直执最终仍由 Java 宿主强制校验。

## 验证

```powershell
$python = & ".\ai\scripts\ensure-env.ps1"
& $python -m pytest .\ai\tests
```
