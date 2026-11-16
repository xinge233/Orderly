[CmdletBinding()]
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
    param([int]$Count = 1)

    $script:CheckCount += $Count
}

function Assert-NoReparsePoint {
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

function Get-MarkdownPaths {
    $output = Invoke-GitText `
        -Purpose "读取 Git 跟踪及未跟踪未忽略 Markdown 清单" `
        -Arguments @(
            "-c",
            "core.quotepath=false",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            "*.md"
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

function Assert-NoMergeConflictMarkers {
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

    $targetRelativePath = [System.IO.Path]::GetRelativePath($repoRoot, $targetFullPath).Replace("\", "/")
    if (-not [string]::IsNullOrEmpty($targetRelativePath)) {
        Assert-NoReparsePoint -RelativePath $targetRelativePath
    }
    if (-not (Test-Path -LiteralPath $targetFullPath)) {
        throw "Markdown 本地链接目标不存在：$SourceRelativePath`:$LineNumber -> $RawTarget"
    }
    return $true
}

try {
    $requiredFiles = @(
        "AGENTS.md",
        "Module/AGENTS.md",
        "docs/agents/architecture-baseline.md",
        "docs/agents/collaboration-workflow.md",
        "docs/repo-map.md",
        "README.md",
        "scripts/doc-gate.ps1",
        "scripts/gate.ps1",
        "scripts/context.ps1",
        "contracts/action-execution.schema.json",
        "contracts/samples/valid/action-execution.json",
        "contracts/samples/invalid/action-execution-empty.json",
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
        "aiCallable",
        '$fromAction',
        "不得在编译期或运行期直接导入",
        "逆序回滚"
    )) {
        if (-not $moduleRules.Contains($sentinel)) {
            throw "Module/AGENTS.md 缺少关键规则：$sentinel"
        }
        Add-Check
    }

    $scriptFiles = @(Get-ChildItem `
        -LiteralPath (Join-Path $repoRoot "scripts") `
        -File `
        -Filter "*.ps1" |
        Sort-Object Name)
    if ($scriptFiles.Count -eq 0) {
        throw "scripts/ 下没有可解析的 PowerShell 脚本。"
    }
    foreach ($scriptFile in $scriptFiles) {
        $relativeScript = [System.IO.Path]::GetRelativePath(
            $repoRoot,
            $scriptFile.FullName
        ).Replace("\", "/")
        Assert-NoReparsePoint -RelativePath $relativeScript
        $tokens = $null
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            $scriptFile.FullName,
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

    $contextScript = Join-Path $repoRoot "scripts\context.ps1"
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

    Write-Output (
        "DOC GATE PASS checks=$script:CheckCount " +
        "scripts=$($scriptFiles.Count) " +
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
