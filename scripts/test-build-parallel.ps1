#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   使用 target 下的临时仓库和 Maven 包装器验证 build.ps1 的并行调度与失败恢复，不依赖 Pester。
#
# 输入：
#   无参数。测试使用当前 PowerShell 解释器启动 fixture build.ps1，因此可用 Windows PowerShell 5.1 或 PowerShell 7 运行。
#
# 副作用：
#   只在 target/test-build-parallel-* 中创建临时源码、Maven 包装器、日志和构建产物；测试失败时保留该目录供排查。
#   不访问网络，不执行真实 Maven，不写入正式模块、宿主 JAR 或应用运行数据。
#
# 安全边界：
#   所有 fixture 路径都限制在本次 target 临时根目录；包装器只生成测试 ZIP，不调用或删除仓库外文件。
#   测试失败时报告原始构建日志和 fixture 路径，不以隐藏断言或吞掉退出码制造成功结果。

param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$testRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $targetRoot ("test-build-parallel-" + [guid]::NewGuid().ToString("N")))
)
$fixtureRoot = Join-Path $testRoot "repository"
$fixtureTarget = Join-Path $fixtureRoot "target"
$wrapperRoot = Join-Path $testRoot "maven-wrapper"
$traceRoot = Join-Path $testRoot "traces"
$driverLogRoot = Join-Path $testRoot "driver-logs"
$fixtureBuildScript = Join-Path $fixtureRoot "scripts\build.ps1"
$powerShellExecutable = (Get-Process -Id $PID).Path
$originalPath = $env:PATH
$succeeded = $false
$scenarioIndex = 0

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
    if (-not $fullPath.Equals($fullRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            -not $fullPath.StartsWith(
                $fullRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
        throw "$Description 超出允许目录：$fullPath"
    }
    return $fullPath
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-LogText {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ""
    }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    try {
        return [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes)
    }
    catch {
        # Windows PowerShell 5.1 的子进程被重定向时可能按当前 ANSI 代码页写出日志。
        return [System.Text.Encoding]::GetEncoding(936).GetString($bytes)
    }
}

function Get-RelativePathFromRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $prefix = $fullRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "路径不在 fixture 根目录内：$fullPath"
    }
    return $fullPath.Substring($prefix.Length).Replace("\", "/")
}

function Get-DirectoryFingerprint {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return "<missing>"
    }
    $lines = @(Get-ChildItem -LiteralPath $Path -Recurse -File -Force |
        Sort-Object FullName |
        ForEach-Object {
            "$(Get-RelativePathFromRoot $_.FullName $Path)`t$(Get-Sha256 $_.FullName)"
        })
    return ($lines -join "`n")
}

function Get-HostFingerprint {
    $hostTarget = Join-Path $fixtureRoot "app\host\target"
    if (-not (Test-Path -LiteralPath $hostTarget -PathType Container)) {
        return "<missing>"
    }
    return (@(Get-ChildItem -LiteralPath $hostTarget -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object Name |
        ForEach-Object { "$($_.Name)`t$(Get-Sha256 $_.FullName)" }) -join "`n")
}

function Get-ArtifactFingerprint {
    return [pscustomobject]@{
        Modules = Get-DirectoryFingerprint (Join-Path $fixtureRoot "target\orderly-modules")
        Host = Get-HostFingerprint
    }
}

function Assert-ArtifactFingerprintEqual {
    param(
        [Parameter(Mandatory = $true)]$Before,
        [Parameter(Mandatory = $true)]$After,
        [Parameter(Mandatory = $true)][string]$Scenario
    )
    if ($Before.Modules -ne $After.Modules) {
        throw "$Scenario 模块暂存发生变化。`n预期：$($Before.Modules)`n实际：$($After.Modules)"
    }
    if ($Before.Host -ne $After.Host) {
        throw "$Scenario 宿主 JAR 发生变化。`n预期：$($Before.Host)`n实际：$($After.Host)"
    }
}

function ConvertTo-ProcessArgument {
    param([string]$Value)
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '(\\*)"', '$1$1\"') + '"'
}

