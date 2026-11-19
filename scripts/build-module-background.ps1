#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   在 Java 框架已经启动后，按用户优先请求逐个构建 Module/module_* 业务模块。
#   每完成一个模块即调用宿主刷新接口；单个模块失败不会阻断其他模块继续构建。
#
# 输入：
#   -BaseUrl 指定已启动的本地宿主；-SkipTests、-Offline、-MavenRepository 透传给 build.ps1。
#   -SkipKernelInstall 表示 BootstrapOnly 已安装 kernel，后续模块不再重复安装。
#   -HealthTimeoutSeconds 限制等待宿主健康状态的时间。
#
# 副作用：
#   写入 target/module-build-status.json、target/module-build-priority.json 的消费状态，
#   调用 build.ps1 构建模块，并通过宿主刷新接口追加已校验 JAR。
#
# 安全边界：
#   只扫描 Module/module_* 普通直接子目录；构建状态中的源码标题只用于展示，不构成契约。
#   JAR 的发布和合同校验仍由 build.ps1 完成，执行资格只能由宿主校验后的 JAR 获得。

param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [switch]$SkipTests,
    [switch]$Offline,
    [string]$MavenRepository,
    [switch]$SkipKernelInstall,
    [ValidateRange(5, 3600)]
    [int]$HealthTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$moduleRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "Module"))
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$statusFile = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "module-build-status.json"))
$priorityFile = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "module-build-priority.json"))
$buildScript = Join-Path $PSScriptRoot "build.ps1"
$sourcePattern = '^module_[a-z0-9_]+$'
$controlSource = "module_control"
$maxStatusBytes = 512KB

function Assert-LocalBaseUrl {
    param([Parameter(Mandatory = $true)][string]$Url)
    $uri = $null
    if (-not [System.Uri]::TryCreate($Url, [System.UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -ne "http") {
        throw "后台模块构建只接受本地 HTTP 宿主地址：$Url"
    }
    $localNames = @("127.0.0.1", "localhost", "::1")
    if ($localNames -notcontains $uri.Host) {
        throw "后台模块构建只允许回环宿主地址：$Url"
    }
    return $uri.GetLeftPart([System.UriPartial]::Authority).TrimEnd("/")
}

function Assert-PlainFileOrMissing {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or
            $item.PSIsContainer) {
        throw "$Description 必须是普通文件：$Path"
    }
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

function Read-PrioritySource {
    Assert-PlainFileOrMissing -Path $priorityFile -Description "模块构建优先请求"
    if (-not (Test-Path -LiteralPath $priorityFile -PathType Leaf)) {
        return $null
    }
    if ((Get-Item -LiteralPath $priorityFile).Length -gt $maxStatusBytes) {
        return $null
    }
    try {
        $value = Get-Content -Raw -Encoding UTF8 -LiteralPath $priorityFile | ConvertFrom-Json
        $sourceName = [string]$value.sourceName
        if ($sourceName -match $sourcePattern -and $sourceName -ne $controlSource) {
            return $sourceName
        }
    }
    catch {
        return $null
    }
    return $null
}

function Clear-PriorityRequest {
    Assert-PlainFileOrMissing -Path $priorityFile -Description "模块构建优先请求"
    if (Test-Path -LiteralPath $priorityFile -PathType Leaf) {
        Remove-Item -LiteralPath $priorityFile -Force
    }
}

function Get-ModuleMetadata {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo]$Directory
    )
    $fallback = [ordered]@{
        title = $Directory.Name
        caption = $Directory.Name
        icon = "modules"
    }
    $manifest = Join-Path $Directory.FullName "src\main\resources\orderly-ui\module.json"
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        return $fallback
    }
    Assert-PlainFileOrMissing -Path $manifest -Description "模块 UI 清单"
    try {
        $root = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifest | ConvertFrom-Json
        $caption = [string]$root.view.caption
        $title = [string]$root.card.title
        $iconName = [string]$root.view.icon
        if (-not [string]::IsNullOrWhiteSpace($caption)) {
            $fallback.caption = $caption.Trim()
        }
        if (-not [string]::IsNullOrWhiteSpace($title)) {
            $fallback.title = $title.Trim()
        }
        if (-not [string]::IsNullOrWhiteSpace($iconName)) {
            $fallback.icon = $iconName.Trim()
        }
    }
    catch {
        # 清单只用于待构建导航展示；解析失败时保留目录名，不提升为契约事实。
    }
    return $fallback
}

