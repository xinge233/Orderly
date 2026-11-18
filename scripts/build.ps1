[CmdletBinding()]
# 用途：
#   按运行时目录约定构建 kernel、所有 Module/module_* JAR 和 Java 宿主 fat JAR。
#
# 输入：
#   -SkipTests 控制宿主构建是否跳测；JDK 21 和 Maven 3.9+ 必须可从 JAVA_HOME/PATH 使用。
#
# 副作用：
#   写入 target、.m2-repository 和模块/宿主 target，重建 target/orderly-modules 运行时暂存目录，
#   并在结束时清理已知测试夹具目录。不会写入应用正式工作区或产品数据目录。
#
# 安全边界：
#   所有递归清理都先确认路径位于仓库 target 或 host target 内；模块发现只扫描 Module/module_*，
#   宿主 fat JAR 不得包含具体模块或用户主题，构建失败必须以非零退出状态阻止启动。

param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$localRepository = Join-Path $repoRoot ".m2-repository"
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$stagingRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "orderly-modules"))

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

function Invoke-Maven {
    param([string[]]$MavenArguments)
    # Maven 失败要保留完整输出并带上退出码，避免调用方只看到笼统的 PowerShell 失败。
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & mvn @MavenArguments
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Maven 执行失败，退出码：$exitCode；参数：$($MavenArguments -join ' ')"
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
    $mavenVersion = Invoke-NativeCapture -FilePath "mvn" -Arguments @("-version")
    if ($mavenVersion.ExitCode -ne 0 -or $mavenVersion.Output -notmatch 'Java version:\s+21(?:\.|\s|,)') {
        throw "Maven 未使用 JDK 21。mvn -version 输出：`n$($mavenVersion.Output)"
    }
}

function Remove-TestArtifacts {
    # 测试会产生多种随机夹具。仅清理这些已知前缀，并在删除前再次校验绝对路径仍在目标根内。
    $testRoots = @(
        $targetRoot,
        [System.IO.Path]::GetFullPath((Join-Path $repoRoot "app\host\target"))
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
                $_.Name -like "module-control-fixture-*" -or
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

function Reset-OrderlyStaging {
    # 运行时模块目录必须从空目录重建，否则已删除模块的陈旧 JAR 仍可能被宿主加载。
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

    # 模块直接从源码目录发现，不维护静态索引；排序保证构建输出和日志顺序稳定。
    $moduleDirectories = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "Module") -Directory -Filter "module_*" |
        Sort-Object Name)
    $moduleNames = @($moduleDirectories | ForEach-Object { $_.Name })
    $controlModules = @($moduleDirectories | Where-Object { $_.Name -eq "module_control" })
    # 系统模块是宿主治理能力的唯一入口，缺失或重复都不能得到一个可启动的模块目录。
    if ($controlModules.Count -ne 1) {
        throw "宿主构建要求且仅允许一个 Module/module_control 源码目录，实际 $($controlModules.Count) 个。"
    }
    Write-Host "扫描到 $($moduleNames.Count) 个模块：$($moduleNames -join ', ')"

    Reset-OrderlyStaging

    # kernel 是模块和宿主共享的 SPI，必须先在本地仓库安装，后续独立模块才能编译。
    Invoke-Maven @(
        "-Dmaven.repo.local=$localRepository",
        "-pl", "app/kernel",
        "-am",
        "install",
        "-DskipTests"
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $systemModuleDirectories = @()
    # 每个模块独立构建，验证它确实自包含 UI、单一契约和可执行 JAR。
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
        # 直接检查 JAR 内容而不是只信任 Maven 成功，防止 UI 或契约漏打包到运行时。
        $moduleArchive = [System.IO.Compression.ZipFile]::OpenRead($moduleJars[0].FullName)
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
                } finally {
                    $reader.Dispose()
                }
                if ($null -ne $contract.system) {
                    if ($contract.system.role -ne "MODULE_CONTROL" -or $contract.system.protected -ne $true) {
                        throw "模块 $($moduleDirectory.Name) 的 system 元数据必须为受保护的 MODULE_CONTROL。"
                    }
                    $systemModuleDirectories += $moduleDirectory.Name
                }
            }
            if ($moduleDirectory.Name -eq "module_control") {
                $controlContracts = @($contractEntries | Where-Object { $_.FullName -match 'module_control\.json$' })
                if ($controlContracts.Count -ne 1) {
                    throw "module_control 必须且只能声明一个 module_control.json 契约。"
                }
            }
        } finally {
            $moduleArchive.Dispose()
        }
        Copy-Item -LiteralPath $moduleJars[0].FullName `
            -Destination (Join-Path $stagingRoot "$($moduleDirectory.Name).jar") -Force
    }
    # 汇总所有模块契约后再次确认只有 module_control 声明系统角色，避免其他模块冒充治理入口。
    $systemModuleDirectories = @($systemModuleDirectories | Sort-Object -Unique)
    if ($systemModuleDirectories.Count -ne 1 -or
            $systemModuleDirectories[0] -ne "module_control") {
        throw "宿主构建要求且仅允许 module_control 声明 MODULE_CONTROL，实际：$($systemModuleDirectories -join ', ')"
    }

    $hostArguments = @(
        "-Dmaven.repo.local=$localRepository",
        "-f", (Join-Path $repoRoot "app\host\pom.xml"),
        "clean", "package"
    )
    # -SkipTests 只影响宿主 Maven 测试；模块 JAR 的发现和自包含检查仍无条件执行。
    if ($SkipTests) {
        $hostArguments += "-DskipTests"
    }
    Write-Host "构建宿主 JAR（模块目录：$stagingRoot）"
    Invoke-Maven $hostArguments

    # 宿主必须产出一个可运行 fat JAR；随后检查其内容，防止模块或主题被静默打进框架。
    $hostJars = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "app\host\target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" })
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
    } finally {
        $archive.Dispose()
    }
    if ($bundledModules.Count -ne 0) {
        throw "宿主 fat JAR 不得包含具体模块：$($bundledModules.FullName -join ', ')"
    }
    if ($bundledThemes.Count -ne 0) {
        throw "宿主 fat JAR 不得包含用户主题：$($bundledThemes.FullName -join ', ')"
    }

    Write-Host "构建完成。运行时模块目录：$stagingRoot"
}
finally {
    # 即使构建中途失败也清理测试夹具并恢复调用方目录，便于下一轮门禁重新开始。
    Remove-TestArtifacts
    Pop-Location
}
