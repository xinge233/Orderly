[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $repoRoot "app\frontend"
$localRepository = Join-Path $repoRoot ".m2-repository"

Push-Location $repoRoot
try {
    & mvn "-Dmaven.repo.local=$localRepository" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 测试失败，退出码：$LASTEXITCODE"
    }

    & node --test (Join-Path $frontendRoot "tests\*.test.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "前端测试失败，退出码：$LASTEXITCODE"
    }

    $forbiddenDelete = & rg -n --glob '*.java' 'Files\.delete|deleteIfExists' app Module
    if ($LASTEXITCODE -eq 0) {
        throw "检测到物理删除调用：`n$forbiddenDelete"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "物理删除扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $hardcodedColor = & rg -n --glob '*.css' --glob '!tokens.css' '#[0-9a-fA-F]{3,8}|rgba?\(' app/frontend
    if ($LASTEXITCODE -eq 0) {
        throw "业务样式出现硬编码色值：`n$hardcodedColor"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "样式色值扫描异常，rg 退出码：$LASTEXITCODE"
    }

    Write-Host "门禁通过：Maven 测试、前端测试、物理删除扫描、样式变量扫描均成功。"
}
finally {
    Pop-Location
}