function Get-SourceDirectories {
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
            $_.Name -ne $controlSource -and
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0
        } |
        Sort-Object Name)
}

function Wait-HostHealth {
    $uri = "$BaseUrl/api/health"
    $deadline = [DateTime]::UtcNow.AddSeconds($HealthTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 2
            if ([string]$health.status -eq "UP") {
                return $health
            }
        }
        catch {
            # 框架尚未开始监听时继续轮询，最终超时会保留明确失败。
        }
        Start-Sleep -Milliseconds 300
    }
    throw "等待本地宿主健康检查超时：$uri"
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

function Set-BuildState {
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)][ValidateSet("QUEUED", "BUILDING", "READY", "FAILED")]
        [string]$Status,
        [string]$Message,
        [switch]$Start,
        [switch]$Finish
    )
    $Entry.status = $Status
    $Entry.message = $Message
    if ($Start) {
        $Entry.startedAt = (Get-Date).ToUniversalTime().ToString("o")
        $Entry.finishedAt = $null
    }
    if ($Finish) {
        $Entry.finishedAt = (Get-Date).ToUniversalTime().ToString("o")
    }
}

function Publish-BuildStatus {
    param([Parameter(Mandatory = $true)][object[]]$Entries)
    $pending = @($Entries | Where-Object {
        $_.status -eq "QUEUED" -or $_.status -eq "BUILDING"
    })
    $modules = @($Entries | Sort-Object sourceName | ForEach-Object {
        $record = [ordered]@{
            sourceName = $_.sourceName
            moduleId = $_.sourceName
            title = $_.title
            caption = $_.caption
            icon = $_.icon
            status = $_.status
            priority = [int]$_.priority
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$_.message)) {
            $record.message = [string]$_.message
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$_.startedAt)) {
            $record.startedAt = [string]$_.startedAt
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$_.finishedAt)) {
            $record.finishedAt = [string]$_.finishedAt
        }
        [pscustomobject]$record
    })
    Write-JsonAtomic -Path $statusFile -Value ([ordered]@{
        schemaVersion = 1
        mode = "background"
        updatedAt = (Get-Date).ToUniversalTime().ToString("o")
        complete = $pending.Count -eq 0
        modules = $modules
    })
}

