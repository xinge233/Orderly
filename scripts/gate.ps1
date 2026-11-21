#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   执行仓库完整质量门禁：快速文档门禁、上下文边界、Python 测试、模块化 Maven 构建、前端/模块 Node 测试和静态禁止模式扫描。
#
# 输入：
#   -DocumentOnly 要求变更集必须是非空且全部为小写 .md 或 .docx 文档。
#   -Offline 将严格 Maven 离线模式和可选 -MavenRepository 透传给 build.ps1；
#   严格离线分支复用现有 ai/.venv，不调用 ensure-env.ps1 联网安装 Python 依赖。
#   -MaxParallel 原样透传给 build.ps1，默认 1；门禁本身不实现模块调度。
#   完整分支读取 Git 变更集、契约样例、源码和测试，并使用当前 JDK 21、Maven、Node 和 Python 3.14。
#
# 副作用：
#   完整分支会创建或更新 ai/.venv、写入 Maven target/.m2-repository，并执行测试构建；
#   快速 Markdown 分支只运行 doc-gate.ps1 和 Git 变更集检查。脚本本身不启动应用服务。
#
# 安全边界：
#   先无条件执行只读文档门禁；纯 Markdown/DOCX 变更跳过重测试，其他或不确定变更走完整门禁。
#   二进制拒读夹具只写入系统临时目录并在严格前缀校验后清理；任何失败都保留原始输出并返回非零退出码。

