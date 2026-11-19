#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   执行只读文档快速门禁：检查关键规则/契约文件、PowerShell 双版本兼容、上下文审计、仓库地图和 Markdown 链接。
#
# 输入：
#   Git 跟踪及未跟踪未忽略的 Markdown/.ps1、关键仓库文件、脚本本身和 .codex/config.toml 审计结果。
#
# 副作用：
#   不访问网络，不启动 Java、Maven 或 Node，不写运行数据；仅读取文件和调用只读 Git/PowerShell 命令。
#
# 安全边界：
#   所有读取限制在仓库内并拒绝重解析点；Markdown 只校验本地相对链接。发现语法、规则、链接或审计错误时
#   返回退出码 1，不能把部分检查通过当作整体通过。

param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repoRootPrefix = $repoRoot.TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
) + [System.IO.Path]::DirectorySeparatorChar
$powerShellExecutable = (Get-Process -Id $PID).Path
$script:CheckCount = 0

function Add-Check {
    # 统一累计检查数，使成功和失败摘要都能证明门禁实际运行过哪些检查。
    param([int]$Count = 1)

    $script:CheckCount += $Count
}

function Assert-NoReparsePoint {
    # 逐段拒绝重解析点，防止文档路径通过链接落到仓库外。
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $currentPath = $repoRoot
    foreach ($segment in @($RelativePath.Replace("\", "/") -split "/")) {
        if ([string]::IsNullOrEmpty($segment)) {
            continue
        }
        $currentPath = Join-Path $currentPath $segment
        if (-not (Test-Path -LiteralPath $currentPath)) {
            continue
        }
        $item = Get-Item -LiteralPath $currentPath -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "拒绝读取重解析点路径：$RelativePath"
        }
    }
}

function Read-TextFile {
    # 读取入口同时执行仓库边界、文件存在和 UTF-8 检查，调用方无需重复安全判断。
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $RelativePath))
    if (-not $fullPath.StartsWith($repoRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Purpose 路径越出仓库：$RelativePath"
    }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$Purpose 文件不存在：$RelativePath"
    }
    Assert-NoReparsePoint -RelativePath $RelativePath
    return [System.IO.File]::ReadAllText(
        $fullPath,
        [System.Text.UTF8Encoding]::new($false, $true)
    )
}

function Invoke-PowerShellScript {
    # 子进程隔离执行可消费其退出码，同时避免审计输出与当前脚本状态互相污染。
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [string[]]$Arguments = @()
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& $powerShellExecutable `
            -NoProfile `
            -NonInteractive `
            -File $ScriptPath @Arguments 2>&1) | Out-String
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

function Invoke-GitText {
    # Git 失败必须立即转成带上下文的异常，避免空输出被误判为“没有文件”或“没有匹配”。
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& git -C $repoRoot @Arguments 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        throw "$Purpose 失败，Git 退出码：$exitCode`n$($output.Trim())"
    }
    return $output
}

