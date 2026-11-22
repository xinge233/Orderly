#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   按运行时目录约定构建 kernel、所有 Module/module_* JAR 和 Java 宿主 fat JAR。
#
# 输入：
#   -SkipTests 控制 Maven 构建是否跳测；-Offline 使用严格 Maven 离线模式；
#   -MavenRepository 指定共享预热 Maven 仓库，默认开发使用 .m2-repository，离线使用包内 maven-repository。
#   -MaxParallel 指定同时执行的模块 Maven clean verify 数量，默认 1 表示保持串行。
#   -BootstrapOnly 只构建 module_system_* 系统模块和宿主，供框架先启动；业务模块由后台队列逐个构建。
#   -ModulesOnly 只构建指定业务模块，不重建宿主；-ModuleName 限定单个 module_* 源码目录。
#   -SkipKernelInstall 供后台队列复用 BootstrapOnly 已安装的 kernel，避免每模块重复安装。
#   -BackgroundWorker 由后台调度器持有仓库锁时使用；跳过本进程锁定并禁用运行时目录直接发布。
#   -ModuleOutputDirectory 与 BackgroundWorker 搭配，接收已校验的单个模块 JAR 供父进程串行发布。
#   -PreserveBusinessModules 仅与 BootstrapOnly 搭配，保留已由构建清单验证的业务模块 JAR。
#   JDK 21 和 Maven 3.9+ 必须可从 JAVA_HOME/PATH 使用。
#
# 副作用：
#   写入 target、共享预热 Maven 仓库、模块/宿主 target 和 target/build-parallel-* 运行目录。
#   模块成功后才替换 target/orderly-modules；宿主失败时恢复上一套模块暂存和宿主 JAR。
#   不写入应用正式工作区或产品数据目录。
#
# 安全边界：
#   所有递归清理都先确认路径位于仓库 target 或 host target 内；模块发现只扫描 Module/module_*。
#   并行 worker 只执行独立模块 Maven clean verify，并使用各自可写仓库与共享只读 tail。
#   宿主 fat JAR 不得包含具体模块或用户主题，任何构建失败都必须保留真实退出信息并返回非零。

param(
    [switch]$SkipTests,
    [switch]$Offline,
    [string]$MavenRepository,
    [ValidateRange(1, 2147483647)]
    [int]$MaxParallel = 1,
    [switch]$BootstrapOnly,
    [switch]$ModulesOnly,
    [string]$ModuleName,
    [switch]$SkipKernelInstall,
    [switch]$BackgroundWorker,
    [string]$ModuleOutputDirectory,
    [switch]$PreserveBusinessModules
)

if ($BootstrapOnly -and $ModulesOnly) {
    throw "-BootstrapOnly 与 -ModulesOnly 不能同时使用。"
}
if ($BootstrapOnly -and -not [string]::IsNullOrWhiteSpace($ModuleName)) {
    throw "-BootstrapOnly 不接受 -ModuleName；系统模块只能由 BootstrapOnly 路径构建。"
}
if (-not $ModulesOnly -and -not [string]::IsNullOrWhiteSpace($ModuleName)) {
    throw "-ModuleName 只能与 -ModulesOnly 一起使用。"
}
if ($BackgroundWorker -and -not $ModulesOnly) {
    throw "-BackgroundWorker 只能与 -ModulesOnly 一起使用。"
}
if ($BackgroundWorker -and [string]::IsNullOrWhiteSpace($ModuleName)) {
    throw "-BackgroundWorker 必须指定单个 -ModuleName。"
}
if ($BackgroundWorker -and [string]::IsNullOrWhiteSpace($ModuleOutputDirectory)) {
    throw "-BackgroundWorker 必须指定 -ModuleOutputDirectory。"
}
if (-not $BackgroundWorker -and -not [string]::IsNullOrWhiteSpace($ModuleOutputDirectory)) {
    throw "-ModuleOutputDirectory 只能与 -BackgroundWorker 一起使用。"
}
if ($PreserveBusinessModules -and -not $BootstrapOnly) {
    throw "-PreserveBusinessModules 只能与 -BootstrapOnly 一起使用。"
}

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildPlanScript = Join-Path $PSScriptRoot "build-plan.ps1"
if (-not (Test-Path -LiteralPath $buildPlanScript -PathType Leaf)) {
    throw "缺少构建检测脚本：$buildPlanScript"
}
. $buildPlanScript
$defaultRepository = if ($Offline) {
    Join-Path $repoRoot "maven-repository"
}
else {
    Join-Path $repoRoot ".m2-repository"
}
$localRepository = if ([string]::IsNullOrWhiteSpace($MavenRepository)) {
    $defaultRepository
}
else {
    [System.IO.Path]::GetFullPath($MavenRepository)
}
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$buildManifestPath = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "build-manifest.json"))
$stagingRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "orderly-modules"))
$hostTargetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "app\host\target"))
$runName = "build-parallel-" + (Get-Date -Format "yyyyMMdd-HHmmss") + "-" + [guid]::NewGuid().ToString("N")
$runRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot $runName))
$candidateStagingRoot = Join-Path $runRoot "candidate-modules"
$workerLogRoot = Join-Path $runRoot "logs"
$workerRepositoryRoot = Join-Path $runRoot "maven-repositories"
$rollbackRoot = Join-Path $runRoot "rollback"
$systemSourcePrefix = "module_system_"
$controlSource = "module_system_control"
$buildLockTimeoutMilliseconds = 120000
$script:mavenExecutable = $null

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
    $mutex = $null
    try {
        $mutex = [System.Threading.Mutex]::new($false, $lockName)
    }
    catch {
        throw "创建仓库构建互斥锁失败。repoRoot=$repoRoot；lockName=$lockName；原因：$($_.Exception.Message)"
    }

    $acquired = $false
    try {
        $acquired = $mutex.WaitOne($buildLockTimeoutMilliseconds, $false)
    }
    catch [System.Threading.AbandonedMutexException] {
        # 前一个持锁进程异常退出时，当前调用会取得所有权；继续构建并保留明确告警。
        $acquired = $true
        Write-Warning "仓库构建互斥锁此前由异常退出进程持有；已接管。repoRoot=$repoRoot；lockName=$lockName"
    }
    catch {
        $reason = $_.Exception.Message
        $mutex.Dispose()
        throw "获取仓库构建互斥锁失败。repoRoot=$repoRoot；lockName=$lockName；原因：$reason"
    }
    if (-not $acquired) {
        $mutex.Dispose()
        throw "等待仓库构建互斥锁超时。repoRoot=$repoRoot；lockName=$lockName；timeoutMilliseconds=$buildLockTimeoutMilliseconds；原因：同一仓库已有 build.ps1 或 modularity.ps1 正在运行。"
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
    catch {
        throw "释放仓库构建互斥锁失败。repoRoot=$repoRoot；lockName=$($BuildLock.Name)；原因：$($_.Exception.Message)"
    }
    finally {
        $BuildLock.Mutex.Dispose()
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$Arguments = @()
    )
    # 捕获原生输出和退出码时暂时使用 Continue，防止 Stderr 先触发终止错误而丢失真实结果。
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

function Get-MavenArguments {
    param(
        [string[]]$MavenArguments = @(),
        [string]$Repository = $localRepository,
        [switch]$UseTail
    )
    $effectiveArguments = @()
    if ($Offline) {
        $effectiveArguments += "-o"
    }
    $effectiveArguments += "-Dmaven.repo.local=$Repository"
    if ($UseTail) {
        $effectiveArguments += "-Dmaven.repo.local.tail=$localRepository"
    }
    $effectiveArguments += $MavenArguments
    return $effectiveArguments
}

function Invoke-Maven {
    param([string[]]$MavenArguments)
    $effectiveArguments = @(Get-MavenArguments -MavenArguments $MavenArguments)

    # 主进程 Maven 调用保持串行；完整输出直接透传，并附带真实退出码。
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & mvn @effectiveArguments
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Maven 执行失败，退出码：$exitCode；参数：$($effectiveArguments -join ' ')"
    }
}

