#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   从当前仓库生成可上传到 module-distribution 分支的 catalog.json 和业务模块源码 ZIP。
#
# 输入：
#   -OutputDirectory 默认 target/module-distribution，必须位于仓库 target 内。
#   -Version 模块分发版本；缺省读取根 POM 的 project.version。
#   -AllowDirtySnapshot 仅供本地预检，正式发布默认要求干净 Git 工作树。
#
# 副作用：
#   重建输出目录并写入目录 JSON、模块 ZIP；不修改 Module 源码、不提交、不推送。
#
# 安全边界：
#   只打包 Module/module_* 普通业务目录，排除全部 module_system_*、target、Git 元数据和
#   运行时数据；ZIP 使用固定条目时间，便于相同源码生成稳定摘要。

param(
    [string]$OutputDirectory,
    [string]$Version,
    [switch]$AllowDirtySnapshot
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$moduleRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "Module"))
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$outputRoot = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    [System.IO.Path]::GetFullPath((Join-Path $targetRoot "module-distribution"))
}
else {
    [System.IO.Path]::GetFullPath($OutputDirectory)
}
$sourcePattern = '^module_[a-z0-9_]+$'
$systemSourcePrefix = "module_system_"

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

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "缺少模块清单：$Path"
    }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $Path | ConvertFrom-Json
}

function Get-ProjectVersion {
    if (-not [string]::IsNullOrWhiteSpace($Version)) {
        return $Version.Trim()
    }
    [xml]$pom = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repoRoot "pom.xml")
    $value = [string]$pom.project.version
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "无法从根 pom.xml 读取 project.version"
    }
    return $value.Trim()
}

function New-StableModuleArchive {
    param(
        [Parameter(Mandatory = $true)][string]$ModuleDirectory,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $moduleName = Split-Path -Leaf $ModuleDirectory
    $files = @(Get-ChildItem -LiteralPath $ModuleDirectory -Recurse -Force -File |
        Where-Object {
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0 -and
            $_.FullName -notmatch '[\\/](target|\.orderly-data|\.git)[\\/]'
        } |
        Sort-Object FullName)
    if (-not $files.Count) {
        throw "模块目录没有可打包文件：$moduleName"
    }

    $archiveStream = [System.IO.File]::Open(
        $Destination,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $archiveStream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            $timestamp = [System.DateTimeOffset]::new(
                2000, 1, 1, 0, 0, 0, [System.TimeSpan]::Zero
            )
            foreach ($file in $files) {
                $relative = $file.FullName.Substring($ModuleDirectory.Length)
                $relative = $relative.TrimStart([char[]]@('\', '/'))
                $relative = $relative.Replace('\', '/')
                $entryName = "$moduleName/$relative"
                $entry = $archive.CreateEntry(
                    $entryName,
                    [System.IO.Compression.CompressionLevel]::Optimal
                )
                $entry.LastWriteTime = $timestamp
                $input = [System.IO.File]::OpenRead($file.FullName)
                try {
                    $output = $entry.Open()
                    try {
                        $input.CopyTo($output)
                    }
                    finally {
                        $output.Dispose()
                    }
                }
                finally {
                    $input.Dispose()
                }
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    finally {
        $archiveStream.Dispose()
    }
}

$outputRoot = Assert-PathWithin $outputRoot $targetRoot "模块分发输出目录"
if (-not $AllowDirtySnapshot) {
    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw "无法读取 Git 工作树状态。"
    }
    if ($dirty.Count -gt 0) {
        throw "正式模块分发必须从干净 Git 工作树生成；本地预检可使用 -AllowDirtySnapshot。"
    }
}
$sourceCommit = (& git rev-parse --short=12 HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($sourceCommit)) {
    throw "无法读取当前 Git 提交。"
}
$projectVersion = Get-ProjectVersion

if (Test-Path -LiteralPath $outputRoot) {
    $outputRoot = Assert-PathWithin $outputRoot $targetRoot "模块分发输出目录"
    Remove-Item -LiteralPath $outputRoot -Recurse -Force
}
$packagesRoot = Join-Path $outputRoot "packages"
[System.IO.Directory]::CreateDirectory($packagesRoot) | Out-Null

$moduleDirectories = @(Get-ChildItem -LiteralPath $moduleRoot -Directory -Filter "module_*" |
    Where-Object {
        $_.Name -match $sourcePattern -and
        -not $_.Name.StartsWith($systemSourcePrefix, [System.StringComparison]::Ordinal) -and
        ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0
    } |
    Sort-Object Name)
if (-not $moduleDirectories.Count) {
    throw "没有发现可发布的业务模块。"
}

$catalogModules = [System.Collections.Generic.List[object]]::new()
foreach ($directory in $moduleDirectories) {
    $contractPath = Join-Path $directory.FullName (
        "src\main\resources\orderly-modules\" + $directory.Name + ".json"
    )
    $uiPath = Join-Path $directory.FullName "src\main\resources\orderly-ui\module.json"
    $contract = Read-JsonFile $contractPath
    $ui = Read-JsonFile $uiPath
    if ([string]$contract.moduleId -ne $directory.Name) {
        throw "模块目录与契约 moduleId 不一致：$($directory.Name)"
    }
    if ($contract.PSObject.Properties.Name -contains "system") {
        throw "业务模块分发包不得包含 system 元数据：$($directory.Name)"
    }
    $title = [string]$contract.title
    $description = [string]$contract.description
    if ([string]::IsNullOrWhiteSpace($title) -or [string]::IsNullOrWhiteSpace($description)) {
        throw "模块契约缺少 title 或 description：$($directory.Name)"
    }
    if ([int]$ui.version -ne 1 -or [string]::IsNullOrWhiteSpace([string]$ui.view.id)) {
        throw "模块 UI 清单无效：$($directory.Name)"
    }

    $safeVersion = $projectVersion -replace '[^A-Za-z0-9._-]', '-'
    $fileName = "$($directory.Name)-$safeVersion-$sourceCommit.zip"
    $packagePath = "packages/$fileName"
    $destination = Join-Path $packagesRoot $fileName
    New-StableModuleArchive -ModuleDirectory $directory.FullName -Destination $destination
    $packageSize = (Get-Item -LiteralPath $destination).Length
    if ($packageSize -gt 20MB) {
        throw "模块包超过宿主允许的 20 MiB：$($directory.Name)，实际 $packageSize 字节"
    }
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $catalogModules.Add([ordered]@{
        moduleId = $directory.Name
        title = $title
        description = $description
        version = "$projectVersion+$sourceCommit"
        packagePath = $packagePath
        sha256 = $hash
        size = $packageSize
    })
}

$catalog = [ordered]@{
    schemaVersion = 1
    apiVersion = 1
    generatedAt = [DateTime]::UtcNow.ToString("o")
    sourceCommit = $sourceCommit
    modules = @($catalogModules)
}
$catalogPath = Join-Path $outputRoot "catalog.json"
$catalogJson = $catalog | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText(
    $catalogPath,
    $catalogJson + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "模块分发生成完成：$outputRoot"
Write-Host "业务模块数量：$($catalogModules.Count)"
Write-Host "目录：$catalogPath"
