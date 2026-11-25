#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   在当前 Git 工作树生成可独立验证的 Orderly 离线源码发布 ZIP。
#
# 输入：
#   默认要求 Git 工作树干净。-AllowDirtySnapshot 仅用于本地验收，允许从脏工作树生成带 gitDirty=true 的快照。
#   源码路径只来自 Git 索引；新增未跟踪文件必须先显式 git add。
#   JDK 21、Maven 3.9+ 必须可用，因为预热会执行 Maven 构建和 Java 测试。Node/Python 测试环境不是必需项。
#
# 副作用：
#   只写入 target/orderly-offline：先清空该受控目录，从空 maven-repository 预热，运行完整构建，
#   再生成源码、Maven 仓库、清单、校验和与 ZIP。不会提交、上传、读取 API Key 或写入普通配置。
#
# 安全边界：
#   所有递归清理与 ZIP 输出都限制在 target/orderly-offline；源码清单只来自 Git 索引中的路径，
#   并拒绝 .workbuddy、重解析点、构建/运行目录、缓存、日志、PID、常见凭据文件和明显私钥内容。
#   第三方许可只列事实和风险，
#   不生成法律责任或兼容性结论。

param(
    [switch]$AllowDirtySnapshot
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$outputRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "orderly-offline"))
$workRoot = [System.IO.Path]::GetFullPath((Join-Path $outputRoot "work"))
$releaseStageRoot = [System.IO.Path]::GetFullPath((Join-Path $outputRoot "staging"))
$warmRepository = [System.IO.Path]::GetFullPath((Join-Path $workRoot "maven-repository"))
$buildScript = Join-Path $PSScriptRoot "build.ps1"

function Assert-PathWithin {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    if (-not $fullPath.Equals($fullRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            -not $fullPath.StartsWith(
                $fullRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
        throw "$Description 超出允许目录：$fullPath"
    }
    return $fullPath
}

function Assert-NoReparsePoint {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description 不得是重解析点：$Path"
    }
}

function Remove-ControlledTree {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $safePath = Assert-PathWithin $Path $outputRoot $Description
    if ($safePath.Equals($outputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description 不得直接清理离线包根目录。"
    }
    if (-not (Test-Path -LiteralPath $safePath)) {
        return
    }
    Assert-NoReparsePoint $safePath $Description
    if (Test-Path -LiteralPath $safePath -PathType Container) {
        Remove-Item -LiteralPath $safePath -Recurse -Force
    }
    else {
        Remove-Item -LiteralPath $safePath -Force
    }
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )
    $parent = Split-Path -Parent $Path
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    [System.IO.File]::WriteAllText(
        $Path,
        $Content,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-RelativePathFromRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $prefix = $fullRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "路径不在预期根目录内：$fullPath"
    }
    return $fullPath.Substring($prefix.Length).Replace("\", "/")
}

function Get-TreeFiles {
    param([Parameter(Mandatory = $true)][string]$Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return @()
    }
    Assert-NoReparsePoint $Root "目录"
    $files = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
    $stack = [System.Collections.Generic.Stack[string]]::new()
    $stack.Push([System.IO.Path]::GetFullPath($Root))
    while ($stack.Count -gt 0) {
        $directory = $stack.Pop()
        foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
            if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "离线包处理拒绝重解析点：$($entry.FullName)"
            }
            if ($entry.PSIsContainer) {
                $stack.Push($entry.FullName)
            }
            else {
                $files.Add($entry)
            }
        }
    }
    return @($files | Sort-Object FullName)
}

function Assert-SafeRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    if ([string]::IsNullOrWhiteSpace($Path) -or
            [System.IO.Path]::IsPathRooted($Path) -or
            $Path.IndexOf('\') -ge 0 -or
            $Path.StartsWith("/", [System.StringComparison]::Ordinal) -or
            $Path -match '^[A-Za-z]:') {
        throw "$Description 不是安全的相对路径：$Path"
    }
    $segments = @($Path -split "/")
    if ($segments.Count -eq 0 -or @($segments | Where-Object {
        [string]::IsNullOrEmpty($_) -or $_ -eq "." -or $_ -eq ".."
    }).Count -gt 0) {
        throw "$Description 含空段、点段或父目录段：$Path"
    }
    if ($Path -match '[\x00-\x1F<>:"|?*]' -or
            @($segments | Where-Object { $_.EndsWith(".", [System.StringComparison]::Ordinal) -or
                $_.EndsWith(" ", [System.StringComparison]::Ordinal) }).Count -gt 0) {
        throw "$Description 含 Windows 不接受或非规范字符：$Path"
    }
    if (@($segments | Where-Object {
        $_ -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$'
    }).Count -gt 0) {
        throw "$Description 含 Windows 保留设备名：$Path"
    }
}

