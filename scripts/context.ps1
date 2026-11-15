[CmdletBinding()]
param(
    [ValidateSet("file", "map", "files", "search", "diff", "audit")]
    [string]$Mode,

    [string]$Path,

    [ValidatePattern("^[A-Za-z0-9._+-]+$")]
    [string]$Kind,

    [string]$Pattern,

    [ValidateRange(1, 1000000)]
    [int]$Start = 1,

    [ValidateRange(0, 1000000)]
    [int]$Count = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:MaxFileBytes = 2MB
$script:MaxFileLines = 500
$script:MaxSearchResults = 200
$script:MaxFileResults = 500
$script:ExcludedDirectories = @(".m2-repository", ".orderly-data", "target")

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = @(& git -C $script:RepoRoot @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($output | ForEach-Object { $_.ToString() })
    }
}

function Initialize-Repository {
    $candidateRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $rootOutput = @(& git -C $candidateRoot rev-parse --show-toplevel 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0 -or $rootOutput.Count -eq 0) {
        throw "无法确定 Git 仓库根目录。"
    }
    $script:RepoRoot = [System.IO.Path]::GetFullPath(($rootOutput[-1].ToString()).Trim())
    $script:RepoRootPrefix = $script:RepoRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
}

function Convert-ToRepoRelativePath {
    param([Parameter(Mandatory = $true)][string]$FullPath)

    $normalizedRoot = $script:RepoRoot.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $relative = $FullPath.Substring($normalizedRoot.Length).TrimStart(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    return $relative.Replace([System.IO.Path]::DirectorySeparatorChar, "/")
}

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$InputPath)

    if ([string]::IsNullOrWhiteSpace($InputPath)) {
        throw "路径不能为空。"
    }

    $fullPath = if ([System.IO.Path]::IsPathRooted($InputPath)) {
        [System.IO.Path]::GetFullPath($InputPath)
    }
    else {
        [System.IO.Path]::GetFullPath((Join-Path $script:RepoRoot $InputPath))
    }
    if (-not $fullPath.StartsWith($script:RepoRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝仓库外路径：$InputPath"
    }

    return [pscustomobject]@{
        FullPath = $fullPath
        RelativePath = Convert-ToRepoRelativePath $fullPath
    }
}

function Test-ExcludedPath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $segments = @($RelativePath -split "/")
    foreach ($segment in $segments) {
        if ($script:ExcludedDirectories -contains $segment) {
            return $true
        }
    }
    return $false
}

function Test-IsIgnored {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    # check-ignore takes literal pathnames and rejects --literal-pathspecs.
    $result = Invoke-Git -Arguments @("check-ignore", "-q", "--", $RelativePath)
    if ($result.ExitCode -eq 0) {
        return $true
    }
    if ($result.ExitCode -eq 1) {
        return $false
    }
    throw "无法检查忽略状态：$RelativePath"
}

function Test-IsTracked {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $result = Invoke-Git -Arguments @(
        "--literal-pathspecs",
        "ls-files",
        "--error-unmatch",
        "--",
        $RelativePath
    )
    if ($result.ExitCode -eq 0) {
        return $true
    }
    if ($result.ExitCode -eq 1) {
        return $false
    }
    throw "无法检查 Git 跟踪状态：$RelativePath"
}

function Assert-NoReparsePoint {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $currentPath = $script:RepoRoot
    foreach ($segment in @($RelativePath -split "/")) {
        if ([string]::IsNullOrEmpty($segment)) {
            continue
        }
        $currentPath = Join-Path $currentPath $segment
        if (-not (Test-Path -LiteralPath $currentPath)) {
            continue
        }
        $item = Get-Item -LiteralPath $currentPath -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "拒绝重解析点路径：$RelativePath"
        }
    }
}