function Assert-Jdk21 {
    # JAVA_HOME 与 Maven 实际使用的 Java 都要核对；只检查其中一个会掩盖工具链版本漂移。
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "JAVA_HOME 未设置，无法保证使用 JDK 21 构建。"
    }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        throw "JAVA_HOME 下未找到 java.exe：$javaExe"
    }
    $javaVersion = Invoke-NativeCapture -FilePath $javaExe -Arguments @("-version")
    if ($javaVersion.ExitCode -ne 0 -or $javaVersion.Output -notmatch 'version "(?<major>\d+)') {
        throw "无法读取 JAVA_HOME 指定的 Java 版本：$javaExe"
    }
    if ([int]$Matches.major -ne 21) {
        throw "构建需要 JDK 21，当前 JAVA_HOME Java 主版本为 $($Matches.major)：$javaExe"
    }
}

function Assert-MavenCapability {
    # 并行模块构建依赖 Maven 3.9 的 maven.repo.local.tail 能力，版本门禁必须先于任何调度。
    $mavenCommand = @(Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1)
    if ($mavenCommand.Count -ne 1) {
        throw "未找到 Maven 命令 mvn。并行构建要求 Apache Maven 3.9 或更高版本。"
    }
    $script:mavenExecutable = $mavenCommand[0].Source
    $mavenArguments = @()
    if ($Offline) {
        $mavenArguments += "-o"
    }
    $mavenArguments += "-Dmaven.repo.local=$localRepository"
    $mavenArguments += "-version"
    $mavenVersion = Invoke-NativeCapture -FilePath $script:mavenExecutable -Arguments $mavenArguments
    if ($mavenVersion.ExitCode -ne 0) {
        throw "无法执行 Maven 版本检查，原始退出码：$($mavenVersion.ExitCode)。输出：`n$($mavenVersion.Output)"
    }
    if ($mavenVersion.Output -notmatch '(?m)^Apache Maven\s+(?<version>\d+\.\d+(?:\.\d+)?(?:[-+][^\s]+)?)') {
        throw "无法从 mvn -version 解析 Maven 版本：`n$($mavenVersion.Output)"
    }
    $versionText = $Matches.version
    if ($versionText -notmatch '^(?<major>\d+)\.(?<minor>\d+)') {
        throw "无法解析 Maven 主次版本：$versionText"
    }
    $major = [int]$Matches.major
    $minor = [int]$Matches.minor
    if ($major -lt 3 -or ($major -eq 3 -and $minor -lt 9)) {
        throw "模块构建需要 Apache Maven 3.9 或更高版本；当前 $versionText。请升级 Maven 后重试。"
    }
    if ($mavenVersion.Output -notmatch 'Java version:\s+21(?:\.|\s|,)') {
        throw "Maven 未使用 JDK 21。mvn -version 输出：`n$($mavenVersion.Output)"
    }
    Write-Host "使用 Maven $versionText；MaxParallel=$MaxParallel"
}