function Get-ForbiddenPackagePathReason {
    param([Parameter(Mandatory = $true)][string]$Path)
    $normalized = $Path.Replace("\", "/")
    $lower = $normalized.ToLowerInvariant()
    $segments = @($lower -split "/")
    $forbiddenDirectoryNames = @(
        ".workbuddy",
        ".git",
        ".m2-repository",
        "target",
        ".orderly-data",
        "node_modules",
        "__pycache__",
        ".pytest_cache",
        ".mypy_cache",
        ".ruff_cache",
        ".cache",
        ".tox",
        ".nox",
        ".nyc_output",
        "coverage",
        ".idea",
        ".vscode",
        ".venv",
        "logs"
    )
    foreach ($segment in $segments) {
        if ($forbiddenDirectoryNames -contains $segment) {
            return "禁止目录：$segment"
        }
    }
    $leaf = [System.IO.Path]::GetFileName($normalized)
    if ($leaf -match '\.(?:log|pid|pyc)$' -or
            $leaf -eq ".coverage" -or
            $leaf.StartsWith("~$", [System.StringComparison]::Ordinal)) {
        return "禁止生成物或诊断文件：$leaf"
    }
    $credentialLeafPatterns = @(
        '^\.env(?:\..*)?$',
        '^\.netrc$',
        '^_netrc$',
        '^\.npmrc$',
        '^\.pypirc$',
        '^settings(?:-security)?\.xml$',
        '^auth.*\.json$',
        '^tokens?.*\.json$',
        '^(?:credentials?|secrets?)(?:\..*)?$',
        '^id_(?:rsa|dsa|ecdsa|ed25519)(?:\.pub)?$',
        '.*\.(?:pem|key|p12|pfx)$'
    )
    foreach ($pattern in $credentialLeafPatterns) {
        if ($leaf -match $pattern) {
            return "禁止凭据文件：$leaf"
        }
    }
    return $null
}

function Assert-PackagePathAllowed {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $reason = Get-ForbiddenPackagePathReason $Path
    if ($null -ne $reason) {
        throw "$Description 包含禁止发布路径：$Path（$reason）"
    }
}

function Assert-NoObviousCredentialContent {
    param([Parameter(Mandatory = $true)][string]$Path)
    $leaf = [System.IO.Path]::GetFileName($Path)
    $extension = [System.IO.Path]::GetExtension($leaf).ToLowerInvariant()
    $textExtensions = @(
        ".md", ".txt", ".json", ".toml", ".yaml", ".yml", ".xml", ".ps1", ".cmd",
        ".js", ".mjs", ".cjs", ".css", ".html", ".java", ".py", ".properties"
    )
    if ($textExtensions -notcontains $extension -and $leaf -ne ".gitignore") {
        return
    }
    $file = Get-Item -LiteralPath $Path -Force
    if ($file.Length -gt 2MB) {
        return
    }
    $content = Get-Content -Raw -LiteralPath $Path -ErrorAction Stop
    if ($content -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----' -or
            $content -match 'api[_-]?key\s*[:=]\s*["''][A-Za-z0-9_-]{24,}["'']') {
        throw "源码文件中检测到疑似凭据内容：$Path"
    }
}

function Copy-SourceTree {
    param([Parameter(Mandatory = $true)][string]$DestinationRoot)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $paths = @(& git -C $repoRoot -c core.quotePath=false ls-files --cached)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "读取 Git 索引源码清单失败，退出码：$exitCode"
    }
    $seen = @{}
    $sourceFiles = [System.Collections.Generic.List[object]]::new()
    foreach ($rawPath in $paths) {
        if ([string]::IsNullOrWhiteSpace($rawPath)) {
            continue
        }
        $relativePath = $rawPath.Replace("\", "/")
        Assert-SafeRelativePath $relativePath "Git 源码路径"
        $lookup = $relativePath.ToLowerInvariant()
        if ($seen.ContainsKey($lookup)) {
            throw "Git 索引源码清单含重复路径：$relativePath"
        }
        $seen[$lookup] = $true
        Assert-PackagePathAllowed $relativePath "Git 索引源码"

        $sourcePath = Assert-PathWithin (Join-Path $repoRoot $relativePath) $repoRoot "源码文件"
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Git 清单中的源码文件不存在：$relativePath"
        }
        Assert-NoReparsePoint $sourcePath "源码文件"
        Assert-NoObviousCredentialContent $sourcePath
        $destinationPath = Assert-PathWithin (
            Join-Path $DestinationRoot $relativePath
        ) $DestinationRoot "源码发布文件"
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $destinationPath)) | Out-Null
        [System.IO.File]::Copy($sourcePath, $destinationPath, $false)
        $sourceFiles.Add([ordered]@{
            path = $relativePath
            size = [long](Get-Item -LiteralPath $sourcePath -Force).Length
            sha256 = Get-Sha256 $sourcePath
        })
    }
    if ($sourceFiles.Count -eq 0) {
        throw "Git 索引源码清单未产生任何发布文件。"
    }
    return @($sourceFiles)
}