function Update-FromHostSnapshot {
    param(
        [Parameter(Mandatory = $true)][object[]]$Entries,
        [Parameter(Mandatory = $true)]$Snapshot
    )
    $hostEntries = @($Snapshot.modules)
    foreach ($entry in $Entries) {
        $hostEntry = $hostEntries |
            Where-Object { $_.sourceName -eq $entry.sourceName } |
            Select-Object -First 1
        if (-not $hostEntry) {
            continue
        }
        if ($hostEntry.status -eq "READY") {
            Set-BuildState -Entry $entry -Status "READY" -Message ([string]$hostEntry.message)
        }
        elseif ($entry.status -eq "QUEUED" -and $hostEntry.status -ne "QUEUED") {
            Set-BuildState `
                -Entry $entry `
                -Status ([string]$hostEntry.status) `
                -Message ([string]$hostEntry.message)
        }
    }
}

function Select-NextEntry {
    param(
        [Parameter(Mandatory = $true)][object[]]$Entries,
        [string]$PrioritySource
    )
    $pending = @($Entries | Where-Object {
        $_.status -eq "QUEUED" -or $_.status -eq "BUILDING"
    })
    if ($PrioritySource) {
        $preferred = $pending |
            Where-Object { $_.sourceName -eq $PrioritySource } |
            Select-Object -First 1
        if ($preferred) {
            return $preferred
        }
        $retry = $Entries |
            Where-Object {
                $_.sourceName -eq $PrioritySource -and $_.status -eq "FAILED"
            } |
            Select-Object -First 1
        if ($retry) {
            return $retry
        }
    }
    if ($pending.Count -gt 0) {
        return $pending | Sort-Object sourceName | Select-Object -First 1
    }
    return $null
}

$BaseUrl = Assert-LocalBaseUrl $BaseUrl
if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) {
    throw "缺少模块构建脚本：$buildScript"
}
Assert-PlainFileOrMissing -Path $statusFile -Description "模块构建状态"
Assert-PlainFileOrMissing -Path $priorityFile -Description "模块构建优先请求"

Write-Host "后台模块构建器等待宿主：$BaseUrl"
$null = Wait-HostHealth

$entries = [System.Collections.Generic.List[object]]::new()
foreach ($directory in @(Get-SourceDirectories)) {
    $metadata = Get-ModuleMetadata $directory
    $entries.Add([pscustomobject]@{
        sourceName = $directory.Name
        title = $metadata.title
        caption = $metadata.caption
        icon = $metadata.icon
        status = "QUEUED"
        message = "等待后台构建"
        priority = 0
        startedAt = $null
        finishedAt = $null
    })
}
if ($entries.Count -eq 0) {
    Publish-BuildStatus -Entries @()
    Write-Host "没有待构建业务模块。"
    exit 0
}

$snapshot = Get-HostBuildSnapshot
Update-FromHostSnapshot -Entries @($entries) -Snapshot $snapshot
Publish-BuildStatus -Entries @($entries)

$failed = [System.Collections.Generic.List[string]]::new()
while ($true) {
    $snapshot = Get-HostBuildSnapshot
    Update-FromHostSnapshot -Entries @($entries) -Snapshot $snapshot
    $prioritySource = Read-PrioritySource
    $entry = Select-NextEntry -Entries @($entries) -PrioritySource $prioritySource
    if (-not $entry) {
        $failedEntries = @($entries | Where-Object { $_.status -eq "FAILED" })
        if ($failedEntries.Count -gt 0) {
            # 保留构建器等待用户再次点击失败模块；重试请求会落入同一个优先文件。
            Start-Sleep -Milliseconds 500
            continue
        }
        break
    }

    $entry.priority = if ($prioritySource -eq $entry.sourceName) { 1 } else { 0 }
    if ($prioritySource -eq $entry.sourceName) {
        Clear-PriorityRequest
    }
    Set-BuildState `
        -Entry $entry `
        -Status "BUILDING" `
        -Message "正在构建模块" `
        -Start
    Publish-BuildStatus -Entries @($entries)
    Write-Host "开始构建模块：$($entry.sourceName)"

    try {
        $buildArguments = @{
            ModulesOnly = $true
            ModuleName = $entry.sourceName
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
        & $buildScript @buildArguments

        $refresh = Invoke-HostRefresh
        $snapshot = Get-HostBuildSnapshot
        $ready = @($snapshot.modules) |
            Where-Object {
                $_.sourceName -eq $entry.sourceName -and $_.status -eq "READY"
            } |
            Select-Object -First 1
        if (-not $ready) {
            throw "构建产物已生成，但宿主未将模块追加为 READY。"
        }
        Set-BuildState `
            -Entry $entry `
            -Status "READY" `
            -Message "模块已校验并加载" `
            -Finish
    }
    catch {
        $failed.Add($entry.sourceName)
        Set-BuildState `
            -Entry $entry `
            -Status "FAILED" `
            -Message ("模块构建或加载失败：" + $_.Exception.Message) `
            -Finish
        Write-Warning "模块 $($entry.sourceName) 构建失败：$($_.Exception.Message)"
    }
    finally {
        Publish-BuildStatus -Entries @($entries)
    }
    Start-Sleep -Milliseconds 150
}

$snapshot = Get-HostBuildSnapshot
Update-FromHostSnapshot -Entries @($entries) -Snapshot $snapshot
Publish-BuildStatus -Entries @($entries)
if ($failed.Count -gt 0) {
    Write-Warning "后台模块构建器结束前仍有失败模块：" + ($failed -join ", ")
}
Write-Host "后台模块构建完成：$($entries.Count) 个模块已就绪。"
