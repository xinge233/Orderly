#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   核对完整 Windows 应用与离线源码包是否来自同一干净提交和源码树。
#
# 输入：
#   -AppDirectory 指向 package-full.ps1 生成的 Orderly 应用目录。
#   -SourcePackage 指向 package-offline.ps1 生成的离线源码 ZIP。
#
# 副作用：
#   只读应用内 full-runtime-manifest.json 与源码 ZIP 根级 maven-manifest.json。
#
# 安全边界：
#   不解压、不启动应用；两份清单必须类型正确、非脏工作树，且版本、提交和源码树摘要一致。

param(
    [Parameter(Mandatory = $true)][string]$AppDirectory,
    [Parameter(Mandatory = $true)][string]$SourcePackage
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-SourceManifest {
    param([Parameter(Mandatory = $true)][string]$ZipPath)

    $resolved = (Resolve-Path -LiteralPath $ZipPath).Path
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolved)
    try {
        $entry = $archive.Entries |
            Where-Object { $_.FullName -eq "maven-manifest.json" } |
            Select-Object -First 1
        if (-not $entry) {
            throw "源码包缺少根级 maven-manifest.json。"
        }
        $reader = [System.IO.StreamReader]::new(
            $entry.Open(),
            [System.Text.UTF8Encoding]::new($false),
            $true
        )
        try {
            return $reader.ReadToEnd() | ConvertFrom-Json -ErrorAction Stop
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

$fullManifestPath = Join-Path $AppDirectory "full-runtime-manifest.json"
if (-not (Test-Path -LiteralPath $fullManifestPath -PathType Leaf)) {
    throw "完整版应用缺少 full-runtime-manifest.json：$fullManifestPath"
}
$full = Get-Content -Raw -Encoding UTF8 -LiteralPath $fullManifestPath |
    ConvertFrom-Json -ErrorAction Stop
$source = Read-SourceManifest $SourcePackage

if ([string]$full.packageType -ne "orderly-full-windows") {
    throw "完整版清单类型无效：$($full.packageType)"
}
if ([string]$source.packageType -ne "orderly-offline-source") {
    throw "源码包清单类型无效：$($source.packageType)"
}
if ([bool]$full.gitDirty -or [bool]$source.gitDirty) {
    throw "正式发布清单不得来自脏 Git 工作树。"
}
foreach ($property in @("projectVersion", "gitCommit", "sourceTreeSha256")) {
    $fullValue = [string]$full.$property
    $sourceValue = [string]$source.$property
    if ([string]::IsNullOrWhiteSpace($fullValue) -or $fullValue -ne $sourceValue) {
        throw "完整版与源码包清单不一致：$property；full=$fullValue source=$sourceValue"
    }
}
if ([int]$full.jdkMajor -ne [int]$source.jdkMajor) {
    throw "完整版与源码包清单的 JDK 版本不一致。"
}

Write-Host "完整版与源码包清单一致：$($full.projectVersion) @ $($full.gitCommit)"
