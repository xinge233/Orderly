#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   构建或复用已有产物，启动本机 Python AI 服务和 Java 宿主，并在退出时清理本次启动的 AI 子进程。
#
# 输入：
#   -Port 指定 Java HTTP 端口，默认 8080；-SkipBuild 复用已有 JAR。
#   -Offline 将严格 Maven 离线模式及可选 -MavenRepository 透传给 build.ps1；
#   该开关只覆盖 Maven 构建，不宣称 Python/Node 或整个应用离线。
#   JAVA_HOME 必须指向 JDK 21，模块和主题目录由仓库根目录推导。
#
# 副作用：
#   未跳过构建时写入 target、.m2-repository 和 ai/.venv；启动期间占用 Java 端口及一个临时回环端口，
#   Python 子进程当前以隐藏窗口启动且未捕获或转发标准日志，失败主要由启动健康检查和退出码体现；
#   Java 退出后脚本也会终止本次创建的 Python 进程。
#
# 安全边界：
#   每次运行生成随机 Bearer Token，只放入当前进程及 Python 子进程环境，不写入仓库或普通配置。
#   AI 地址和宿主地址固定使用 127.0.0.1；脚本不操作个人目录，运行时数据仍由宿主限定在应用工作区。

param(
    [int]$Port = 8080,
    [switch]$SkipBuild,
    [switch]$Offline,
    [string]$MavenRepository
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$hostRoot = Join-Path $repoRoot "app\host"
$modulesRoot = Join-Path $repoRoot "target\orderly-modules"
$themesRoot = Join-Path $repoRoot "themes"
$aiEnvScript = Join-Path $repoRoot "ai\scripts\ensure-env.ps1"

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$Arguments = @()
    )
    # 原生命令的非零退出是待判断结果，不应先被 Stop 偏好转成无退出码的 PowerShell 异常。
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& $FilePath @Arguments 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        Output = $output
        ExitCode = $exitCode
    }
}

function Get-FreeTcpPort {
    # 让操作系统分配一个仅回环可用的临时端口；释放后仍由后续健康检查确认实际启动成功。
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function New-IpcToken {
    # 使用加密安全随机源生成单次进程令牌，避免可预测令牌被本机其他进程复用。
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
    }
    finally {
        $random.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function Wait-AiService {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [System.Diagnostics.Process]$Process
    )
    # 同时检查子进程早退和健康状态：仅等待 HTTP 可能在 Python 已崩溃时白白占用启动时间。
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Python AI 服务启动失败，退出码：$($Process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/v1/health" -Method Get `
                -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 2
            if ($health.status -eq "UP") {
                return
            }
        } catch {
            # 服务尚未开始监听时健康请求会失败，这是启动轮询的一部分，不掩盖最终的 30 秒超时。
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for Python AI service health: $BaseUrl"
}

# 默认先构建，确保不会在源码已变化但运行 JAR 陈旧时启动应用。
if (-not $SkipBuild) {
    $buildArguments = @{
        SkipTests = $true
    }
    if ($Offline) {
        $buildArguments.Offline = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
        $buildArguments.MavenRepository = $MavenRepository
    }
    & (Join-Path $PSScriptRoot "build.ps1") @buildArguments
}

# 显式校验 JAVA_HOME，而不依赖 PATH，防止构建或启动阶段悄悄切换到其他 JDK。
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "JAVA_HOME 未设置；启动必须显式使用 JDK 21。"
}
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
    throw "JAVA_HOME 下未找到 java.exe：$javaExe"
}
$javaVersion = Invoke-NativeCapture -FilePath $javaExe -Arguments @("-version")
if ($javaVersion.ExitCode -ne 0 -or $javaVersion.Output -notmatch 'version "(?<major>\d+)' -or [int]$Matches.major -ne 21) {
    throw "启动需要 JDK 21，当前 JAVA_HOME Java 输出：`n$($javaVersion.Output)"
}

# 构建约定只产出一个可运行 host JAR；这里按时间选择仅用于解析现有产物。
$jar = Get-ChildItem -LiteralPath (Join-Path $hostRoot "target") -Filter "*.jar" -File |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "未找到可运行的 host JAR。"
}

if ($Offline) {
    # 严格 Maven 离线启动不得触发 ensure-env 的 pip 网络安装；复用已准备好的 AI 环境。
    $python = Join-Path $repoRoot "ai\.venv\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw "严格离线启动需要现有 ai/.venv；未运行 ensure-env.ps1，也不会联网安装 Python 依赖。"
    }
}
else {
    if (-not (Test-Path -LiteralPath $aiEnvScript -PathType Leaf)) {
        throw "未找到 Python AI 环境脚本：$aiEnvScript"
    }
    $python = & $aiEnvScript
}
if (-not $python -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Python AI 环境准备失败。"
}

# 以下所有启动命令都以仓库根为工作目录；finally 必须恢复调用方目录和环境变量。
Push-Location $repoRoot
$aiProcess = $null
$previousToken = $env:ORDERLY_AI_IPC_TOKEN
$previousAiPort = $env:ORDERLY_AI_PORT
try {
    # 先分配端口和令牌，再启动 Python 并等待健康；Java 只会在 AI 已就绪后启动。
    $aiPort = Get-FreeTcpPort
    $aiBaseUrl = "http://127.0.0.1:$aiPort"
    $aiToken = New-IpcToken
    $env:ORDERLY_AI_IPC_TOKEN = $aiToken
    $env:ORDERLY_AI_PORT = [string]$aiPort

    Write-Host "使用 Java：$javaExe"
    Write-Host "模块目录：$modulesRoot"
    Write-Host "AI 服务：$aiBaseUrl"
    Write-Host "本地地址：http://127.0.0.1:$Port/"
    # Python 作为受控子进程后台运行，日志和生命周期由当前启动会话负责。
    $aiProcess = Start-Process -FilePath $python `
        -ArgumentList @("-m", "orderly_ai", "--port", [string]$aiPort) `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -PassThru
    Wait-AiService -BaseUrl $aiBaseUrl -Token $aiToken -Process $aiProcess

    # Java 是前台长驻进程。暂时使用 Continue 以读取真实退出码，随后由本脚本统一转成明确失败。
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $javaExe `
            "-Dorderly.modules-dir=$modulesRoot" `
            "-Dorderly.themes-dir=$themesRoot" `
            "-Dorderly.ai-service.url=$aiBaseUrl" `
            -jar $jar.FullName `
            "--server.port=$Port"
        $appExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($appExitCode -ne 0) {
        throw "应用退出码：$appExitCode"
    }
}
finally {
    # 无论 Java 正常退出、启动失败还是调用方中断，都清理本次创建的 AI 进程并恢复环境，
    # 防止遗留 Python 服务、临时令牌或工作目录影响下一次启动。
    if ($aiProcess -and -not $aiProcess.HasExited) {
        Stop-Process -Id $aiProcess.Id -Force
        $aiProcess.WaitForExit(5000) | Out-Null
    }
    $env:ORDERLY_AI_IPC_TOKEN = $previousToken
    $env:ORDERLY_AI_PORT = $previousAiPort
    Pop-Location
}
