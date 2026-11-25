#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   构建或复用已有产物，启动本机 Python AI 服务和 Java 宿主，并在退出时清理本次启动的 AI 子进程。
#
# 输入：
#   -Port 指定 Java HTTP 端口，默认 8080；-SkipBuild 复用已有 JAR。
#   -ForceBuild 忽略构建清单并完整重建；-RuntimeManifest 按预构建运行包清单启动。
#   -BackgroundModules 先只构建控制模块与宿主，随后由后台队列并行构建并单线程追加业务模块。
#   -Offline 将严格 Maven 离线模式及可选 -MavenRepository 透传给 build.ps1，
#   并要求复用现有 ai/.venv；该开关不宣称 Node 或整个应用离线。
#   -MavenOffline 只让 Maven 使用包内 maven-repository 严格离线构建，
#   Python 环境仍由 ensure-env.ps1 正常准备。
#   -AiExecutable 使用应用目录内的 PyInstaller AI 可执行文件，跳过 Python 环境准备。
#   -MaxParallel 原样透传给前台完整构建，默认 1；后台并发由宿主持久化设置决定。
#   JAVA_HOME 必须指向 JDK 21，模块和主题目录由仓库根目录推导。
#
# 副作用：
#   未跳过构建时写入 target、对应 Maven 本地仓库和 ai/.venv；启动期间占用 Java 端口及一个临时回环端口，
#   AI 子进程以隐藏窗口启动；后台模式写独立日志，前台模式由健康检查和退出码体现失败。
#   Java 退出后脚本也会终止本次创建的 AI 进程。
#
# 安全边界：
#   每次运行生成随机 Bearer Token，只放入当前进程及 Python 子进程环境，不写入仓库或普通配置。
#   AI 地址和宿主地址固定使用 127.0.0.1；脚本不操作个人目录，运行时数据仍由宿主限定在应用工作区。

param(
    [int]$Port = 8080,
    [switch]$SkipBuild,
    [switch]$ForceBuild,
    [switch]$Offline,
    [switch]$MavenOffline,
    [string]$MavenRepository,
    [ValidateRange(1, 2147483647)]
    [int]$MaxParallel = 1,
    [switch]$BackgroundModules,
    [string]$RuntimeManifest,
    [string]$AiExecutable
)