function New-FixtureModule {
    param([Parameter(Mandatory = $true)][string]$Name)
    $moduleRoot = Assert-PathWithin (Join-Path $fixtureRoot "Module\$Name") $fixtureRoot "fixture 模块"
    Write-Utf8File (Join-Path $moduleRoot "pom.xml") @"
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <artifactId>$Name</artifactId>
</project>
"@
}

function Initialize-Fixture {
    [System.IO.Directory]::CreateDirectory($testRoot) | Out-Null
    [System.IO.Directory]::CreateDirectory((Join-Path $fixtureRoot "scripts")) | Out-Null
    [System.IO.Directory]::CreateDirectory((Join-Path $fixtureRoot "app\host\target")) | Out-Null
    [System.IO.Directory]::CreateDirectory((Join-Path $fixtureRoot "maven-repository")) | Out-Null
    [System.IO.Directory]::CreateDirectory($wrapperRoot) | Out-Null
    [System.IO.Directory]::CreateDirectory($traceRoot) | Out-Null
    [System.IO.Directory]::CreateDirectory($driverLogRoot) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "build.ps1") -Destination $fixtureBuildScript
    foreach ($moduleName in @("module_control", "module_alpha", "module_beta", "module_gamma")) {
        New-FixtureModule $moduleName
    }

    $wrapperScript = @'
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$arguments = @($args)
if ($arguments -contains "-version") {
    Write-Output "Apache Maven 3.9.16 (fixture)"
    Write-Output "Maven home: fixture"
    Write-Output "Java version: 21.0.12.1, vendor: fixture"
    exit 0
}

function Get-ArgumentValue {
    param([string]$Name)
    for ($index = 0; $index -lt $arguments.Count - 1; $index++) {
        if ($arguments[$index] -eq $Name) {
            return $arguments[$index + 1]
        }
    }
    return $null
}

function Get-DefinitionArgument {
    param([string]$Prefix)
    foreach ($argument in $arguments) {
        if ($argument.StartsWith($Prefix, [System.StringComparison]::Ordinal)) {
            return $argument.Substring($Prefix.Length)
        }
    }
    return $null
}

