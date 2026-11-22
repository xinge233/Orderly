#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   使用隔离临时仓库验证构建清单写入、输入指纹变化和局部构建决策。
#
# 输入：
#   无；要求当前环境可使用 Git 和 JDK 21。
#
# 副作用：
#   只在 target/test-build-plan-* 中创建临时 Git 仓库和假 JAR，结束后清理。
#
# 安全边界：
#   不调用 Maven、不启动应用、不读取产品数据；清理前确认路径属于 target。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
. (Join-Path $PSScriptRoot "build-plan.ps1")

$fixtureRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $targetRoot ("test-build-plan-" + [guid]::NewGuid().ToString("N")))
)
$manifestPath = Join-Path $fixtureRoot "target\build-manifest.json"

function Write-FixtureFile {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Content
    )
    $path = Join-Path $fixtureRoot $RelativePath
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [System.IO.File]::WriteAllText(
        $path,
        $Content,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Assert-Plan {
    param(
        [Parameter(Mandatory = $true)]$Plan,
        [Parameter(Mandatory = $true)][bool]$BuildRequired,
        [Parameter(Mandatory = $true)][bool]$FullBuildRequired,
        [Parameter(Mandatory = $true)][bool]$HostBuildRequired,
        [AllowEmptyCollection()]
        [Parameter(Mandatory = $true)][string[]]$BusinessModuleNames,
        [Parameter(Mandatory = $true)][string]$Case
    )
    if ([bool]$Plan.BuildRequired -ne $BuildRequired -or
            [bool]$Plan.FullBuildRequired -ne $FullBuildRequired -or
            [bool]$Plan.HostBuildRequired -ne $HostBuildRequired -or
            ((@($Plan.BusinessModuleNames) -join "|") -ne ($BusinessModuleNames -join "|"))) {
        throw "构建计划断言失败：$Case；实际=$($Plan | ConvertTo-Json -Depth 6 -Compress)"
    }
}

try {
    [System.IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
    Write-FixtureFile "pom.xml" @"
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>test</groupId>
  <artifactId>fixture</artifactId>
  <version>1.0.0</version>
</project>
"@
    Write-FixtureFile "app/kernel/pom.xml" "<project />"
    Write-FixtureFile "app/kernel/src/main/Kernel.java" "class Kernel {}"
    Write-FixtureFile "app/host/pom.xml" "<project />"
    Write-FixtureFile "app/host/src/main/Host.java" "class Host {}"
    Write-FixtureFile "app/frontend/index.html" "<!doctype html><title>fixture</title>"
    Write-FixtureFile "app/frontend/src/app.js" "export const value = 1;"
    Write-FixtureFile "app/frontend/styles/app.css" ":root { --fixture: 1; }"
    Write-FixtureFile "contracts/sample.json" "{}"
    Write-FixtureFile "Module/module_system_control/pom.xml" "<project />"
    Write-FixtureFile "Module/module_system_control/src/main/Control.java" "class Control {}"
    Write-FixtureFile "Module/module_fixture/pom.xml" "<project />"
    Write-FixtureFile "Module/module_fixture/src/main/Fixture.java" "class Fixture { int value = 1; }"
    Write-FixtureFile "app/host/target/orderly-host-1.0.0.jar" "host-artifact"
    Write-FixtureFile "target/orderly-modules/module_system_control.jar" "system-artifact"
    Write-FixtureFile "target/orderly-modules/module_fixture.jar" "business-artifact"

    & git -C $fixtureRoot init --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "初始化临时 Git 仓库失败。"
    }
    & git -C $fixtureRoot config user.name "Orderly Test"
    & git -C $fixtureRoot config user.email "orderly-test@example.invalid"
    if ($LASTEXITCODE -ne 0) {
        throw "配置临时 Git 仓库失败。"
    }
    & git -C $fixtureRoot add .
    if ($LASTEXITCODE -ne 0) {
        throw "暂存临时 Git 仓库失败。"
    }
    & git -C $fixtureRoot commit --quiet -m "fixture"
    if ($LASTEXITCODE -ne 0) {
        throw "提交临时 Git 仓库失败。"
    }

    Write-OrderlyBuildManifest `
        -RepoRoot $fixtureRoot `
        -ManifestPath $manifestPath `
        -Mode "full" `
        -BuiltModuleNames @("module_system_control", "module_fixture")
    $baseline = Resolve-OrderlyBuildPlan -RepoRoot $fixtureRoot -ManifestPath $manifestPath
    Assert-Plan `
        -Plan $baseline `
        -BuildRequired $false `
        -FullBuildRequired $false `
        -HostBuildRequired $false `
        -BusinessModuleNames @() `
        -Case "未变化"

    Write-FixtureFile "Module/module_fixture/src/main/Fixture.java" "class Fixture { int value = 2; }"
    $moduleChanged = Resolve-OrderlyBuildPlan -RepoRoot $fixtureRoot -ManifestPath $manifestPath
    Assert-Plan `
        -Plan $moduleChanged `
        -BuildRequired $true `
        -FullBuildRequired $false `
        -HostBuildRequired $false `
        -BusinessModuleNames @("module_fixture") `
        -Case "业务模块变化"

    Write-OrderlyBuildManifest `
        -RepoRoot $fixtureRoot `
        -ManifestPath $manifestPath `
        -Mode "full" `
        -BuiltModuleNames @("module_system_control", "module_fixture")
    Write-FixtureFile "app/host/src/main/Host.java" "class Host { int value = 2; }"
    $hostChanged = Resolve-OrderlyBuildPlan -RepoRoot $fixtureRoot -ManifestPath $manifestPath
    Assert-Plan `
        -Plan $hostChanged `
        -BuildRequired $true `
        -FullBuildRequired $false `
        -HostBuildRequired $true `
        -BusinessModuleNames @() `
        -Case "宿主变化"

    Write-OrderlyBuildManifest `
        -RepoRoot $fixtureRoot `
        -ManifestPath $manifestPath `
        -Mode "full" `
        -BuiltModuleNames @("module_system_control", "module_fixture")
    Write-FixtureFile "pom.xml" @"
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>test</groupId>
  <artifactId>fixture</artifactId>
  <version>1.0.1</version>
</project>
"@
    $sharedChanged = Resolve-OrderlyBuildPlan -RepoRoot $fixtureRoot -ManifestPath $manifestPath
    if (-not $sharedChanged.FullBuildRequired -or
            @($sharedChanged.BusinessModuleNames) -notcontains "module_fixture") {
        throw "共享输入变化未触发完整构建：$($sharedChanged | ConvertTo-Json -Depth 6 -Compress)"
    }

    Write-Host "构建计划测试通过：未变化、模块变化、宿主变化和共享输入变化均按预期决策。"
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        $resolved = Assert-OrderlyPathWithin $fixtureRoot $targetRoot "构建计划测试目录"
        if ($resolved.Equals($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "拒绝清理 target 根目录。"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