if ($SkipBuild -and $ForceBuild) {
    throw "-SkipBuild 与 -ForceBuild 不能同时使用。"
}
if (-not [string]::IsNullOrWhiteSpace($RuntimeManifest) -and $ForceBuild) {
    throw "-RuntimeManifest 与 -ForceBuild 不能同时使用。"
}
if (-not [string]::IsNullOrWhiteSpace($RuntimeManifest) -and $BackgroundModules) {
    throw "预构建运行包不允许启动源码模块构建器。"
}

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildPlanScript = Join-Path $PSScriptRoot "build-plan.ps1"
if (-not (Test-Path -LiteralPath $buildPlanScript -PathType Leaf)) {
    throw "缺少构建检测脚本：$buildPlanScript"
}
. $buildPlanScript
$hostRoot = Join-Path $repoRoot "app\host"
$modulesRoot = Join-Path $repoRoot "target\orderly-modules"
$themesRoot = Join-Path $repoRoot "themes"
$aiEnvScript = Join-Path $repoRoot "ai\scripts\ensure-env.ps1"
$buildManifestPath = Join-Path $repoRoot "target\build-manifest.json"
$aiExecutablePath = if ([string]::IsNullOrWhiteSpace($AiExecutable)) {
    ""
}
else {
    $candidate = [System.IO.Path]::GetFullPath($AiExecutable)
    $fullRoot = [System.IO.Path]::GetFullPath($repoRoot).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    if (-not $candidate.StartsWith(
            $fullRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "AI 可执行文件必须位于应用目录内：$candidate"
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "AI 可执行文件不存在：$candidate"
    }
    $candidate
}

# 模块生成器的 Python AI 由 Java 宿主按需启动；优先使用准备后的本地虚拟环境。
# 该变量只传递解释器路径，不包含模型凭据。
if ([string]::IsNullOrWhiteSpace($env:ORDERLY_PYTHON_EXECUTABLE)) {
    $modulePython = Join-Path $repoRoot "ai\.venv\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $modulePython -PathType Leaf)) {
        $pythonCommand = Get-Command python -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($pythonCommand) {
            $modulePython = $pythonCommand.Source
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($modulePython)) {
        $env:ORDERLY_PYTHON_EXECUTABLE = $modulePython
    }
}
if (-not [string]::IsNullOrWhiteSpace($aiExecutablePath)) {
    $env:ORDERLY_AI_EXECUTABLE = $aiExecutablePath
}

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

function Wait-JavaHost {
    param(
        [string]$BaseUrl,
        [System.Diagnostics.Process]$Process
    )
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Java 宿主启动失败，退出码：$($Process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/api/health/live" -Method Get -TimeoutSec 2
            if ([string]$health.status -eq "UP") {
                return $health
            }
        }
        catch {
            # 首次启动时 Spring 尚未完成监听，继续轮询。
        }
        Start-Sleep -Milliseconds 250
    }
    throw "等待 Java 宿主存活检查超时：$BaseUrl/api/health/live"
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

function Invoke-RequiredBuild {
    param([Parameter(Mandatory = $true)][hashtable]$BaseBuildArguments)

    $plan = Resolve-OrderlyBuildPlan -RepoRoot $repoRoot -ManifestPath $buildManifestPath
    if (-not $plan.BuildRequired) {
        Write-Host "构建清单有效，跳过 Maven 构建。"
        Write-Host "ORDERLY_BUILD_SKIPPED=1"
        return
    }

    Write-Host "构建检测要求更新产物。"
    foreach ($reason in @($plan.Reasons)) {
        Write-Host "  - $reason"
    }

    if ($plan.FullBuildRequired) {
        & (Join-Path $PSScriptRoot "build.ps1") @BaseBuildArguments
        return
    }

    $moduleBuildCount = 0
    foreach ($moduleName in @($plan.BusinessModuleNames)) {
        $moduleArguments = @{
            ModulesOnly = $true
            ModuleName = $moduleName
            SkipTests = $true
        }
        if ($BaseBuildArguments.ContainsKey("Offline")) {
            $moduleArguments.Offline = $BaseBuildArguments.Offline
        }
        if ($BaseBuildArguments.ContainsKey("MavenRepository")) {
            $moduleArguments.MavenRepository = $BaseBuildArguments.MavenRepository
        }
        if ($moduleBuildCount -gt 0) {
            $moduleArguments.SkipKernelInstall = $true
        }
        & (Join-Path $PSScriptRoot "build.ps1") @moduleArguments
        $moduleBuildCount++
    }

    if ($plan.HostBuildRequired) {
        $bootstrapArguments = @{
            BootstrapOnly = $true
            PreserveBusinessModules = $true
            SkipTests = $true
        }
        if ($BaseBuildArguments.ContainsKey("Offline")) {
            $bootstrapArguments.Offline = $BaseBuildArguments.Offline
        }
        if ($BaseBuildArguments.ContainsKey("MavenRepository")) {
            $bootstrapArguments.MavenRepository = $BaseBuildArguments.MavenRepository
        }
        if ($moduleBuildCount -gt 0) {
            $bootstrapArguments.SkipKernelInstall = $true
        }
        & (Join-Path $PSScriptRoot "build.ps1") @bootstrapArguments
    }
}

function Read-RuntimeManifest {
    param([Parameter(Mandatory = $true)][string]$Path)

    $manifestPath = Assert-OrderlyPathWithin $Path $repoRoot "运行包清单"
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "运行包清单不存在：$manifestPath"
    }
    try {
        $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath |
            ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "运行包清单 JSON 损坏：$($_.Exception.Message)"
    }
    if ([int]$manifest.schemaVersion -ne 1 -or
            [string]$manifest.packageType -ne "orderly-runtime") {
        throw "运行包清单版本或类型不受支持。"
    }
    if ([int]$manifest.jdkMajor -ne 21) {
        throw "运行包要求 JDK 21，清单记录：$($manifest.jdkMajor)"
    }
    if (-not (Test-OrderlyManifestArtifact -RepoRoot $repoRoot -Artifact $manifest.hostJar)) {
        throw "运行包宿主 JAR 缺失、大小不符或 SHA-256 不匹配。"
    }
    $modules = @($manifest.modules)
    if ($modules.Count -eq 0) {
        throw "运行包未登记任何模块 JAR。"
    }
    foreach ($module in $modules) {
        if (-not (Test-OrderlyManifestArtifact -RepoRoot $repoRoot -Artifact $module.artifact)) {
            throw "运行包模块 JAR 缺失、大小不符或 SHA-256 不匹配：$($module.moduleId)"
        }
    }
    $hostPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ([string]$manifest.hostJar.path)))
    $firstModulePath = [System.IO.Path]::GetFullPath(
        (Join-Path $repoRoot ([string]$modules[0].artifact.path))
    )
    $runtimeModulesRoot = Split-Path -Parent $firstModulePath
    foreach ($module in $modules) {
        $modulePath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ([string]$module.artifact.path)))
        if (-not (Split-Path -Parent $modulePath).Equals(
                $runtimeModulesRoot,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
            throw "运行包模块 JAR 必须位于同一模块目录：$modulePath"
        }
    }
    $runtimeThemesRoot = Assert-OrderlyPathWithin `
        (Join-Path $repoRoot ([string]$manifest.themesRoot)) `
        $repoRoot `
        "运行包主题目录"
    if (-not (Test-Path -LiteralPath $runtimeThemesRoot -PathType Container)) {
        throw "运行包主题目录不存在：$runtimeThemesRoot"
    }
    return [pscustomobject]@{
        HostJar = $hostPath
        ModulesRoot = $runtimeModulesRoot
        ThemesRoot = $runtimeThemesRoot
    }
}

function Invoke-BackgroundStart {
    param(
        [Parameter(Mandatory = $true)][string]$JavaExecutable,
        [Parameter(Mandatory = $true)][string]$HostJar,
        [Parameter(Mandatory = $true)][string]$ModulesDirectory,
        [Parameter(Mandatory = $true)][string]$ThemesDirectory,
        [Parameter(Mandatory = $true)][int]$HttpPort,
        [string]$AiExecutable,
        [switch]$OfflineMode,
        [switch]$SkipMavenTests,
        [switch]$UseMavenOffline,
        [string]$Repository,
        [switch]$SkipBackgroundModules
    )
    $runtimeStateRoot = if ([string]::IsNullOrWhiteSpace($env:ORDERLY_DATA_DIR)) {
        Join-Path $repoRoot "target"
    }
    else {
        Join-Path $env:ORDERLY_DATA_DIR "runtime"
    }
    $logRoot = Join-Path $runtimeStateRoot "background"
    [System.IO.Directory]::CreateDirectory($logRoot) | Out-Null
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $javaOut = Join-Path $logRoot "java-$stamp.out.log"
    $javaErr = Join-Path $logRoot "java-$stamp.err.log"
    $builderOut = Join-Path $logRoot "module-builder-$stamp.out.log"
    $builderErr = Join-Path $logRoot "module-builder-$stamp.err.log"
    $watcherOut = Join-Path $logRoot "module-watcher-$stamp.out.log"
    $watcherErr = Join-Path $logRoot "module-watcher-$stamp.err.log"
    $aiOut = Join-Path $logRoot "ai-$stamp.out.log"
    $aiErr = Join-Path $logRoot "ai-$stamp.err.log"
    $baseUrl = "http://127.0.0.1:$HttpPort"

    $previousToken = $env:ORDERLY_AI_IPC_TOKEN
    $previousAiPort = $env:ORDERLY_AI_PORT
    $javaProcess = $null
    $builderProcess = $null
    $moduleWatcherProcess = $null
    $aiProcess = $null
    Push-Location $repoRoot
    try {
        $aiPort = Get-FreeTcpPort
        $aiBaseUrl = "http://127.0.0.1:$aiPort"
        $env:ORDERLY_AI_IPC_TOKEN = New-IpcToken
        $env:ORDERLY_AI_PORT = [string]$aiPort

        $javaArguments = @(
            "-Dorderly.modules-dir=$ModulesDirectory",
            "-Dorderly.module-source-dir=$repoRoot\Module",
            "-Dorderly.themes-dir=$ThemesDirectory",
            ("-Dorderly.background-modules-enabled=" + $(if ($SkipBackgroundModules) { "false" } else { "true" })),
            "-Dorderly.module-build-status=$(Join-Path $runtimeStateRoot 'module-build-status.json')",
            "-Dorderly.module-build-priority=$(Join-Path $runtimeStateRoot 'module-build-priority.json')",
            "-Dorderly.ai-service.url=$aiBaseUrl",
            "-jar",
            $HostJar,
            "--server.port=$HttpPort"
        )
        if (-not [string]::IsNullOrWhiteSpace($env:ORDERLY_DATA_DIR)) {
            $javaArguments = @("-Dorderly.data-dir=$env:ORDERLY_DATA_DIR") + $javaArguments
        }
        Write-Host "使用 Java：$JavaExecutable"
        Write-Host "模块目录：$ModulesDirectory"
        Write-Host "本地地址：$baseUrl/"
        Write-Host "Java 日志：$javaOut"
        $javaProcess = Start-Process `
            -FilePath $JavaExecutable `
            -ArgumentList (Join-ProcessArguments $javaArguments) `
            -WorkingDirectory $repoRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $javaOut `
            -RedirectStandardError $javaErr `
            -PassThru
        Wait-JavaHost -BaseUrl $baseUrl -Process $javaProcess | Out-Null
        if (-not $SkipBackgroundModules) {
            Write-Host "框架已启动，开始后台准备业务模块。"
            Write-Host "模块构建日志：$builderOut"

            $builderArguments = @(
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                (Join-Path $PSScriptRoot "build-module-background.ps1"),
                "-BaseUrl",
                $baseUrl,
                "-RuntimeStateRoot",
                $runtimeStateRoot,
                "-SkipKernelInstall"
            )
            if ($SkipMavenTests) {
                $builderArguments += "-SkipTests"
            }
            if ($OfflineMode -or $UseMavenOffline) {
                $builderArguments += "-Offline"
            }
            if (-not [string]::IsNullOrWhiteSpace($Repository)) {
                $builderArguments += @("-MavenRepository", $Repository)
            }
            $powerShellExecutable = (Get-Process -Id $PID).Path
            $builderProcess = Start-Process `
                -FilePath $powerShellExecutable `
                -ArgumentList (Join-ProcessArguments $builderArguments) `
                -WorkingDirectory $repoRoot `
                -WindowStyle Hidden `
                -RedirectStandardOutput $builderOut `
                -RedirectStandardError $builderErr `
                -PassThru

            $watcherArguments = @(
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                (Join-Path $PSScriptRoot "watch-module-downloads.ps1"),
                "-BaseUrl",
                $baseUrl,
                "-InitialBuilderPid",
                [string]$builderProcess.Id,
                "-RuntimeStateRoot",
                $runtimeStateRoot,
                "-SkipKernelInstall"
            )
            if ($SkipMavenTests) {
                $watcherArguments += "-SkipTests"
            }
            if ($OfflineMode -or $UseMavenOffline) {
                $watcherArguments += "-Offline"
            }
            if (-not [string]::IsNullOrWhiteSpace($Repository)) {
                $watcherArguments += @("-MavenRepository", $Repository)
            }
            $moduleWatcherProcess = Start-Process `
                -FilePath $powerShellExecutable `
                -ArgumentList (Join-ProcessArguments $watcherArguments) `
                -WorkingDirectory $repoRoot `
                -WindowStyle Hidden `
                -RedirectStandardOutput $watcherOut `
                -RedirectStandardError $watcherErr `
                -PassThru
            Write-Host "模块下载监督器日志：$watcherOut"
        }
        else {
            Write-Host "运行包模式：跳过源码模块构建器与下载监督器。"
        }

        $aiPreparationError = $null
        try {
            if (-not [string]::IsNullOrWhiteSpace($AiExecutable)) {
                $aiProcess = Start-Process `
                    -FilePath $AiExecutable `
                    -ArgumentList (Join-ProcessArguments @("--port", [string]$aiPort)) `
                    -WorkingDirectory (Split-Path -Parent $AiExecutable) `
                    -WindowStyle Hidden `
                    -RedirectStandardOutput $aiOut `
                    -RedirectStandardError $aiErr `
                    -PassThru
            }
            else {
            if ($OfflineMode) {
                $python = Join-Path $repoRoot "ai\.venv\Scripts\python.exe"
                if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
                    throw "严格离线启动需要现有 ai/.venv。"
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
            $aiProcess = Start-Process `
                -FilePath $python `
                -ArgumentList (Join-ProcessArguments @(
                    "-m",
                    "orderly_ai",
                    "--port",
                    [string]$aiPort
                )) `
                -WorkingDirectory $repoRoot `
                -WindowStyle Hidden `
                -RedirectStandardOutput $aiOut `
                -RedirectStandardError $aiErr `
                -PassThru
            }
            Wait-AiService -BaseUrl $aiBaseUrl -Token $env:ORDERLY_AI_IPC_TOKEN -Process $aiProcess
            Write-Host "AI 服务已就绪：$aiBaseUrl"
        }
        catch {
            $aiPreparationError = $_
            if (-not [string]::IsNullOrWhiteSpace($AiExecutable)) {
                throw $aiPreparationError
            }
            Write-Warning "Java 与模块构建继续运行，但 AI 服务未就绪：$($_.Exception.Message)"
        }

        while (-not $javaProcess.HasExited) {
            Start-Sleep -Milliseconds 500
        }
        $javaProcess.Refresh()
        $appExitCode = [int]$javaProcess.ExitCode

        if ($builderProcess.HasExited) {
            $builderProcess.Refresh()
            if ($builderProcess.ExitCode -ne 0) {
                Write-Warning "后台模块构建器退出码：$($builderProcess.ExitCode)；日志：$builderErr"
            }
        }
        if ($moduleWatcherProcess.HasExited) {
            $moduleWatcherProcess.Refresh()
            if ($moduleWatcherProcess.ExitCode -ne 0) {
                Write-Warning "模块下载监督器退出码：$($moduleWatcherProcess.ExitCode)；日志：$watcherErr"
            }
        }
        if ($aiPreparationError) {
            Write-Warning "AI 服务启动失败：$($aiPreparationError.Exception.Message)；日志：$aiErr"
        }
        if ($appExitCode -ne 0) {
            throw "应用退出码：$appExitCode；Java 日志：$javaErr"
        }
    }
    finally {
        if ($moduleWatcherProcess -and -not $moduleWatcherProcess.HasExited) {
            & taskkill.exe /PID $moduleWatcherProcess.Id /T /F 2>&1 | Out-Null
            $moduleWatcherProcess.WaitForExit(5000) | Out-Null
        }
        if ($builderProcess -and -not $builderProcess.HasExited) {
            # build.ps1 会启动 Maven 子进程；按进程树终止，避免留下后台构建。
            & taskkill.exe /PID $builderProcess.Id /T /F 2>&1 | Out-Null
            $builderProcess.WaitForExit(5000) | Out-Null
        }
        if ($aiProcess -and -not $aiProcess.HasExited) {
            Stop-Process -Id $aiProcess.Id -Force
            $aiProcess.WaitForExit(5000) | Out-Null
        }
        if ($javaProcess -and -not $javaProcess.HasExited) {
            Stop-Process -Id $javaProcess.Id -Force
            $javaProcess.WaitForExit(5000) | Out-Null
        }
        $env:ORDERLY_AI_IPC_TOKEN = $previousToken
        $env:ORDERLY_AI_PORT = $previousAiPort
        Pop-Location
    }
}

