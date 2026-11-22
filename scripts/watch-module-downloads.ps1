#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   在首轮后台模块构建完成后持续发现 Module/module_* 新目录，逐个构建、发布并请求宿主刷新。
#
# 输入：
#   -BaseUrl 指定本地宿主；-InitialBuilderPid 等待首轮构建器退出后再扫描。
#   -SkipTests、-Offline、-MavenRepository、-SkipKernelInstall 透传给 build.ps1。
#
# 副作用：
#   调用 build.ps1 构建单个模块并调用宿主刷新接口；空闲时不持有仓库构建锁。
#
# 安全边界：
#   只接受 Module 下的普通业务模块直接子目录；module_system_* 永远不会进入构建队列。

param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [int]$InitialBuilderPid = 0,
    [switch]$SkipTests,
    [switch]$Offline,
    [string]$MavenRepository,
    [switch]$SkipKernelInstall,
    [ValidateRange(100, 10000)]
    [int]$PollMilliseconds = 750,
    [ValidateRange(5, 3600)]
    [int]$RefreshTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$moduleRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "Module"))
$statusFile = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target\module-build-status.json"))
$priorityFile = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target\module-build-priority.json"))
$buildScript = Join-Path $PSScriptRoot "build.ps1"
$sourcePattern = '^module_[a-z0-9_]+$'
$systemSourcePrefix = "module_system_"
$maxPriorityBytes = 512KB