param(
    [switch]$DocumentOnly,
    [switch]$Offline,
    [string]$MavenRepository,
    [ValidateRange(1, 2147483647)]
    [int]$MaxParallel = 1
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$frontendRoot = Join-Path $repoRoot "app\frontend"
$contextScript = Join-Path $PSScriptRoot "context.ps1"
$docGateScript = Join-Path $PSScriptRoot "doc-gate.ps1"
$aiRoot = Join-Path $repoRoot "ai"
$aiEnvScript = Join-Path $aiRoot "scripts\ensure-env.ps1"
$contractSampleValidator = Join-Path $PSScriptRoot "validate-contract-samples.py"
$powerShellExecutable = (Get-Process -Id $PID).Path

function Invoke-ContextProcess {
    # 以无配置、非交互子进程运行门禁脚本，返回输出和真实退出码供当前门禁判断。
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
    # 拒绝用例不仅要失败，还要包含预期原因且不能回显文件正文，才能证明边界不是偶然触发。
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
    # 构造首个缓冲块为普通文本、后部含 NUL 的临时 Git 仓库，验证二进制检测不是只看文件头。
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
        # 递归清理前校验路径位于系统临时根下且目录名带专用前缀，避免夹具异常时误删其他目录。
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

function Test-DocumentArtifactPath {
    # 所有安全路径下的小写 .md/.docx 文档统一走只读文档门禁；异常路径和其他扩展名退回完整门禁。
    param([Parameter(Mandatory = $true)][string]$Path)

    $normalizedPath = $Path.Replace("\", "/")
    $pathSegments = @($normalizedPath -split "/")
    if (@($pathSegments | Where-Object {
        [string]::IsNullOrEmpty($_) -or $_ -eq "." -or $_ -eq ".."
    }).Count -gt 0) {
        return $false
    }
    $extension = [System.IO.Path]::GetExtension($normalizedPath)
    return $extension.Equals(".md", [System.StringComparison]::Ordinal) -or
        $extension.Equals(".docx", [System.StringComparison]::Ordinal)
}

function Assert-DocumentArtifactPathClassification {
    # 分类自检防止后续重新引入目录白名单，导致普通文档再触发无关的重测试。
    $cases = @(
        @{ Path = "AGENTS.md"; Expected = $true },
        @{ Path = "docs/note.md"; Expected = $true },
        @{ Path = "themes/README.md"; Expected = $true },
        @{ Path = "Module/module_fs/README.md"; Expected = $true },
        @{ Path = ".codex/agents/ui-design-lead/README.md"; Expected = $true },
        @{ Path = "docs/product.docx"; Expected = $true },
        @{ Path = "README.MD"; Expected = $false },
        @{ Path = "docs/product.DOCX"; Expected = $false },
        @{ Path = "docs/product.docm"; Expected = $false },
        @{ Path = "docs/../AGENTS.md"; Expected = $false },
        @{ Path = "scripts/gate.ps1"; Expected = $false }
    )
    foreach ($case in $cases) {
        if ((Test-DocumentArtifactPath -Path $case.Path) -ne $case.Expected) {
            throw "文档路径分类自检失败：$($case.Path)"
        }
    }
}

Assert-DocumentArtifactPathClassification

function Get-GateChangeSet {
    # porcelain -z 能处理特殊字符路径；重命名、冲突或未知状态标记为分类不确定，强制走完整分支。
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

# 门禁期间以仓库根为当前目录；finally 只负责恢复目录，最终成功退出留给脚本末尾。
Push-Location $repoRoot
try {
    # 文档门禁无条件先执行，避免快速分支被错误配置绕过。
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
    $nonDocumentPaths = @($changePaths | Where-Object {
        -not (Test-DocumentArtifactPath -Path $_)
    })
    $invalidDocumentPaths = @($changePaths | Where-Object {
        $extension = [System.IO.Path]::GetExtension($_)
        ($extension.Equals(".md", [System.StringComparison]::Ordinal) -or
            $extension.Equals(".docx", [System.StringComparison]::Ordinal)) -and
            -not (Test-DocumentArtifactPath -Path $_)
    })
    # 只有非空、分类可信且全部为安全路径下的小写 Markdown/DOCX 时，才允许跳过构建和重测试。
    $isNonEmptyDocumentChange = $changePaths.Count -gt 0 -and
        -not $changeSet.ClassificationUncertain -and
        $nonDocumentPaths.Count -eq 0 -and
        $invalidDocumentPaths.Count -eq 0

    # -DocumentOnly 是强制断言而不是优化选项：不满足纯文档条件必须失败且不得启动构建。
    if ($DocumentOnly) {
        if (-not $isNonEmptyDocumentChange) {
            $reason = if ($changePaths.Count -eq 0) {
                "变更集为空"
            }
            elseif ($changeSet.ClassificationUncertain) {
                "变更分类不确定"
            }
            elseif ($nonDocumentPaths.Count -gt 0) {
                "变更集包含非文档文件"
            }
            else {
                "变更集包含路径不合法的文档文件"
            }
            throw "-DocumentOnly 要求非空纯 Markdown/DOCX 变更集；当前$reason。"
        }
        Write-Host "门禁通过：-DocumentOnly 检测到 $($changePaths.Count) 个纯文档变更；未运行 Maven/前端测试。"
        exit 0
    }

    if ($isNonEmptyDocumentChange) {
        Write-Host "门禁通过：纯文档分支，检测到 $($changePaths.Count) 个变更；未运行 Maven/前端测试。"
        exit 0
    }

    $fullGateReason = if ($changePaths.Count -eq 0) {
        "变更集为空"
    }
    elseif ($changeSet.ClassificationUncertain) {
        "变更分类不确定"
    }
    elseif ($nonDocumentPaths.Count -gt 0) {
        "变更集包含非文档文件"
    }
    else {
        "变更集包含路径不合法的文档文件"
    }
    Write-Host "完整门禁：$fullGateReason，继续执行 Maven、前端和边界检查。"

    # 完整分支先用 AST 检查上下文工具，再测试其成功读取和各类拒绝路径。
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

    if (-not (Test-Path -LiteralPath $contractSampleValidator -PathType Leaf)) {
        throw "缺少契约样例校验器：$contractSampleValidator"
    }
    if ($Offline) {
        # 严格 Maven 离线门禁不运行 ensure-env，避免意外触发 pip 网络安装。
        $python = Join-Path $aiRoot ".venv\Scripts\python.exe"
        if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
            throw "严格离线门禁需要现有 ai/.venv；未运行 ensure-env.ps1，也不会联网安装 Python 依赖。"
        }
    }
    else {
        if (-not (Test-Path -LiteralPath $aiEnvScript -PathType Leaf)) {
            throw "缺少 Python AI 环境脚本：$aiEnvScript"
        }
        # Python 环境可能在首次门禁联网安装依赖；后续契约、编译和测试复用同一个解释器。
        $python = & $aiEnvScript
    }
    if (-not $python -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw "Python AI 环境准备失败。"
    }

    # 仓库地图和模块规范是导航与模块边界的文档事实源，必须与脚本引用的关键约定一致。
    $repoMapText = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repoRoot "docs\repo-map.md")
    if ($repoMapText.Contains("``````")) {
        throw "docs/repo-map.md 不得包含代码围栏。"
    }

    $moduleRulesPath = Join-Path $repoRoot "Module\AGENTS.md"
    if (-not (Test-Path -LiteralPath $moduleRulesPath -PathType Leaf)) {
        throw "缺少模块目录规范：Module/AGENTS.md"
    }
    $moduleRules = Get-Content -Raw -Encoding UTF8 -LiteralPath $moduleRulesPath
    foreach ($requiredRule in @(
        "actionAiCallableEnabled",
        "queryAiCallableEnabled",
        '契约不得声明静态 `aiCallable`',
        "`$fromAction",
        "不得在编译期或运行期直接导入",
        "逆序回滚"
    )) {
        if (-not $moduleRules.Contains($requiredRule)) {
            throw "Module/AGENTS.md 缺少规则：$requiredRule"
        }
    }
    # 关键契约和样例必须存在，随后同时验证合法样例通过、非法样例被拒绝。
    foreach ($requiredContract in @(
        "contracts\action-execution.schema.json",
        "contracts\samples\valid\action-execution.json",
        "contracts\samples\invalid\action-execution-empty.json",
        "contracts\ai-settings.schema.json",
        "contracts\samples\valid\ai-settings-deepseek.json",
        "contracts\samples\valid\ai-settings-custom.json",
        "contracts\samples\invalid\ai-settings-custom-missing-base.json",
        "contracts\samples\invalid\ai-settings-custom-missing-model.json",
        "contracts\ai-turn-request.schema.json",
        "contracts\ai-turn-response.schema.json",
        "contracts\ai-service-turn-request.schema.json",
        "contracts\ai-service-decision.schema.json",
        "contracts\ai-query-continuation-request.schema.json",
        "contracts\build-settings.schema.json",
        "contracts\host-shutdown-response.schema.json",
        "contracts\module-build-status.schema.json",
        "contracts\module-build-priority-request.schema.json",
        "contracts\module.schema.json",
        "contracts\samples\valid\ai-service-turn-request.json",
        "contracts\samples\valid\ai-service-decision-query.json",
        "contracts\samples\valid\ai-query-continuation-request.json",
        "contracts\samples\valid\build-settings.json",
        "contracts\samples\valid\host-shutdown-response.json",
        "contracts\samples\valid\module-build-status.json",
        "contracts\samples\valid\module-build-priority-request.json",
        "contracts\samples\valid\module-system-control.json",
        "contracts\samples\valid\module-system-service.json",
        "contracts\samples\invalid\ai-query-continuation-request-failed-payload.json",
        "contracts\samples\invalid\ai-service-decision-query-with-actions.json",
        "contracts\samples\invalid\build-settings-invalid-concurrency.json",
        "contracts\samples\invalid\host-shutdown-response-invalid-status.json",
        "contracts\samples\invalid\module-build-status-unsafe-source.json",
        "contracts\samples\invalid\module-build-priority-request-unsafe-source.json",
        "contracts\samples\invalid\module-system-invalid-role.json",
        "contracts\samples\invalid\module-system-protected-missing-prefix.json",
        "contracts\samples\invalid\module-system-reserved-prefix-business.json",
        "contracts\samples\invalid\module-system-service-governance.json"
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $requiredContract) -PathType Leaf)) {
            throw "缺少 Action 直执契约或样例：$requiredContract"
        }
    }
    foreach ($contractCase in @(
        @{ Schema = "contracts\ai-settings.schema.json"; Sample = "contracts\samples\valid\ai-settings-deepseek.json"; Expected = $true },
        @{ Schema = "contracts\ai-settings.schema.json"; Sample = "contracts\samples\valid\ai-settings-custom.json"; Expected = $true },
        @{ Schema = "contracts\ai-settings.schema.json"; Sample = "contracts\samples\invalid\ai-settings-custom-missing-base.json"; Expected = $false },
        @{ Schema = "contracts\ai-settings.schema.json"; Sample = "contracts\samples\invalid\ai-settings-custom-missing-model.json"; Expected = $false },
        @{ Schema = "contracts\ai-turn-request.schema.json"; Sample = "contracts\samples\valid\ai-turn-request.json"; Expected = $true },
        @{ Schema = "contracts\ai-turn-request.schema.json"; Sample = "contracts\samples\invalid\ai-turn-request-empty.json"; Expected = $false },
        @{ Schema = "contracts\ai-turn-response.schema.json"; Sample = "contracts\samples\valid\ai-turn-response-plan.json"; Expected = $true },
        @{ Schema = "contracts\ai-service-turn-request.schema.json"; Sample = "contracts\samples\valid\ai-service-turn-request.json"; Expected = $true },
        @{ Schema = "contracts\ai-service-turn-request.schema.json"; Sample = "contracts\samples\invalid\ai-service-turn-request-missing-plan.json"; Expected = $false },
        @{ Schema = "contracts\ai-service-decision.schema.json"; Sample = "contracts\samples\valid\ai-service-decision-query.json"; Expected = $true },
        @{ Schema = "contracts\ai-service-decision.schema.json"; Sample = "contracts\samples\invalid\ai-service-decision-query-five.json"; Expected = $false },
        @{ Schema = "contracts\ai-service-decision.schema.json"; Sample = "contracts\samples\invalid\ai-service-decision-query-with-actions.json"; Expected = $false },
        @{ Schema = "contracts\ai-query-continuation-request.schema.json"; Sample = "contracts\samples\valid\ai-query-continuation-request.json"; Expected = $true },
        @{ Schema = "contracts\ai-query-continuation-request.schema.json"; Sample = "contracts\samples\invalid\ai-query-continuation-request-failed-payload.json"; Expected = $false },
        @{ Schema = "contracts\build-settings.schema.json"; Sample = "contracts\samples\valid\build-settings.json"; Expected = $true },
        @{ Schema = "contracts\build-settings.schema.json"; Sample = "contracts\samples\invalid\build-settings-invalid-concurrency.json"; Expected = $false },
        @{ Schema = "contracts\host-shutdown-response.schema.json"; Sample = "contracts\samples\valid\host-shutdown-response.json"; Expected = $true },
        @{ Schema = "contracts\host-shutdown-response.schema.json"; Sample = "contracts\samples\invalid\host-shutdown-response-invalid-status.json"; Expected = $false },
        @{ Schema = "contracts\module-build-status.schema.json"; Sample = "contracts\samples\valid\module-build-status.json"; Expected = $true },
        @{ Schema = "contracts\module-build-status.schema.json"; Sample = "contracts\samples\invalid\module-build-status-unsafe-source.json"; Expected = $false },
        @{ Schema = "contracts\module-build-priority-request.schema.json"; Sample = "contracts\samples\valid\module-build-priority-request.json"; Expected = $true },
        @{ Schema = "contracts\module-build-priority-request.schema.json"; Sample = "contracts\samples\invalid\module-build-priority-request-unsafe-source.json"; Expected = $false },
        @{ Schema = "contracts\module.schema.json"; Sample = "contracts\samples\valid\module-system-control.json"; Expected = $true },
        @{ Schema = "contracts\module.schema.json"; Sample = "contracts\samples\valid\module-system-service.json"; Expected = $true },
        @{ Schema = "contracts\module.schema.json"; Sample = "contracts\samples\invalid\module-system-invalid-role.json"; Expected = $false },
        @{ Schema = "contracts\module.schema.json"; Sample = "contracts\samples\invalid\module-system-protected-missing-prefix.json"; Expected = $false },
        @{ Schema = "contracts\module.schema.json"; Sample = "contracts\samples\invalid\module-system-reserved-prefix-business.json"; Expected = $false },
        @{ Schema = "contracts\module.schema.json"; Sample = "contracts\samples\invalid\module-system-service-governance.json"; Expected = $false }
    )) {
        $schemaPath = Join-Path $repoRoot $contractCase.Schema
        $samplePath = Join-Path $repoRoot $contractCase.Sample
        $null = Get-Content -Raw -Encoding UTF8 -LiteralPath $samplePath
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            # Windows PowerShell 5.1 会把原生 stderr 与 Stop 偏好组合成可终止异常；这里只读取真实退出码。
            $ErrorActionPreference = "Continue"
            $validationOutput = (& $python $contractSampleValidator $schemaPath $samplePath 2>&1) |
                Out-String
            $validationExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($validationExitCode -ne 0 -and $validationExitCode -ne 1) {
            throw "契约样例校验器工具或 Schema 错误：$($contractCase.Sample)，退出码：$validationExitCode`n$($validationOutput.Trim())"
        }
        $actual = $validationExitCode -eq 0
        if ($actual -ne $contractCase.Expected) {
            throw "契约样例校验失败：$($contractCase.Sample) 期望 $($contractCase.Expected)，实际 $actual"
        }
    }

    $ignoreLines = @(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $repoRoot ".gitignore") |
        ForEach-Object { $_.Trim() })
    foreach ($requiredIgnoreLine in @(
        ".m2-repository/",
        "target/",
        ".orderly-data/",
        "~$*"
    )) {
        if ($ignoreLines -notcontains $requiredIgnoreLine) {
            throw ".gitignore 缺少规则：$requiredIgnoreLine"
        }
    }

    Write-Host "上下文门禁通过：语法、成功读取、边界拒绝、正文不泄漏、审计、仓库地图与忽略规则均成功。"

    Push-Location $aiRoot
    try {
        & $python -m compileall -q src tests
        if ($LASTEXITCODE -ne 0) {
            throw "Python AI 语法检查失败，退出码：$LASTEXITCODE"
        }
        & $python -m pytest
        if ($LASTEXITCODE -ne 0) {
            throw "Python AI 测试失败，退出码：$LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    $buildArguments = @{}
    if ($Offline) {
        $buildArguments.Offline = $true
    }
    if (-not [string]::IsNullOrWhiteSpace($MavenRepository)) {
        $buildArguments.MavenRepository = $MavenRepository
    }
    $buildArguments.MaxParallel = $MaxParallel
    & (Join-Path $PSScriptRoot "build.ps1") @buildArguments
    if ($LASTEXITCODE -ne 0) {
        throw "模块化构建失败，退出码：$LASTEXITCODE"
    }

    # 前端和每个模块 UI 脚本都做语法检查，避免只验证框架而遗漏外部模块资源。
    $sourceFiles = @("app.js", "ai-dialog.js", "ai-settings.js", "api.js", "models.js", "modules.js", "settings.js")
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

    $moduleJsTestPaths = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot "Module") -Directory -Filter "module_*" |
        ForEach-Object {
            $moduleJsTestRoot = Join-Path $_.FullName "src\test\js"
            if (Test-Path -LiteralPath $moduleJsTestRoot -PathType Container) {
                Get-ChildItem -LiteralPath $moduleJsTestRoot -Recurse -File -Filter "*.test.mjs"
            }
        } |
        Sort-Object FullName |
        ForEach-Object { $_.FullName })
    if ($moduleJsTestPaths.Count -gt 0) {
        & node --test @moduleJsTestPaths
        if ($LASTEXITCODE -ne 0) {
            throw "模块 Node 测试失败，退出码：$LASTEXITCODE"
        }
    }

    & node --test (Join-Path $repoRoot "app\host\src\test\js\*.test.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "模块 UI 契约样例校验失败，退出码：$LASTEXITCODE"
    }

    & node (Join-Path $repoRoot "scripts\validate-study-data.mjs")
    if ($LASTEXITCODE -ne 0) {
        throw "学习数据契约校验失败，退出码：$LASTEXITCODE"
    }

    # 以下 rg 扫描遵循同一退出语义：0 表示命中禁止模式，1 表示干净，其他值表示扫描工具本身异常。
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

    # 固定模块映射和固定业务端点扫描保护框架/模块边界；命中说明框架重新硬编码了具体业务。
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

    # 设置面板不应把整个内容区设为 live region，否则大量设置变化会制造噪声并干扰辅助技术。
    $globalPanelLive = & rg -n 'id="settings-panel"[^>]*aria-live' app/frontend/index.html
    if ($LASTEXITCODE -eq 0) {
        throw "设置面板仍使用整体 aria-live：`n$globalPanelLive"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "设置面板 live region 扫描异常，rg 退出码：$LASTEXITCODE"
    }

    # 主题只能提供声明式 CSS 和登记资源；脚本、!important 都可能绕过宿主安全层。
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

    # 只有此前所有命令均以预期状态完成，才会到达唯一成功出口。
    Write-Host "门禁通过：上下文约束、模块化 Maven 测试、契约样例、语法、前端与模块 Node 测试、目录主题边界、classpath 回退、基础动画、设置 live region 和样式变量检查均成功。"
}
finally {
    Pop-Location
}

exit 0
