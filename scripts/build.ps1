[CmdletBinding()]
param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$localRepository = Join-Path $repoRoot ".m2-repository"
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$stagingRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "orderly-modules"))
$indexPath = Join-Path $repoRoot "Module\modules.index"

function Invoke-Maven {
    param([string[]]$MavenArguments)
    & mvn @MavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 执行失败，退出码：$LASTEXITCODE；参数：$($MavenArguments -join ' ')"
    }
}

function Assert-Jdk21 {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "JAVA_HOME 未设置，无法保证使用 JDK 21 构建。"
    }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        throw "JAVA_HOME 下未找到 java.exe：$javaExe"
    }
    $versionLines = (& $javaExe -version 2>&1) | Out-String
    if ($LASTEXITCODE -ne 0 -or $versionLines -notmatch 'version "(?<major>\d+)') {
        throw "无法读取 JAVA_HOME 指定的 Java 版本：$javaExe"
    }
    if ([int]$Matches.major -ne 21) {
        throw "构建需要 JDK 21，当前 JAVA_HOME Java 主版本为 $($Matches.major)：$javaExe"
    }
    $mavenVersion = (& mvn -version 2>&1) | Out-String
    if ($LASTEXITCODE -ne 0 -or $mavenVersion -notmatch 'Java version:\s+21(?:\.|\s|,)') {
        throw "Maven 未使用 JDK 21。mvn -version 输出：`n$mavenVersion"
    }
}

function Reset-OrderlyStaging {
    $targetPrefix = $targetRoot.TrimEnd('\') + '\'
    if (-not $stagingRoot.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝清理 repo target 之外的路径：$stagingRoot"
    }
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $stagingRoot | Out-Null
}

Push-Location $repoRoot
try {
    Assert-Jdk21

    $moduleDirectories = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "Module") -Directory -Filter "module_*" |
        Sort-Object Name)
    $moduleNames = @($moduleDirectories | ForEach-Object { $_.Name })
    $indexText = if ($moduleNames.Count -gt 0) { ($moduleNames -join "`n") + "`n" } else { "" }
    [System.IO.File]::WriteAllText($indexPath, $indexText, [System.Text.UTF8Encoding]::new($false))
    Write-Host "扫描到 $($moduleNames.Count) 个模块：$($moduleNames -join ', ')"

    Reset-OrderlyStaging

    Invoke-Maven @(
        "-Dmaven.repo.local=$localRepository",
        "-pl", "app/kernel",
        "-am",
        "install",
        "-DskipTests"
    )

    foreach ($moduleDirectory in $moduleDirectories) {
        $pom = Join-Path $moduleDirectory.FullName "pom.xml"
        if (-not (Test-Path -LiteralPath $pom -PathType Leaf)) {
            throw "模块目录缺少 pom.xml：$($moduleDirectory.FullName)"
        }
        Write-Host "构建模块：$($moduleDirectory.Name)"
        Invoke-Maven @(
            "-Dmaven.repo.local=$localRepository",
            "-f", $pom,
            "clean", "package",
            "-DskipTests"
        )
        $moduleJars = @(Get-ChildItem -LiteralPath (Join-Path $moduleDirectory.FullName "target") -Filter "*.jar" -File |
            Where-Object {
                $_.Name -notlike "*.original" -and
                $_.Name -notlike "*-sources.jar" -and
                $_.Name -notlike "*-javadoc.jar"
            })
        if ($moduleJars.Count -ne 1) {
            throw "模块 $($moduleDirectory.Name) 应产出一个可运行 JAR，实际 $($moduleJars.Count) 个。"
        }
        Copy-Item -LiteralPath $moduleJars[0].FullName `
            -Destination (Join-Path $stagingRoot "$($moduleDirectory.Name).jar") -Force
    }

    $hostArguments = @(
        "-Dmaven.repo.local=$localRepository",
        "-f", (Join-Path $repoRoot "app\host\pom.xml"),
        "clean", "package"
    )
    if ($SkipTests) {
        $hostArguments += "-DskipTests"
    }
    Write-Host "构建宿主 JAR（模块目录：$stagingRoot）"
    Invoke-Maven $hostArguments

    $hostJars = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "app\host\target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" })
    if ($hostJars.Count -ne 1) {
        throw "宿主构建应产出一个 fat JAR，实际 $($hostJars.Count) 个。"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($hostJars[0].FullName)
    try {
        $bundledModules = @($archive.Entries | Where-Object {
            $_.FullName -match '^BOOT-INF/lib/module[-_].+\.jar$' -or
            $_.FullName -match '^BOOT-INF/classes/orderly-modules/.+\.json$'
        })
    } finally {
        $archive.Dispose()
    }
    if ($bundledModules.Count -ne 0) {
        throw "宿主 fat JAR 不得包含具体模块：$($bundledModules.FullName -join ', ')"
    }

    Write-Host "构建完成。运行时模块目录：$stagingRoot"
}
finally {
    Pop-Location
}
