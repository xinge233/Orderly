param(
    [int]$Port = $(if ($env:ORDERLY_AI_PORT) { [int]$env:ORDERLY_AI_PORT } else { 8091 })
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not $env:ORDERLY_AI_IPC_TOKEN) {
    throw "启动前必须通过环境变量 ORDERLY_AI_IPC_TOKEN 提供临时鉴权令牌。"
}

$python = & (Join-Path $PSScriptRoot "ensure-env.ps1")
if (-not $python -or -not (Test-Path -LiteralPath $python)) {
    throw "Orderly AI Python 环境准备失败。"
}

& $python -m orderly_ai --port $Port