function Assert-RepositorySource {
    if ($Offline) {
        if (-not (Test-Path -LiteralPath $localRepository -PathType Container)) {
            throw "严格离线构建要求现有共享 Maven 仓库：$localRepository"
        }
    }
    else {
        [System.IO.Directory]::CreateDirectory($localRepository) | Out-Null
    }
    $repositoryItem = Get-Item -LiteralPath $localRepository -Force
    if (($repositoryItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "构建拒绝使用重解析点 Maven 仓库：$localRepository"
    }
}

function ConvertTo-CmdArgument {
    param([string]$Value)
    $Value = $Value.Replace("%", "%%")
    if ($Value -notmatch '[\s"&|<>^]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '""') + '"'
}

function Get-LogText {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ""
    }
    return (Get-Content -Raw -LiteralPath $Path)
}

function Write-WorkerLog {
    param(
        [Parameter(Mandatory = $true)]$Worker
    )
    Write-Host "模块 $($Worker.Name) 的 Maven 退出码：$($Worker.ExitCode)"
    $stdout = Get-LogText $Worker.StdoutPath
    if (-not [string]::IsNullOrWhiteSpace($stdout)) {
        Write-Host $stdout.TrimEnd()
    }
    $stderr = Get-LogText $Worker.StderrPath
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        Write-Host $stderr.TrimEnd()
    }
    Write-Host "模块 $($Worker.Name) stdout：$($Worker.StdoutPath)"
    Write-Host "模块 $($Worker.Name) stderr：$($Worker.StderrPath)"
}

function Start-ModuleWorker {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo]$ModuleDirectory,
        [Parameter(Mandatory = $true)][bool]$UseIsolatedRepository
    )
    $pom = Join-Path $ModuleDirectory.FullName "pom.xml"
    if (-not (Test-Path -LiteralPath $pom -PathType Leaf)) {
        throw "模块目录缺少 pom.xml：$($ModuleDirectory.FullName)"
    }

    $workerRoot = Assert-PathWithin `
        (Join-Path $workerLogRoot $ModuleDirectory.Name) `
        $runRoot `
        "模块 worker 日志目录"
    [System.IO.Directory]::CreateDirectory($workerRoot) | Out-Null
    $workerRepository = $localRepository
    $tailRepository = $null
    if ($UseIsolatedRepository) {
        $workerRepository = Assert-PathWithin `
            (Join-Path $workerRepositoryRoot $ModuleDirectory.Name) `
            $runRoot `
            "模块 worker Maven 仓库"
        [System.IO.Directory]::CreateDirectory($workerRepository) | Out-Null
        $tailRepository = $localRepository
        if ([System.IO.Path]::GetFullPath($workerRepository).Equals(
                [System.IO.Path]::GetFullPath($localRepository),
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
            throw "模块 $($ModuleDirectory.Name) 的 worker 仓库不得与共享 tail 仓库相同。"
        }
    }

    $stdoutPath = Join-Path $workerRoot "maven-stdout.log"
    $stderrPath = Join-Path $workerRoot "maven-stderr.log"
    $workerCommandPath = Join-Path $workerRoot "maven-worker.cmd"
    $moduleArguments = @(
        "-f", $pom,
        "clean", "verify"
    )
    if ($SkipTests) {
        $moduleArguments += "-DskipTests"
    }
    $mavenArgumentParameters = @{
        MavenArguments = $moduleArguments
        Repository = $workerRepository
    }
    if ($UseIsolatedRepository) {
        $mavenArgumentParameters.UseTail = $true
    }
    $effectiveArguments = @(Get-MavenArguments @mavenArgumentParameters)
    $commandLine = "call $(ConvertTo-CmdArgument $script:mavenExecutable)"
    foreach ($argument in $effectiveArguments) {
        $commandLine += " $(ConvertTo-CmdArgument $argument)"
    }
    $workerCommand = "@echo off`r`n$commandLine`r`nexit /b %ERRORLEVEL%`r`n"
    $ansiCodePage = [System.Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage
    [System.IO.File]::WriteAllText(
        $workerCommandPath,
        $workerCommand,
        [System.Text.Encoding]::GetEncoding($ansiCodePage)
    )

    $repositoryMode = if ($UseIsolatedRepository) { "隔离仓库+共享tail" } else { "共享仓库" }
    Write-Host "启动模块 worker：$($ModuleDirectory.Name)；模式：$repositoryMode；仓库：$workerRepository"
    # 经 cmd.exe 启动 .cmd，并使用 .NET Process 读取真实退出码；Windows PowerShell 5.1 的
    # Start-Process 对 .cmd 子进程可能返回 0，不能用于判断 Maven 是否失败。
    $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = $env:ComSpec
    $processInfo.Arguments = '/d /s /c ""' + $workerCommandPath + '""'
    $processInfo.WorkingDirectory = $repoRoot
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
            throw "模块 worker 进程未能启动。"
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
        if ($process) { $process.Dispose() }
        throw
    }
    return [pscustomobject]@{
        Name = $ModuleDirectory.Name
        Directory = $ModuleDirectory
        CommandPath = $workerCommandPath
        Process = $process
        StdoutStream = $stdoutStream
        StderrStream = $stderrStream
        StdoutTask = $stdoutTask
        StderrTask = $stderrTask
        ExitCode = $null
        StdoutPath = $stdoutPath
        StderrPath = $stderrPath
        Repository = $workerRepository
        TailRepository = $tailRepository
        Isolated = $UseIsolatedRepository
    }
}

