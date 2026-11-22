#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   从已通过内容指纹验证的构建产物生成预构建运行包，不包含 Maven 仓库和 Java 源码。
#
# 输入：
#   -OutputDirectory 指定 target 下的输出目录；-AllowDirtySnapshot 仅供本地验收脏工作树。
#
# 副作用：
#   写入 target/orderly-runtime 暂存、ZIP 和 SHA-256 文件；不修改运行数据或 Maven 仓库。
#
# 安全边界：
#   正式包要求干净 Git 工作树；只复制清单登记的 JAR、受控主题和运行时脚本，拒绝重解析点。

param(
    [string]$OutputDirectory,
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

$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Join-Path $targetRoot "orderly-runtime"
}
else {
    $OutputDirectory
}
$outputRoot = Assert-OrderlyPathWithin $outputRoot $targetRoot "运行包输出目录"
$stagingRoot = Join-Path $outputRoot "staging"
$buildManifestPath = Join-Path $targetRoot "build-manifest.json"

function Assert-ContainerWithoutReparse {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Description 不存在：$Path"
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description 不能是重解析点：$Path"
    }
}

function Copy-RuntimeFile {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "运行包源文件不存在：$Source"
    }
    $item = Get-Item -LiteralPath $Source -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "运行包源文件不能是重解析点：$Source"
    }
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $Destination)) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

function Copy-RuntimeTree {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    Assert-ContainerWithoutReparse $Source "运行包源目录"
    $stack = [System.Collections.Generic.Stack[string]]::new()
    $stack.Push([System.IO.Path]::GetFullPath($Source))
    while ($stack.Count -gt 0) {
        $directory = $stack.Pop()
        foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
            if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "运行包复制拒绝重解析点：$($entry.FullName)"
            }
            if (@("target", ".venv", "__pycache__", ".pytest_cache") -contains $entry.Name) {
                continue
            }
            $relative = Get-OrderlyRelativePath $entry.FullName $Source
            $destinationPath = Join-Path $Destination $relative
            if ($entry.PSIsContainer) {
                [System.IO.Directory]::CreateDirectory($destinationPath) | Out-Null
                $stack.Push($entry.FullName)
            }
            else {
                Copy-RuntimeFile $entry.FullName $destinationPath
            }
        }
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
    $lines = @()
    foreach ($file in @(Get-ChildItem -LiteralPath $Root -Recurse -File -Force |
        Where-Object { $_.FullName -ne $path } |
        Sort-Object FullName)) {
        $relative = Get-OrderlyRelativePath $file.FullName $Root
        $lines += "$(Get-OrderlySha256 $file.FullName)  $relative"
    }
    [System.IO.File]::WriteAllText(
        $path,
        (($lines -join "`n") + "`n"),
        [System.Text.UTF8Encoding]::new($false)
    )
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
        throw "读取 Git 源码清单失败：$($paths -join ' ')"
    }
    $records = [System.Collections.Generic.List[string]]::new()
    foreach ($relativePath in @($paths | Sort-Object)) {
        $fullPath = Join-Path $repoRoot ([string]$relativePath)
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Git 跟踪文件缺失：$relativePath"
        }
        $item = Get-Item -LiteralPath $fullPath -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Git 跟踪文件不能是重解析点：$relativePath"
        }
        $records.Add("$($relativePath.Replace('\', '/'))`t$($item.Length)`t$(Get-OrderlySha256 $fullPath)")
    }
    $records.Sort([System.StringComparer]::Ordinal)
    return Get-OrderlyStringSha256 (($records -join "`n") + "`n")
}

$gitState = Get-OrderlyGitState $repoRoot
if ($gitState.Dirty -and -not $AllowDirtySnapshot) {
    throw "正式运行包要求干净 Git 工作树；本地验收可使用 -AllowDirtySnapshot。"
}
$buildManifest = Read-OrderlyBuildManifest $buildManifestPath
if ($null -eq $buildManifest) {
    throw "缺少有效构建清单；请先执行 scripts/build.ps1。"
}
if ([string]$buildManifest.gitCommit -ne $gitState.Commit) {
    throw "构建清单提交与当前 HEAD 不一致；请重新构建。"
}
$plan = Resolve-OrderlyBuildPlan -RepoRoot $repoRoot -ManifestPath $buildManifestPath
if ($plan.BuildRequired) {
    throw "构建产物与当前源码不一致，拒绝生成运行包：$($plan.Reasons -join '；')"
}

$sourceDirectories = @(Get-OrderlyModuleSourceDirectories $repoRoot)
$expectedModuleNames = @($sourceDirectories | ForEach-Object { $_.Name } | Sort-Object)
$manifestModuleNames = @($buildManifest.modules | ForEach-Object { [string]$_.sourceName } | Sort-Object)
if (($expectedModuleNames -join "|") -ne ($manifestModuleNames -join "|")) {
    throw "构建清单模块集合与当前源码不一致。"
}

