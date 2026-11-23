#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   在 Java 框架已经启动后，按宿主设置并行构建 Module/module_* 业务模块。
#   多个 Maven worker 可并行；模块校验、原子发布和宿主刷新始终由当前进程单线程执行。
#
# 输入：
#   -BaseUrl 指定已启动的本地宿主；-SkipTests、-Offline、-MavenRepository 透传给 build.ps1。
#   -SkipKernelInstall 表示 BootstrapOnly 已安装 kernel，后续模块不再重复安装。
#   -RuntimeStateRoot 指定宿主共享的构建状态目录；默认使用仓库 target。
#   -HealthTimeoutSeconds 限制等待宿主健康状态的时间。
#
# 副作用：
#   写入 target/module-build-status.json、target/module-build-priority.json 的消费状态，
#   调用 build.ps1 构建模块，并通过宿主刷新接口追加已校验 JAR。
#
# 安全边界：
#   只扫描 Module/module_* 普通直接子目录；构建状态中的源码标题只用于展示，不构成契约。
#   JAR 的发布和合同校验仍由 build.ps1 完成，执行资格只能由宿主校验后的 JAR 获得。
#   当前进程持有仓库构建锁；worker 使用隔离 Maven 仓库并只交接已校验 JAR。

param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [switch]$SkipTests,
    [switch]$Offline,
    [string]$MavenRepository,
    [switch]$SkipKernelInstall,
    [string]$RuntimeStateRoot,
    [ValidateRange(5, 3600)]
    [int]$HealthTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot "build-plan.ps1")
$moduleRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "Module"))
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$stagingRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "orderly-modules"))
$buildManifestPath = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "build-manifest.json"))
$stateRoot = if ([string]::IsNullOrWhiteSpace($RuntimeStateRoot)) {
    $targetRoot
}
else {
    [System.IO.Path]::GetFullPath($RuntimeStateRoot)
}
$statusFile = [System.IO.Path]::GetFullPath((Join-Path $stateRoot "module-build-status.json"))
$priorityFile = [System.IO.Path]::GetFullPath((Join-Path $stateRoot "module-build-priority.json"))
$buildScript = Join-Path $PSScriptRoot "build.ps1"
$sourcePattern = '^module_[a-z0-9_]+$'
$systemSourcePrefix = "module_system_"
$maxStatusBytes = 512KB
$buildLockTimeoutMilliseconds = 120000
$minimumParallel = 1
$maximumParallel = 4
$workerRunRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $targetRoot ("background\module-workers-" + (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [guid]::NewGuid().ToString("N")))
)
$powerShellExecutable = (Get-Process -Id $PID).Path

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
            -not $_.Name.StartsWith($systemSourcePrefix, [System.StringComparison]::Ordinal) -and
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0
        } |
        Sort-Object Name)
}

function Wait-HostHealth {
    $uri = "$BaseUrl/api/health/live"
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

function Assert-PathWithin {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $prefix = $fullRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.Equals($fullRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            -not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description 超出允许目录：$fullPath"
    }
    return $fullPath
}

function Get-BuildLockName {
    param([Parameter(Mandatory = $true)][string]$Root)
    $normalizedRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ).ToLowerInvariant()
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($normalizedRoot)
        $hash = ([System.BitConverter]::ToString(
            $algorithm.ComputeHash($bytes)
        ) -replace "-", "").ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
    return "Local\Orderly.Build.$hash"
}

function Enter-BuildLock {
    $lockName = Get-BuildLockName $repoRoot
    $mutex = [System.Threading.Mutex]::new($false, $lockName)
    $acquired = $false
    try {
        $acquired = $mutex.WaitOne($buildLockTimeoutMilliseconds, $false)
    }
    catch [System.Threading.AbandonedMutexException] {
        $acquired = $true
        Write-Warning "仓库构建互斥锁此前由异常退出进程持有；后台构建器已接管。lockName=$lockName"
    }
    catch {
        $reason = $_.Exception.Message
        $mutex.Dispose()
        throw "获取后台构建锁失败。lockName=$lockName；原因：$reason"
    }
    if (-not $acquired) {
        $mutex.Dispose()
        throw "等待后台构建锁超时。lockName=$lockName；timeoutMilliseconds=$buildLockTimeoutMilliseconds"
    }
    return [pscustomobject]@{
        Mutex = $mutex
        Name = $lockName
        Held = $true
    }
}