function Invoke-ModuleWorkers {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo[]]$ModuleDirectories,
        [bool]$ForceIsolatedRepository = $false
    )
    if ($ModuleDirectories.Count -eq 0) {
        return @()
    }

    $effectiveParallel = [Math]::Min($MaxParallel, $ModuleDirectories.Count)
    $useIsolatedRepository = $effectiveParallel -gt 1 -or $ForceIsolatedRepository
    if ($useIsolatedRepository) {
        [System.IO.Directory]::CreateDirectory($workerRepositoryRoot) | Out-Null
    }
    $modeDescription = if ($useIsolatedRepository) {
        "隔离仓库+共享tail"
    }
    else {
        "共享仓库（串行）"
    }
    Write-Host "模块构建调度：$($ModuleDirectories.Count) 个模块，MaxParallel=$MaxParallel，有效并发=$effectiveParallel，模式=$modeDescription"
    $running = [System.Collections.Generic.List[object]]::new()
    $completed = [System.Collections.Generic.List[object]]::new()
    $nextIndex = 0
    $failureSeen = $false
    $launchFailure = $null

    while ($nextIndex -lt $ModuleDirectories.Count -or $running.Count -gt 0) {
        while (-not $failureSeen -and
                $running.Count -lt $effectiveParallel -and
                $nextIndex -lt $ModuleDirectories.Count) {
            $moduleDirectory = $ModuleDirectories[$nextIndex]
            try {
                $worker = Start-ModuleWorker `
                    -ModuleDirectory $moduleDirectory `
                    -UseIsolatedRepository $useIsolatedRepository
                $running.Add($worker)
                $nextIndex++
            }
            catch {
                # 启动失败也必须停止继续派发，并等待此前已启动的 worker 收尾。
                $launchFailure = $_
                $failureSeen = $true
            }
        }

        if ($running.Count -eq 0) {
            break
        }

        $exited = @($running | Where-Object { $_.Process.HasExited })
        if ($exited.Count -eq 0) {
            Start-Sleep -Milliseconds 100
            continue
        }

        foreach ($worker in $exited) {
            $worker.Process.WaitForExit()
            $worker.StdoutTask.GetAwaiter().GetResult() | Out-Null
            $worker.StderrTask.GetAwaiter().GetResult() | Out-Null
            $worker.StdoutStream.Dispose()
            $worker.StderrStream.Dispose()
            $worker.Process.Refresh()
            $worker.ExitCode = [int]$worker.Process.ExitCode
            $worker.Process.Dispose()
            Write-WorkerLog $worker
            $completed.Add($worker)
            $running.Remove($worker) | Out-Null
            if ($worker.ExitCode -ne 0) {
                $failureSeen = $true
            }
        }
    }

    $failed = @($completed | Where-Object { $_.ExitCode -ne 0 })
    if ($launchFailure) {
        throw "模块 worker 启动失败；已等待并收尾此前启动的 worker，未发布候选暂存。模块=$($moduleDirectories[$nextIndex].Name)；原因：$($launchFailure.Exception.Message)"
    }
    if ($failed.Count -gt 0) {
        $details = @($failed | ForEach-Object {
            "模块=$($_.Name)；Maven 原始退出码=$($_.ExitCode)；stdout=$($_.StdoutPath)；stderr=$($_.StderrPath)"
        }) -join "`n"
        throw "模块 Maven clean verify 失败；已等待并收尾已启动 worker，未发布候选暂存。`n$details"
    }
    return @($completed)
}