if (Test-Path -LiteralPath $outputRoot) {
    $resolvedOutput = Assert-OrderlyPathWithin $outputRoot $targetRoot "运行包输出目录"
    if ($resolvedOutput.Equals($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清空 target 根目录。"
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($stagingRoot) | Out-Null

$projectVersion = [string]$buildManifest.projectVersion
$packageName = "Orderly-$projectVersion-runtime"
$releaseRoot = Join-Path $stagingRoot $packageName
[System.IO.Directory]::CreateDirectory($releaseRoot) | Out-Null

$hostJarSource = Join-Path $repoRoot ([string]$buildManifest.host.artifact.path)
$hostJarRelative = "app/orderly-host-$projectVersion.jar"
Copy-RuntimeFile $hostJarSource (Join-Path $releaseRoot $hostJarRelative)

$runtimeModules = @()
foreach ($moduleRecord in @($buildManifest.modules | Sort-Object { $_.sourceName })) {
    $sourceName = [string]$moduleRecord.sourceName
    $sourceJar = Join-Path $repoRoot ([string]$moduleRecord.artifact.path)
    $moduleRelative = "modules/$sourceName.jar"
    Copy-RuntimeFile $sourceJar (Join-Path $releaseRoot $moduleRelative)
    $runtimeModules += [ordered]@{
        moduleId = [string]$moduleRecord.moduleId
        artifact = [ordered]@{
            path = $moduleRelative
            size = [long](Get-Item -LiteralPath (Join-Path $releaseRoot $moduleRelative)).Length
            sha256 = Get-OrderlySha256 (Join-Path $releaseRoot $moduleRelative)
        }
    }
}

$runtimeThemesRoot = Join-Path $releaseRoot "themes"
[System.IO.Directory]::CreateDirectory($runtimeThemesRoot) | Out-Null
Copy-RuntimeTree (Join-Path $repoRoot "themes") $runtimeThemesRoot

foreach ($relativeFile in @(
    "启动归序.cmd",
    "scripts/start.ps1",
    "scripts/build-plan.ps1",
    "ai/pyproject.toml",
    "ai/requirements.txt",
    "ai/scripts/ensure-env.ps1"
)) {
    Copy-RuntimeFile `
        (Join-Path $repoRoot $relativeFile) `
        (Join-Path $releaseRoot $relativeFile)
}
Copy-RuntimeTree `
    (Join-Path $repoRoot "ai\src\orderly_ai") `
    (Join-Path $releaseRoot "ai\src\orderly_ai")

$runtimeManifest = [ordered]@{
    schemaVersion = 1
    packageType = "orderly-runtime"
    projectVersion = $projectVersion
    gitCommit = $gitState.Commit
    sourceTreeSha256 = Get-GitSourceTreeSha256
    jdkMajor = 21
    themesRoot = "themes"
    hostJar = [ordered]@{
        path = $hostJarRelative
        size = [long](Get-Item -LiteralPath (Join-Path $releaseRoot $hostJarRelative)).Length
        sha256 = Get-OrderlySha256 (Join-Path $releaseRoot $hostJarRelative)
    }
    modules = @($runtimeModules | Sort-Object { $_.moduleId })
}
Write-JsonFile (Join-Path $releaseRoot "runtime-manifest.json") $runtimeManifest

$runtimeReadme = @"
# 归序运行时包

这是预构建运行包。普通启动不会调用 Maven。

环境要求：

- Windows PowerShell 5.1 或 PowerShell 7+
- JDK 21
- Python 3.14

双击根目录的 ``启动归序.cmd``。首次运行仍可能需要为 Python AI 服务准备本地虚拟环境。
该包只包含内置模块；通过源码安装新模块仍需要 Maven。
"@
[System.IO.File]::WriteAllText(
    (Join-Path $releaseRoot "README-runtime.md"),
    ($runtimeReadme.Trim() + "`n"),
    [System.Text.UTF8Encoding]::new($false)
)

Write-ChecksumFile $releaseRoot
$zipPath = Join-Path $outputRoot "$packageName.zip"
if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $releaseRoot,
    $zipPath,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)
$zipHash = Get-OrderlySha256 $zipPath
[System.IO.File]::WriteAllText(
    "$zipPath.sha256",
    "$zipHash  $([System.IO.Path]::GetFileName($zipPath))`n",
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "预构建运行包已生成：$zipPath"
Write-Host "模块数量：$($runtimeModules.Count)；ZIP SHA-256：$zipHash"
