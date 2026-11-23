#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   构建 Windows 完整安装版应用目录、便携 ZIP 和可选 setup.exe。
#
# 输入：
#   -OutputDirectory 指定构建物目录，默认 target/full-release。
#   -MavenRepository 指定内置共享 Maven 仓库，默认 .m2-repository。
#   -JdkHome 指定要随包分发的完整 JDK 21。
#   -MavenHome 指定要随包分发的 Maven 3.9+ 目录。
#   -SkipTests 只允许明确的快速打包；默认执行 Java/Maven 测试。
#   -SkipInstaller 只生成应用目录和 ZIP，不调用需要 WiX 的 jpackage EXE 安装器。
#   -WixHome 指定 WiX Toolset 3.x 根目录或其 bin 目录；未指定时自动发现。
#   -AllowDirtySnapshot 仅供本地验收；正式包必须来自干净 Git 提交。
#
# 副作用：
#   写入 target/full-release 或用户显式指定的构建物目录；不写用户运行数据。
#
# 安全边界：
#   只复制 Git 跟踪源码和明确登记的构建产物，拒绝重解析点；不复制凭据、.venv 或用户数据。

param(
    [string]$OutputDirectory,
    [string]$MavenRepository,
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MavenHome,
    [string]$WixHome,
    [switch]$SkipTests,
    [switch]$SkipInstaller,
    [switch]$AllowDirtySnapshot
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$buildPlanScript = Join-Path $PSScriptRoot "build-plan.ps1"
if (-not (Test-Path -LiteralPath $buildPlanScript -PathType Leaf)) {
    throw "缺少构建检测脚本：$buildPlanScript"
}
. $buildPlanScript

function Assert-NotRoot {
    param([Parameter(Mandatory = $true)][string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $root = [System.IO.Path]::GetPathRoot($fullPath)
    if ($fullPath.TrimEnd('\') -eq $root.TrimEnd('\')) {
        throw "拒绝把构建物写入磁盘根目录：$fullPath"
    }
}

function Copy-Tree {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "复制源目录不存在：$Source"
    }
    [System.IO.Directory]::CreateDirectory($Destination) | Out-Null
    $stack = [System.Collections.Generic.Stack[string]]::new()
    $stack.Push([System.IO.Path]::GetFullPath($Source))
    while ($stack.Count -gt 0) {
        $directory = $stack.Pop()
        foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
            if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "拒绝复制重解析点：$($entry.FullName)"
            }
            $relative = Get-OrderlyRelativePath $entry.FullName $Source
            $destinationPath = Join-Path $Destination $relative
            if ($entry.PSIsContainer) {
                [System.IO.Directory]::CreateDirectory($destinationPath) | Out-Null
                $stack.Push($entry.FullName)
            }
            else {
                [System.IO.Directory]::CreateDirectory((Split-Path -Parent $destinationPath)) | Out-Null
                Copy-Item -LiteralPath $entry.FullName -Destination $destinationPath -Force
            }
        }
    }
}

function Copy-TrackedSource {
    param([Parameter(Mandatory = $true)][string]$Destination)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $paths = @(& git -C $repoRoot -c core.quotepath=false ls-files 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "读取 Git 跟踪源码清单失败：$($paths -join ' ')"
    }
    foreach ($relativePath in @($paths | Sort-Object)) {
        $relative = ([string]$relativePath).Replace("\", "/")
        if ([string]::IsNullOrWhiteSpace($relative)) {
            continue
        }
        $source = Join-Path $repoRoot $relative
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Git 跟踪源码缺失：$relative"
        }
        $item = Get-Item -LiteralPath $source -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "拒绝复制重解析点源码：$relative"
        }
        $destinationPath = [System.IO.Path]::GetFullPath((Join-Path $Destination $relative))
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $destinationPath)) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destinationPath -Force
    }
}

