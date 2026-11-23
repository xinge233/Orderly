#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   使用 PyInstaller onedir 模式构建 Windows AI 可执行文件。
#
# 输入：
#   -OutputDirectory 指定 onedir 输出根目录，默认 target/ai-binary。
#   -PythonExecutable 指定已安装 PyInstaller 的 Python 3.14 解释器。
#
# 副作用：
#   写入 target 下的 PyInstaller 工作目录和 onedir 产物；不读取或写入 API Key。
#
# 安全边界：
#   AI 二进制仍只监听 127.0.0.1，Bearer Token 由启动脚本在进程环境中注入。

param(
    [string]$OutputDirectory,
    [string]$PythonExecutable,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$aiRoot = Join-Path $repoRoot "ai"
$entryPoint = Join-Path $aiRoot "packaging\orderly_ai_entry.py"
$aiEnvScript = Join-Path $aiRoot "scripts\ensure-env.ps1"
$python = if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    Join-Path $aiRoot ".venv\Scripts\python.exe"
}
else {
    [System.IO.Path]::GetFullPath($PythonExecutable)
}
$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $targetRoot "ai-binary"
}
else {
    [System.IO.Path]::GetFullPath($OutputDirectory)
}

function Assert-UnderTarget {
    param([Parameter(Mandatory = $true)][string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $targetPrefix = $targetRoot.TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "AI 打包输出必须位于 target 内：$fullPath"
    }
    return $fullPath
}

$outputRoot = Assert-UnderTarget $outputRoot
$distRoot = Join-Path $outputRoot "dist"
$workRoot = Join-Path $outputRoot "work"
$specRoot = Join-Path $outputRoot "spec"

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    if (-not [string]::IsNullOrWhiteSpace($PythonExecutable)) {
        throw "指定的 AI Python 不存在：$python"
    }
    if (-not (Test-Path -LiteralPath $aiEnvScript -PathType Leaf)) {
        throw "未找到 AI 环境准备脚本：$aiEnvScript"
    }
    $python = & $aiEnvScript
}
if (-not $python -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "AI Python 环境准备失败：$python"
}
if (-not (Test-Path -LiteralPath $entryPoint -PathType Leaf)) {
    throw "未找到 PyInstaller 入口：$entryPoint"
}

& $python -m PyInstaller --version *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "安装 AI 打包依赖：${aiRoot}[packaging]"
    & $python -m pip install --disable-pip-version-check -e "${aiRoot}[packaging]"
    if ($LASTEXITCODE -ne 0) {
        throw "安装 PyInstaller 打包依赖失败，退出码：$LASTEXITCODE"
    }
    & $python -m PyInstaller --version *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "PyInstaller 安装后仍不可用。"
    }
}

foreach ($path in @($distRoot, $workRoot, $specRoot)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
    [System.IO.Directory]::CreateDirectory($path) | Out-Null
}

$arguments = @(
    "-m", "PyInstaller",
    "--noconfirm",
    "--clean",
    "--onedir",
    "--console",
    "--name", "orderly-ai",
    "--distpath", $distRoot,
    "--workpath", $workRoot,
    "--specpath", $specRoot,
    "--paths", (Join-Path $aiRoot "src"),
    "--collect-submodules", "uvicorn",
    "--hidden-import", "uvicorn.logging",
    "--hidden-import", "uvicorn.loops.auto",
    "--hidden-import", "uvicorn.protocols.http.auto",
    "--hidden-import", "uvicorn.protocols.websockets.auto",
    "--hidden-import", "uvicorn.lifespan.on",
    $entryPoint
)

& $python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller 构建 AI 可执行文件失败，退出码：$LASTEXITCODE"
}

$executable = Join-Path $distRoot "orderly-ai\orderly-ai.exe"
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "PyInstaller 未生成预期 AI 可执行文件：$executable"
}

if (-not $SkipVerify) {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try {
        $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
    $tokenBytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($tokenBytes)
    }
    finally {
        $random.Dispose()
    }
    $token = [Convert]::ToBase64String($tokenBytes)
    $previousToken = $env:ORDERLY_AI_IPC_TOKEN
    $previousPort = $env:ORDERLY_AI_PORT
    $process = $null
    try {
        $env:ORDERLY_AI_IPC_TOKEN = $token
        $env:ORDERLY_AI_PORT = [string]$port
        $process = Start-Process `
            -FilePath $executable `
            -ArgumentList "--port $port" `
            -WorkingDirectory (Split-Path -Parent $executable) `
            -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $outputRoot "ai-verify.out.log") `
            -RedirectStandardError (Join-Path $outputRoot "ai-verify.err.log") `
            -PassThru
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        $healthy = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($process.HasExited) {
                throw "AI EXE 验证进程提前退出，退出码：$($process.ExitCode)"
            }
            try {
                $health = Invoke-RestMethod `
                    -Uri "http://127.0.0.1:$port/v1/health" `
                    -Headers @{ Authorization = "Bearer $token" } `
                    -TimeoutSec 3
                if ([string]$health.status -eq "UP") {
                    $healthy = $true
                    break
                }
            }
            catch {
            }
            Start-Sleep -Milliseconds 300
        }
        if (-not $healthy) {
            throw "AI EXE 健康检查超时。"
        }
        $null = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$port/v1/settings" `
            -Headers @{ Authorization = "Bearer $token" } `
            -TimeoutSec 5
    }
    finally {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force
            $process.WaitForExit(5000) | Out-Null
        }
        $env:ORDERLY_AI_IPC_TOKEN = $previousToken
        $env:ORDERLY_AI_PORT = $previousPort
    }
}

Write-Host "AI 可执行文件已生成：$executable"
Write-Output $executable
