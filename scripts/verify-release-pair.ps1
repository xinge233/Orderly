#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   核对预构建运行包与离线源码包是否来自同一版本、提交和源码树。
#
# 输入：
#   -RuntimePackage 指向运行包 ZIP；-SourcePackage 指向离线源码包 ZIP。
#
# 副作用：
#   只读 ZIP 内的两个清单，不解压、不启动应用、不修改仓库。
#
# 安全边界：
#   只读取固定根级清单，拒绝路径、包类型或来源摘要不一致。

param(
    [Parameter(Mandatory = $true)][string]$RuntimePackage,
    [Parameter(Mandatory = $true)][string]$SourcePackage
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-RootManifest {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$EntryName
    )
    $resolved = (Resolve-Path -LiteralPath $ZipPath).Path
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolved)
    try {
        $entry = $archive.Entries |
            Where-Object { $_.FullName -eq $EntryName } |
            Select-Object -First 1
        if (-not $entry) {
            throw "发布包缺少根级清单：$EntryName"
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

$runtime = Read-RootManifest $RuntimePackage "runtime-manifest.json"
$source = Read-RootManifest $SourcePackage "maven-manifest.json"
if ([string]$runtime.packageType -ne "orderly-runtime") {
    throw "运行包清单类型无效：$($runtime.packageType)"
}
if ([string]$source.packageType -ne "orderly-offline-source") {
    throw "源码包清单类型无效：$($source.packageType)"
}
foreach ($property in @("projectVersion", "gitCommit", "sourceTreeSha256")) {
    $runtimeValue = [string]$runtime.$property
    $sourceValue = [string]$source.$property
    if ([string]::IsNullOrWhiteSpace($runtimeValue) -or $runtimeValue -ne $sourceValue) {
        throw "双轨发布清单不一致：$property；runtime=$runtimeValue source=$sourceValue"
    }
}
if ([int]$runtime.jdkMajor -ne [int]$source.jdkMajor) {
    throw "双轨发布清单 JDK 版本不一致。"
}

Write-Host "双轨发布清单一致：$($runtime.projectVersion) @ $($runtime.gitCommit)"