function Get-GitSourceTreeSha256 {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $paths = @(& git -C $repoRoot -c core.quotepath=false ls-files 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "读取 Git 源码指纹清单失败：$($paths -join ' ')"
    }
    $records = [System.Collections.Generic.List[string]]::new()
    foreach ($relativePath in @($paths | Sort-Object)) {
        $relative = ([string]$relativePath).Replace("\", "/")
        $source = Join-Path $repoRoot $relative
        $item = Get-Item -LiteralPath $source -Force
        $records.Add("$relative`t$($item.Length)`t$(Get-OrderlySha256 $source)")
    }
    $records.Sort([System.StringComparer]::Ordinal)
    $payload = ($records -join "`n")
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($payload + "`n")
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    [System.IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine),
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Write-ChecksumFile {
    param([Parameter(Mandatory = $true)][string]$Root)
    $path = Join-Path $Root "files.sha256"
    $jpackageMetadataPath = Join-Path $Root "app\.jpackage.xml"
    $lines = @(
        Get-ChildItem -LiteralPath $Root -Recurse -File -Force |
            Where-Object { $_.FullName -ne $path -and $_.FullName -ne $jpackageMetadataPath } |
            Sort-Object FullName |
            ForEach-Object {
                "$(Get-OrderlySha256 $_.FullName)  $(Get-OrderlyRelativePath $_.FullName $Root)"
            }
    )
    [System.IO.File]::WriteAllText(
        $path,
        (($lines -join "`n") + "`n"),
        [System.Text.UTF8Encoding]::new($false)
    )
}

$outputRoot = [System.IO.Path]::GetFullPath(
    $(if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        Join-Path $targetRoot "full-release"
    }
    else {
        $OutputDirectory
    })
)
Assert-NotRoot $outputRoot
$stagingRoot = Join-Path $outputRoot "staging"
$aiBinaryRoot = Join-Path $targetRoot "ai-binary"
$mavenRepositoryPath = if ([string]::IsNullOrWhiteSpace($MavenRepository)) {
    Join-Path $repoRoot ".m2-repository"
}
else {
    [System.IO.Path]::GetFullPath($MavenRepository)
}

$gitState = Get-OrderlyGitState $repoRoot
if ($gitState.Dirty -and -not $AllowDirtySnapshot) {
    throw "正式完整版要求干净 Git 工作树；本地验收可使用 -AllowDirtySnapshot。"
}
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw "必须通过 -JdkHome 或 JAVA_HOME 提供 JDK 21。"
}
$jdkPath = [System.IO.Path]::GetFullPath($JdkHome)
$jdkExe = Join-Path $jdkPath "bin\javac.exe"
if (-not (Test-Path -LiteralPath $jdkExe -PathType Leaf)) {
    throw "JdkHome 不是完整 JDK 21（缺少 javac.exe）：$jdkPath"
}
if ([string]::IsNullOrWhiteSpace($MavenHome)) {
    $mavenCommand = @(Get-Command mvn.cmd -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1)
    if ($mavenCommand.Count -ne 1) {
        throw "未找到 mvn.cmd，请通过 -MavenHome 指定 Maven 3.9+。"
    }
    $MavenHome = Split-Path -Parent (Split-Path -Parent $mavenCommand[0].Source)
}
$mavenPath = [System.IO.Path]::GetFullPath($MavenHome)
$mavenExecutable = Join-Path $mavenPath "bin\mvn.cmd"
if (-not (Test-Path -LiteralPath $mavenExecutable -PathType Leaf)) {
    throw "MavenHome 缺少 bin\\mvn.cmd：$mavenPath"
}
if (-not (Test-Path -LiteralPath $mavenRepositoryPath -PathType Container)) {
    throw "内置 Maven 仓库不存在：$mavenRepositoryPath"
}
$jpackage = Join-Path $jdkPath "bin\jpackage.exe"
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw "JDK 21 缺少 jpackage.exe：$jpackage"
}

function Resolve-WixBin {
    param([string]$ConfiguredRoot)

    function Test-WixBin {
        param([string]$Path)
        if ([string]::IsNullOrWhiteSpace($Path)) {
            return $false
        }
        return (Test-Path -LiteralPath (Join-Path $Path "candle.exe") -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $Path "light.exe") -PathType Leaf)
    }

    if (-not [string]::IsNullOrWhiteSpace($ConfiguredRoot)) {
        $fullRoot = [System.IO.Path]::GetFullPath($ConfiguredRoot)
        foreach ($candidate in @($fullRoot, (Join-Path $fullRoot "bin"))) {
            if (Test-WixBin $candidate) {
                return $candidate
            }
        }
        throw "WixHome 未包含 candle.exe 与 light.exe：$fullRoot"
    }

    $command = @(Get-Command candle.exe -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1)
    if ($command.Count -eq 1) {
        $commandBin = Split-Path -Parent $command[0].Source
        if (Test-WixBin $commandBin) {
            return $commandBin
        }
    }

    $candidateRoots = @()
    $programFilesX86 = [Environment]::GetFolderPath(
        [Environment+SpecialFolder]::ProgramFilesX86
    )
    $programFiles = [Environment]::GetFolderPath(
        [Environment+SpecialFolder]::ProgramFiles
    )
    foreach ($basePath in @($programFilesX86, $programFiles)) {
        if ([string]::IsNullOrWhiteSpace($basePath)) {
            continue
        }
        $candidateRoots += @(
            (Join-Path $basePath "WiX Toolset v3.14\bin"),
            (Join-Path $basePath "WiX Toolset v3.11\bin"),
            (Join-Path $basePath "WiX Toolset v3.10\bin")
        )
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ChocolateyInstall)) {
        $candidateRoots += Join-Path $env:ChocolateyInstall "bin"
    }
    foreach ($candidate in $candidateRoots) {
        if (Test-WixBin $candidate) {
            return $candidate
        }
    }
    throw "未找到 WiX Toolset 3.x。请安装 WiX 3.x，或通过 -WixHome 指定包含 candle.exe 与 light.exe 的 bin 目录。"
}