function Get-ModuleJar {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo]$ModuleDirectory
    )
    $moduleJars = @(Get-ChildItem `
        -LiteralPath (Join-Path $ModuleDirectory.FullName "target") `
        -Filter "*.jar" `
        -File |
        Where-Object {
            $_.Name -notlike "*.original" -and
            $_.Name -notlike "*-sources.jar" -and
            $_.Name -notlike "*-javadoc.jar"
        })
    if ($moduleJars.Count -ne 1) {
        throw "模块 $($ModuleDirectory.Name) 应产出一个可运行 JAR，实际 $($moduleJars.Count) 个。"
    }
    return $moduleJars[0]
}

function Publish-CandidateModules {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo[]]$ModuleDirectories,
        [switch]$RequireControlModule
    )
    [System.IO.Directory]::CreateDirectory($candidateStagingRoot) | Out-Null

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $systemModuleDirectories = @()
    # 每个模块的 JAR 检查、唯一契约和复制全部由主进程串行完成，worker 不触碰正式暂存目录。
    foreach ($moduleDirectory in $ModuleDirectories) {
        $moduleJar = Get-ModuleJar $moduleDirectory
        $moduleArchive = [System.IO.Compression.ZipFile]::OpenRead($moduleJar.FullName)
        try {
            $assetNames = @($moduleArchive.Entries | ForEach-Object { $_.FullName })
            foreach ($requiredAsset in @("orderly-ui/module.json", "orderly-ui/module.js", "orderly-ui/module.css")) {
                if ($assetNames -notcontains $requiredAsset) {
                    throw "模块 $($moduleDirectory.Name) 缺少自包含 UI 资源：$requiredAsset"
                }
            }
            $contractEntries = @($moduleArchive.Entries | Where-Object {
                $_.FullName -match '^orderly-modules/[^/]+\.json$'
            })
            if ($contractEntries.Count -ne 1) {
                throw "模块 $($moduleDirectory.Name) 必须且只能包含一个 orderly-modules/*.json 契约，实际 $($contractEntries.Count) 个。"
            }
            foreach ($contractEntry in $contractEntries) {
                $reader = [System.IO.StreamReader]::new(
                    $contractEntry.Open(),
                    [System.Text.UTF8Encoding]::new($false),
                    $true
                )
                try {
                    $contract = $reader.ReadToEnd() | ConvertFrom-Json
                }
                finally {
                    $reader.Dispose()
                }
                if ([string]$contract.moduleId -ne $moduleDirectory.Name) {
                    throw "模块目录名与契约 moduleId 不一致：$($moduleDirectory.Name) -> $($contract.moduleId)"
                }
                if ($null -ne $contract.system) {
                    if (-not ([string]$contract.moduleId).StartsWith(
                            $systemSourcePrefix,
                            [System.StringComparison]::Ordinal
                        )) {
                        throw "受保护系统模块必须使用 module_system_ 前缀：$($moduleDirectory.Name)"
                    }
                    if (@("MODULE_CONTROL", "SYSTEM_SERVICE") -notcontains [string]$contract.system.role -or
                            $contract.system.protected -ne $true) {
                        throw "模块 $($moduleDirectory.Name) 的 system 元数据必须声明受保护系统角色。"
                    }
                    $capabilities = @($contract.system.capabilities)
                    if ($capabilities.Count -eq 0 -or
                            @($capabilities | Where-Object {
                                [string]$_ -notin @("MODULE_GOVERNANCE")
                            }).Count -gt 0) {
                        throw "模块 $($moduleDirectory.Name) 声明了不支持的系统能力。"
                    }
                    if ($contract.system.role -eq "MODULE_CONTROL" -and
                            $capabilities -notcontains "MODULE_GOVERNANCE") {
                        throw "MODULE_CONTROL 必须声明 MODULE_GOVERNANCE 能力。"
                    }
                    $systemModuleDirectories += $moduleDirectory.Name
                }
                elseif (([string]$contract.moduleId).StartsWith(
                        $systemSourcePrefix,
                        [System.StringComparison]::Ordinal
                    )) {
                    throw "module_system_ 前缀只允许宿主内置受保护模块使用：$($moduleDirectory.Name)"
                }
            }
            if ($moduleDirectory.Name -eq $controlSource) {
                $controlContracts = @($contractEntries | Where-Object {
                    $_.FullName -match 'module_system_control\.json$'
                })
                if ($controlContracts.Count -ne 1) {
                    throw "module_system_control 必须且只能声明一个 module_system_control.json 契约。"
                }
            }
        }
        finally {
            $moduleArchive.Dispose()
        }
        Copy-Item -LiteralPath $moduleJar.FullName `
            -Destination (Join-Path $candidateStagingRoot "$($moduleDirectory.Name).jar") -Force
    }

    $systemModuleDirectories = @($systemModuleDirectories | Sort-Object -Unique)
    if ($RequireControlModule -and
            $systemModuleDirectories -notcontains $controlSource) {
        throw "宿主构建要求受保护控制模块 $controlSource，实际系统模块：$($systemModuleDirectories -join ', ')"
    }
    if (-not $RequireControlModule -and $systemModuleDirectories.Count -ne 0) {
        throw "业务模块构建不得包含系统模块契约，实际：$($systemModuleDirectories -join ', ')"
    }
}