function Get-SafeTrackedFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InputPath
    )

    $resolved = Resolve-RepoPath -InputPath $InputPath
    if (Test-ExcludedPath -RelativePath $resolved.RelativePath) {
        throw "拒绝忽略目录中的文件：$($resolved.RelativePath)"
    }
    if (Test-IsIgnored -RelativePath $resolved.RelativePath) {
        throw "拒绝忽略文件：$($resolved.RelativePath)"
    }
    if (-not (Test-IsTracked -RelativePath $resolved.RelativePath)) {
        throw "拒绝未跟踪文件：$($resolved.RelativePath)"
    }
    if (-not (Test-Path -LiteralPath $resolved.FullPath -PathType Leaf)) {
        if (Test-Path -LiteralPath $resolved.FullPath -PathType Container) {
            throw "拒绝目录，file 模式只接受文件：$($resolved.RelativePath)"
        }
        throw "文件不存在：$($resolved.RelativePath)"
    }

    Assert-NoReparsePoint -RelativePath $resolved.RelativePath
    $fileSize = (Get-Item -LiteralPath $resolved.FullPath -Force).Length
    if ($fileSize -gt $script:MaxFileBytes) {
        throw "拒绝超限文件：$($resolved.RelativePath)，$fileSize 字节，上限 $($script:MaxFileBytes) 字节。"
    }

    $stream = [System.IO.File]::Open(
        $resolved.FullPath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    try {
        $buffer = New-Object byte[] 8192
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            for ($index = 0; $index -lt $read; $index++) {
                if ($buffer[$index] -eq 0) {
                    throw "拒绝二进制文件：$($resolved.RelativePath)"
                }
            }
        }
    }
    finally {
        $stream.Dispose()
    }

    return [pscustomobject]@{
        FullPath = $resolved.FullPath
        RelativePath = $resolved.RelativePath
        Bytes = $fileSize
    }
}

function Format-DisplayText {
    param([AllowNull()][string]$Text)

    if ($null -eq $Text) {
        return ""
    }
    $builder = [System.Text.StringBuilder]::new($Text.Length)
    foreach ($character in $Text.ToCharArray()) {
        $code = [int][char]$character
        if (($code -lt 32 -and $code -ne 9) -or $code -eq 127) {
            [void]$builder.Append("?")
        }
        else {
            [void]$builder.Append($character)
        }
    }
    return $builder.ToString()
}

function Read-TextLines {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$File,
        [Parameter(Mandatory = $true)]
        [int]$LineStart,
        [Parameter(Mandatory = $true)]
        [int]$LineCount
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    $reader = [System.IO.StreamReader]::new(
        $File.FullPath,
        [System.Text.UTF8Encoding]::new($false, $true),
        $true
    )
    try {
        while ($null -ne ($line = $reader.ReadLine())) {
            $lines.Add($line)
        }
    }
    catch [System.Text.DecoderFallbackException] {
        throw "拒绝无法按 UTF-8 读取的文本文件：$($File.RelativePath)"
    }
    finally {
        $reader.Dispose()
    }

    $total = $lines.Count
    $lastRequested = [Math]::Min($total, $LineStart + $LineCount - 1)
    if ($total -eq 0 -or $LineStart -gt $total) {
        Write-Output "FILE path=$($File.RelativePath) bytes=$($File.Bytes) lines=none/$total"
        return
    }

    Write-Output "FILE path=$($File.RelativePath) bytes=$($File.Bytes) lines=$LineStart-$lastRequested/$total"
    for ($index = $LineStart - 1; $index -lt $lastRequested; $index++) {
        Write-Output ("{0,6}: {1}" -f ($index + 1), (Format-DisplayText $lines[$index]))
    }
}

function Read-TrackedText {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $file = Get-SafeTrackedFile -InputPath $RelativePath
    return [System.IO.File]::ReadAllText(
        $file.FullPath,
        [System.Text.UTF8Encoding]::new($false, $true)
    )
}

function Get-TrackedRelativePaths {
    $result = Invoke-Git -Arguments @("-c", "core.quotepath=false", "ls-files")
    if ($result.ExitCode -ne 0) {
        throw "无法读取 Git 跟踪文件清单。"
    }

    $paths = @($result.Lines |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_.Replace("\", "/") } |
        Where-Object { -not (Test-ExcludedPath -RelativePath $_) } |
        Sort-Object)
    foreach ($relativePath in $paths) {
        Assert-NoReparsePoint -RelativePath $relativePath
    }
    return $paths
}

function Test-KindMatch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath,
        [AllowNull()]
        [string]$RequestedKind
    )

    if ([string]::IsNullOrWhiteSpace($RequestedKind)) {
        return $true
    }
    $extension = [System.IO.Path]::GetExtension($RelativePath).TrimStart(".").ToLowerInvariant()
    $normalizedKind = $RequestedKind.Trim().TrimStart(".").ToLowerInvariant()
    return $extension -eq $normalizedKind
}

