#requires -Version 5.1
# 用途：
#   在前台启动已隔离的 Orderly Python AI 服务，作为 Java 宿主 IPC 网关的对端进程。
#
# 输入：
#   -Port 或 ORDERLY_AI_PORT 指定端口，默认 8091；ORDERLY_AI_IPC_TOKEN 必须由调用方临时注入。
#
# 副作用：
#   复用或创建 ai/.venv，随后持续占用端口并运行 orderly_ai，直到服务退出或调用方终止进程。
#
# 安全边界：
#   临时 Bearer Token 只通过当前进程环境传给 Python，不写入磁盘、命令行参数或本脚本输出。
#   服务必须由实现侧保持在回环地址；本脚本不代替 Python 服务执行鉴权或对外网络隔离。

param(
    [int]$Port = $(if ($env:ORDERLY_AI_PORT) { [int]$env:ORDERLY_AI_PORT } else { 8091 })
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# 缺少令牌时在启动前失败，避免服务以不可鉴权状态进入可连接窗口。
if (-not $env:ORDERLY_AI_IPC_TOKEN) {
    throw "启动前必须通过环境变量 ORDERLY_AI_IPC_TOKEN 提供临时鉴权令牌。"
}

# 环境脚本可能安装依赖，返回的绝对路径必须再次校验，防止空输出被当作可用解释器。
$python = & (Join-Path $PSScriptRoot "ensure-env.ps1")
if (-not $python -or -not (Test-Path -LiteralPath $python)) {
    throw "Orderly AI Python 环境准备失败。"
}

# 前台执行以保留 Python 的真实退出状态；调用方负责生命周期和进程清理。
& $python -m orderly_ai --port $Port
