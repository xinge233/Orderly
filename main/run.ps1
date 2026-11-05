<#
.SYNOPSIS
    启动 Orderly 本地应用或执行完整构建。

.PARAMETER Test
    仅执行测试与打包，不启动长期运行的服务。

.EXAMPLE
    .\run.ps1

.EXAMPLE
    .\run.ps1 -Test
#>
[CmdletBinding()]
param(
    [switch]$Test
)

$ErrorActionPreference = "Stop"
$mainRoot = $PSScriptRoot
$backendRoot = Join-Path $mainRoot "backend"

# 当前机器的用户目录 Maven 仓库不可写，因此显式使用工作区内的可忽略缓存目录。
# 路径使用绝对形式，避免不同 PowerShell/Maven 组合对相对仓库路径的解释差异。
$localRepository = Join-Path $backendRoot ".m2-repository"
$goal = if ($Test) { "package" } else { "spring-boot:run" }

Push-Location $backendRoot
try {
    & mvn "-Dmaven.repo.local=$localRepository" $goal
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 执行失败，退出码：$LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