function Resolve-Mode {
    if ([string]::IsNullOrWhiteSpace($Mode)) {
        if (-not [string]::IsNullOrWhiteSpace($Path)) {
            return "file"
        }
        return "map"
    }
    return $Mode
}

function Invoke-FileMode {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "file 模式需要 -Path。"
    }
    if (-not [string]::IsNullOrWhiteSpace($Pattern) -or
            -not [string]::IsNullOrWhiteSpace($Kind)) {
        throw "file 模式不接受 -Pattern 或 -Kind。"
    }

    $lineCount = if ($Count -eq 0) { 200 } else { $Count }
    if ($lineCount -gt $script:MaxFileLines) {
        throw "请求超限：file 模式的 -Count 最大为 $($script:MaxFileLines)。"
    }
    $file = Get-SafeTrackedFile -InputPath $Path
    Read-TextLines -File $file -LineStart $Start -LineCount $lineCount
}

function Invoke-MapMode {
    $mapPath = if ([string]::IsNullOrWhiteSpace($Path)) { "docs/repo-map.md" } else { $Path }
    $resolvedMap = Resolve-RepoPath -InputPath $mapPath
    if ($resolvedMap.RelativePath -ne "docs/repo-map.md") {
        throw "map 模式只允许读取 docs/repo-map.md。"
    }
    if (-not [string]::IsNullOrWhiteSpace($Pattern) -or
            -not [string]::IsNullOrWhiteSpace($Kind)) {
        throw "map 模式不接受 -Pattern 或 -Kind。"
    }

    $lineCount = if ($Count -eq 0) { 200 } else { $Count }
    if ($lineCount -gt 200) {
        throw "请求超限：map 模式的 -Count 最大为 200。"
    }
    $file = Get-SafeTrackedFile -InputPath $mapPath
    Read-TextLines -File $file -LineStart $Start -LineCount $lineCount
}

function Invoke-FilesMode {
    if (-not [string]::IsNullOrWhiteSpace($Pattern)) {
        throw "files 模式不接受 -Pattern；请使用 search 模式。"
    }

    $resultLimit = if ($Count -eq 0) { 200 } else { $Count }
    if ($resultLimit -gt $script:MaxFileResults) {
        throw "请求超限：files 模式的 -Count 最大为 $($script:MaxFileResults)。"
    }

    $files = @(Get-TrackedRelativePaths | Where-Object {
        (Test-KindMatch -RelativePath $_ -RequestedKind $Kind)
    })
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $exactFile = Get-SafeTrackedFile -InputPath $Path
        $files = @($files | Where-Object { $_ -eq $exactFile.RelativePath })
    }

    $total = $files.Count
    $selected = @($files | Select-Object -Skip ($Start - 1) -First $resultLimit)
    $lastReturned = if ($selected.Count -eq 0) { 0 } else { $Start + $selected.Count - 1 }
    Write-Output "FILES total=$total range=$Start-$lastReturned kind=$(if ([string]::IsNullOrWhiteSpace($Kind)) { "all" } else { $Kind })"
    foreach ($relativePath in $selected) {
        Write-Output $relativePath
    }
}