function Exit-BuildLock {
    param($BuildLock)
    if ($null -eq $BuildLock -or -not $BuildLock.Held) {
        return
    }
    try {
        $BuildLock.Mutex.ReleaseMutex()
        $BuildLock.Held = $false
    }
    finally {
        $BuildLock.Mutex.Dispose()
    }
}

function ConvertTo-ProcessArgument {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Join-ProcessArguments {
    param([string[]]$Arguments)
    return (@($Arguments | ForEach-Object { ConvertTo-ProcessArgument ([string]$_) }) -join " ")
}

function Get-HostBuildConcurrency {
    $payload = Invoke-RestMethod -Uri "$BaseUrl/api/system/build-settings" -Method Get -TimeoutSec 5
    $value = 0
    if (-not [int]::TryParse([string]$payload.maxParallel, [ref]$value) -or
            $value -lt $minimumParallel -or
            $value -gt $maximumParallel) {
        throw "宿主返回的模块构建并发数非法：$($payload.maxParallel)"
    }
    return $value
}

function Start-BackgroundModuleWorker {
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)][int]$Concurrency
    )
    $workerId = "$($Entry.sourceName)-$([guid]::NewGuid().ToString('N'))"
    $workerRoot = Assert-PathWithin `
        (Join-Path $workerRunRoot $workerId) `
        $workerRunRoot `
        "后台模块 worker 目录"
    $outputDirectory = Assert-PathWithin `
        (Join-Path $workerRoot "output") `
        $workerRunRoot `
        "后台模块输出目录"
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    $stdoutPath = Join-Path $workerRoot "build-stdout.log"
    $stderrPath = Join-Path $workerRoot "build-stderr.log"
    $arguments = @(
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        $buildScript,
        "-ModulesOnly",
        "-ModuleName",
        $Entry.sourceName,
        "-MaxParallel",
        "1",
        "-BackgroundWorker",
        "-ModuleOutputDirectory",
        $outputDirectory,
        "-SkipKernelInstall"
    )
    if ($SkipTests) {
        $arguments += "-SkipTests"
    }
    if ($Offline) {
        $arguments += "-Offline"
    }
    if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
        $arguments += @("-MavenRepository", $MavenRepository)
    }

    Set-BuildState `
        -Entry $Entry `
        -Status "BUILDING" `
        -Message "并行构建中（最多 $Concurrency 个）" `
        -Start
    Publish-BuildStatus -Entries @($entries)
    $process = Start-Process `
        -FilePath $powerShellExecutable `
        -ArgumentList (Join-ProcessArguments $arguments) `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru
    Write-Host "启动后台模块 worker：$($Entry.sourceName)；PID=$($process.Id)"
    return [pscustomobject]@{
        Entry = $Entry
        Process = $process
        OutputDirectory = $outputDirectory
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
        ExitCode = $null
    }
}

function Publish-CompletedModule {
    param([Parameter(Mandatory = $true)]$Worker)
    $entry = $Worker.Entry
    $sourceJar = Assert-PathWithin `
        (Join-Path $Worker.OutputDirectory "$($entry.sourceName).jar") `
        $Worker.OutputDirectory `
        "后台模块交接 JAR"
    $sourceHash = Assert-PathWithin `
        (Join-Path $Worker.OutputDirectory "$($entry.sourceName).jar.sha256") `
        $Worker.OutputDirectory `
        "后台模块校验文件"
    if (-not (Test-Path -LiteralPath $sourceJar -PathType Leaf) -or
            -not (Test-Path -LiteralPath $sourceHash -PathType Leaf)) {
        throw "后台模块 worker 未生成完整交接产物：$($entry.sourceName)"
    }
    $expectedHash = ((Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceHash).Trim() -split '\s+')[0]
    $actualHash = (Get-FileHash -LiteralPath $sourceJar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expectedHash -ne $actualHash) {
        throw "后台模块交接 JAR 哈希不一致：$($entry.sourceName)"
    }

    [System.IO.Directory]::CreateDirectory($stagingRoot) | Out-Null
    $destination = Assert-PathWithin `
        (Join-Path $stagingRoot "$($entry.sourceName).jar") `
        $stagingRoot `
        "运行时模块 JAR"
    $temporary = Assert-PathWithin `
        (Join-Path $stagingRoot ".$($entry.sourceName).$([guid]::NewGuid().ToString('N')).tmp") `
        $stagingRoot `
        "运行时模块临时 JAR"
    try {
        [System.IO.File]::Copy($sourceJar, $temporary, $true)
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            $backup = "$destination.backup-$([guid]::NewGuid().ToString('N'))"
            try {
                [System.IO.File]::Replace($temporary, $destination, $backup, $true)
            }
            finally {
                if (Test-Path -LiteralPath $backup) {
                    Remove-Item -LiteralPath $backup -Force
                }
            }
        }
        else {
            [System.IO.File]::Move($temporary, $destination)
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }

    $null = Invoke-HostRefresh
    $snapshot = Get-HostBuildSnapshot
    $ready = @($snapshot.modules) |
        Where-Object {
            $_.sourceName -eq $entry.sourceName -and $_.status -eq "READY"
        } |
        Select-Object -First 1
    if (-not $ready) {
        throw "模块 JAR 已发布，但宿主未将模块追加为 READY：$($entry.sourceName)"
    }
    if ($null -ne (Read-OrderlyBuildManifest $buildManifestPath)) {
        Write-OrderlyBuildManifest `
            -RepoRoot $repoRoot `
            -ManifestPath $buildManifestPath `
            -Mode "modules-only" `
            -BuiltModuleNames @($entry.sourceName)
    }
    Set-BuildState `
        -Entry $entry `
        -Status "READY" `
        -Message "模块已校验并加载" `
        -Finish
    Publish-BuildStatus -Entries @($entries)
    Write-Host "已单线程发布模块：$($entry.sourceName)"
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
        [string]$PrioritySource,
        [string[]]$ExcludeSourceNames = @()
    )
    $pending = @($Entries | Where-Object {
        ($_.status -eq "QUEUED" -or $_.status -eq "BUILDING") -and
        $ExcludeSourceNames -notcontains $_.sourceName
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
                $_.sourceName -eq $PrioritySource -and
                $_.status -eq "FAILED" -and
                $ExcludeSourceNames -notcontains $_.sourceName
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

$running = @{}
$buildLock = Enter-BuildLock
try {
    [System.IO.Directory]::CreateDirectory($workerRunRoot) | Out-Null
    $concurrency = Get-HostBuildConcurrency
    Write-Host "后台模块构建并发：$concurrency（允许 $minimumParallel-$maximumParallel）"

    $snapshot = Get-HostBuildSnapshot
    Update-FromHostSnapshot -Entries @($entries) -Snapshot $snapshot
    Publish-BuildStatus -Entries @($entries)

    $failed = [System.Collections.Generic.List[string]]::new()
    $publishQueue = [System.Collections.Generic.Queue[object]]::new()
    $nextSettingsRead = [DateTime]::UtcNow

    while ($true) {
        $snapshot = Get-HostBuildSnapshot
        Update-FromHostSnapshot -Entries @($entries) -Snapshot $snapshot

        if ([DateTime]::UtcNow -ge $nextSettingsRead) {
            try {
                $nextConcurrency = Get-HostBuildConcurrency
                if ($nextConcurrency -ne $concurrency) {
                    Write-Host "后台模块构建并发已调整为 $nextConcurrency。"
                    $concurrency = $nextConcurrency
                }
            }
            catch {
                Write-Warning "读取模块构建并发设置失败，继续使用 $concurrency：$($_.Exception.Message)"
            }
            $nextSettingsRead = [DateTime]::UtcNow.AddSeconds(1)
        }

        foreach ($key in @($running.Keys)) {
            $worker = $running[$key]
            if (-not $worker.Process.HasExited) {
                continue
            }
            $worker.Process.WaitForExit()
            $worker.ExitCode = [int]$worker.Process.ExitCode
            $worker.Process.Dispose()
            $running.Remove($key)
            if ($worker.ExitCode -eq 0) {
                Set-BuildState `
                    -Entry $worker.Entry `
                    -Status "BUILDING" `
                    -Message "构建完成，等待单线程发布"
                $publishQueue.Enqueue($worker)
            }
            else {
                $failed.Add($worker.Entry.sourceName)
                Set-BuildState `
                    -Entry $worker.Entry `
                    -Status "FAILED" `
                    -Message ("模块构建失败，退出码 $($worker.ExitCode)；日志：" + $worker.StderrPath) `
                    -Finish
                Write-Warning "模块 $($worker.Entry.sourceName) 构建失败，退出码 $($worker.ExitCode)：$($worker.StderrPath)"
            }
            Publish-BuildStatus -Entries @($entries)
        }

        # 同一时间只有当前调度器能发布；worker 只产出候选 JAR。
        while ($publishQueue.Count -gt 0) {
            $worker = $publishQueue.Dequeue()
            try {
                Publish-CompletedModule -Worker $worker
            }
            catch {
                $failed.Add($worker.Entry.sourceName)
                Set-BuildState `
                    -Entry $worker.Entry `
                    -Status "FAILED" `
                    -Message ("模块发布或加载失败：" + $_.Exception.Message) `
                    -Finish
                Publish-BuildStatus -Entries @($entries)
                Write-Warning "模块 $($worker.Entry.sourceName) 发布失败：$($_.Exception.Message)"
            }
        }

        $prioritySource = Read-PrioritySource
        while ($running.Count -lt $concurrency) {
            $runningNames = @($running.Values | ForEach-Object { $_.Entry.sourceName })
            $entry = Select-NextEntry `
                -Entries @($entries) `
                -PrioritySource $prioritySource `
                -ExcludeSourceNames $runningNames
            if (-not $entry) {
                break
            }
            $entry.priority = if ($prioritySource -eq $entry.sourceName) { 1 } else { 0 }
            if ($prioritySource -eq $entry.sourceName) {
                Clear-PriorityRequest
                $prioritySource = $null
            }
            try {
                $worker = Start-BackgroundModuleWorker -Entry $entry -Concurrency $concurrency
                $running[[string]$worker.Process.Id] = $worker
            }
            catch {
                $failed.Add($entry.sourceName)
                Set-BuildState `
                    -Entry $entry `
                    -Status "FAILED" `
                    -Message ("后台模块 worker 启动失败：" + $_.Exception.Message) `
                    -Finish
                Publish-BuildStatus -Entries @($entries)
                Write-Warning "模块 $($entry.sourceName) worker 启动失败：$($_.Exception.Message)"
            }
        }

        $hasPending = @($entries | Where-Object {
            $_.status -eq "QUEUED" -or $_.status -eq "BUILDING"
        }).Count -gt 0
        if (-not $hasPending -and $running.Count -eq 0 -and $publishQueue.Count -eq 0) {
            $failedEntries = @($entries | Where-Object { $_.status -eq "FAILED" })
            if ($failedEntries.Count -gt 0) {
                # 首轮构建结束即释放仓库锁；长期监督器负责后续新模块与显式重试。
                Write-Warning "后台模块首轮构建仍有失败项：" +
                    (($failedEntries | ForEach-Object { $_.sourceName }) -join ", ")
            }
            break
        }
        Start-Sleep -Milliseconds 100
    }

    $snapshot = Get-HostBuildSnapshot
    Update-FromHostSnapshot -Entries @($entries) -Snapshot $snapshot
    Publish-BuildStatus -Entries @($entries)
    if ($failed.Count -gt 0) {
        Write-Warning "后台模块构建器结束前仍有失败模块：" + (($failed | Sort-Object -Unique) -join ", ")
    }
    Write-Host "后台模块构建完成：$($entries.Count) 个模块已就绪。"
}
finally {
    foreach ($worker in @($running.Values)) {
        if ($worker.Process -and -not $worker.Process.HasExited) {
            & taskkill.exe /PID $worker.Process.Id /T /F 2>&1 | Out-Null
        }
    }
    Exit-BuildLock $buildLock
}