$wixBin = if ($SkipInstaller) {
    $null
}
else {
    Resolve-WixBin $WixHome
}

foreach ($path in @($stagingRoot)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}
[System.IO.Directory]::CreateDirectory($stagingRoot) | Out-Null
$jpackageRoot = Join-Path $stagingRoot "jpackage"
[System.IO.Directory]::CreateDirectory($jpackageRoot) | Out-Null

$buildArguments = @{
    MaxParallel = 2
    MavenRepository = $mavenRepositoryPath
}
if ($SkipTests) {
    $buildArguments.SkipTests = $true
}
& (Join-Path $PSScriptRoot "build.ps1") @buildArguments
if ($LASTEXITCODE -ne 0) {
    throw "完整版 Java/模块构建失败，退出码：$LASTEXITCODE"
}

& (Join-Path $PSScriptRoot "build-ai-executable.ps1") -OutputDirectory $aiBinaryRoot
if ($LASTEXITCODE -ne 0) {
    throw "AI 可执行文件构建失败，退出码：$LASTEXITCODE"
}
$aiDist = Join-Path $aiBinaryRoot "dist\orderly-ai"
$aiExecutable = Join-Path $aiDist "orderly-ai.exe"
if (-not (Test-Path -LiteralPath $aiExecutable -PathType Leaf)) {
    throw "AI 可执行文件不存在：$aiExecutable"
}

$buildManifestPath = Join-Path $targetRoot "build-manifest.json"
$buildManifest = Read-OrderlyBuildManifest $buildManifestPath
if ($null -eq $buildManifest) {
    throw "缺少有效构建清单：$buildManifestPath"
}
$projectVersion = [string]$buildManifest.projectVersion
$hostJar = Join-Path $repoRoot ([string]$buildManifest.host.artifact.path)
if (-not (Test-Path -LiteralPath $hostJar -PathType Leaf)) {
    throw "宿主 JAR 不存在：$hostJar"
}
$launcherJar = Join-Path (Split-Path -Parent $hostJar) (
    "orderly-host-$projectVersion-launcher.jar"
)
if (-not (Test-Path -LiteralPath $launcherJar -PathType Leaf)) {
    throw "完整版桌面启动器 JAR 不存在：$launcherJar"
}

$jpackageInput = Join-Path $jpackageRoot "input"
[System.IO.Directory]::CreateDirectory($jpackageInput) | Out-Null
Copy-Item `
    -LiteralPath $launcherJar `
    -Destination (Join-Path $jpackageInput (Split-Path -Leaf $launcherJar)) `
    -Force
& $jpackage `
    --type app-image `
    --name Orderly `
    --app-version $projectVersion `
    --description "归序 Orderly 本地可控工作流" `
    --vendor "Orderly" `
    --input $jpackageInput `
    --main-jar (Split-Path -Leaf $launcherJar) `
    --main-class "com.orderly.host.desktop.PackagedLauncher" `
    --dest $jpackageRoot
if ($LASTEXITCODE -ne 0) {
    throw "jpackage 应用镜像生成失败，退出码：$LASTEXITCODE"
}
$appRoot = Join-Path $jpackageRoot "Orderly"
if (-not (Test-Path -LiteralPath (Join-Path $appRoot "Orderly.exe") -PathType Leaf)) {
    throw "jpackage 未生成预期 Orderly.exe：$appRoot"
}

Copy-TrackedSource $appRoot
$hostTarget = Join-Path $appRoot "app\host\target"
[System.IO.Directory]::CreateDirectory($hostTarget) | Out-Null
Copy-Item -LiteralPath $hostJar -Destination (Join-Path $hostTarget (Split-Path -Leaf $hostJar)) -Force
$appTarget = Join-Path $appRoot "target"
[System.IO.Directory]::CreateDirectory($appTarget) | Out-Null
Copy-Item -LiteralPath $buildManifestPath -Destination (Join-Path $appTarget "build-manifest.json") -Force
Copy-Tree (Join-Path $targetRoot "orderly-modules") (Join-Path $appRoot "target\orderly-modules")
Copy-Tree $aiDist (Join-Path $appRoot "ai")
Copy-Tree $jdkPath (Join-Path $appRoot "toolchains\jdk")
Copy-Tree $mavenPath (Join-Path $appRoot "toolchains\maven")
Copy-Tree $mavenRepositoryPath (Join-Path $appRoot "maven-repository")