function Invoke-SearchMode {
    if ([string]::IsNullOrWhiteSpace($Pattern)) {
        throw "search 模式需要 -Pattern。"
    }
    if ($Pattern.Length -gt 200) {
        throw "请求超限：search 模式的 -Pattern 最大为 200 个字符。"
    }

    $resultLimit = if ($Count -eq 0) { 50 } else { $Count }
    if ($resultLimit -gt $script:MaxSearchResults) {
        throw "请求超限：search 模式的 -Count 最大为 $($script:MaxSearchResults)。"
    }

    $searchPaths = @(Get-TrackedRelativePaths | Where-Object {
        Test-KindMatch -RelativePath $_ -RequestedKind $Kind
    })
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $exactFile = Get-SafeTrackedFile -InputPath $Path
        $searchPaths = @($searchPaths | Where-Object { $_ -eq $exactFile.RelativePath })
    }
    if ($searchPaths.Count -eq 0) {
        Write-Output "SEARCH pattern=$Pattern matches=0 truncated=false"
        return
    }

    $arguments = @(
        "grep",
        "-n",
        "-I",
        "--fixed-strings",
        "-e",
        $Pattern,
        "--"
    ) + $searchPaths
    $matches = @(& git -C $script:RepoRoot @arguments 2>&1 |
        Select-Object -First ($resultLimit + 1) |
        ForEach-Object { $_.ToString() })
    $gitExitCode = $LASTEXITCODE
    if ($matches.Count -eq 0) {
        if ($gitExitCode -eq 0 -or $gitExitCode -eq 1) {
            Write-Output "SEARCH pattern=$Pattern matches=0 truncated=false"
            return
        }
        throw "Git 搜索失败，退出码：$gitExitCode"
    }

    $truncated = $matches.Count -gt $resultLimit
    $displayed = @($matches | Select-Object -First $resultLimit)
    Write-Output "SEARCH pattern=$Pattern matches=$($displayed.Count) truncated=$($truncated.ToString().ToLowerInvariant())"
    foreach ($match in $displayed) {
        Write-Output (Format-DisplayText $match)
    }
}

function Invoke-DiffMode {
    if (-not [string]::IsNullOrWhiteSpace($Pattern) -or
            -not [string]::IsNullOrWhiteSpace($Kind)) {
        throw "diff 模式不接受 -Pattern 或 -Kind。"
    }

    $arguments = @("diff", "--stat", "--no-ext-diff", "--no-textconv", "HEAD", "--")
    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $file = Get-SafeTrackedFile -InputPath $Path
        $arguments += $file.RelativePath
    }
    $result = Invoke-Git -Arguments $arguments
    if ($result.ExitCode -ne 0) {
        throw "Git 差异统计失败，退出码：$($result.ExitCode)"
    }

    Write-Output "DIFF STAT"
    foreach ($line in $result.Lines) {
        Write-Output (Format-DisplayText $line)
    }
}

function Test-ConfigEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $pattern = "(?m)^\s*" +
        [regex]::Escape($Key) +
        "\s*=\s*" +
        [regex]::Escape($Value) +
        "\s*$"
    return [regex]::Matches($Text, $pattern).Count -eq 1
}

function Get-ConfigKeyCount {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Key
    )

    $pattern = "(?m)^\s*" + [regex]::Escape($Key) + "\s*="
    return [regex]::Matches($Text, $pattern).Count
}