function Copy-MavenRepository {
    param(
        [Parameter(Mandatory = $true)][string]$SourceRoot,
        [Parameter(Mandatory = $true)][string]$DestinationRoot
    )
    if (-not (Test-Path -LiteralPath $SourceRoot -PathType Container)) {
        throw "预热 Maven 仓库不存在：$SourceRoot"
    }
    $files = @(Get-TreeFiles $SourceRoot)
    if ($files.Count -eq 0) {
        throw "预热 Maven 仓库为空，无法生成离线包。"
    }
    foreach ($file in $files) {
        $relativePath = Get-RelativePathFromRoot $file.FullName $SourceRoot
        Assert-PackagePathAllowed "maven-repository/$relativePath" "Maven 仓库文件"
        $destinationPath = Assert-PathWithin (
            Join-Path $DestinationRoot $relativePath
        ) $DestinationRoot "Maven 仓库发布文件"
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $destinationPath)) | Out-Null
        [System.IO.File]::Copy($file.FullName, $destinationPath, $false)
    }
    return $files.Count
}

function Get-MavenVersion {
    $arguments = @(
        "-Dmaven.repo.local=$warmRepository",
        "-version"
    )
    $result = Invoke-NativeCapture -FilePath "mvn" -Arguments $arguments
    if ($result.ExitCode -ne 0 -or
            $result.Output -notmatch '(?m)^Apache Maven\s+(?<version>\S+)') {
        throw "无法读取精确 Maven 版本。输出：`n$($result.Output)"
    }
    return $Matches.version
}

function Get-JdkMajor {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "JAVA_HOME 未设置。"
    }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        throw "JAVA_HOME 下缺少 java.exe：$javaExe"
    }
    $version = Invoke-NativeCapture -FilePath $javaExe -Arguments @("-version")
    if ($version.ExitCode -ne 0 -or $version.Output -notmatch 'version "(?<major>\d+)') {
        throw "无法读取 JDK 主版本：`n$($version.Output)"
    }
    return [int]$Matches.major
}

function Get-ProjectVersion {
    $pomPath = Join-Path $repoRoot "pom.xml"
    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $false
    $xml.Load($pomPath)
    $version = [string]$xml.project.version
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "根 pom.xml 未声明项目版本。"
    }
    return $version
}

function Get-GitCommit {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $commit = (& git -C $repoRoot rev-parse HEAD 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        return "unknown"
    }
    $trimmed = $commit.Trim()
    if ($trimmed -notmatch '^[0-9a-fA-F]{40,64}$') {
        return "unknown"
    }
    return $trimmed.ToLowerInvariant()
}

function Get-GitWorktreeDirty {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $status = (& git -C $repoRoot `
            status `
            --porcelain=v1 `
            --untracked-files=normal `
            --ignore-submodules=none 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "读取 Git 工作树状态失败，退出码：$exitCode`n$($status.Trim())"
    }
    return -not [string]::IsNullOrWhiteSpace($status)
}