function Assert-LocalBaseUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $uri = $null
    if (-not [System.Uri]::TryCreate($Url, [System.UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -ne "http") {
        throw "模块下载监督器只接受本地 HTTP 宿主地址：$Url"
    }
    if (@("127.0.0.1", "localhost", "::1") -notcontains $uri.Host) {
        throw "模块下载监督器只允许回环宿主地址：$Url"
    }
    return $uri.GetLeftPart([System.UriPartial]::Authority).TrimEnd("/")
}

function Get-HostBuildSnapshot {
    return Invoke-RestMethod -Uri "$BaseUrl/api/system/module-builds" -Method Get -TimeoutSec 5
}

function Invoke-HostRefresh {
    return Invoke-RestMethod `
        -Uri "$BaseUrl/api/system/modules/refresh" `
        -Method Post `
        -ContentType "application/json" `
        -Body "{}" `
        -TimeoutSec 30
}

function Write-JsonAtomic {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $parent = Split-Path -Parent $Path
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent (
        ".$([System.IO.Path]::GetFileName($Path)).$([guid]::NewGuid().ToString('N')).tmp"
    )
    try {
        $json = $Value | ConvertTo-Json -Depth 8
        [System.IO.File]::WriteAllText(
            $temporary,
            $json + [Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false)
        )
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            $backup = "$Path.backup-$([guid]::NewGuid().ToString('N'))"
            try {
                [System.IO.File]::Replace($temporary, $Path, $backup, $true)
            }
            finally {
                if (Test-Path -LiteralPath $backup) {
                    Remove-Item -LiteralPath $backup -Force
                }
            }
        }
        else {
            [System.IO.File]::Move($temporary, $Path)
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Set-BuildStatusEntry {
    param(
        [Parameter(Mandatory = $true)][string]$SourceName,
        [Parameter(Mandatory = $true)][string]$Status,
        [string]$Message,
        [string]$StartedAt,
        [string]$FinishedAt
    )
    $modules = @()
    if (Test-Path -LiteralPath $statusFile -PathType Leaf) {
        try {
            $current = Get-Content -Raw -Encoding UTF8 -LiteralPath $statusFile | ConvertFrom-Json
            if ($current -and [int]$current.schemaVersion -eq 1 -and $current.modules) {
                $modules = @($current.modules)
            }
        }
        catch {
            $modules = @()
        }
    }
    $existing = $modules |
        Where-Object { $_.sourceName -eq $SourceName } |
        Select-Object -First 1
    $title = if ($existing) { [string]$existing.title } else { $SourceName }
    $caption = if ($existing) { [string]$existing.caption } else { $SourceName }
    $icon = if ($existing) { [string]$existing.icon } else { "modules" }
    $record = [ordered]@{
        sourceName = $SourceName
        moduleId = $SourceName
        title = $title
        caption = $caption
        icon = $icon
        status = $Status
        message = $Message
        priority = 0
    }
    if (-not [string]::IsNullOrWhiteSpace($StartedAt)) {
        $record.startedAt = $StartedAt
    }
    elseif ($existing -and -not [string]::IsNullOrWhiteSpace([string]$existing.startedAt)) {
        $record.startedAt = [string]$existing.startedAt
    }
    if (-not [string]::IsNullOrWhiteSpace($FinishedAt)) {
        $record.finishedAt = $FinishedAt
    }
    $nextModules = @($modules |
        Where-Object { $_.sourceName -ne $SourceName }) + @($record)
    $nextModules = @($nextModules | Sort-Object sourceName)
    $payload = [ordered]@{
        schemaVersion = 1
        mode = "background"
        updatedAt = [DateTime]::UtcNow.ToString("o")
        complete = @($nextModules | Where-Object { $_.status -ne "READY" }).Count -eq 0
        modules = $nextModules
    }
    Write-JsonAtomic -Path $statusFile -Value $payload
}

function Read-PrioritySource {
    if (-not (Test-Path -LiteralPath $priorityFile -PathType Leaf)) {
        return $null
    }
    $item = Get-Item -LiteralPath $priorityFile -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $item.Length -gt $maxPriorityBytes) {
        return $null
    }
    try {
        $value = Get-Content -Raw -Encoding UTF8 -LiteralPath $priorityFile | ConvertFrom-Json
        $sourceName = [string]$value.sourceName
        if ($sourceName -match $sourcePattern -and
                -not $sourceName.StartsWith(
                    $systemSourcePrefix,
                    [System.StringComparison]::Ordinal
                )) {
            return $sourceName
        }
    }
    catch {
        return $null
    }
    return $null
}

function Clear-PriorityRequest {
    if (Test-Path -LiteralPath $priorityFile -PathType Leaf) {
        Remove-Item -LiteralPath $priorityFile -Force
    }
}

function Get-BusinessSourceDirectories {
    if (-not (Test-Path -LiteralPath $moduleRoot -PathType Container)) {
        return @()
    }
    $rootItem = Get-Item -LiteralPath $moduleRoot -Force
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "模块源码根不能是重解析点：$moduleRoot"
    }
    return @(Get-ChildItem -LiteralPath $moduleRoot -Directory -Filter "module_*" |
        Where-Object {
            $_.Name -match $sourcePattern -and
            -not $_.Name.StartsWith($systemSourcePrefix, [System.StringComparison]::Ordinal) -and
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0
        } |
        Sort-Object Name)
}

function Wait-HostReady {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/api/health/live" -Method Get -TimeoutSec 2
            if ([string]$health.status -eq "UP") {
                return
            }
        }
        catch {
            # 宿主仍在启动或退出时继续轮询，最终超时会留下明确错误。
        }
        Start-Sleep -Milliseconds 300
    }
    throw "等待本地宿主存活检查超时：$BaseUrl/api/health/live"
}

function Wait-ModuleReady {
    param([Parameter(Mandatory = $true)][string]$SourceName)
    $deadline = [DateTime]::UtcNow.AddSeconds($RefreshTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $snapshot = Get-HostBuildSnapshot
        $entry = @($snapshot.modules) |
            Where-Object { $_.sourceName -eq $SourceName } |
            Select-Object -First 1
        if ($entry -and $entry.status -eq "READY") {
            return $true
        }
        if ($entry -and $entry.status -eq "FAILED") {
            return $false
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

$BaseUrl = Assert-LocalBaseUrl $BaseUrl
if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) {
    throw "缺少模块构建脚本：$buildScript"
}
if ($InitialBuilderPid -gt 0) {
    while (Get-Process -Id $InitialBuilderPid -ErrorAction SilentlyContinue) {
        Start-Sleep -Milliseconds 300
    }
}
Wait-HostReady

$known = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
$pending = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)
$failed = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::Ordinal
)

$initialSnapshot = Get-HostBuildSnapshot
foreach ($entry in @($initialSnapshot.modules)) {
    $sourceName = [string]$entry.sourceName
    if ($sourceName -notmatch $sourcePattern -or
            $sourceName.StartsWith($systemSourcePrefix, [System.StringComparison]::Ordinal)) {
        continue
    }
    $known.Add($sourceName) | Out-Null
    if ($entry.status -ne "READY") {
        $pending.Add($sourceName) | Out-Null
    }
}

Write-Host "模块下载监督器已启动：$BaseUrl"
while ($true) {
    foreach ($directory in @(Get-BusinessSourceDirectories)) {
        if ($known.Add($directory.Name)) {
            $pending.Add($directory.Name) | Out-Null
            Write-Host "发现新业务模块，等待构建：$($directory.Name)"
        }
    }

    $prioritySource = Read-PrioritySource
    if ($prioritySource) {
        $failed.Remove($prioritySource) | Out-Null
        $known.Add($prioritySource) | Out-Null
        $pending.Add($prioritySource) | Out-Null
        Clear-PriorityRequest
    }

    if ($pending.Count -gt 0) {
        $sourceName = @($pending) | Sort-Object | Select-Object -First 1
        $pending.Remove($sourceName) | Out-Null
        $startedAt = [DateTime]::UtcNow.ToString("o")
        Set-BuildStatusEntry `
            -SourceName $sourceName `
            -Status "BUILDING" `
            -Message "正在构建新下载模块" `
            -StartedAt $startedAt
        Write-Host "开始构建新模块：$sourceName"
        $buildArguments = @{
            ModulesOnly = $true
            ModuleName = $sourceName
            MaxParallel = 1
        }
        if ($SkipTests) {
            $buildArguments.SkipTests = $true
        }
        if ($Offline) {
            $buildArguments.Offline = $true
        }
        if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
            $buildArguments.MavenRepository = $MavenRepository
        }
        if ($SkipKernelInstall) {
            $buildArguments.SkipKernelInstall = $true
        }
        try {
            & $buildScript @buildArguments
            $refresh = Invoke-HostRefresh
            $relatedIssue = @($refresh.issues) |
                Where-Object {
                    $_.code -in @("MODULE_LOAD_FAILED", "MODULE_NOT_BUILT", "RESTART_REQUIRED") -and
                    [string]$_.message -like "*$sourceName*"
                } |
                Select-Object -First 1
            if ($relatedIssue) {
                throw [string]$relatedIssue.message
            }
            if (Wait-ModuleReady -SourceName $sourceName) {
                Set-BuildStatusEntry `
                    -SourceName $sourceName `
                    -Status "READY" `
                    -Message "模块已校验并加载" `
                    -StartedAt $startedAt `
                    -FinishedAt ([DateTime]::UtcNow.ToString("o"))
                Write-Host "新模块已构建并追加：$sourceName"
            }
            else {
                $failed.Add($sourceName) | Out-Null
                Set-BuildStatusEntry `
                    -SourceName $sourceName `
                    -Status "FAILED" `
                    -Message "模块已构建，但宿主未在限定时间内追加" `
                    -StartedAt $startedAt `
                    -FinishedAt ([DateTime]::UtcNow.ToString("o"))
                Write-Warning "新模块已构建，但宿主未在限定时间内追加：$sourceName"
            }
        }
        catch {
            $failed.Add($sourceName) | Out-Null
            Set-BuildStatusEntry `
                -SourceName $sourceName `
                -Status "FAILED" `
                -Message ("构建或加载失败：" + $_.Exception.Message) `
                -StartedAt $startedAt `
                -FinishedAt ([DateTime]::UtcNow.ToString("o"))
            Write-Warning "新模块构建失败：$sourceName；$($_.Exception.Message)"
        }
    }

    Start-Sleep -Milliseconds $PollMilliseconds
}
