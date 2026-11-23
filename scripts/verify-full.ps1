#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   验证完整 Windows 应用目录的清单、哈希、内置工具链和实际启动链路。
#
# 输入：
#   -AppDirectory 指向 package-full.ps1 生成的 Orderly 应用目录。
#   -SkipLaunch 只验证结构与哈希。
#
# 副作用：
#   在 AppDirectory 同级的 verification-data 下创建隔离用户数据，并实际启动/退出应用。
#
# 安全边界：
#   启动测试移除外部 JAVA_HOME，并使用最小 PATH；应用必须依靠自身内置工具链运行。

param(
    [Parameter(Mandatory = $true)][string]$AppDirectory,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$buildPlanScript = Join-Path $PSScriptRoot "build-plan.ps1"
. $buildPlanScript

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Assert-OrdinaryTree {
    param([Parameter(Mandatory = $true)][string]$Root)
    foreach ($item in Get-ChildItem -LiteralPath $Root -Recurse -Force) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "完整版目录含重解析点：$($item.FullName)"
        }
    }
}

function Assert-SafeRelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or
            [System.IO.Path]::IsPathRooted($Path) -or
            $Path.IndexOf('\') -ge 0 -or
            $Path.StartsWith("/") -or
            $Path -match '^[A-Za-z]:' -or
            @($Path -split "/" | Where-Object {
                [string]::IsNullOrEmpty($_) -or $_ -eq "." -or $_ -eq ".."
            }).Count -gt 0) {
        throw "清单含不安全相对路径：$Path"
    }
}

function Wait-HostHealth {
    param(
        [string]$BaseUrl,
        [System.Diagnostics.Process]$Process
    )
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "完整版启动进程提前退出，退出码：$($Process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/api/health/live" -TimeoutSec 3
            if ([string]$health.status -eq "UP") {
                return
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 500
    }
    throw "完整版宿主健康检查超时：$BaseUrl"
}

function Wait-AiProxy {
    param([string]$BaseUrl)
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $settings = Invoke-RestMethod -Uri "$BaseUrl/api/ai/settings" -TimeoutSec 3
            if ($null -ne $settings) {
                return
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 500
    }
    throw "完整版 AI 服务代理未在 45 秒内就绪：$BaseUrl/api/ai/settings"
}

$appRoot = [System.IO.Path]::GetFullPath($AppDirectory)
if (-not (Test-Path -LiteralPath $appRoot -PathType Container)) {
    throw "完整版应用目录不存在：$appRoot"
}
Assert-OrdinaryTree $appRoot

foreach ($required in @(
    "Orderly.exe",
    "full-runtime-manifest.json",
    "files.sha256",
    "runtime\release",
    "runtime\bin\server\jvm.dll",
    "toolchains\jdk\bin\javac.exe",
    "toolchains\maven\bin\mvn.cmd",
    "ai\orderly-ai.exe",
    "scripts\start.ps1",
    "target\build-manifest.json",
    "target\orderly-modules"
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $appRoot $required))) {
        throw "完整版缺少必需路径：$required"
    }
}

$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (
    Join-Path $appRoot "full-runtime-manifest.json"
) | ConvertFrom-Json
if ([int]$manifest.schemaVersion -ne 1 -or
        [string]$manifest.packageType -ne "orderly-full-windows" -or
        [int]$manifest.jdkMajor -ne 21 -or
        [string]$manifest.launchMode -ne "packaged") {
    throw "完整版运行时清单版本、类型或启动模式无效。"
}
foreach ($artifact in @(
    $manifest.hostJar,
    $manifest.desktopLauncher,
    $manifest.aiExecutable
) + @($manifest.modules)) {
    $relative = [string]$artifact.path
    Assert-SafeRelativePath $relative
    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $appRoot $relative.Replace("/", "\")))
    if (-not $fullPath.StartsWith(
            $appRoot.TrimEnd('\') + '\',
            [System.StringComparison]::OrdinalIgnoreCase
        ) -or
            -not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "完整版清单工件不存在或越界：$relative"
    }
    if ((Get-Item -LiteralPath $fullPath).Length -ne [long]$artifact.size) {
        throw "完整版清单工件大小不符：$relative"
    }
    $expectedHash = [string]$artifact.sha256
    if ((Get-OrderlySha256 $fullPath) -ne $expectedHash.ToLowerInvariant()) {
        throw "完整版清单工件哈希不符：$relative"
    }
}

$checksumPath = Join-Path $appRoot "files.sha256"
$seen = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $checksumPath) {
    if ([string]::IsNullOrWhiteSpace($line)) {
        continue
    }
    if ($line -notmatch '^(?<hash>[0-9a-fA-F]{64})  (?<path>.+)$') {
        throw "files.sha256 行格式损坏：$line"
    }
    $relative = $Matches.path
    Assert-SafeRelativePath $relative
    if ($relative -eq "files.sha256" -or -not $seen.Add($relative)) {
        throw "files.sha256 路径重复或包含自身：$relative"
    }
    $fullPath = Join-Path $appRoot $relative.Replace("/", "\")
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf) -or
            (Get-OrderlySha256 $fullPath) -ne $Matches.hash.ToLowerInvariant()) {
        throw "files.sha256 文件缺失或哈希不符：$relative"
    }
}
$actualFiles = @(
    Get-ChildItem -LiteralPath $appRoot -Recurse -File -Force |
        ForEach-Object { Get-OrderlyRelativePath $_.FullName $appRoot } |
        Where-Object { $_ -ne "files.sha256" -and $_ -ne "app/.jpackage.xml" } |
        Sort-Object
)
if (($actualFiles -join "|") -ne (@($seen | Sort-Object) -join "|")) {
    throw "files.sha256 没有完整覆盖应用目录。"
}