$runtimeManifest = [ordered]@{
    schemaVersion = 1
    packageType = "orderly-full-windows"
    projectVersion = $projectVersion
    gitCommit = $gitState.Commit
    gitDirty = [bool]$gitState.Dirty
    sourceTreeSha256 = Get-GitSourceTreeSha256
    jdkMajor = 21
    launchMode = "packaged"
    dataRootPolicy = "LOCALAPPDATA_OR_ORDERLY_DATA_DIR"
    hostJar = [ordered]@{
        path = "app/host/target/" + (Split-Path -Leaf $hostJar)
        size = [long](Get-Item -LiteralPath $hostJar).Length
        sha256 = Get-OrderlySha256 $hostJar
    }
    desktopLauncher = [ordered]@{
        path = "app/" + (Split-Path -Leaf $launcherJar)
        size = [long](Get-Item -LiteralPath (Join-Path $appRoot "app\$(Split-Path -Leaf $launcherJar)")).Length
        sha256 = Get-OrderlySha256 (Join-Path $appRoot "app\$(Split-Path -Leaf $launcherJar)")
    }
    aiExecutable = [ordered]@{
        path = "ai/orderly-ai.exe"
        size = [long](Get-Item -LiteralPath (Join-Path $appRoot "ai\orderly-ai.exe")).Length
        sha256 = Get-OrderlySha256 (Join-Path $appRoot "ai\orderly-ai.exe")
    }
    modules = @($buildManifest.modules | ForEach-Object {
        $relative = "target/orderly-modules/$($_.sourceName).jar"
        $path = Join-Path $appRoot $relative.Replace("/", "\")
        [ordered]@{
            moduleId = [string]$_.moduleId
            path = $relative
            size = [long](Get-Item -LiteralPath $path).Length
            sha256 = Get-OrderlySha256 $path
        }
    })
}
Write-JsonFile (Join-Path $appRoot "full-runtime-manifest.json") $runtimeManifest

$readme = @"
# 归序 Orderly 完整版

双击 Orderly.exe 启动。安装目录包含 Java 运行时、完整 JDK、Maven、本地仓库和 AI 可执行文件。
用户不需要安装 JDK、Python 或 Maven。

工作区、执行记录和回收站写入：
%LOCALAPPDATA%\Orderly\data

模块源码和运行模块保留在安装目录。安装位置必须位于当前用户可写目录。
AI API Key 只保存在 AI 服务进程内存中，不会写入此目录或日志。
"@
[System.IO.File]::WriteAllText(
    (Join-Path $appRoot "README-full.txt"),
    $readme.Trim() + "`n",
    [System.Text.UTF8Encoding]::new($false)
)

Write-ChecksumFile $appRoot
$zipPath = Join-Path $outputRoot "Orderly-$projectVersion-full-app.zip"
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $appRoot,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)
[System.IO.File]::WriteAllText(
    "$zipPath.sha256",
    "$(Get-OrderlySha256 $zipPath)  $([System.IO.Path]::GetFileName($zipPath))`n",
    [System.Text.UTF8Encoding]::new($false)
)

$installerPath = $null
if (-not $SkipInstaller) {
    $installerRoot = Join-Path $outputRoot "installer"
    if (Test-Path -LiteralPath $installerRoot) {
        Remove-Item -LiteralPath $installerRoot -Recurse -Force
    }
    [System.IO.Directory]::CreateDirectory($installerRoot) | Out-Null
    $previousPath = $env:PATH
    try {
        $env:PATH = "$wixBin;$previousPath"
        & $jpackage `
            --type exe `
            --name Orderly `
            --app-image $appRoot `
            --dest $installerRoot `
            --app-version $projectVersion `
            --win-per-user-install `
            --win-menu `
            --win-shortcut `
            --vendor "Orderly" `
            --description "归序 Orderly 本地可控工作流"
    }
    finally {
        $env:PATH = $previousPath
    }
    if ($LASTEXITCODE -ne 0) {
        throw "完整版安装器生成失败，jpackage 退出码：$LASTEXITCODE"
    }
    $installerPath = Get-ChildItem -LiteralPath $installerRoot -Filter "*.exe" -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $installerPath) {
        throw "jpackage 未生成安装器 EXE。"
    }
    [System.IO.File]::WriteAllText(
        "$($installerPath.FullName).sha256",
        "$(Get-OrderlySha256 $installerPath.FullName)  $($installerPath.Name)`n",
        [System.Text.UTF8Encoding]::new($false)
    )
}

Write-Host "完整版应用目录：$appRoot"
Write-Host "完整版 ZIP：$zipPath"
if ($installerPath) {
    Write-Host "完整版安装器：$($installerPath.FullName)"
}