function Get-SourceTreeSha256 {
    param([Parameter(Mandatory = $true)][object[]]$SourceFiles)
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($record in $SourceFiles) {
        $lines.Add("$($record.path)`t$($record.size)`t$($record.sha256)")
    }
    $lines.Sort([System.StringComparer]::Ordinal)
    $canonical = if ($lines.Count -eq 0) {
        ""
    }
    else {
        ($lines -join "`n") + "`n"
    }
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($canonical)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $algorithm.ComputeHash($bytes)
    }
    finally {
        $algorithm.Dispose()
    }
    return ([System.BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
}

function Write-ChecksumFile {
    param([Parameter(Mandatory = $true)][string]$ReleaseRoot)
    $checksumPath = Join-Path $ReleaseRoot "files.sha256"
    $lines = @()
    foreach ($file in @(Get-TreeFiles $ReleaseRoot)) {
        $relativePath = Get-RelativePathFromRoot $file.FullName $ReleaseRoot
        if ($relativePath -eq "files.sha256") {
            continue
        }
        $lines += "$(Get-Sha256 $file.FullName)  $relativePath"
    }
    Write-Utf8File $checksumPath ((($lines | Sort-Object) -join "`n") + "`n")
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    Write-Utf8File $Path (($Value | ConvertTo-Json -Depth 20) + "`n")
}

function New-ReleaseZip {
    param(
        [Parameter(Mandatory = $true)][string]$ReleaseRoot,
        [Parameter(Mandatory = $true)][string]$ZipPath
    )
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $ZipPath) {
        Assert-PathWithin $ZipPath $outputRoot "离线 ZIP" | Out-Null
        Remove-Item -LiteralPath $ZipPath -Force
    }
    $archive = [System.IO.Compression.ZipFile]::Open(
        $ZipPath,
        [System.IO.Compression.ZipArchiveMode]::Create
    )
    try {
        foreach ($file in @(Get-TreeFiles $ReleaseRoot)) {
            $relativePath = Get-RelativePathFromRoot $file.FullName $ReleaseRoot
            Assert-PackagePathAllowed $relativePath "离线 ZIP 条目"
            Assert-SafeRelativePath $relativePath "离线 ZIP 条目"
            $entry = $archive.CreateEntry(
                $relativePath,
                [System.IO.Compression.CompressionLevel]::Optimal
            )
            $input = [System.IO.File]::OpenRead($file.FullName)
            try {
                $output = $entry.Open()
                try {
                    $input.CopyTo($output)
                }
                finally {
                    $output.Dispose()
                }
            }
            finally {
                $input.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& $FilePath @Arguments 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        Output = $output
        ExitCode = $exitCode
    }
}

$gitDirty = Get-GitWorktreeDirty
if ($gitDirty -and -not $AllowDirtySnapshot) {
    throw "正式离线包默认要求 Git 工作树干净。当前工作树包含已修改、已暂存或未跟踪路径；仅本地验收可使用 -AllowDirtySnapshot。"
}
$gitCommit = Get-GitCommit

Assert-PathWithin $outputRoot $targetRoot "离线包输出根目录" | Out-Null
if (Test-Path -LiteralPath $outputRoot) {
    Assert-NoReparsePoint $outputRoot "离线包输出根目录"
    Remove-Item -LiteralPath $outputRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $outputRoot | Out-Null
New-Item -ItemType Directory -Path $workRoot | Out-Null

Write-Host "从空 Maven 仓库预热：$warmRepository"
& $buildScript -MavenRepository $warmRepository
if (-not $? -or $LASTEXITCODE -ne 0) {
    throw "离线包预热构建失败，退出码：$LASTEXITCODE"
}

$projectVersion = Get-ProjectVersion
$packageName = "Orderly-$projectVersion-offline-source"
$releaseRoot = Assert-PathWithin (
    Join-Path $releaseStageRoot $packageName
) $outputRoot "离线包暂存目录"
New-Item -ItemType Directory -Path $releaseRoot | Out-Null

Write-Host "复制 Git 源码文件并过滤构建/运行产物。"
$sourceFiles = @(Copy-SourceTree $releaseRoot)
$sourceFileCount = $sourceFiles.Count
$sourceTreeSha256 = Get-SourceTreeSha256 $sourceFiles
$repositoryDestination = Join-Path $releaseRoot "maven-repository"
$repositoryFileCount = Copy-MavenRepository $warmRepository $repositoryDestination

$projectPoms = @()
foreach ($pomFile in @(Get-TreeFiles $releaseRoot | Where-Object {
    $_.Name -eq "pom.xml" -and
    -not (Get-RelativePathFromRoot $_.FullName $releaseRoot).StartsWith(
        "maven-repository/",
        [System.StringComparison]::Ordinal
    )
})) {
    $projectPoms += [ordered]@{
        path = Get-RelativePathFromRoot $pomFile.FullName $releaseRoot
        sha256 = Get-Sha256 $pomFile.FullName
    }
}

$repositoryManifestFiles = @()
$thirdPartyArtifacts = @()
foreach ($repositoryFile in @(Get-TreeFiles $repositoryDestination)) {
    $packageRelativePath = Get-RelativePathFromRoot $repositoryFile.FullName $releaseRoot
    $repositoryRelativePath = Get-RelativePathFromRoot $repositoryFile.FullName $repositoryDestination
    $fileRecord = [ordered]@{
        path = $packageRelativePath
        size = [long]$repositoryFile.Length
        sha256 = Get-Sha256 $repositoryFile.FullName
    }
    $repositoryManifestFiles += $fileRecord
    if (-not $repositoryRelativePath.StartsWith(
            "com/orderly/",
            [System.StringComparison]::Ordinal
        ) -and
            $repositoryRelativePath -match '\.(?:jar|pom)$') {
        $thirdPartyArtifacts += [ordered]@{
            path = $packageRelativePath
            size = [long]$repositoryFile.Length
            sha256 = $fileRecord.sha256
            licenseStatus = "NOT_DETERMINED"
        }
    }
}
$webView2SdkRoot = Join-Path $releaseRoot "app\desktop\webview2-sdk"
if (Test-Path -LiteralPath $webView2SdkRoot -PathType Container) {
    foreach ($sdkFile in @(Get-TreeFiles $webView2SdkRoot | Sort-Object FullName)) {
        $packageRelativePath = Get-RelativePathFromRoot $sdkFile.FullName $releaseRoot
        $thirdPartyArtifacts += [ordered]@{
            path = $packageRelativePath
            size = [long]$sdkFile.Length
            sha256 = Get-Sha256 $sdkFile.FullName
            licenseStatus = "NOT_DETERMINED"
        }
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    packageType = "orderly-offline-source"
    projectVersion = $projectVersion
    gitCommit = $gitCommit
    gitDirty = [bool]$gitDirty
    sourceTreeSha256 = $sourceTreeSha256
    sourceFiles = @($sourceFiles | Sort-Object { $_.path })
    jdkMajor = Get-JdkMajor
    mavenVersion = Get-MavenVersion
    mavenRepositoryRoot = "maven-repository"
    projectPoms = @($projectPoms | Sort-Object { $_.path })
    mavenRepositoryFiles = @($repositoryManifestFiles | Sort-Object { $_.path })
}
Write-JsonFile (Join-Path $releaseRoot "maven-manifest.json") $manifest

$thirdPartyManifest = [ordered]@{
    schemaVersion = 1
    scope = "Maven repository artifacts and the pinned WebView2 SDK captured by the full online warm build"
    licenseConclusion = "NOT_DETERMINED"
    warning = "This inventory is not a license review or legal conclusion. License files and artifact metadata have not been verified."
    artifacts = @($thirdPartyArtifacts | Sort-Object { $_.path })
}
Write-JsonFile (Join-Path $releaseRoot "third-party-artifacts.json") $thirdPartyManifest

$licenseRisk = @'
# Third-Party License Risk

Status: **NOT_DETERMINED**

This offline source package contains a Maven repository snapshot captured during a full build
and the pinned Microsoft WebView2 SDK files. The included `third-party-artifacts.json` lists
captured third-party files with sizes and SHA-256 hashes. It does not establish license
compatibility, attribution completeness, or redistribution rights.

No license conclusion has been made. Review the artifact licenses, notices, transitive
dependencies, and distribution obligations before publishing or redistributing this package.
'@
Write-Utf8File (Join-Path $releaseRoot "THIRD-PARTY-LICENSE-RISK.md") ($licenseRisk.Trim() + "`n")

Write-ChecksumFile $releaseRoot
$zipPath = Join-Path $outputRoot "$packageName.zip"
New-ReleaseZip $releaseRoot $zipPath
$zipHash = Get-Sha256 $zipPath
Write-Utf8File "$zipPath.sha256" "$zipHash  $([System.IO.Path]::GetFileName($zipPath))`n"

Remove-ControlledTree $releaseStageRoot "离线包暂存目录"
Remove-ControlledTree $workRoot "Maven 预热工作目录"

Write-Host "离线源码包已生成：$zipPath"
Write-Host "源码文件：$sourceFileCount；Maven 仓库文件：$repositoryFileCount；ZIP SHA-256：$zipHash"
