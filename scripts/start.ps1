[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$hostRoot = Join-Path $repoRoot "app\host"
$localRepository = Join-Path $repoRoot ".m2-repository"

Push-Location $repoRoot
try {
    & mvn "-Dmaven.repo.local=$localRepository" -q -pl app/host -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "构建失败，退出码：$LASTEXITCODE"
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $hostRoot "target") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*.original" } |
        Select-Object -First 1
    if (-not $jar) {
        throw "未找到可运行的 host JAR。"
    }

    & java -jar $jar.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "应用退出码：$LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