function Invoke-AuditMode {
    $issues = [System.Collections.Generic.List[string]]::new()

    try {
        $config = Read-TrackedText -RelativePath ".codex/config.toml"
        $requiredConfig = [ordered]@{
            web_search = '"live"'
            sandbox_mode = '"danger-full-access"'
            approval_policy = '"never"'
        }
        $optionalConfig = [ordered]@{
            tool_output_token_limit = "10000"
            model_auto_compact_token_limit = "90000"
            model_auto_compact_token_limit_scope = '"total"'
        }
        foreach ($entry in $requiredConfig.GetEnumerator()) {
            $keyCount = Get-ConfigKeyCount -Text $config -Key $entry.Key
            if ($keyCount -eq 0) {
                $issues.Add("必需配置键缺失：$($entry.Key)")
            }
            elseif ($keyCount -gt 1) {
                $issues.Add("必需配置键重复：$($entry.Key)")
            }
            elseif (-not (Test-ConfigEntry -Text $config -Key $entry.Key -Value $entry.Value)) {
                $issues.Add("必需配置键取值不符：$($entry.Key)")
            }
        }
        foreach ($entry in $optionalConfig.GetEnumerator()) {
            $keyCount = Get-ConfigKeyCount -Text $config -Key $entry.Key
            if ($keyCount -eq 0) {
                continue
            }
            if ($keyCount -ne 1) {
                $issues.Add("可选配置键重复：$($entry.Key)")
            }
            elseif (-not (Test-ConfigEntry -Text $config -Key $entry.Key -Value $entry.Value)) {
                $issues.Add("可选配置键取值不符：$($entry.Key)")
            }
        }
    }
    catch {
        $issues.Add("配置检查失败：$($_.Exception.Message)")
    }

    try {
        $mapFile = Get-SafeTrackedFile -InputPath "docs/repo-map.md"
        $mapLines = @([System.IO.File]::ReadAllLines(
            $mapFile.FullPath,
            [System.Text.UTF8Encoding]::new($false, $true)
        ))
        if ($mapLines.Count -lt 100 -or $mapLines.Count -gt 160) {
            $issues.Add("repo-map 行数应为 100-160，实际 $($mapLines.Count)")
        }
        if (($mapLines -join "`n").Contains("``````")) {
            $issues.Add("repo-map 不得包含代码围栏")
        }
        foreach ($requiredHeading in @("## 读取路由", "## 忽略目录与二进制资产", "## 验证命令")) {
            if ($mapLines -notcontains $requiredHeading) {
                $issues.Add("repo-map 缺少必需章节：$requiredHeading")
            }
        }
    }
    catch {
        $issues.Add("repo-map 检查失败：$($_.Exception.Message)")
    }

    try {
        $ignoreText = Read-TrackedText -RelativePath ".gitignore"
        $ignoreLines = @($ignoreText -split "\r?\n" | ForEach-Object { $_.Trim() })
        foreach ($requiredIgnoreLine in @(
            ".m2-repository/",
            "target/",
            ".orderly-data/",
            "output/~$*"
        )) {
            if ($ignoreLines -notcontains $requiredIgnoreLine) {
                $issues.Add("忽略规则缺失：$requiredIgnoreLine")
            }
        }
    }
    catch {
        $issues.Add("忽略规则检查失败：$($_.Exception.Message)")
    }

    try {
        $trackedFiles = @(Get-TrackedRelativePaths)
        if ($trackedFiles.Count -eq 0) {
            $issues.Add("扫描选择为空")
        }
        $excludedSelection = @($trackedFiles | Where-Object {
            Test-ExcludedPath -RelativePath $_
        })
        if ($excludedSelection.Count -ne 0) {
            $issues.Add("扫描选择包含忽略目录：$($excludedSelection -join ', ')")
        }

        $contextText = Read-TrackedText -RelativePath "scripts/context.ps1"
        $forbiddenSearchCommand = "r" + "g -uu"
        if ($contextText.Contains($forbiddenSearchCommand)) {
            $issues.Add("context 脚本不得使用 " + $forbiddenSearchCommand)
        }
        if (-not $contextText.Contains("ls-files") -or -not $contextText.Contains("grep")) {
            $issues.Add("context 脚本未同时声明 Git 文件清单与受限搜索路径")
        }
    }
    catch {
        $issues.Add("扫描选择检查失败：$($_.Exception.Message)")
    }

    if ($issues.Count -ne 0) {
        Write-Output "AUDIT FAIL issues=$($issues.Count)"
        foreach ($issue in $issues) {
            Write-Output "FAIL $issue"
        }
        exit 1
    }

    Write-Output "AUDIT PASS config=required-exact,optional-when-present repo-map=true ignore=4 scan=tracked-only"
}

try {
    Initialize-Repository
    $resolvedMode = Resolve-Mode
    switch ($resolvedMode) {
        "file" { Invoke-FileMode }
        "map" { Invoke-MapMode }
        "files" { Invoke-FilesMode }
        "search" { Invoke-SearchMode }
        "diff" { Invoke-DiffMode }
        "audit" { Invoke-AuditMode }
        default { throw "未知模式：$resolvedMode" }
    }
    exit 0
}
catch {
    [Console]::Error.WriteLine("context: $($_.Exception.Message)")
    exit 1
}
