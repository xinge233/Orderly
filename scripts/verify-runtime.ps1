#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   验证预构建运行包的 ZIP、清单、JAR、文件集合和实际无 Maven 启动路径。
#
# 输入：
#   -PackagePath 指向 package-runtime.ps1 生成的 ZIP；同目录必须存在同名 .sha256。
#   -SkipLaunch 只执行结构与哈希验证；默认会实际启动并执行 HTTP 烟测。
#
# 副作用：
#   只在 target/orderly-runtime/verify-* 中解压和运行，结束后清理临时目录。
#
# 安全边界：
#   拒绝路径越界、反斜杠、盘符、重解析点、清单外 JAR 和哈希不符；运行包缺少运行时脚本时失败。

param(
    [Parameter(Mandatory = $true)][string]$PackagePath,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$outputRoot = Join-Path $targetRoot "orderly-runtime"
$verificationRoot = Join-Path $outputRoot ("verify-" + (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [guid]::NewGuid().ToString("N"))
$buildPlanScript = Join-Path $PSScriptRoot "build-plan.ps1"
. $buildPlanScript
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-SafeRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or
            [System.IO.Path]::IsPathRooted($Path) -or
            $Path.IndexOf('\') -ge 0 -or
            $Path.StartsWith("/", [System.StringComparison]::Ordinal) -or
            $Path -match '^[A-Za-z]:') {
        throw "$Description 不是安全的相对路径：$Path"
    }
    $segments = @($Path -split "/")
    if (@($segments | Where-Object {
        [string]::IsNullOrEmpty($_) -or $_ -eq "." -or $_ -eq ".."
    }).Count -gt 0) {
        throw "$Description 含空段、点段或父目录段：$Path"
    }
    if ($Path -match '[\x00-\x1F<>:"|?*]' -or
            @($segments | Where-Object {
                $_.EndsWith(".", [System.StringComparison]::Ordinal) -or
                $_.EndsWith(" ", [System.StringComparison]::Ordinal)
            }).Count -gt 0) {
        throw "$Description 含 Windows 不接受字符：$Path"
    }
}

function Expand-SafeRuntimeZip {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    [System.IO.Directory]::CreateDirectory($Destination) | Out-Null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($entry in $archive.Entries) {
            $relative = $entry.FullName.Replace("\", "/")
            if ([string]::IsNullOrEmpty($entry.Name)) {
                $relative = $relative.TrimEnd("/")
            }
            Assert-SafeRelativePath $relative "ZIP 条目"
            $destinationPath = [System.IO.Path]::GetFullPath(
                (Join-Path $Destination $relative)
            )
            if (-not $destinationPath.StartsWith(
                    $Destination.TrimEnd('\') + '\',
                    [System.StringComparison]::OrdinalIgnoreCase
                )) {
                throw "ZIP 条目超出解压根目录：$relative"
            }
            if ([string]::IsNullOrEmpty($entry.Name)) {
                [System.IO.Directory]::CreateDirectory($destinationPath) | Out-Null
                continue
            }
            [System.IO.Directory]::CreateDirectory((Split-Path -Parent $destinationPath)) | Out-Null
            $stream = $entry.Open()
            try {
                $output = [System.IO.File]::Open(
                    $destinationPath,
                    [System.IO.FileMode]::CreateNew,
                    [System.IO.FileAccess]::Write,
                    [System.IO.FileShare]::None
                )
                try {
                    $stream.CopyTo($output)
                }
                finally {
                    $output.Dispose()
                }
            }
            finally {
                $stream.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

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

function Wait-RuntimeHealth {
    param(
        [Parameter(Mandatory = $true)][string]$BaseUrl,
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$Process
    )
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "运行包启动进程提前退出，退出码：$($Process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/api/health" -TimeoutSec 3
            if ([string]$health.status -eq "UP") {
                return $health
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 500
    }
    throw "运行包健康检查超时：$BaseUrl"
}

$failure = $null
$cleanupFailure = $null
$process = $null
try {
    if (-not (Test-Path -LiteralPath $PackagePath -PathType Leaf)) {
        throw "运行包不存在：$PackagePath"
    }
    $zipPath = (Resolve-Path -LiteralPath $PackagePath).Path
    $sidecar = "$zipPath.sha256"
    if (-not (Test-Path -LiteralPath $sidecar -PathType Leaf)) {
        throw "缺少运行包 SHA-256 文件：$sidecar"
    }
    $sidecarText = (Get-Content -Raw -Encoding UTF8 -LiteralPath $sidecar).Trim()
    if ($sidecarText -notmatch '^(?<hash>[0-9a-fA-F]{64})\s+\*?(?<name>[^\r\n]+)$') {
        throw "运行包 SHA-256 文件格式损坏。"
    }
    if ($Matches.name.Trim() -ne [System.IO.Path]::GetFileName($zipPath)) {
        throw "运行包 SHA-256 文件名不匹配。"
    }
    $actualZipHash = Get-OrderlySha256 $zipPath
    if ($actualZipHash -ne $Matches.hash.ToLowerInvariant()) {
        throw "运行包 ZIP SHA-256 不匹配。"
    }

    [System.IO.Directory]::CreateDirectory($outputRoot) | Out-Null
    Expand-SafeRuntimeZip $zipPath $verificationRoot
    foreach ($item in @(Get-ChildItem -LiteralPath $verificationRoot -Recurse -Force)) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "运行包解压后出现重解析点：$($item.FullName)"
        }
    }

    foreach ($required in @(
        "runtime-manifest.json",
        "files.sha256",
        "启动归序.cmd",
        "scripts/start.ps1",
        "scripts/build-plan.ps1",
        "ai/pyproject.toml",
        "ai/requirements.txt",
        "ai/scripts/ensure-env.ps1",
        "ai/src/orderly_ai",
        "themes"
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $verificationRoot $required))) {
            throw "运行包缺少必需结构：$required"
        }
    }

    $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath (
        Join-Path $verificationRoot "runtime-manifest.json"
    ) | ConvertFrom-Json
    if ([int]$manifest.schemaVersion -ne 1 -or
            [string]$manifest.packageType -ne "orderly-runtime" -or
            [int]$manifest.jdkMajor -ne 21) {
        throw "运行包清单版本、类型或 JDK 不兼容。"
    }
    if (-not (Test-OrderlyManifestArtifact -RepoRoot $verificationRoot -Artifact $manifest.hostJar)) {
        throw "运行包宿主 JAR 无效。"
    }
    $moduleIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $manifestJars = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $hostJarFull = [System.IO.Path]::GetFullPath(
        (Join-Path $verificationRoot ([string]$manifest.hostJar.path))
    )
    $null = $manifestJars.Add($hostJarFull)
    foreach ($module in @($manifest.modules)) {
        if (-not $moduleIds.Add([string]$module.moduleId)) {
            throw "运行包模块重复：$($module.moduleId)"
        }
        if (-not (Test-OrderlyManifestArtifact -RepoRoot $verificationRoot -Artifact $module.artifact)) {
            throw "运行包模块 JAR 无效：$($module.moduleId)"
        }
        $moduleJarFull = [System.IO.Path]::GetFullPath(
            (Join-Path $verificationRoot ([string]$module.artifact.path))
        )
        $null = $manifestJars.Add($moduleJarFull)
    }
    $actualJars = @(Get-ChildItem -LiteralPath $verificationRoot -Recurse -File -Filter "*.jar" |
        ForEach-Object { $_.FullName })
    if ($actualJars.Count -ne $manifestJars.Count -or
            @($actualJars | Where-Object { -not $manifestJars.Contains($_) }).Count -gt 0) {
        throw "运行包 JAR 集合与清单不一致。"
    }

    $checksumPath = Join-Path $verificationRoot "files.sha256"
    $checksumSeen = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    foreach ($line in @(Get-Content -Encoding UTF8 -LiteralPath $checksumPath)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^(?<hash>[0-9a-fA-F]{64})  (?<path>.+)$') {
            throw "files.sha256 行格式损坏：$line"
        }
        $relative = $Matches.path.Replace("\", "/")
        Assert-SafeRelativePath $relative "files.sha256 路径"
        if ($relative -eq "files.sha256" -or -not $checksumSeen.Add($relative)) {
            throw "files.sha256 路径重复或记录了自身：$relative"
        }
        $fullPath = Join-Path $verificationRoot $relative
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf) -or
                (Get-OrderlySha256 $fullPath) -ne $Matches.hash.ToLowerInvariant()) {
            throw "files.sha256 记录的文件缺失或哈希不符：$relative"
        }
    }
    $actualFiles = @(Get-ChildItem -LiteralPath $verificationRoot -Recurse -File |
        ForEach-Object { Get-OrderlyRelativePath $_.FullName $verificationRoot } |
        Where-Object { $_ -ne "files.sha256" } |
        Sort-Object)
    $expectedFiles = @($checksumSeen | Sort-Object)
    if (($actualFiles -join "|") -ne ($expectedFiles -join "|")) {
        throw "files.sha256 文件集合与运行包不一致。"
    }

    if (-not $SkipLaunch) {
        $port = Get-FreeTcpPort
        $baseUrl = "http://127.0.0.1:$port"
        $stdout = Join-Path $verificationRoot "runtime-smoke.out.log"
        $stderr = Join-Path $verificationRoot "runtime-smoke.err.log"
        $powerShellExecutable = (Get-Process -Id $PID).Path
        $processArguments = @(
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            (Join-Path $verificationRoot "scripts\start.ps1"),
            "-RuntimeManifest",
            (Join-Path $verificationRoot "runtime-manifest.json"),
            "-Port",
            [string]$port
        )
        $argumentLine = (@($processArguments | ForEach-Object {
            if ([string]$_ -match '\s') {
                '"' + ([string]$_ -replace '"', '\"') + '"'
            }
            else {
                [string]$_
            }
        }) -join " ")
        $process = Start-Process `
            -FilePath $powerShellExecutable `
            -ArgumentList $argumentLine `
            -WorkingDirectory $verificationRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru
        $null = Wait-RuntimeHealth -BaseUrl $baseUrl -Process $process
        $null = Invoke-RestMethod -Uri "$baseUrl/api/modules" -TimeoutSec 5
        $null = Invoke-RestMethod -Uri "$baseUrl/api/system-modules" -TimeoutSec 5
        $null = Invoke-RestMethod -Uri "$baseUrl/api/themes" -TimeoutSec 5
        Invoke-RestMethod `
            -Uri "$baseUrl/api/system/shutdown" `
            -Method Post `
            -Headers @{ "X-Orderly-Action" = "shutdown" } `
            -TimeoutSec 10 | Out-Null
        if (-not $process.WaitForExit(120000)) {
            throw "运行包烟测完成后启动进程未在 120 秒内退出。"
        }
    }
    Write-Host "运行包验证通过：清单、哈希、模块集合和无 Maven 启动路径均有效。"
}
catch {
    $failure = $_
}
finally {
    try {
        if ($process -and -not $process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
            $process.WaitForExit(5000) | Out-Null
        }
        if (Test-Path -LiteralPath $verificationRoot) {
            $resolved = Assert-OrderlyPathWithin $verificationRoot $outputRoot "运行包验证临时目录"
            if ($resolved.Equals($outputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "拒绝清理运行包输出根目录。"
            }
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
    catch {
        $cleanupFailure = $_
    }
}

if ($failure) {
    $message = "运行包验证失败：$($failure.Exception.Message)"
    if ($cleanupFailure) {
        $message += "；临时目录清理失败：$($cleanupFailure.Exception.Message)"
    }
    throw $message
}
if ($cleanupFailure) {
    throw "运行包验证成功但临时目录清理失败：$($cleanupFailure.Exception.Message)"
}