function Write-TraceEvent {
    param(
        [string]$Kind,
        [string]$ModuleName,
        [long]$StartTicks,
        [long]$EndTicks,
        [string]$LocalRepository,
        [string]$TailRepository,
        [int]$ExitCode
    )
    $traceDir = $env:ORDERLY_TEST_TRACE_DIR
    if ([string]::IsNullOrWhiteSpace($traceDir)) {
        return
    }
    [System.IO.Directory]::CreateDirectory($traceDir) | Out-Null
    $eventPath = Join-Path $traceDir (
        "event-" + [guid]::NewGuid().ToString("N") + ".json"
    )
    $record = [ordered]@{
        kind = $Kind
        module = $ModuleName
        startUtcTicks = $StartTicks
        endUtcTicks = $EndTicks
        localRepository = $LocalRepository
        tailRepository = $TailRepository
        offline = $arguments -contains "-o"
        exitCode = $ExitCode
    }
    [System.IO.File]::WriteAllText(
        $eventPath,
        ($record | ConvertTo-Json -Compress),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Add-ZipText {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [string]$EntryName,
        [string]$Content
    )
    $entry = $Archive.CreateEntry($EntryName)
    $stream = $entry.Open()
    try {
        $writer = [System.IO.StreamWriter]::new(
            $stream,
            [System.Text.UTF8Encoding]::new($false)
        )
        try {
            $writer.Write($Content)
        }
        finally {
            $writer.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function New-FixtureModuleJar {
    param([string]$ModuleRoot, [string]$ModuleName)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $target = Join-Path $ModuleRoot "target"
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    [System.IO.Directory]::CreateDirectory($target) | Out-Null
    $jarPath = Join-Path $target "$ModuleName.jar"
    $archive = [System.IO.Compression.ZipFile]::Open(
        $jarPath,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        Add-ZipText $archive "orderly-ui/module.json" '{"version":1,"view":{"id":"fixture"}}'
        Add-ZipText $archive "orderly-ui/module.js" "export default function mount() {}"
        Add-ZipText $archive "orderly-ui/module.css" ".fixture { color: var(--color-text); }"
        $contract = [ordered]@{ moduleId = $ModuleName }
        if ($ModuleName -eq "module_control") {
            $contract.system = [ordered]@{
                role = "MODULE_CONTROL"
                protected = $true
            }
        }
        Add-ZipText $archive "orderly-modules/$ModuleName.json" (
            $contract | ConvertTo-Json -Compress -Depth 5
        )
    }
    finally {
        $archive.Dispose()
    }
}

function New-FixtureHostJar {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $hostTarget = Join-Path $env:ORDERLY_TEST_FIXTURE_ROOT "app\host\target"
    [System.IO.Directory]::CreateDirectory($hostTarget) | Out-Null
    foreach ($jar in @(Get-ChildItem -LiteralPath $hostTarget -Filter "*.jar" -File)) {
        Remove-Item -LiteralPath $jar.FullName -Force
    }
    $jarPath = Join-Path $hostTarget "orderly-host-fixture.jar"
    $archive = [System.IO.Compression.ZipFile]::Open(
        $jarPath,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        Add-ZipText $archive "BOOT-INF/classes/application.properties" "fixture=true"
    }
    finally {
        $archive.Dispose()
    }
}

$startTicks = [DateTime]::UtcNow.Ticks
if ($env:ORDERLY_TEST_REQUIRE_OFFLINE -eq "1" -and -not ($arguments -contains "-o")) {
    [Console]::Error.WriteLine("FIXTURE ERROR: missing -o for offline invocation")
    Write-TraceEvent "invalid" "" $startTicks ([DateTime]::UtcNow.Ticks) "" "" 61
    exit 61
}

$phase = "unknown"
$moduleName = ""
$pomPath = Get-ArgumentValue "-f"
if ($pomPath) {
    $fullPomPath = [System.IO.Path]::GetFullPath($pomPath)
    if ($fullPomPath -match '[\\/]Module[\\/](?<name>module_[^\\/]+)[\\/]pom\.xml$') {
        $phase = "module"
        $moduleName = $Matches.name
    }
    elseif ($fullPomPath -match '[\\/]app[\\/]host[\\/]pom\.xml$') {
        $phase = "host"
        $moduleName = "host"
    }
}
if ($phase -eq "unknown" -and
        $arguments -contains "-pl" -and
        $arguments -contains "app/kernel" -and
        $arguments -contains "install") {
    $phase = "kernel"
    $moduleName = "kernel"
}

$localRepository = Get-DefinitionArgument "-Dmaven.repo.local="
$tailRepository = Get-DefinitionArgument "-Dmaven.repo.local.tail="
if ($phase -eq "module") {
    if ([string]::IsNullOrWhiteSpace($localRepository)) {
        [Console]::Error.WriteLine("FIXTURE ERROR: module invocation lacks maven.repo.local")
        Write-TraceEvent $phase $moduleName $startTicks ([DateTime]::UtcNow.Ticks) $localRepository $tailRepository 62
        exit 62
    }
    $moduleRoot = Split-Path -Parent $fullPomPath
    $delay = 150
    if ($env:ORDERLY_TEST_MVN_DELAY_MS) {
        $configuredDelay = [int]$env:ORDERLY_TEST_MVN_DELAY_MS
        if ($configuredDelay -gt 0) {
            $delay = $configuredDelay
        }
    }
    if ($delay -gt 0) {
        Start-Sleep -Milliseconds $delay
    }
    if ($env:ORDERLY_TEST_MVN_FAIL_MODULE -eq $moduleName) {
        $rawExitCode = if ($env:ORDERLY_TEST_MVN_MODULE_EXIT_CODE) {
            [int]$env:ORDERLY_TEST_MVN_MODULE_EXIT_CODE
        }
        else {
            37
        }
        [Console]::Error.WriteLine("INJECTED_MVN_FAILURE module=$moduleName rawExitCode=$rawExitCode")
        Write-TraceEvent $phase $moduleName $startTicks ([DateTime]::UtcNow.Ticks) $localRepository $tailRepository $rawExitCode
        exit $rawExitCode
    }
    New-FixtureModuleJar $moduleRoot $moduleName
    Write-TraceEvent $phase $moduleName $startTicks ([DateTime]::UtcNow.Ticks) $localRepository $tailRepository 0
    Write-Output "FIXTURE MODULE BUILD OK: $moduleName"
    exit 0
}

$delay = 0
if ($env:ORDERLY_TEST_MVN_DELAY_MS) {
    $delay = [int]$env:ORDERLY_TEST_MVN_DELAY_MS
}
if ($delay -gt 0) {
    Start-Sleep -Milliseconds $delay
}

if ($phase -eq "host") {
    $hostTarget = Join-Path $env:ORDERLY_TEST_FIXTURE_ROOT "app\host\target"
    [System.IO.Directory]::CreateDirectory($hostTarget) | Out-Null
    foreach ($jar in @(Get-ChildItem -LiteralPath $hostTarget -Filter "*.jar" -File)) {
        Remove-Item -LiteralPath $jar.FullName -Force
    }
    if ($env:ORDERLY_TEST_MVN_FAIL_HOST -eq "1") {
        $rawExitCode = if ($env:ORDERLY_TEST_MVN_HOST_EXIT_CODE) {
            [int]$env:ORDERLY_TEST_MVN_HOST_EXIT_CODE
        }
        else {
            55
        }
        [Console]::Error.WriteLine("INJECTED_MVN_FAILURE phase=host rawExitCode=$rawExitCode")
        Write-TraceEvent $phase $moduleName $startTicks ([DateTime]::UtcNow.Ticks) $localRepository $tailRepository $rawExitCode
        exit $rawExitCode
    }
    New-FixtureHostJar
    Write-TraceEvent $phase $moduleName $startTicks ([DateTime]::UtcNow.Ticks) $localRepository $tailRepository 0
    Write-Output "FIXTURE HOST BUILD OK"
    exit 0
}

Write-TraceEvent $phase $moduleName $startTicks ([DateTime]::UtcNow.Ticks) $localRepository $tailRepository 0
Write-Output "FIXTURE KERNEL INSTALL OK"
exit 0
'@
    Write-Utf8File (Join-Path $wrapperRoot "maven-wrapper.ps1") $wrapperScript
    Write-Utf8File (Join-Path $wrapperRoot "mvn.cmd") @"
@echo off
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0maven-wrapper.ps1" %*
exit /b %ERRORLEVEL%
"@
}

function Get-TraceEvents {
    param([Parameter(Mandatory = $true)][string]$Directory)
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $Directory -Filter "event-*.json" -File |
        ForEach-Object {
            Get-Content -Raw -Encoding UTF8 -LiteralPath $_.FullName |
                ConvertFrom-Json
        })
}

function Get-MaxConcurrency {
    param([object[]]$Events)
    $maximum = 0
    foreach ($candidate in $Events) {
        $active = @($Events | Where-Object {
            $_.startUtcTicks -le $candidate.startUtcTicks -and
            $_.endUtcTicks -gt $candidate.startUtcTicks
        }).Count
        if ($active -gt $maximum) {
            $maximum = $active
        }
    }
    return $maximum
}

function Assert-NoOverlap {
    param([object[]]$Events)
    for ($left = 0; $left -lt $Events.Count; $left++) {
        for ($right = $left + 1; $right -lt $Events.Count; $right++) {
            if ($Events[$left].startUtcTicks -lt $Events[$right].endUtcTicks -and
                    $Events[$right].startUtcTicks -lt $Events[$left].endUtcTicks) {
                throw "默认串行测试检测到模块重叠：$($Events[$left].module) 与 $($Events[$right].module)"
            }
        }
    }
}

function Assert-NoCrossBuildOverlap {
    param(
        [object[]]$LeftEvents,
        [object[]]$RightEvents
    )
    foreach ($left in $LeftEvents) {
        foreach ($right in $RightEvents) {
            if ($left.startUtcTicks -lt $right.endUtcTicks -and
                    $right.startUtcTicks -lt $left.endUtcTicks) {
                throw "两个外部构建发生重叠，仓库构建互斥锁未生效：$($left.module) 与 $($right.module)"
            }
        }
    }
}

function Get-LatestBuildRun {
    return Get-ChildItem -LiteralPath $fixtureTarget -Directory -Filter "build-parallel-*" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Start-FixtureBuildProcess {
    param(
        [string]$Scenario,
        [string[]]$BuildArguments = @(),
        [hashtable]$Environment = @{}
    )
    $script:scenarioIndex++
    $scenarioRoot = Assert-PathWithin `
        (Join-Path $testRoot ("scenario-" + $script:scenarioIndex + "-" + $Scenario)) `
        $testRoot `
        "测试场景目录"
    [System.IO.Directory]::CreateDirectory($scenarioRoot) | Out-Null
    $scenarioTrace = Join-Path $scenarioRoot "trace"
    $stdoutPath = Join-Path $scenarioRoot "build-stdout.log"
    $stderrPath = Join-Path $scenarioRoot "build-stderr.log"
    [System.IO.Directory]::CreateDirectory($scenarioTrace) | Out-Null

    $saved = @{}
    $allEnvironment = @{
        PATH = "$wrapperRoot;$originalPath"
        ORDERLY_TEST_FIXTURE_ROOT = $fixtureRoot
        ORDERLY_TEST_TRACE_DIR = $scenarioTrace
        ORDERLY_TEST_MVN_DELAY_MS = "0"
        ORDERLY_TEST_MVN_FAIL_MODULE = ""
        ORDERLY_TEST_MVN_MODULE_EXIT_CODE = ""
        ORDERLY_TEST_MVN_FAIL_HOST = ""
        ORDERLY_TEST_MVN_HOST_EXIT_CODE = ""
        ORDERLY_TEST_REQUIRE_OFFLINE = ""
    }
    foreach ($entry in $Environment.GetEnumerator()) {
        $allEnvironment[$entry.Key] = [string]$entry.Value
    }
    foreach ($name in $allEnvironment.Keys) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $allEnvironment[$name], "Process")
    }
    try {
        $processArguments = @()
        foreach ($argument in @(
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-File", $fixtureBuildScript
        ) + $BuildArguments) {
            $processArguments += ConvertTo-ProcessArgument $argument
        }
        $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $processInfo.FileName = $powerShellExecutable
        $processInfo.Arguments = $processArguments -join " "
        $processInfo.WorkingDirectory = $fixtureRoot
        $processInfo.UseShellExecute = $false
        $processInfo.CreateNoWindow = $true
        $processInfo.RedirectStandardOutput = $true
        $processInfo.RedirectStandardError = $true
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $processInfo
        $stdoutStream = $null
        $stderrStream = $null
        try {
            if (-not $process.Start()) {
                throw "fixture 构建子进程未能启动。"
            }
            $stdoutStream = [System.IO.File]::Open(
                $stdoutPath,
                [System.IO.FileMode]::Create,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::Read
            )
            $stderrStream = [System.IO.File]::Open(
                $stderrPath,
                [System.IO.FileMode]::Create,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::Read
            )
            $stdoutTask = $process.StandardOutput.BaseStream.CopyToAsync($stdoutStream)
            $stderrTask = $process.StandardError.BaseStream.CopyToAsync($stderrStream)
        }
        catch {
            if ($stdoutStream) { $stdoutStream.Dispose() }
            if ($stderrStream) { $stderrStream.Dispose() }
            $process.Dispose()
            throw
        }
    }
    finally {
        foreach ($name in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], "Process")
        }
    }
    return [pscustomobject]@{
        Process = $process
        StdoutStream = $stdoutStream
        StderrStream = $stderrStream
        StdoutTask = $stdoutTask
        StderrTask = $stderrTask
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
        TraceDirectory = $scenarioTrace
        ScenarioRoot = $scenarioRoot
    }
}

function Complete-FixtureBuildProcess {
    param([Parameter(Mandatory = $true)]$BuildProcess)
    try {
        $BuildProcess.Process.WaitForExit()
        $BuildProcess.StdoutTask.GetAwaiter().GetResult() | Out-Null
        $BuildProcess.StderrTask.GetAwaiter().GetResult() | Out-Null
        $exitCode = [int]$BuildProcess.Process.ExitCode
    }
    finally {
        if ($BuildProcess.StdoutStream) { $BuildProcess.StdoutStream.Dispose() }
        if ($BuildProcess.StderrStream) { $BuildProcess.StderrStream.Dispose() }
        $BuildProcess.Process.Dispose()
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        StdoutPath = $BuildProcess.StdoutPath
        StderrPath = $BuildProcess.StderrPath
        TraceDirectory = $BuildProcess.TraceDirectory
        ScenarioRoot = $BuildProcess.ScenarioRoot
    }
}

function Invoke-FixtureBuild {
    param(
        [string]$Scenario,
        [string[]]$BuildArguments = @(),
        [hashtable]$Environment = @{}
    )
    $buildProcess = Start-FixtureBuildProcess `
        -Scenario $Scenario `
        -BuildArguments $BuildArguments `
        -Environment $Environment
    return Complete-FixtureBuildProcess $buildProcess
}

function Get-CombinedOutput {
    param([Parameter(Mandatory = $true)]$Result)
    $stdout = Get-LogText $Result.StdoutPath
    $stderr = Get-LogText $Result.StderrPath
    return "$stdout`n$stderr"
}

try {
    Initialize-Fixture
    [Environment]::SetEnvironmentVariable("PATH", "$wrapperRoot;$originalPath", "Process")

    Write-Host "[1/6] 验证默认串行使用共享仓库"
    $serialResult = Invoke-FixtureBuild `
        -Scenario "serial" `
        -BuildArguments @("-SkipTests") `
        -Environment @{ ORDERLY_TEST_MVN_DELAY_MS = "250" }
    if ($serialResult.ExitCode -ne 0) {
        throw "默认串行构建失败，退出码：$($serialResult.ExitCode)`n$(Get-CombinedOutput $serialResult)"
    }
    $serialModules = @(Get-TraceEvents $serialResult.TraceDirectory |
        Where-Object { $_.kind -eq "module" })
    if ($serialModules.Count -ne 4) {
        throw "默认串行测试应执行 4 个模块 worker，实际 $($serialModules.Count) 个。"
    }
    Assert-NoOverlap $serialModules
    $expectedShared = [System.IO.Path]::GetFullPath((Join-Path $fixtureRoot ".m2-repository"))
    foreach ($event in $serialModules) {
        if ([System.IO.Path]::GetFullPath($event.localRepository) -ne $expectedShared) {
            throw "默认串行模块未使用共享 maven.repo.local：$($event.localRepository)"
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$event.tailRepository)) {
            throw "默认串行模块不应使用 maven.repo.local.tail：$($event.tailRepository)"
        }
    }
    $serialRun = Get-LatestBuildRun
    if (-not $serialRun) {
        throw "默认串行构建未创建 target/build-parallel-* 运行目录。"
    }
    foreach ($event in $serialModules) {
        foreach ($logName in @("maven-stdout.log", "maven-stderr.log")) {
            $logPath = Join-Path $serialRun.FullName "logs\$($event.module)\$logName"
            if (-not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
                throw "默认串行构建缺少 worker 原始日志：$logPath"
            }
        }
    }
    if (Test-Path -LiteralPath (Join-Path $serialRun.FullName "maven-repositories")) {
        throw "默认串行构建不应创建 maven-repositories 隔离仓库目录。"
    }

    Write-Host "[2/6] 验证显式并发重叠与仓库隔离"
    $parallelResult = Invoke-FixtureBuild `
        -Scenario "parallel" `
        -BuildArguments @("-SkipTests", "-MaxParallel", "3") `
        -Environment @{ ORDERLY_TEST_MVN_DELAY_MS = "350" }
    if ($parallelResult.ExitCode -ne 0) {
        throw "并行构建失败，退出码：$($parallelResult.ExitCode)`n$(Get-CombinedOutput $parallelResult)"
    }
    $parallelModules = @(Get-TraceEvents $parallelResult.TraceDirectory |
        Where-Object { $_.kind -eq "module" })
    if ($parallelModules.Count -ne 4) {
        throw "并行测试应执行 4 个模块 worker，实际 $($parallelModules.Count) 个。"
    }
    $maxConcurrency = Get-MaxConcurrency $parallelModules
    if ($maxConcurrency -lt 2) {
        throw "MaxParallel=3 未检测到模块并发执行。"
    }
    if ($maxConcurrency -gt 3) {
        throw "模块并发超过 MaxParallel=3，实际 $maxConcurrency。"
    }
    $localRepositories = @($parallelModules | ForEach-Object { $_.localRepository } | Sort-Object -Unique)
    if ($localRepositories.Count -ne $parallelModules.Count) {
        throw "并行 worker 未使用各自独立的 maven.repo.local。"
    }
    $expectedTail = [System.IO.Path]::GetFullPath((Join-Path $fixtureRoot ".m2-repository"))
    foreach ($event in $parallelModules) {
        if ([System.IO.Path]::GetFullPath($event.localRepository) -eq $expectedTail) {
            throw "并行 worker 不得直接写共享预热仓库：$($event.localRepository)"
        }
        if ([System.IO.Path]::GetFullPath($event.tailRepository) -ne $expectedTail) {
            throw "并行 worker 的共享 tail 不是默认预热仓库：$($event.tailRepository)"
        }
    }

    $baseline = Get-ArtifactFingerprint
    if ($baseline.Modules -eq "<missing>" -or [string]::IsNullOrWhiteSpace($baseline.Host)) {
        throw "并发成功后未生成模块暂存或宿主 JAR 基线。"
    }

    Write-Host "[3/6] 验证模块 worker 失败、原始退出码、日志和产物不变"
    $moduleFailure = Invoke-FixtureBuild `
        -Scenario "module-failure" `
        -BuildArguments @("-SkipTests", "-MaxParallel", "2") `
        -Environment @{
            ORDERLY_TEST_MVN_DELAY_MS = "150"
            ORDERLY_TEST_MVN_FAIL_MODULE = "module_alpha"
            ORDERLY_TEST_MVN_MODULE_EXIT_CODE = "37"
        }
    if ($moduleFailure.ExitCode -eq 0) {
        throw "模块 worker 失败未返回非零退出码。"
    }
    $moduleFailureOutput = Get-CombinedOutput $moduleFailure
    foreach ($expectedText in @("module_alpha", "原始退出码=37", "stdout=", "stderr=")) {
        if (-not $moduleFailureOutput.Contains($expectedText)) {
            throw "模块失败报告缺少内容：$expectedText`n$moduleFailureOutput"
        }
    }
    $moduleFailureRun = Get-LatestBuildRun
    $moduleFailureLog = Join-Path $moduleFailureRun.FullName "logs\module_alpha\maven-stderr.log"
    if (-not (Test-Path -LiteralPath $moduleFailureLog -PathType Leaf)) {
        throw "模块失败未保留 worker stderr 日志：$moduleFailureLog"
    }
    if (-not (Get-Content -Raw -LiteralPath $moduleFailureLog).Contains("INJECTED_MVN_FAILURE")) {
        throw "模块失败 worker stderr 未保留 Maven 原始错误。"
    }
    Assert-ArtifactFingerprintEqual $baseline (Get-ArtifactFingerprint) "模块失败后"

    Write-Host "[4/6] 验证宿主阶段失败恢复上一套模块暂存和宿主 JAR"
    $hostFailure = Invoke-FixtureBuild `
        -Scenario "host-failure" `
        -BuildArguments @("-SkipTests", "-MaxParallel", "2") `
        -Environment @{
            ORDERLY_TEST_MVN_DELAY_MS = "50"
            ORDERLY_TEST_MVN_FAIL_HOST = "1"
            ORDERLY_TEST_MVN_HOST_EXIT_CODE = "55"
        }
    if ($hostFailure.ExitCode -eq 0) {
        throw "宿主失败未返回非零退出码。"
    }
    $hostFailureOutput = Get-CombinedOutput $hostFailure
    foreach ($expectedText in @("55", "已恢复上一套模块暂存和宿主 JAR")) {
        if (-not $hostFailureOutput.Contains($expectedText)) {
            throw "宿主失败报告缺少内容：$expectedText`n$hostFailureOutput"
        }
    }
    Assert-ArtifactFingerprintEqual $baseline (Get-ArtifactFingerprint) "宿主失败后"

    Write-Host "[5/6] 验证严格离线每次 Maven 调用都保留 -o"
    $offlineRepository = Join-Path $fixtureRoot "maven-repository"
    $offlineResult = Invoke-FixtureBuild `
        -Scenario "offline" `
        -BuildArguments @(
            "-SkipTests",
            "-Offline",
            "-MavenRepository", $offlineRepository,
            "-MaxParallel", "2"
        ) `
        -Environment @{
            ORDERLY_TEST_MVN_DELAY_MS = "50"
            ORDERLY_TEST_REQUIRE_OFFLINE = "1"
        }
    if ($offlineResult.ExitCode -ne 0) {
        throw "严格离线并行构建失败，退出码：$($offlineResult.ExitCode)`n$(Get-CombinedOutput $offlineResult)"
    }
    $offlineEvents = @(Get-TraceEvents $offlineResult.TraceDirectory |
        Where-Object { $_.kind -in @("kernel", "module", "host") })
    if ($offlineEvents.Count -lt 6) {
        throw "离线测试未记录 kernel、4 个模块和宿主调用，实际 $($offlineEvents.Count)。"
    }
    foreach ($event in $offlineEvents) {
        if ($event.offline -ne $true) {
            throw "离线构建存在未带 -o 的 Maven 调用：$($event.kind)/$($event.module)"
        }
    }

    Write-Host "[6/6] 验证两个外部 build.ps1 通过同一仓库锁串行等待"
    $lockEnvironment = @{ ORDERLY_TEST_MVN_DELAY_MS = "700" }
    $firstBuild = Start-FixtureBuildProcess `
        -Scenario "lock-first" `
        -BuildArguments @("-SkipTests", "-MaxParallel", "2") `
        -Environment $lockEnvironment
    Start-Sleep -Milliseconds 150
    $secondBuild = Start-FixtureBuildProcess `
        -Scenario "lock-second" `
        -BuildArguments @("-SkipTests", "-MaxParallel", "2") `
        -Environment $lockEnvironment
    $firstResult = Complete-FixtureBuildProcess $firstBuild
    $secondResult = Complete-FixtureBuildProcess $secondBuild
    if ($firstResult.ExitCode -ne 0) {
        throw "首个并发构建失败，退出码：$($firstResult.ExitCode)`n$(Get-CombinedOutput $firstResult)"
    }
    if ($secondResult.ExitCode -ne 0) {
        throw "等待锁的第二个构建失败，退出码：$($secondResult.ExitCode)`n$(Get-CombinedOutput $secondResult)"
    }
    $firstBuildModules = @(Get-TraceEvents $firstResult.TraceDirectory |
        Where-Object { $_.kind -eq "module" })
    $secondBuildModules = @(Get-TraceEvents $secondResult.TraceDirectory |
        Where-Object { $_.kind -eq "module" })
    if ($firstBuildModules.Count -ne 4 -or $secondBuildModules.Count -ne 4) {
        throw "并发构建锁测试的模块事件数量不完整。first=$($firstBuildModules.Count) second=$($secondBuildModules.Count)"
    }
    Assert-NoCrossBuildOverlap $firstBuildModules $secondBuildModules

    $succeeded = $true
    Write-Host "并行构建测试通过：仓库锁等待、默认串行共享仓库、显式并发隔离仓库、失败退出码、原始日志、模块/宿主产物恢复和离线 -o 均符合预期。"
}
catch {
    if (Test-Path -LiteralPath $testRoot) {
        Write-Host "测试 fixture 保留于：$testRoot"
    }
    throw
}
finally {
    [Environment]::SetEnvironmentVariable("PATH", $originalPath, "Process")
    if ($succeeded -and (Test-Path -LiteralPath $testRoot)) {
        $safeTestRoot = Assert-PathWithin $testRoot $targetRoot "并行构建测试临时目录"
        if (-not $safeTestRoot.Equals(
                [System.IO.Path]::GetFullPath($targetRoot),
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
            Remove-Item -LiteralPath $safeTestRoot -Recurse -Force
        }
    }
}
