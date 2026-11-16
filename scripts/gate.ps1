[CmdletBinding()]
param(
    [switch]$DocumentOnly
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $repoRoot "app\frontend"
$contextScript = Join-Path $PSScriptRoot "context.ps1"
$docGateScript = Join-Path $PSScriptRoot "doc-gate.ps1"
$powerShellExecutable = (Get-Process -Id $PID).Path

function Invoke-ContextProcess {
    param(
        [string[]]$Arguments,
        [string]$ScriptPath
    )

    $selectedContextScript = if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
        $contextScript
    }
    else {
        $ScriptPath
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& $powerShellExecutable `
            -NoProfile `
            -NonInteractive `
            -File $selectedContextScript @Arguments 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Assert-ContextRejected {
    param(
        [Parameter(Mandatory = $true)]
        [string]$CaseName,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [string]$ExpectedText,
        [string]$ScriptPath,
        [string]$ForbiddenText
    )

    $result = Invoke-ContextProcess -Arguments $Arguments -ScriptPath $ScriptPath
    if ($result.ExitCode -eq 0) {
        throw "上下文工具未拒绝 $CaseName。"
    }
    if (-not [string]::IsNullOrEmpty($ExpectedText) -and
            -not $result.Output.Contains($ExpectedText)) {
        throw "上下文工具拒绝 $CaseName 时未包含预期原因：$ExpectedText"
    }
    if (-not [string]::IsNullOrEmpty($ForbiddenText) -and
            $result.Output.Contains($ForbiddenText)) {
        throw "上下文工具拒绝 $CaseName 时泄漏了文件正文。"
    }
}

function Assert-ContextBinaryTailRejected {
    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
        "orderly-context-binary-" + [guid]::NewGuid().ToString("N")
    )
    try {
        $tempScripts = Join-Path $tempRoot "scripts"
        New-Item -ItemType Directory -Path $tempScripts -Force | Out-Null
        $tempContext = Join-Path $tempScripts "context.ps1"
        Copy-Item -LiteralPath $contextScript -Destination $tempContext

        $binary = New-Object byte[] 9001
        $prefix = [System.Text.Encoding]::ASCII.GetBytes("x" * 9000)
        [System.Array]::Copy($prefix, $binary, $prefix.Length)
        [System.IO.File]::WriteAllBytes((Join-Path $tempRoot "tail-null.bin"), $binary)

        & git -C $tempRoot init --quiet 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "无法创建二进制检测临时仓库，退出码：$LASTEXITCODE"
        }
        & git -C $tempRoot add -- "tail-null.bin" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "无法暂存二进制检测夹具，退出码：$LASTEXITCODE"
        }

        Assert-ContextRejected `
            -CaseName "首块后含 NUL 的二进制文件" `
            -ScriptPath $tempContext `
            -Arguments @("-Path", "tail-null.bin") `
            -ExpectedText "拒绝二进制文件：tail-null.bin"
    }
    finally {
        if (Test-Path -LiteralPath $tempRoot) {
            $tempBase = [System.IO.Path]::GetFullPath(
                [System.IO.Path]::GetTempPath()
            ).TrimEnd(
                [System.IO.Path]::DirectorySeparatorChar,
                [System.IO.Path]::AltDirectorySeparatorChar
            )
            $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
            $expectedPrefix = $tempBase + [System.IO.Path]::DirectorySeparatorChar
            $leaf = [System.IO.Path]::GetFileName($resolvedTempRoot)
            if (-not $resolvedTempRoot.StartsWith(
                    $expectedPrefix,
                    [System.StringComparison]::OrdinalIgnoreCase
                ) -or
                -not $leaf.StartsWith(
                    "orderly-context-binary-",
                    [System.StringComparison]::Ordinal
                )) {
                throw "拒绝清理非预期的二进制检测临时目录：$resolvedTempRoot"
            }
            Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
        }
    }
}

function Test-DocumentFastPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $normalizedPath = $Path.Replace("\", "/")
    $pathSegments = @($normalizedPath -split "/")
    if (@($pathSegments | Where-Object {
        [string]::IsNullOrEmpty($_) -or $_ -eq "." -or $_ -eq ".."
    }).Count -gt 0) {
        return $false
    }
    if (-not [System.IO.Path]::GetExtension($normalizedPath).Equals(
            ".md",
            [System.StringComparison]::Ordinal
        )) {
        return $false
    }

    if (@(
        "AGENTS.md",
        "README.md",
        "Module/AGENTS.md",
        "Module/README.md"
    ) -ccontains $normalizedPath) {
        return $true
    }
    if ($normalizedPath.StartsWith("docs/", [System.StringComparison]::Ordinal) -or
            $normalizedPath.StartsWith(
                ".codex/agents/",
                [System.StringComparison]::Ordinal
            )) {
        return $true
    }
    return $false
}

function Get-GateChangeSet {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $statusOutput = (& git -C $repoRoot `
            status `
            --porcelain=v1 `
            -z `
            --untracked-files=all `
            --ignore-submodules=none 2>&1) | Out-String
        $gitExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($gitExitCode -ne 0) {
        throw "读取 Git 变更集失败，退出码：$gitExitCode`n$($statusOutput.Trim())"
    }

    $trimmed = $statusOutput.TrimEnd([char[]]@([char]0, [char]13, [char]10))
    if ([string]::IsNullOrEmpty($trimmed)) {
        return [pscustomobject]@{
            Paths = @()
            ClassificationUncertain = $false
        }
    }

    $tokens = @($trimmed -split "`0")
    $paths = [System.Collections.Generic.List[string]]::new()
    $classificationUncertain = $false
    $conflictPairs = @("DD", "AU", "UD", "UA", "DU", "AA", "UU")
    $index = 0
    while ($index -lt $tokens.Count) {
        $entry = $tokens[$index]
        $index++
        if ([string]::IsNullOrEmpty($entry)) {
            continue
        }
        if ($entry.Length -lt 4 -or $entry[2] -ne [char]32) {
            $classificationUncertain = $true
            continue
        }

        $xStatus = [char]$entry[0]
        $yStatus = [char]$entry[1]
        $knownStatuses = @(
            [char]32,
            [char]"M",
            [char]"A",
            [char]"D",
            [char]"R",
            [char]"C",
            [char]"U",
            [char]"?",
            [char]"!",
            [char]"T"
        )
        if ($knownStatuses -notcontains $xStatus -or
                $knownStatuses -notcontains $yStatus) {
            $classificationUncertain = $true
        }
        $path = $entry.Substring(3).Replace("\", "/")
        $paths.Add($path)

        $statusPair = "$xStatus$yStatus"
        if ($conflictPairs -contains $statusPair) {
            $classificationUncertain = $true
        }

        if ($xStatus -eq [char]"R" -or
                $xStatus -eq [char]"C" -or
                $yStatus -eq [char]"R" -or
                $yStatus -eq [char]"C") {
            if ($index -ge $tokens.Count -or [string]::IsNullOrEmpty($tokens[$index])) {
                $classificationUncertain = $true
            }
            else {
                $paths.Add($tokens[$index].Replace("\", "/"))
                $index++
            }
        }

        if ($xStatus -eq [char]"T" -or $yStatus -eq [char]"T" -or
                $xStatus -eq [char]"U" -or $yStatus -eq [char]"U") {
            $classificationUncertain = $true
        }
    }

    return [pscustomobject]@{
        Paths = @($paths | Sort-Object -Unique)
        ClassificationUncertain = $classificationUncertain
    }
}

Push-Location $repoRoot
try {
    $docGateResult = Invoke-ContextProcess `
        -Arguments @() `
        -ScriptPath $docGateScript
    if (-not [string]::IsNullOrWhiteSpace($docGateResult.Output)) {
        Write-Host $docGateResult.Output.TrimEnd()
    }
    if ($docGateResult.ExitCode -ne 0) {
        throw "快速文档门禁失败，退出码：$($docGateResult.ExitCode)"
    }

    $changeSet = Get-GateChangeSet
    $changePaths = @($changeSet.Paths)
    $nonMarkdownPaths = @($changePaths | Where-Object {
        -not [System.IO.Path]::GetExtension($_).Equals(
            ".md",
            [System.StringComparison]::Ordinal
        )
    })
    $nonWhitelistedMarkdownPaths = @($changePaths | Where-Object {
        [System.IO.Path]::GetExtension($_).Equals(
            ".md",
            [System.StringComparison]::Ordinal
        ) -and -not (Test-DocumentFastPath -Path $_)
    })
    $isNonEmptyFastPathMarkdown = $changePaths.Count -gt 0 -and
        -not $changeSet.ClassificationUncertain -and
        $nonMarkdownPaths.Count -eq 0 -and
        $nonWhitelistedMarkdownPaths.Count -eq 0

    if ($DocumentOnly) {
        if (-not $isNonEmptyFastPathMarkdown) {
            $reason = if ($changePaths.Count -eq 0) {
                "变更集为空"
            }
            elseif ($changeSet.ClassificationUncertain) {
                "变更分类不确定"
            }
            elseif ($nonMarkdownPaths.Count -gt 0) {
                "变更集包含非 Markdown 文件"
            }
            else {
                "变更集包含不在快速白名单中的 Markdown 文件"
            }
            throw "-DocumentOnly 要求非空快速白名单 Markdown 变更集；当前$reason。"
        }
        Write-Host "门禁通过：-DocumentOnly 检测到 $($changePaths.Count) 个快速白名单 Markdown 变更；未运行 Maven/前端测试。"
        exit 0
    }

    if ($isNonEmptyFastPathMarkdown) {
        Write-Host "门禁通过：快速白名单 Markdown 分支，检测到 $($changePaths.Count) 个变更；未运行 Maven/前端测试。"
        exit 0
    }

    $fullGateReason = if ($changePaths.Count -eq 0) {
        "变更集为空"
    }
    elseif ($changeSet.ClassificationUncertain) {
        "变更分类不确定"
    }
    elseif ($nonMarkdownPaths.Count -gt 0) {
        "变更集包含非 Markdown 文件"
    }
    else {
        "变更集包含不在快速白名单中的 Markdown 文件"
    }
    Write-Host "完整门禁：$fullGateReason，继续执行 Maven、前端和边界检查。"

    $tokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $contextScript,
        [ref]$tokens,
        [ref]$parseErrors
    ) | Out-Null
    if ($parseErrors.Count -ne 0) {
        throw "上下文工具语法解析失败：`n$($parseErrors.Message -join "`n")"
    }

    $contextRead = Invoke-ContextProcess -Arguments @("-Path", "AGENTS.md")
    if ($contextRead.ExitCode -ne 0) {
        throw "上下文工具无法读取 AGENTS.md，退出码：$($contextRead.ExitCode)`n$($contextRead.Output)"
    }
    if (-not $contextRead.Output.Contains("FILE path=AGENTS.md bytes=")) {
        throw "上下文工具成功读取时未输出路径与字节量。"
    }

    Assert-ContextRejected `
        -CaseName "仓库外路径" `
        -Arguments @("-Path", "..\AGENTS.md")
    Assert-ContextRejected `
        -CaseName "目录" `
        -Arguments @("-Path", "app")
    Assert-ContextRejected `
        -CaseName "二进制文件" `
        -Arguments @("-Path", "docs\归序Orderly-项目策划书-v7.docx")
    Assert-ContextBinaryTailRejected
    Assert-ContextRejected `
        -CaseName "未跟踪文件" `
        -Arguments @("-Path", ".git\config") `
        -ForbiddenText "[core]"
    Assert-ContextRejected `
        -CaseName "含 pathspec 元字符的未跟踪路径" `
        -Arguments @("-Path", "AGENTS.m[d]") `
        -ExpectedText "拒绝未跟踪文件：AGENTS.m[d]" `
        -ForbiddenText "# AGENTS.md"
    Assert-ContextRejected `
        -CaseName "忽略路径" `
        -Arguments @("-Path", "target\context-tool-untracked-probe.tmp")
    Assert-ContextRejected `
        -CaseName "超限读取" `
        -Arguments @("-Path", "AGENTS.md", "-Count", "501") `
        -ForbiddenText "# AGENTS.md"

    $audit = Invoke-ContextProcess -Arguments @("-Mode", "audit")
    if ($audit.ExitCode -ne 0) {
        throw "上下文工具审计失败，退出码：$($audit.ExitCode)`n$($audit.Output)"
    }

    $repoMapText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot "docs\repo-map.md")
    if ($repoMapText.Contains("``````")) {
        throw "docs/repo-map.md 不得包含代码围栏。"
    }

    $moduleRulesPath = Join-Path $repoRoot "Module\AGENTS.md"
    if (-not (Test-Path -LiteralPath $moduleRulesPath -PathType Leaf)) {
        throw "缺少模块目录规范：Module/AGENTS.md"
    }
    $moduleRules = Get-Content -Raw -LiteralPath $moduleRulesPath
    foreach ($requiredRule in @(
        "aiCallable",
        "`$fromAction",
        "不得在编译期或运行期直接导入",
        "逆序回滚"
    )) {
        if (-not $moduleRules.Contains($requiredRule)) {
            throw "Module/AGENTS.md 缺少规则：$requiredRule"
        }
    }
    foreach ($requiredContract in @(
        "contracts\action-execution.schema.json",
        "contracts\samples\valid\action-execution.json",
        "contracts\samples\invalid\action-execution-empty.json"
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $requiredContract) -PathType Leaf)) {
            throw "缺少 Action 直执契约或样例：$requiredContract"
        }
    }

    $ignoreLines = @(Get-Content -LiteralPath (Join-Path $repoRoot ".gitignore") |
        ForEach-Object { $_.Trim() })
    foreach ($requiredIgnoreLine in @(
        ".m2-repository/",
        "target/",
        ".orderly-data/",
        "output/~$*"
    )) {
        if ($ignoreLines -notcontains $requiredIgnoreLine) {
            throw ".gitignore 缺少规则：$requiredIgnoreLine"
        }
    }

    Write-Host "上下文门禁通过：语法、成功读取、边界拒绝、正文不泄漏、审计、仓库地图与忽略规则均成功。"

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

    Write-Host "门禁通过：上下文约束、模块化 Maven 测试、契约样例、语法、前端测试、目录主题边界、classpath 回退、基础动画、设置 live region 和样式变量检查均成功。"
}
finally {
    Pop-Location
}

exit 0
