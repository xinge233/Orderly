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

    $sourceFiles = @("app.js", "api.js", "models.js", "modules.js", "settings.js")
    foreach ($sourceFile in $sourceFiles) {
        & node --check (Join-Path $frontendRoot "src\$sourceFile")
        if ($LASTEXITCODE -ne 0) {
            throw "前端语法检查失败：$sourceFile，退出码：$LASTEXITCODE"
        }
    }

    $moduleScripts = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "Module") -Directory -Filter "module_*" |
        ForEach-Object { Join-Path $_.FullName "src\main\resources\orderly-ui\module.js" })
    foreach ($moduleScript in $moduleScripts) {
        if (-not (Test-Path -LiteralPath $moduleScript -PathType Leaf)) {
            throw "模块缺少 UI 脚本：$moduleScript"
        }
        & node --check $moduleScript
        if ($LASTEXITCODE -ne 0) {
            throw "模块 UI 脚本语法检查失败：$moduleScript，退出码：$LASTEXITCODE"
        }
    }

    & node --test (Join-Path $frontendRoot "tests\*.test.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "前端测试失败，退出码：$LASTEXITCODE"
    }

    & node --test (Join-Path $repoRoot "app\host\src\test\js\*.test.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "模块 UI 契约样例校验失败，退出码：$LASTEXITCODE"
    }

    & node (Join-Path $repoRoot "scripts\validate-study-data.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "学习数据契约校验失败，退出码：$LASTEXITCODE"
    }

    $forbiddenDelete = & rg -n --glob '*.java' 'Files\.delete|deleteIfExists' app Module
    if ($LASTEXITCODE -eq 0) {
        throw "检测到物理删除调用：`n$forbiddenDelete"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "物理删除扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $hardcodedColor = & rg -n --glob '*.css' --glob '!tokens.css' '#[0-9a-fA-F]{3,8}|rgba?\(' app/frontend Module
    if ($LASTEXITCODE -eq 0) {
        throw "业务样式出现硬编码色值：`n$hardcodedColor"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "样式色值扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $fixedModuleCode = & rg -n 'MODULE_VIEW_ADAPTERS|getFiles|getTodos|getCalendar|loadFiles|loadTodos|loadCalendar|overviewData|filePath|/api/files|/api/todos|/api/calendar|/api/trash|module_fs|module_todo|module_calendar' app/frontend/src app/host/src/main/java
    if ($LASTEXITCODE -eq 0) {
        throw "框架源码仍包含具体模块映射或固定业务端点：`n$fixedModuleCode"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "固定模块扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $staticThemes = & rg -n 'THEME_OPTIONS' app/frontend/src
    if ($LASTEXITCODE -eq 0) {
        throw "前端仍保留静态主题列表：`n$staticThemes"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "静态主题扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $classpathThemes = & rg -n 'classpath\*?:themes|PathMatchingResourcePatternResolver' app/host/src/main/java app/host/pom.xml
    if ($LASTEXITCODE -eq 0) {
        throw "宿主仍保留 classpath 主题回退：`n$classpathThemes"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "classpath 主题扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $baseMotion = & rg -n '@keyframes\b|(^|[\s;])animation\s*:|(^|[\s;])transition\s*:' app/frontend/styles/app.css
    if ($LASTEXITCODE -eq 0) {
        throw "基础组件 CSS 仍包含硬编码动画或过渡：`n$baseMotion"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "基础动画扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $globalPanelLive = & rg -n 'id="settings-panel"[^>]*aria-live' app/frontend/index.html
    if ($LASTEXITCODE -eq 0) {
        throw "设置面板仍使用整体 aria-live：`n$globalPanelLive"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "设置面板 live region 扫描异常，rg 退出码：$LASTEXITCODE"
    }

    $themeScripts = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "themes") -Recurse -File -Filter "*.js")
    if ($themeScripts.Count -ne 0) {
        throw "主题包不得包含 JavaScript：$($themeScripts.FullName -join ', ')"
    }

    $themeImportant = & rg -n '!important' themes --glob '*.css'
    if ($LASTEXITCODE -eq 0) {
        throw "仓库示例主题不得用 !important 绕过动效安全层：`n$themeImportant"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "主题 important 扫描异常，rg 退出码：$LASTEXITCODE"
    }

    Write-Host "门禁通过：模块化 Maven 测试、契约样例、语法、前端测试、目录主题边界、classpath 回退、基础动画、设置 live region 和样式变量检查均成功。"
}
finally {
    Pop-Location
}

exit 0