& (Join-Path $appRoot "toolchains\jdk\bin\java.exe") -version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "内置 JDK 无法运行。"
}
& (Join-Path $appRoot "toolchains\maven\bin\mvn.cmd") -version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "内置 Maven 无法运行。"
}

if ($SkipLaunch) {
    Write-Host "完整版结构验证通过：清单、哈希、JDK、Maven 和 AI 可执行文件均有效。"
    exit 0
}

$verificationRoot = Join-Path $appRoot "..\verification-data"
$verificationRoot = [System.IO.Path]::GetFullPath($verificationRoot)
if (Test-Path -LiteralPath $verificationRoot) {
    Remove-Item -LiteralPath $verificationRoot -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($verificationRoot) | Out-Null

$port = Get-FreeTcpPort
$baseUrl = "http://127.0.0.1:$port"
$previousData = $env:ORDERLY_DATA_DIR
$previousPort = $env:ORDERLY_HTTP_PORT
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$process = $null
try {
    $env:ORDERLY_DATA_DIR = $verificationRoot
    $env:ORDERLY_HTTP_PORT = [string]$port
    $env:JAVA_HOME = ""
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"

    $process = Start-Process `
        -FilePath (Join-Path $appRoot "Orderly.exe") `
        -WorkingDirectory $appRoot `
        -WindowStyle Hidden `
        -PassThru
    Wait-HostHealth -BaseUrl $baseUrl -Process $process
    Wait-AiProxy -BaseUrl $baseUrl
    $businessModules = @(
        (Invoke-RestMethod -Uri "$baseUrl/api/modules" -TimeoutSec 5) |
            ForEach-Object { $_ }
    )
    $systemModules = @(
        (Invoke-RestMethod -Uri "$baseUrl/api/system-modules" -TimeoutSec 5) |
            ForEach-Object { $_ }
    )
    $themes = Invoke-RestMethod -Uri "$baseUrl/api/themes" -TimeoutSec 5
    $expectedBusinessCount = @($manifest.modules | Where-Object {
        -not ([string]$_.moduleId).StartsWith(
            "module_system_",
            [System.StringComparison]::Ordinal
        )
    }).Count
    $expectedSystemCount = @($manifest.modules).Count - $expectedBusinessCount
    if ($businessModules.Count -ne $expectedBusinessCount) {
        throw "完整版业务模块数量不符：期望 $expectedBusinessCount，实际 $($businessModules.Count)。"
    }
    if ($systemModules.Count -ne $expectedSystemCount) {
        throw "完整版系统模块数量不符：期望 $expectedSystemCount，实际 $($systemModules.Count)。"
    }
    if (@($themes.items).Count -eq 0) {
        throw "完整版未加载任何目录主题。"
    }
    Invoke-RestMethod `
        -Uri "$baseUrl/api/system/shutdown" `
        -Method Post `
        -Headers @{ "X-Orderly-Action" = "shutdown" } `
        -TimeoutSec 10 | Out-Null
    if (-not $process.WaitForExit(120000)) {
        throw "完整版退出请求后未在 120 秒内结束。"
    }
    Start-Sleep -Seconds 1
    $current = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
    $excludedPids = @($PID, [int]$current.ParentProcessId)
    $remaining = @(Get-CimInstance Win32_Process |
        Where-Object {
            $_.ProcessId -notin $excludedPids -and
            $_.CommandLine -and
            $_.CommandLine.IndexOf($appRoot, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            @("Orderly.exe", "java.exe", "powershell.exe", "pwsh.exe", "cmd.exe", "orderly-ai.exe") -contains $_.Name
        })
    if ($remaining.Count -gt 0) {
        throw "完整版退出后仍有进程引用应用目录：$($remaining.ProcessId -join ',')"
    }
}
finally {
    if ($process -and -not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
    }
    $env:ORDERLY_DATA_DIR = $previousData
    $env:ORDERLY_HTTP_PORT = $previousPort
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}

Write-Host "完整版验证通过：无外部 JDK/Python/Maven 的启动、AI 代理、模块、主题和退出链路均有效。"