function Get-RepoRelativePath {
    # .NET Framework 没有 Path.GetRelativePath；通过已校验的仓库前缀生成跨 PowerShell 5.1/7 的相对路径。
    param([Parameter(Mandatory = $true)][string]$FullPath)

    $normalizedRoot = $repoRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $normalizedPath = [System.IO.Path]::GetFullPath($FullPath)
    if ($normalizedPath.Equals(
            $normalizedRoot,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        return ""
    }
    if (-not $normalizedPath.StartsWith(
            $repoRootPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "路径越出仓库：$FullPath"
    }
    return $normalizedPath.Substring($repoRootPrefix.Length).Replace("\", "/")
}

function Test-TrackedPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& git -C $repoRoot `
            --literal-pathspecs `
            ls-files `
            --error-unmatch `
            -- `
            $RelativePath 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -eq 0) {
        return $true
    }
    if ($exitCode -eq 1) {
        return $false
    }
    throw "无法检查 Markdown 跟踪状态：$RelativePath`n$($output.Trim())"
}

function Get-WorkingTreePaths {
    # 使用 NUL 分隔可安全处理空格和特殊字符；同时包含已跟踪和未跟踪未忽略文件。
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $output = Invoke-GitText `
        -Purpose $Purpose `
        -Arguments @(
            "-c",
            "core.quotepath=false",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            $Pattern
        )
    $trimmed = $output.TrimEnd([char[]]@([char]0, [char]13, [char]10))
    if ([string]::IsNullOrEmpty($trimmed)) {
        return @()
    }
    return @($trimmed -split "`0" |
        Where-Object { -not [string]::IsNullOrEmpty($_) } |
        ForEach-Object { $_.Replace("\", "/") } |
        Sort-Object -Unique)
}

function Get-MarkdownPaths {
    return @(Get-WorkingTreePaths `
        -Pattern "*.md" `
        -Purpose "读取 Git 跟踪及未跟踪未忽略 Markdown 清单")
}

function Get-PowerShellPaths {
    return @(Get-WorkingTreePaths `
        -Pattern "*.ps1" `
        -Purpose "读取 Git 跟踪及未跟踪未忽略 PowerShell 清单")
}

function Test-Utf8Bom {
    param([Parameter(Mandatory = $true)][string]$FullPath)

    $bytes = [System.IO.File]::ReadAllBytes($FullPath)
    return $bytes.Length -ge 3 -and
        $bytes[0] -eq 0xEF -and
        $bytes[1] -eq 0xBB -and
        $bytes[2] -eq 0xBF
}

function Assert-NoMergeConflictMarkers {
    # 冲突分隔符需要结合开标记判断，降低普通表格或标题被误报的概率。
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )

    $hasOpeningMarker = [regex]::IsMatch($Text, "(?m)^<{7,}(?:\s.*)?$")
    $lineNumber = 0
    foreach ($line in @($Text -split "\r?\n")) {
        $lineNumber++
        if ($line -match "^<{7,}(?:\s.*)?$" -or
                $line -match "^>{7,}(?:\s.*)?$") {
            throw "Markdown 含合并冲突标记：$RelativePath`:$lineNumber"
        }
        if ($hasOpeningMarker -and (
                $line -match "^={7,}(?:\s.*)?$" -or
                $line -match "^\|{7,}(?:\s.*)?$"
            )) {
            throw "Markdown 含合并冲突标记：$RelativePath`:$lineNumber"
        }
    }
}

function Get-MarkdownLinkTargets {
    # 跳过围栏代码和行内代码，只提取真实 Markdown 链接，减少示例语法造成的假失败。
    param([Parameter(Mandatory = $true)][string]$Text)

    $targets = [System.Collections.Generic.List[object]]::new()
    $lineNumber = 0
    $fenceCharacter = [char]0
    $fenceLength = 0

    foreach ($line in @($Text -split "\r?\n")) {
        $lineNumber++
        $fenceMatch = [regex]::Match($line, '^\s{0,3}(`{3,}|~{3,})')
        if ($fenceMatch.Success) {
            $marker = $fenceMatch.Groups[1].Value
            if ($fenceCharacter -eq [char]0) {
                $fenceCharacter = $marker[0]
                $fenceLength = $marker.Length
            }
            elseif ($marker[0] -eq $fenceCharacter -and $marker.Length -ge $fenceLength) {
                $fenceCharacter = [char]0
                $fenceLength = 0
            }
            continue
        }
        if ($fenceCharacter -ne [char]0) {
            continue
        }

        $lineWithoutInlineCode = [regex]::Replace($line, '`[^`]*`', "")
        foreach ($match in [regex]::Matches(
                $lineWithoutInlineCode,
                '!?\[[^\]]*\]\(\s*(<[^>\r\n]+>|[^)\r\n]+?)\s*\)'
            )) {
            $targets.Add([pscustomobject]@{
                Target = $match.Groups[1].Value
                Line = $lineNumber
            })
        }

        $definitionMatch = [regex]::Match(
            $lineWithoutInlineCode,
            '^\s{0,3}\[[^\]]+\]:\s*(<[^>\r\n]+>|\S+)'
        )
        if ($definitionMatch.Success) {
            $targets.Add([pscustomobject]@{
                Target = $definitionMatch.Groups[1].Value
                Line = $lineNumber
            })
        }
    }

    return $targets
}

function Get-NormalizedLinkDestination {
    # 外部 URL、锚点和绝对系统路径不归本地链接门禁处理；其余目标在调用方做仓库边界校验。
    param([Parameter(Mandatory = $true)][string]$RawTarget)

    $target = $RawTarget.Trim()
    if ($target.StartsWith("<") -and $target.EndsWith(">")) {
        $target = $target.Substring(1, $target.Length - 2).Trim()
    }
    else {
        $titleMatch = [regex]::Match(
            $target,
            '^(?<target>\S+)(?:\s+(?:"[^"]*"|''[^'']*''|\([^)]*\)))?\s*$'
        )
        if ($titleMatch.Success) {
            $target = $titleMatch.Groups["target"].Value
        }
        else {
            $target = ($target -split "\s+", 2)[0]
        }
    }

    $target = [regex]::Replace($target, '\\([\\() ])', '$1')
    if ([string]::IsNullOrWhiteSpace($target) -or
            $target.StartsWith("#") -or
            $target.StartsWith("//") -or
            $target.StartsWith("/") -or
            $target.StartsWith("\") -or
            $target.StartsWith("~") -or
            $target -match "^[A-Za-z][A-Za-z0-9+.-]*:") {
        return $null
    }

    $pathPart = ($target -split "[?#]", 2)[0]
    if ([string]::IsNullOrWhiteSpace($pathPart)) {
        return $null
    }
    return [System.Uri]::UnescapeDataString($pathPart)
}

function Assert-LocalMarkdownLink {
    # 解析后必须仍位于仓库内，目标可以不存在于 Git 清单中，但磁盘路径必须真实存在。
    param(
        [Parameter(Mandatory = $true)][string]$SourceRelativePath,
        [Parameter(Mandatory = $true)][string]$RawTarget,
        [Parameter(Mandatory = $true)][int]$LineNumber
    )

    $destination = Get-NormalizedLinkDestination -RawTarget $RawTarget
    if ($null -eq $destination) {
        return $false
    }

    $sourceFullPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $SourceRelativePath))
    $sourceDirectory = Split-Path -Parent $sourceFullPath
    try {
        $targetFullPath = [System.IO.Path]::GetFullPath((Join-Path $sourceDirectory $destination))
    }
    catch {
        throw "Markdown 本地链接路径非法：$SourceRelativePath`:$LineNumber -> $RawTarget"
    }
    if (-not $targetFullPath.StartsWith(
            $repoRootPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "Markdown 本地链接越出仓库：$SourceRelativePath`:$LineNumber -> $RawTarget"
    }

    $targetRelativePath = Get-RepoRelativePath -FullPath $targetFullPath
    if (-not [string]::IsNullOrEmpty($targetRelativePath)) {
        Assert-NoReparsePoint -RelativePath $targetRelativePath
    }
    if (-not (Test-Path -LiteralPath $targetFullPath)) {
        throw "Markdown 本地链接目标不存在：$SourceRelativePath`:$LineNumber -> $RawTarget"
    }
    return $true
}

try {
    # 关键文件、规则和角色卡是架构与验证链的最小事实集；缺失时不能继续给出绿色文档门禁。
    $requiredFiles = @(
        "AGENTS.md",
        "Module/AGENTS.md",
        ".codex/agents/project-manager.toml",
        ".codex/agents/software-engineer.toml",
        ".codex/agents/module-design-lead/AGENTS.md",
        ".codex/agents/ui-design-lead/agent.toml",
        ".codex/agents/ui-design-lead/README.md",
        "docs/agents/architecture-baseline.md",
        "docs/agents/collaboration-workflow.md",
        "docs/repo-map.md",
        "README.md",
        "scripts/doc-gate.ps1",
        "scripts/gate.ps1",
        "scripts/context.ps1",
        "scripts/validate-contract-samples.py",
        "contracts/action-execution.schema.json",
        "contracts/samples/valid/action-execution.json",
        "contracts/samples/invalid/action-execution-empty.json",
        "contracts/ai-settings.schema.json",
        "contracts/ai-turn-request.schema.json",
        "contracts/ai-turn-response.schema.json",
        ".gitignore"
    )
    foreach ($requiredFile in $requiredFiles) {
        $fullPath = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $requiredFile))
        Assert-NoReparsePoint -RelativePath $requiredFile
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "缺少关键文件：$requiredFile"
        }
        Add-Check
    }

    $rootRules = Read-TextFile -RelativePath "AGENTS.md" -Purpose "根规则"
    foreach ($sentinel in @(
        "风险先于执行",
        "物理删除永不出现",
        "框架与模块分离"
    )) {
        if (-not $rootRules.Contains($sentinel)) {
            throw "AGENTS.md 缺少关键规则：$sentinel"
        }
        Add-Check
    }

    $moduleRules = Read-TextFile -RelativePath "Module/AGENTS.md" -Purpose "模块规则"
    foreach ($sentinel in @(
        "actionAiCallableEnabled",
        "queryAiCallableEnabled",
        '契约不得声明静态 `aiCallable`',
        '$fromAction',
        "不得在编译期或运行期直接导入",
        "逆序回滚"
    )) {
        if (-not $moduleRules.Contains($sentinel)) {
            throw "Module/AGENTS.md 缺少关键规则：$sentinel"
        }
        Add-Check
    }

    # 所有跟踪及未跟踪未忽略 .ps1 都要在两种解释器实际执行前通过 BOM、AST 和兼容 API 扫描。
    $scriptPaths = @(Get-PowerShellPaths)
    if ($scriptPaths.Count -eq 0) {
        throw "Git PowerShell 清单为空。"
    }
    $requiresScripts = @(
        "scripts/context.ps1",
        "scripts/doc-gate.ps1",
        "scripts/gate.ps1",
        "scripts/start.ps1",
        "ai/scripts/ensure-env.ps1",
        "ai/scripts/start.ps1"
    )
    $requiresScriptLookup = @{}
    foreach ($requiresScript in $requiresScripts) {
        $requiresScriptLookup[$requiresScript] = $true
    }
    $forbiddenPowerShellTokens = @(
        [pscustomobject]@{
            Token = "[System.IO.Path]::Get" + "RelativePath"
            Reason = ".NET Core 相对路径 API"
        },
        [pscustomobject]@{
            Token = "[System.Security.Cryptography.RandomNumberGenerator]::" + "Fill"
            Reason = ".NET Core 随机数 API"
        },
        [pscustomobject]@{
            Token = "Test-" + "Json"
            Reason = "PowerShell 7 专属 JSON cmdlet"
        }
    )
    foreach ($relativeScript in $scriptPaths) {
        $scriptFullPath = [System.IO.Path]::GetFullPath(
            (Join-Path $repoRoot $relativeScript)
        )
        Assert-NoReparsePoint -RelativePath $relativeScript
        if (-not (Test-Path -LiteralPath $scriptFullPath -PathType Leaf)) {
            throw "PowerShell 清单中的文件不存在：$relativeScript"
        }
        if (-not (Test-Utf8Bom -FullPath $scriptFullPath)) {
            throw "PowerShell 脚本必须保存为 UTF-8 BOM：$relativeScript"
        }
        Add-Check

        $scriptText = Read-TextFile `
            -RelativePath $relativeScript `
            -Purpose "PowerShell"
        foreach ($forbiddenToken in $forbiddenPowerShellTokens) {
            if ($scriptText.IndexOf(
                    $forbiddenToken.Token,
                    [System.StringComparison]::OrdinalIgnoreCase
                ) -ge 0) {
                throw "PowerShell 脚本包含非 5.1 兼容 API（$($forbiddenToken.Reason)）：$relativeScript"
            }
        }

        if ($requiresScriptLookup.ContainsKey($relativeScript)) {
            $withoutBom = $scriptText.TrimStart([char]0xFEFF)
            if (-not [regex]::IsMatch(
                    $withoutBom,
                    "(?i)^#requires\s+-Version\s+5\.1(?:\s|$)"
                )) {
                throw "PowerShell 脚本首行缺少 #requires -Version 5.1：$relativeScript"
            }
            Add-Check
        }

        $tokens = $null
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            $scriptFullPath,
            [ref]$tokens,
            [ref]$parseErrors
        ) | Out-Null
        if ($parseErrors.Count -ne 0) {
            $details = @($parseErrors | ForEach-Object {
                "第 $($_.Extent.StartLineNumber) 行：$($_.Message)"
            }) -join "`n"
            throw "PowerShell AST 语法解析失败：$relativeScript`n$details"
        }
        Add-Check
    }
    foreach ($requiresScript in $requiresScripts) {
        if (-not ($scriptPaths -contains $requiresScript)) {
            throw "PowerShell 清单缺少兼容入口：$requiresScript"
        }
    }

    $contextScript = Join-Path $repoRoot "scripts\context.ps1"
    # 上下文工具审计独立运行，确保受限读取配置本身没有被后续脚本修改破坏。
    $contextAudit = Invoke-PowerShellScript `
        -ScriptPath $contextScript `
        -Arguments @("-Mode", "audit")
    Write-Output $contextAudit.Output.TrimEnd()
    if ($contextAudit.ExitCode -ne 0) {
        throw "context.ps1 -Mode audit 失败，退出码：$($contextAudit.ExitCode)"
    }
    if (-not $contextAudit.Output.Contains("AUDIT PASS")) {
        throw "context.ps1 -Mode audit 未返回 AUDIT PASS。"
    }
    Add-Check

    $repoMapText = Read-TextFile -RelativePath "docs/repo-map.md" -Purpose "仓库地图"
    $repoMapLineNumber = 0
    foreach ($line in @($repoMapText -split "\r?\n")) {
        $repoMapLineNumber++
        if ($line -match '^\s{0,3}(`{3,}|~{3,})') {
            throw "docs/repo-map.md 含 Markdown 代码围栏，第 $repoMapLineNumber 行。"
        }
    }
    Add-Check

    $readmeText = Read-TextFile -RelativePath "README.md" -Purpose "README"
    foreach ($sentinel in @(
        "Windows PowerShell 5.1",
        "PowerShell 7+"
    )) {
        if (-not $readmeText.Contains($sentinel)) {
            throw "README.md 缺少 PowerShell 双兼容说明：$sentinel"
        }
        Add-Check
    }
    foreach ($registration in @(
        [pscustomobject]@{
            Path = "README.md"
            Text = $readmeText
        },
        [pscustomobject]@{
            Path = "docs/repo-map.md"
            Text = $repoMapText
        }
    )) {
        $normalizedRegistrationText = $registration.Text.Replace("\", "/")
        if (-not $normalizedRegistrationText.Contains("scripts/doc-gate.ps1")) {
            throw "$($registration.Path) 未登记 scripts/doc-gate.ps1。"
        }
        Add-Check
    }

    # 仓库地图声明的 scripts/*.ps1 必须存在，防止验证入口指向已删除或改名的脚本。
    $repoMapScriptReferences = @([regex]::Matches(
        $repoMapText,
        "\.\\scripts\\([A-Za-z0-9._-]+\.ps1)"
    ) | ForEach-Object {
        $_.Groups[1].Value
    } | Sort-Object -Unique)
    if ($repoMapScriptReferences.Count -eq 0) {
        throw "docs/repo-map.md 未登记任何 .\\scripts\\*.ps1 命令。"
    }
    foreach ($scriptName in $repoMapScriptReferences) {
        $scriptPath = Join-Path $repoRoot "scripts\$scriptName"
        if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
            throw "docs/repo-map.md 引用的脚本不存在：$scriptName"
        }
        Add-Check
    }

    # 最后一轮同时扫描合并冲突和本地链接；已跟踪但工作区删除的普通 Markdown 只跳过正文。
    $markdownPaths = @(Get-MarkdownPaths)
    if ($markdownPaths.Count -eq 0) {
        throw "Git Markdown 清单为空。"
    }
    $linkCount = 0
    $deletedMarkdownCount = 0
    foreach ($markdownPath in $markdownPaths) {
        $markdownFullPath = [System.IO.Path]::GetFullPath(
            (Join-Path $repoRoot $markdownPath)
        )
        if (-not $markdownFullPath.StartsWith(
                $repoRootPrefix,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
            throw "Markdown 路径越出仓库：$markdownPath"
        }
        if (-not (Test-Path -LiteralPath $markdownFullPath -PathType Leaf)) {
            if (Test-TrackedPath -RelativePath $markdownPath) {
                $deletedMarkdownCount++
                Add-Check
                continue
            }
            throw "Markdown 清单中的未跟踪文件不存在：$markdownPath"
        }

        $markdownText = Read-TextFile `
            -RelativePath $markdownPath `
            -Purpose "Markdown"
        Assert-NoMergeConflictMarkers `
            -Text $markdownText `
            -RelativePath $markdownPath
        Add-Check

        foreach ($linkTarget in @(Get-MarkdownLinkTargets -Text $markdownText)) {
            if (Assert-LocalMarkdownLink `
                    -SourceRelativePath $markdownPath `
                    -RawTarget $linkTarget.Target `
                    -LineNumber $linkTarget.Line) {
                $linkCount++
                Add-Check
            }
        }
    }

    # 成功摘要列出关键计数；任何异常都会落入 catch 并转换为退出码 1。
    Write-Output (
        "DOC GATE PASS checks=$script:CheckCount " +
        "scripts=$($scriptPaths.Count) " +
        "powerShellCompatibility=ast+bom+requires " +
        "keyFiles=$($requiredFiles.Count) " +
        "rules=7 " +
        "markdownFiles=$($markdownPaths.Count) " +
        "deletedMarkdownSkipped=$deletedMarkdownCount " +
        "localLinks=$linkCount " +
        "repoMapScripts=$($repoMapScriptReferences.Count) " +
        "contextAudit=exit0"
    )
    Write-Output "DOC GATE PASS readonly=true network=false java=false maven=false node=false runtimeWrites=false"
    exit 0
}
catch {
    [Console]::Error.WriteLine("DOC GATE FAIL checks=$script:CheckCount")
    [Console]::Error.WriteLine("DOC GATE FAIL: $($_.Exception.Message)")
    exit 1
}
