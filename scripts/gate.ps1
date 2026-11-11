[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $repoRoot "app\frontend"

Push-Location $repoRoot
try {
    & (Join-Path $PSScriptRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "模块化构建失败，退出码：$LASTEXITCODE"
    }

    $sourceFiles = @("app.js", "api.js", "models.js", "settings.js")
    foreach ($sourceFile in $sourceFiles) {
        & node --check (Join-Path $frontendRoot "src\$sourceFile")
        if ($LASTEXITCODE -ne 0) {
            throw "前端语法检查失败：$sourceFile，退出码：$LASTEXITCODE"
        }
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

    $staticThemes = & rg -n 'THEME_OPTIONS' app/frontend/src
    if ($LASTEXITCODE -eq 0) {
        throw "前端仍保留静态主题列表：`n$staticThemes"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "静态主题扫描异常，rg 退出码：$LASTEXITCODE"
    }

    Write-Host "门禁通过：模块化 Maven 测试、前端语法与测试、物理删除扫描、样式变量扫描、动态主题扫描均成功。"
}
finally {
    Pop-Location
}

exit 0