function Copy-PreservedBusinessModuleJars {
    if (-not (Test-Path -LiteralPath $stagingRoot -PathType Container)) {
        return
    }
    foreach ($jar in @(Get-ChildItem -LiteralPath $stagingRoot -Filter "*.jar" -File -Force |
        Where-Object {
            -not $_.Name.StartsWith($systemSourcePrefix, [System.StringComparison]::Ordinal)
        } |
        Sort-Object Name)) {
        if (($jar.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "运行时模块 JAR 不能是重解析点：$($jar.FullName)"
        }
        $destination = Assert-PathWithin `
            (Join-Path $candidateStagingRoot $jar.Name) `
            $candidateStagingRoot `
            "保留的业务模块 JAR"
        if (Test-Path -LiteralPath $destination) {
            throw "候选模块暂存出现重复 JAR：$destination"
        }
        Copy-Item -LiteralPath $jar.FullName -Destination $destination -Force
        Write-Host "保留已验证业务模块 JAR：$($jar.Name)"
    }
}

function Publish-RuntimeModuleJars {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo[]]$ModuleDirectories
    )
    [System.IO.Directory]::CreateDirectory($stagingRoot) | Out-Null

    foreach ($moduleDirectory in $ModuleDirectories) {
        $moduleJar = Get-ModuleJar $moduleDirectory
        $destination = Assert-PathWithin `
            (Join-Path $stagingRoot "$($moduleDirectory.Name).jar") `
            $stagingRoot `
            "运行时模块 JAR"
        if (Test-Path -LiteralPath $destination -PathType Container) {
            throw "运行时模块目标不是普通 JAR 文件：$destination"
        }
        $destinationItem = Get-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
        if ($destinationItem -and
                ($destinationItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "运行时模块 JAR 不能是重解析点：$destination"
        }
        $temporaryName = ".$($moduleDirectory.Name).$([guid]::NewGuid().ToString('N')).tmp"
        $temporary = Assert-PathWithin `
            (Join-Path $stagingRoot $temporaryName) `
            $stagingRoot `
            "运行时模块临时 JAR"
        try {
            [System.IO.File]::Copy($moduleJar.FullName, $temporary, $true)
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
        Write-Host "已原子发布模块 JAR：$destination"
    }
}

function Publish-BackgroundModuleOutput {
    param(
        [Parameter(Mandatory = $true)][System.IO.DirectoryInfo[]]$ModuleDirectories
    )
    if ($ModuleDirectories.Count -ne 1) {
        throw "BackgroundWorker 必须且只能交接一个模块 JAR，实际 $($ModuleDirectories.Count) 个。"
    }
    $moduleDirectory = $ModuleDirectories[0]
    $outputRoot = Assert-PathWithin `
        ([System.IO.Path]::GetFullPath($ModuleOutputDirectory)) `
        $targetRoot `
        "后台模块输出目录"
    [System.IO.Directory]::CreateDirectory($outputRoot) | Out-Null
    $source = Assert-PathWithin `
        (Join-Path $candidateStagingRoot "$($moduleDirectory.Name).jar") `
        $candidateStagingRoot `
        "后台模块候选 JAR"
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "后台模块候选 JAR 不存在：$source"
    }
    $destination = Assert-PathWithin `
        (Join-Path $outputRoot "$($moduleDirectory.Name).jar") `
        $outputRoot `
        "后台模块交接 JAR"
    [System.IO.File]::Copy($source, $destination, $true)
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $hashFile = Assert-PathWithin `
        (Join-Path $outputRoot "$($moduleDirectory.Name).jar.sha256") `
        $outputRoot `
        "后台模块交接校验文件"
    [System.IO.File]::WriteAllText(
        $hashFile,
        "$hash  $($moduleDirectory.Name).jar$([Environment]::NewLine)",
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Host "后台模块构建完成，等待单线程发布：$destination"
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
        throw "路径不在预期根目录内：$fullPath"
    }
    return $fullPath.Substring($prefix.Length).Replace("\", "/")
}

function Get-StringSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-DirectoryFingerprint {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return "<missing>"
    }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "目录指纹目标不是目录：$Path"
    }
    $lines = @(Get-ChildItem -LiteralPath $Path -Recurse -File -Force |
        Sort-Object FullName |
        ForEach-Object {
            $relativePath = Get-RelativePathFromRoot $_.FullName $Path
            "$relativePath`t$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
        })
    if ($lines.Count -eq 0) {
        return "dir-empty:$((Get-StringSha256 ''))"
    }
    return "dir:$((Get-StringSha256 (($lines -join "`n") + "`n")))"
}

function Get-HostJars {
    if (-not (Test-Path -LiteralPath $hostTargetRoot -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $hostTargetRoot -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object Name)
}

function Get-HostJarFingerprint {
    $records = @(Get-HostJars | ForEach-Object {
        "$($_.Name)`t$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
    })
    if ($records.Count -eq 0) {
        return "<missing>"
    }
    return ($records -join "`n")
}

function New-HostStageTransaction {
    [System.IO.Directory]::CreateDirectory($rollbackRoot) | Out-Null
    $stagingBackup = Join-Path $rollbackRoot "orderly-modules-before"
    $hostJarBackup = Join-Path $rollbackRoot "host-jars-before"
    [System.IO.Directory]::CreateDirectory($hostJarBackup) | Out-Null

    if (Test-Path -LiteralPath $stagingRoot -PathType Container) {
        Copy-Item -LiteralPath $stagingRoot -Destination $stagingBackup -Recurse -Force
    }
    foreach ($jar in @(Get-HostJars)) {
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $hostJarBackup $jar.Name) -Force
    }

    $preparedStaging = Assert-PathWithin `
        (Join-Path $targetRoot ("orderly-modules-next-" + [guid]::NewGuid().ToString("N"))) `
        $targetRoot `
        "候选模块暂存"
    Copy-Item -LiteralPath $candidateStagingRoot -Destination $preparedStaging -Recurse -Force

    return [pscustomobject]@{
        StagingExisted = Test-Path -LiteralPath $stagingRoot -PathType Container
        StagingFingerprint = Get-DirectoryFingerprint $stagingRoot
        StagingBackup = $stagingBackup
        HostJarBackup = $hostJarBackup
        HostJarFingerprint = Get-HostJarFingerprint
        PreparedStaging = $preparedStaging
        StagingPublished = $false
    }
}

function Publish-HostStage {
    param([Parameter(Mandatory = $true)]$Transaction)
    if (Test-Path -LiteralPath $Transaction.PreparedStaging) {
        if (Test-Path -LiteralPath $stagingRoot) {
            Remove-Item -LiteralPath $stagingRoot -Recurse -Force
        }
        Move-Item -LiteralPath $Transaction.PreparedStaging -Destination $stagingRoot
        $Transaction.StagingPublished = $true
        return
    }
    throw "候选模块暂存目录不存在：$($Transaction.PreparedStaging)"
}

function Restore-HostStage {
    param([Parameter(Mandatory = $true)]$Transaction)
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
    if ($Transaction.StagingExisted -and
            (Test-Path -LiteralPath $Transaction.StagingBackup -PathType Container)) {
        Move-Item -LiteralPath $Transaction.StagingBackup -Destination $stagingRoot
    }
    if (Test-Path -LiteralPath $Transaction.PreparedStaging) {
        Remove-Item -LiteralPath $Transaction.PreparedStaging -Recurse -Force
    }

    if (-not (Test-Path -LiteralPath $hostTargetRoot -PathType Container)) {
        [System.IO.Directory]::CreateDirectory($hostTargetRoot) | Out-Null
    }
    foreach ($jar in @(Get-HostJars)) {
        Remove-Item -LiteralPath $jar.FullName -Force
    }
    foreach ($jar in @(Get-ChildItem -LiteralPath $Transaction.HostJarBackup -Filter "*.jar" -File)) {
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $hostTargetRoot $jar.Name) -Force
    }

    $restoredStagingFingerprint = Get-DirectoryFingerprint $stagingRoot
    $restoredHostJarFingerprint = Get-HostJarFingerprint
    if ($restoredStagingFingerprint -ne $Transaction.StagingFingerprint) {
        throw "模块暂存恢复后指纹不一致。预期=$($Transaction.StagingFingerprint) 实际=$restoredStagingFingerprint"
    }
    if ($restoredHostJarFingerprint -ne $Transaction.HostJarFingerprint) {
        throw "宿主 JAR 恢复后指纹不一致。预期=$($Transaction.HostJarFingerprint) 实际=$restoredHostJarFingerprint"
    }
}

function Complete-HostStage {
    param([Parameter(Mandatory = $true)]$Transaction)
    if (Test-Path -LiteralPath $Transaction.StagingBackup) {
        Remove-Item -LiteralPath $Transaction.StagingBackup -Recurse -Force
    }
    if (Test-Path -LiteralPath $Transaction.HostJarBackup) {
        Remove-Item -LiteralPath $Transaction.HostJarBackup -Recurse -Force
    }
}

function Remove-TestArtifacts {
    # 测试会产生多种随机夹具。仅清理这些已知前缀，并在删除前再次校验绝对路径仍在目标根内。
    $testRoots = @(
        $targetRoot,
        $hostTargetRoot
    )
    foreach ($testRoot in $testRoots) {
        if (-not (Test-Path -LiteralPath $testRoot -PathType Container)) {
            continue
        }
        Get-ChildItem -LiteralPath $testRoot -Directory -Force -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -like "test-data-*" -or
                $_.Name -like "zero-module-data-*" -or
                $_.Name -like "zero-modules-*" -or
                $_.Name -like "module-init-data-*" -or
                $_.Name -like "module-system-control-fixture-*" -or
                $_.Name -like "module-refresh-fixture-*" -or
                $_.Name -like "module-state-corruption-*" -or
                $_.Name -like "module-folder-*" -or
                $_.Name -like "module-system-*" -or
                $_.Name -like "theme-catalog-*"
            } |
            ForEach-Object {
                $fullPath = [System.IO.Path]::GetFullPath($_.FullName)
                $fullRoot = $testRoot.TrimEnd('\') + '\'
                if (-not $fullPath.StartsWith($fullRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                    throw "拒绝清理测试目录之外的路径：$fullPath"
                }
                Remove-Item -LiteralPath $fullPath -Recurse -Force
            }
    }
}

$buildLock = $null
Push-Location $repoRoot
try {
    if (-not $BackgroundWorker) {
        $buildLock = Enter-BuildLock
    }
    Assert-PathWithin $runRoot $targetRoot "构建运行目录" | Out-Null
    [System.IO.Directory]::CreateDirectory($runRoot) | Out-Null
    [System.IO.Directory]::CreateDirectory($workerLogRoot) | Out-Null
    Write-Host "构建运行目录：$runRoot"

    Assert-Jdk21
    Assert-MavenCapability
    Assert-RepositorySource

    # 模块直接从源码目录发现，不维护静态索引；排序保证串行输出和并行启动顺序稳定。
    $moduleDirectories = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "Module") -Directory -Filter "module_*" |
        Sort-Object Name)
    $moduleNames = @($moduleDirectories | ForEach-Object { $_.Name })
    $systemModules = @($moduleDirectories | Where-Object {
        $_.Name.StartsWith($systemSourcePrefix, [System.StringComparison]::Ordinal)
    })
    $controlModules = @($moduleDirectories | Where-Object { $_.Name -eq $controlSource })
    if (-not $ModulesOnly -and $controlModules.Count -ne 1) {
        # 系统模块是宿主治理能力的唯一入口，缺失或重复都不能得到一个可启动的模块目录。
        throw "宿主构建要求且仅允许一个 Module/$controlSource 源码目录，实际 $($controlModules.Count) 个。"
    }
    Write-Host "扫描到 $($moduleNames.Count) 个模块源码：$($moduleNames -join ', ')"

    $moduleDirectoriesToBuild = if ($BootstrapOnly) {
        @($systemModules)
    }
    elseif ($ModulesOnly -and -not [string]::IsNullOrWhiteSpace($ModuleName)) {
        if ($ModuleName -notmatch '^module_[a-z0-9_]+$' -or
                $ModuleName.StartsWith(
                    $systemSourcePrefix,
                    [System.StringComparison]::Ordinal
                )) {
            throw "-ModuleName 必须是已发现的普通业务模块名，且不能使用 module_system_ 前缀：$ModuleName"
        }
        $selected = @($moduleDirectories | Where-Object { $_.Name -eq $ModuleName })
        if ($selected.Count -ne 1) {
            throw "未发现指定业务模块源码：Module/$ModuleName"
        }
        $selected
    }
    elseif ($ModulesOnly) {
        @($moduleDirectories | Where-Object {
            -not $_.Name.StartsWith(
                $systemSourcePrefix,
                [System.StringComparison]::Ordinal
            )
        })
    }
    else {
        @($moduleDirectories)
    }
    if ($moduleDirectoriesToBuild.Count -eq 0) {
        Write-Host "没有需要构建的模块。"
        return
    }
    $moduleNamesToBuild = @($moduleDirectoriesToBuild | ForEach-Object { $_.Name })
    foreach ($moduleDirectory in $moduleDirectoriesToBuild) {
        $pom = Join-Path $moduleDirectory.FullName "pom.xml"
        if (-not (Test-Path -LiteralPath $pom -PathType Leaf)) {
            throw "模块目录缺少 pom.xml：$($moduleDirectory.FullName)"
        }
    }
    $buildMode = if ($BootstrapOnly) {
        "bootstrap"
    }
    elseif ($ModulesOnly) {
        "modules-only"
    }
    else {
        "full"
    }
    Write-Host "构建模式：$buildMode；本次模块：$($moduleNamesToBuild -join ', ')"

    # kernel 是模块和宿主共享的 SPI，必须先安装到共享预热仓库；只有显式并行 worker 以只读 tail 读取它。
    if (-not $SkipKernelInstall) {
        $kernelArguments = @(
            "-pl", "app/kernel",
            "-am",
            "install"
        )
        if ($SkipTests) {
            $kernelArguments += "-DskipTests"
        }
        Invoke-Maven $kernelArguments
    }
    else {
        Write-Host "复用 BootstrapOnly 已安装的 kernel。"
    }

    $workers = @(Invoke-ModuleWorkers `
        -ModuleDirectories $moduleDirectoriesToBuild `
        -ForceIsolatedRepository:$BackgroundWorker)
    if ($workers.Count -ne $moduleDirectoriesToBuild.Count) {
        throw "模块 worker 完成数与发现数不一致。发现=$($moduleDirectoriesToBuild.Count) 完成=$($workers.Count)"
    }

    Publish-CandidateModules `
        -ModuleDirectories $moduleDirectoriesToBuild `
        -RequireControlModule:(-not $ModulesOnly)
    if ($PreserveBusinessModules) {
        Copy-PreservedBusinessModuleJars
    }

    if ($ModulesOnly) {
        if ($BackgroundWorker) {
            Publish-BackgroundModuleOutput -ModuleDirectories $moduleDirectoriesToBuild
            Write-Host "后台业务模块构建完成，未发布运行时目录：$($moduleNamesToBuild -join ', ')"
        }
        else {
            Publish-RuntimeModuleJars -ModuleDirectories $moduleDirectoriesToBuild
            Write-Host "业务模块构建完成，未重建宿主：$($moduleNamesToBuild -join ', ')"
        }
    }
    else {

    $hostArguments = @(
        "-f", (Join-Path $repoRoot "app\host\pom.xml"),
        "clean", "package"
    )
    # -SkipTests 只影响 Maven 测试；模块 JAR 的发现和自包含检查仍无条件执行。
    if ($SkipTests) {
        $hostArguments += "-DskipTests"
    }

    $transaction = New-HostStageTransaction
    try {
        Publish-HostStage $transaction
        Write-Host "构建宿主 JAR（模块目录：$stagingRoot）"
        Invoke-Maven $hostArguments

        # 宿主必须产出一个可运行 fat JAR；随后检查其内容，防止模块或主题被静默打进框架。
        $hostJars = @(Get-HostJars)
        if ($hostJars.Count -ne 1) {
            throw "宿主构建应产出一个 fat JAR，实际 $($hostJars.Count) 个。"
        }
        $archive = [System.IO.Compression.ZipFile]::OpenRead($hostJars[0].FullName)
        try {
            $bundledModules = @($archive.Entries | Where-Object {
                $_.FullName -match '^BOOT-INF/lib/module[-_].+\.jar$' -or
                $_.FullName -match '^BOOT-INF/classes/orderly-modules/.+\.json$' -or
                $_.FullName -match '(^|/)orderly-ui/'
            })
            $bundledThemes = @($archive.Entries | Where-Object {
                $_.FullName -match '^BOOT-INF/classes/themes(?:/|$)' -or
                $_.FullName -match '(^|/)themes/[^/]+/theme\.json$'
            })
        }
        finally {
            $archive.Dispose()
        }
        if ($bundledModules.Count -ne 0) {
            throw "宿主 fat JAR 不得包含具体模块：$($bundledModules.FullName -join ', ')"
        }
        if ($bundledThemes.Count -ne 0) {
            throw "宿主 fat JAR 不得包含用户主题：$($bundledThemes.FullName -join ', ')"
        }

        Complete-HostStage $transaction
    }
    catch {
        $hostFailure = $_
        try {
            Restore-HostStage $transaction
        }
        catch {
            throw "宿主阶段失败，且恢复上一套模块暂存/宿主 JAR 失败：$($_.Exception.Message)`n宿主阶段原始错误：$($hostFailure.Exception.Message)"
        }
        throw "宿主阶段失败；已恢复上一套模块暂存和宿主 JAR。原始错误：$($hostFailure.Exception.Message)"
    }

    Write-Host "构建完成。运行时模块目录：$stagingRoot"
    if ($MaxParallel -gt 1 -and $moduleDirectoriesToBuild.Count -gt 1) {
        Write-Host "模块 worker 日志与隔离仓库：$runRoot"
    }
    else {
        Write-Host "模块 worker 日志：$runRoot；模块 Maven 使用共享仓库：$localRepository"
    }
    }
    if (-not $BackgroundWorker) {
        $existingManifest = Read-OrderlyBuildManifest $buildManifestPath
        if ($ModulesOnly -and $null -eq $existingManifest) {
            Write-Warning "仅构建模块时没有现有构建清单，未更新 target/build-manifest.json。"
        }
        else {
            Write-OrderlyBuildManifest `
                -RepoRoot $repoRoot `
                -ManifestPath $buildManifestPath `
                -Mode $buildMode `
                -BuiltModuleNames $moduleNamesToBuild `
                -PreserveBusinessModules:$PreserveBusinessModules
        }
    }
}
finally {
    # 即使构建中途失败也清理测试夹具并恢复调用方目录；正式暂存和宿主 JAR 由宿主阶段事务单独恢复。
    try {
        if ($null -ne $buildLock -and $buildLock.Held) {
            Remove-TestArtifacts
        }
    }
    finally {
        try {
            Exit-BuildLock $buildLock
        }
        finally {
            Pop-Location
        }
    }
}