$runtime = $null
if (-not [string]::IsNullOrWhiteSpace($RuntimeManifest)) {
    $runtime = Read-RuntimeManifest $RuntimeManifest
}

# 源码模式先按内容指纹判断是否需要构建；运行包模式只校验清单中的 JAR。
if (-not $SkipBuild -and $null -eq $runtime) {
    $buildArguments = @{
        SkipTests = $true
        MaxParallel = $MaxParallel
    }
    if ($Offline -or $MavenOffline) {
        $buildArguments.Offline = $true
    }
    if ($MavenOffline -and [string]::IsNullOrWhiteSpace($MavenRepository)) {
        $MavenRepository = Join-Path $repoRoot "maven-repository"
    }
    if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
        $buildArguments.MavenRepository = $MavenRepository
    }
    if ($ForceBuild) {
        Write-Host "强制完整构建：-ForceBuild"
        & (Join-Path $PSScriptRoot "build.ps1") @buildArguments
    }
    else {
        Invoke-RequiredBuild -BaseBuildArguments $buildArguments
    }
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

# 运行包按清单使用固定 JAR；开发模式优先使用构建清单，-SkipBuild 时兼容旧产物。
if ($null -ne $runtime) {
    $jar = Get-Item -LiteralPath $runtime.HostJar -Force
    $modulesRoot = $runtime.ModulesRoot
    $themesRoot = $runtime.ThemesRoot
}
else {
    $buildManifest = Read-OrderlyBuildManifest $buildManifestPath
    if ($null -ne $buildManifest -and
            (Test-OrderlyManifestArtifact `
                -RepoRoot $repoRoot `
                -Artifact $buildManifest.host.artifact)) {
        $jar = Get-Item `
            -LiteralPath (Join-Path $repoRoot ([string]$buildManifest.host.artifact.path)) `
            -Force
    }
    else {
        $jar = Get-ChildItem -LiteralPath (Join-Path $hostRoot "target") -Filter "*.jar" -File |
            Where-Object {
                $_.Name -notlike "*.original" -and
                $_.Name -notlike "*-launcher.jar"
            } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }
}
if (-not $jar) {
    throw "未找到可运行的 host JAR。"
}

if ($BackgroundModules -or $null -ne $runtime) {
    Invoke-BackgroundStart `
        -JavaExecutable $javaExe `
        -HostJar $jar.FullName `
        -ModulesDirectory $modulesRoot `
        -ThemesDirectory $themesRoot `
        -HttpPort $Port `
        -AiExecutable $aiExecutablePath `
        -OfflineMode:$Offline `
        -SkipMavenTests:(-not $RuntimeManifest) `
        -UseMavenOffline:($Offline -or $MavenOffline) `
        -Repository $MavenRepository `
        -SkipBackgroundModules:($null -ne $runtime)
    return
}

$python = ""
if ([string]::IsNullOrWhiteSpace($AiExecutable) -and $Offline) {
    # 严格 Maven 离线启动不得触发 ensure-env 的 pip 网络安装；复用已准备好的 AI 环境。
    $python = Join-Path $repoRoot "ai\.venv\Scripts\python.exe"
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw "严格离线启动需要现有 ai/.venv；未运行 ensure-env.ps1，也不会联网安装 Python 依赖。"
    }
}
elseif ([string]::IsNullOrWhiteSpace($AiExecutable)) {
    if (-not (Test-Path -LiteralPath $aiEnvScript -PathType Leaf)) {
        throw "未找到 Python AI 环境脚本：$aiEnvScript"
    }
    $python = & $aiEnvScript
}
if ([string]::IsNullOrWhiteSpace($AiExecutable) -and
        (-not $python -or -not (Test-Path -LiteralPath $python -PathType Leaf))) {
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
    if (-not [string]::IsNullOrWhiteSpace($AiExecutable)) {
        $aiProcess = Start-Process `
            -FilePath $AiExecutable `
            -ArgumentList (Join-ProcessArguments @("--port", [string]$aiPort)) `
            -WorkingDirectory (Split-Path -Parent $AiExecutable) `
            -WindowStyle Hidden `
            -PassThru
    }
    else {
        $aiProcess = Start-Process -FilePath $python `
            -ArgumentList @("-m", "orderly_ai", "--port", [string]$aiPort) `
            -WorkingDirectory $repoRoot `
            -WindowStyle Hidden `
            -PassThru
    }
    Wait-AiService -BaseUrl $aiBaseUrl -Token $aiToken -Process $aiProcess

    # Java 是前台长驻进程。暂时使用 Continue 以读取真实退出码，随后由本脚本统一转成明确失败。
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $hostArguments = @(
            "-Dorderly.modules-dir=$modulesRoot",
            "-Dorderly.themes-dir=$themesRoot",
            "-Dorderly.ai-service.url=$aiBaseUrl",
            "-jar",
            $jar.FullName,
            "--server.port=$Port"
        )
        if (-not [string]::IsNullOrWhiteSpace($env:ORDERLY_DATA_DIR)) {
            $hostArguments = @("-Dorderly.data-dir=$env:ORDERLY_DATA_DIR") + $hostArguments
        }
        & $javaExe @hostArguments
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
