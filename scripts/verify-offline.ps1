#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   安全解压并验证 Orderly 离线源码发布 ZIP，然后在包内以严格 Maven 离线模式执行完整构建。
#
# 输入：
#   -PackagePath 指向 package-offline.ps1 生成的 ZIP；同目录必须存在同名 .sha256。
#   验证机必须使用与清单一致的 JDK 主版本和精确 Maven 版本。
#   -MaxParallel 原样透传给包内 build.ps1，默认 1；验证器本身不实现调度。
#   验证只执行包内 Maven 构建与 Java 测试，不要求 Node/Python 测试环境，也不运行 Node/Python 测试。
#
# 副作用：
#   只在 target/orderly-offline/verify-* 中解压和执行构建；结束后清理临时目录，不访问 Maven 远端。
#   该脚本只证明 Maven 构建离线，不证明 Python/Node 或整个应用可以离线安装和运行。
#
# 安全边界：
#   ZIP 条目、清单路径、解压路径和递归清理都进行根目录、路径穿越、重复项与重解析点校验。
#   禁止路径、额外文件、缺失文件、重复项、大小或 SHA-256 不符均失败；
#   构建始终传入 -Offline 和包内 maven-repository。

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$PackagePath,
    [ValidateRange(1, 2147483647)]
    [int]$MaxParallel = 1
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$outputRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "orderly-offline"))
$verificationRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $outputRoot ("verify-" + [guid]::NewGuid().ToString("N")))
)
$failure = $null
$cleanupFailure = $null

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
        throw "$Description 包含禁止路径：$Path（$reason）"
    }
}

function Assert-UniqueRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][hashtable]$Seen,
        [Parameter(Mandatory = $true)][string]$Description
    )
    Assert-SafeRelativePath $Path $Description
    $lookup = $Path.ToLowerInvariant()
    if ($Seen.ContainsKey($lookup)) {
        throw "$Description 含重复路径：$Path"
    }
    $Seen[$lookup] = $true
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

function Get-TreeItems {
    param([Parameter(Mandatory = $true)][string]$Root)
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return @()
    }
    Assert-NoReparsePoint $Root "目录"
    $items = [System.Collections.Generic.List[System.IO.FileSystemInfo]]::new()
    $stack = [System.Collections.Generic.Stack[string]]::new()
    $stack.Push([System.IO.Path]::GetFullPath($Root))
    while ($stack.Count -gt 0) {
        $directory = $stack.Pop()
        foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
            if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "离线包校验拒绝重解析点：$($entry.FullName)"
            }
            $items.Add($entry)
            if ($entry.PSIsContainer) {
                $stack.Push($entry.FullName)
            }
        }
    }
    return @($items | Sort-Object FullName)
}

function Get-TreeFiles {
    param([Parameter(Mandatory = $true)][string]$Root)
    return @(Get-TreeItems $Root | Where-Object { -not $_.PSIsContainer })
}

function Get-TreeDirectories {
    param([Parameter(Mandatory = $true)][string]$Root)
    return @(Get-TreeItems $Root | Where-Object { $_.PSIsContainer })
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
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

function Get-RequiredProperty {
    param(
        [Parameter(Mandatory = $true)]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Description
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        throw "$Description 缺少字段：$Name"
    }
    return $property.Value
}

function Get-PackagePath {
    param(
        [Parameter(Mandatory = $true)][string]$RelativePath,
        [Parameter(Mandatory = $true)][string]$ExtractRoot,
        [Parameter(Mandatory = $true)][string]$Description
    )
    Assert-SafeRelativePath $RelativePath $Description
    $fullPath = Assert-PathWithin (
        Join-Path $ExtractRoot $RelativePath
    ) $ExtractRoot $Description
    $currentRoot = [System.IO.Path]::GetFullPath($ExtractRoot)
    $relativeSegments = @($RelativePath -split "/")
    for ($index = 0; $index -lt $relativeSegments.Count; $index++) {
        $currentRoot = Join-Path $currentRoot $relativeSegments[$index]
        if (Test-Path -LiteralPath $currentRoot) {
            Assert-NoReparsePoint $currentRoot $Description
        }
    }
    return $fullPath
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

function Get-JdkMajor {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw "JAVA_HOME 未设置，无法验证 JDK 版本。"
    }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        throw "JAVA_HOME 下缺少 java.exe：$javaExe"
    }
    $result = Invoke-NativeCapture -FilePath $javaExe -Arguments @("-version")
    if ($result.ExitCode -ne 0 -or $result.Output -notmatch 'version "(?<major>\d+)') {
        throw "无法读取当前 JDK 主版本：`n$($result.Output)"
    }
    return [int]$Matches.major
}

function Get-MavenVersion {
    param([Parameter(Mandatory = $true)][string]$RepositoryPath)
    $args = @(
        "-o",
        "-Dmaven.repo.local=$RepositoryPath",
        "-version"
    )
    $result = Invoke-NativeCapture -FilePath "mvn" -Arguments $args
    if ($result.ExitCode -ne 0 -or
            $result.Output -notmatch '(?m)^Apache Maven\s+(?<version>\S+)') {
        throw "无法读取当前精确 Maven 版本：`n$($result.Output)"
    }
    return $Matches.version
}

function Test-ZipEntryReparsePoint {
    param(
        [Parameter(Mandatory = $true)][System.IO.Compression.ZipArchiveEntry]$Entry
    )
    $unixFileType = ($Entry.ExternalAttributes -shr 16) -band 0xF000
    if ($unixFileType -eq 0xA000) {
        throw "ZIP 含符号链接条目：$($Entry.FullName)"
    }
}

function Expand-SafeZip {
    param(
        [Parameter(Mandatory = $true)][string]$ZipPath,
        [Parameter(Mandatory = $true)][string]$DestinationRoot
    )
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Directory]::CreateDirectory($DestinationRoot) | Out-Null
    $seen = @{}
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($entry in $archive.Entries) {
            Test-ZipEntryReparsePoint $entry
            $entryPath = $entry.FullName
            if ([string]::IsNullOrEmpty($entryPath)) {
                throw "ZIP 含空条目路径。"
            }
            $isDirectory = $entryPath.EndsWith("/", [System.StringComparison]::Ordinal)
            if ($isDirectory) {
                $entryPath = $entryPath.Substring(0, $entryPath.Length - 1)
            }
            Assert-SafeRelativePath $entryPath "ZIP 条目"
            Assert-PackagePathAllowed $entryPath "ZIP 条目"
            Assert-UniqueRelativePath $entryPath $seen "ZIP 条目"
            $destinationPath = Get-PackagePath $entryPath $DestinationRoot "ZIP 解压路径"
            if ($isDirectory) {
                [System.IO.Directory]::CreateDirectory($destinationPath) | Out-Null
                continue
            }
            if (Test-Path -LiteralPath $destinationPath) {
                throw "ZIP 文件与已有目录或其他条目冲突：$entryPath"
            }
            [System.IO.Directory]::CreateDirectory(
                (Split-Path -Parent $destinationPath)
            ) | Out-Null
            $input = $entry.Open()
            try {
                $output = [System.IO.File]::Open(
                    $destinationPath,
                    [System.IO.FileMode]::CreateNew,
                    [System.IO.FileAccess]::Write,
                    [System.IO.FileShare]::None
                )
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

try {
    Assert-PathWithin $verificationRoot $outputRoot "离线验证临时目录" | Out-Null
    if (-not (Test-Path -LiteralPath $PackagePath -PathType Leaf)) {
        throw "离线包不存在或不是文件：$PackagePath"
    }
    $zipPath = (Resolve-Path -LiteralPath $PackagePath).Path
    Assert-NoReparsePoint $zipPath "离线 ZIP"
    $sidecarPath = "$zipPath.sha256"
    if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)) {
        throw "缺少离线 ZIP 校验文件：$sidecarPath"
    }
    Assert-NoReparsePoint $sidecarPath "离线 ZIP 校验文件"
    $sidecarText = (Get-Content -Raw -Encoding UTF8 -LiteralPath $sidecarPath).Trim()
    if ($sidecarText -notmatch '^(?<hash>[0-9a-fA-F]{64})\s+\*?(?<name>[^\r\n]+)$') {
        throw "离线 ZIP 校验文件格式损坏：$sidecarPath"
    }
    $expectedZipHash = $Matches.hash.ToLowerInvariant()
    $expectedZipName = [System.IO.Path]::GetFileName($Matches.name.Trim())
    if ($expectedZipName -ne [System.IO.Path]::GetFileName($zipPath)) {
        throw "离线 ZIP 校验文件记录的文件名不匹配：$expectedZipName"
    }
    $actualZipHash = Get-Sha256 $zipPath
    if ($actualZipHash -ne $expectedZipHash) {
        throw "离线 ZIP SHA-256 不符。预期=$expectedZipHash 实际=$actualZipHash"
    }

    if (-not (Test-Path -LiteralPath $outputRoot -PathType Container)) {
        New-Item -ItemType Directory -Path $outputRoot | Out-Null
    }
    New-Item -ItemType Directory -Path $verificationRoot | Out-Null
    Write-Host "安全解压离线包：$zipPath"
    Expand-SafeZip $zipPath $verificationRoot
    foreach ($item in @(Get-TreeItems $verificationRoot)) {
        $relativeItemPath = Get-RelativePathFromRoot $item.FullName $verificationRoot
        Assert-PackagePathAllowed $relativeItemPath "解压后文件集"
    }

    $requiredPaths = @(
        "maven-manifest.json",
        "files.sha256",
        "third-party-artifacts.json",
        "THIRD-PARTY-LICENSE-RISK.md",
        "pom.xml",
        "maven-repository"
    )
    foreach ($requiredPath in $requiredPaths) {
        $requiredFullPath = Assert-PathWithin (
            Join-Path $verificationRoot $requiredPath
        ) $verificationRoot "必需包结构"
        if (-not (Test-Path -LiteralPath $requiredFullPath)) {
            throw "离线包缺少必需结构：$requiredPath"
        }
        Assert-NoReparsePoint $requiredFullPath "必需包结构"
    }

    $manifestPath = Join-Path $verificationRoot "maven-manifest.json"
    try {
        $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath |
            ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "maven-manifest.json 缺失或 JSON 损坏：$($_.Exception.Message)"
    }
    if ($null -eq $manifest) {
        throw "maven-manifest.json 内容为空。"
    }
    $schemaVersion = [int](Get-RequiredProperty $manifest "schemaVersion" "maven-manifest.json")
    if ($schemaVersion -ne 1) {
        throw "不支持 maven-manifest.json schemaVersion：$schemaVersion"
    }
    $projectVersion = [string](Get-RequiredProperty $manifest "projectVersion" "maven-manifest.json")
    if ([string]::IsNullOrWhiteSpace($projectVersion)) {
        throw "maven-manifest.json 的 projectVersion 为空。"
    }
    $gitCommit = [string](Get-RequiredProperty $manifest "gitCommit" "maven-manifest.json")
    if ([string]::IsNullOrWhiteSpace($gitCommit)) {
        throw "maven-manifest.json 的 gitCommit 为空。"
    }
    $gitDirtyValue = Get-RequiredProperty $manifest "gitDirty" "maven-manifest.json"
    if ($gitDirtyValue -isnot [bool]) {
        throw "maven-manifest.json 的 gitDirty 必须是布尔值。"
    }
    $sourceTreeSha256 = [string](
        Get-RequiredProperty $manifest "sourceTreeSha256" "maven-manifest.json"
    )
    if ($sourceTreeSha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw "maven-manifest.json 的 sourceTreeSha256 格式无效。"
    }
    $jdkMajor = [int](Get-RequiredProperty $manifest "jdkMajor" "maven-manifest.json")
    $currentJdkMajor = Get-JdkMajor
    if ($jdkMajor -ne $currentJdkMajor) {
        throw "JDK 主版本不匹配。清单=$jdkMajor 当前=$currentJdkMajor"
    }
    $mavenVersion = [string](Get-RequiredProperty $manifest "mavenVersion" "maven-manifest.json")
    if ([string]::IsNullOrWhiteSpace($mavenVersion)) {
        throw "maven-manifest.json 的 mavenVersion 为空。"
    }
    $repositoryRoot = [string](Get-RequiredProperty $manifest "mavenRepositoryRoot" "maven-manifest.json")
    if ($repositoryRoot -ne "maven-repository") {
        throw "mavenRepositoryRoot 必须为 maven-repository：$repositoryRoot"
    }
    $repositoryPath = Assert-PathWithin (
        Join-Path $verificationRoot $repositoryRoot
    ) $verificationRoot "Maven 仓库"
    if (-not (Test-Path -LiteralPath $repositoryPath -PathType Container)) {
        throw "离线包 Maven 仓库目录缺失：$repositoryRoot"
    }
    $currentMavenVersion = Get-MavenVersion $repositoryPath
    if ($mavenVersion -ne $currentMavenVersion) {
        throw "Maven 版本不匹配。清单=$mavenVersion 当前=$currentMavenVersion"
    }

    $pomPaths = @(Get-RequiredProperty $manifest "projectPoms" "maven-manifest.json")
    if ($pomPaths.Count -eq 0) {
        throw "maven-manifest.json 未记录任何项目 POM。"
    }
    $pomSeen = @{}
    $manifestPomSet = @()
    foreach ($pomRecord in $pomPaths) {
        $pomRelativePath = [string](Get-RequiredProperty $pomRecord "path" "projectPoms")
        Assert-UniqueRelativePath $pomRelativePath $pomSeen "项目 POM 路径"
        if ([System.IO.Path]::GetFileName($pomRelativePath) -ne "pom.xml") {
            throw "项目 POM 路径未指向 pom.xml：$pomRelativePath"
        }
        $expectedPomHash = [string](Get-RequiredProperty $pomRecord "sha256" "projectPoms")
        if ($expectedPomHash -notmatch '^[0-9a-fA-F]{64}$') {
            throw "项目 POM SHA-256 格式无效：$pomRelativePath"
        }
        $pomFullPath = Get-PackagePath $pomRelativePath $verificationRoot "项目 POM"
        if (-not (Test-Path -LiteralPath $pomFullPath -PathType Leaf)) {
            throw "项目 POM 缺失：$pomRelativePath"
        }
        $actualPomHash = Get-Sha256 $pomFullPath
        if ($actualPomHash -ne $expectedPomHash.ToLowerInvariant()) {
            throw "项目 POM SHA-256 不符：$pomRelativePath"
        }
        $manifestPomSet += $pomRelativePath.ToLowerInvariant()
    }
    if ($manifestPomSet -notcontains "pom.xml") {
        throw "maven-manifest.json 未记录根 pom.xml。"
    }
    $actualPomSet = @(Get-TreeFiles $verificationRoot | Where-Object {
        $_.Name -eq "pom.xml" -and
        -not (Get-RelativePathFromRoot $_.FullName $verificationRoot).StartsWith(
            "maven-repository/",
            [System.StringComparison]::Ordinal
        )
    } | ForEach-Object {
        (Get-RelativePathFromRoot $_.FullName $verificationRoot).ToLowerInvariant()
    } | Sort-Object)
    $expectedPomSet = @($manifestPomSet | Sort-Object)
    if (($actualPomSet -join "|") -ne ($expectedPomSet -join "|")) {
        throw "项目 POM 清单不完整或含额外 POM。清单=$($expectedPomSet -join ',') 实际=$($actualPomSet -join ',')"
    }

    $metadataFiles = @(
        "maven-manifest.json",
        "files.sha256",
        "third-party-artifacts.json",
        "THIRD-PARTY-LICENSE-RISK.md"
    )
    $metadataFileSet = @{}
    foreach ($metadataFile in $metadataFiles) {
        $metadataFileSet[$metadataFile.ToLowerInvariant()] = $true
    }
    $sourceRecords = @(Get-RequiredProperty $manifest "sourceFiles" "maven-manifest.json")
    if ($sourceRecords.Count -eq 0) {
        throw "maven-manifest.json 未记录任何源码文件。"
    }
    $sourceSeen = @{}
    $manifestSourceSet = @()
    $validatedSourceRecords = [System.Collections.Generic.List[object]]::new()
    foreach ($sourceRecord in $sourceRecords) {
        $sourceRelativePath = [string](
            Get-RequiredProperty $sourceRecord "path" "sourceFiles"
        )
        Assert-UniqueRelativePath $sourceRelativePath $sourceSeen "源码文件路径"
        Assert-PackagePathAllowed $sourceRelativePath "源码文件路径"
        $sourceLookup = $sourceRelativePath.ToLowerInvariant()
        if ($sourceLookup.StartsWith(
                "maven-repository/",
                [System.StringComparison]::Ordinal
            ) -or
                $metadataFileSet.ContainsKey($sourceLookup)) {
            throw "sourceFiles 不得记录 Maven 仓库或发布元数据路径：$sourceRelativePath"
        }
        $sourceSize = [long](
            Get-RequiredProperty $sourceRecord "size" "sourceFiles"
        )
        $sourceHash = [string](
            Get-RequiredProperty $sourceRecord "sha256" "sourceFiles"
        )
        if ($sourceSize -lt 0 -or $sourceHash -notmatch '^[0-9a-fA-F]{64}$') {
            throw "源码文件大小或 SHA-256 格式无效：$sourceRelativePath"
        }
        $sourceFullPath = Get-PackagePath `
            $sourceRelativePath `
            $verificationRoot `
            "源码文件"
        if (-not (Test-Path -LiteralPath $sourceFullPath -PathType Leaf)) {
            throw "源码文件缺失：$sourceRelativePath"
        }
        $actualSourceSize = (Get-Item -LiteralPath $sourceFullPath -Force).Length
        if ($actualSourceSize -ne $sourceSize) {
            throw "源码文件大小不符：$sourceRelativePath 预期=$sourceSize 实际=$actualSourceSize"
        }
        $actualSourceHash = Get-Sha256 $sourceFullPath
        if ($actualSourceHash -ne $sourceHash.ToLowerInvariant()) {
            throw "源码文件 SHA-256 不符：$sourceRelativePath"
        }
        $manifestSourceSet += $sourceLookup
        $validatedSourceRecords.Add([ordered]@{
            path = $sourceRelativePath
            size = $sourceSize
            sha256 = $sourceHash.ToLowerInvariant()
        })
    }
    $actualSourceSet = @(Get-TreeFiles $verificationRoot | ForEach-Object {
        $relativePath = Get-RelativePathFromRoot $_.FullName $verificationRoot
        $lookup = $relativePath.ToLowerInvariant()
        if (-not $lookup.StartsWith(
                "maven-repository/",
                [System.StringComparison]::Ordinal
            ) -and
                -not $metadataFileSet.ContainsKey($lookup)) {
            $lookup
        }
    } | Where-Object { $null -ne $_ } | Sort-Object)
    $expectedSourceSet = @($manifestSourceSet | Sort-Object)
    if (($actualSourceSet -join "|") -ne ($expectedSourceSet -join "|")) {
        $missing = @($expectedSourceSet | Where-Object { $actualSourceSet -notcontains $_ })
        $extra = @($actualSourceSet | Where-Object { $expectedSourceSet -notcontains $_ })
        throw "sourceFiles 清单不符。缺失=$($missing -join ',') 额外=$($extra -join ',')"
    }
    $actualSourceTreeSha256 = Get-SourceTreeSha256 $validatedSourceRecords
    if ($actualSourceTreeSha256 -ne $sourceTreeSha256.ToLowerInvariant()) {
        throw "sourceTreeSha256 不符。预期=$($sourceTreeSha256.ToLowerInvariant()) 实际=$actualSourceTreeSha256"
    }

    $repositoryRecords = @(Get-RequiredProperty $manifest "mavenRepositoryFiles" "maven-manifest.json")
    if ($repositoryRecords.Count -eq 0) {
        throw "maven-manifest.json 未记录 Maven 仓库文件。"
    }
    $repositorySeen = @{}
    $manifestRepositorySet = @()
    foreach ($record in $repositoryRecords) {
        $relativePath = [string](Get-RequiredProperty $record "path" "mavenRepositoryFiles")
        Assert-UniqueRelativePath $relativePath $repositorySeen "Maven 仓库文件路径"
        if (-not $relativePath.StartsWith(
                "maven-repository/",
                [System.StringComparison]::Ordinal
            )) {
            throw "Maven 仓库文件路径未位于 maven-repository：$relativePath"
        }
        $recordSize = [long](Get-RequiredProperty $record "size" "mavenRepositoryFiles")
        $recordHash = [string](Get-RequiredProperty $record "sha256" "mavenRepositoryFiles")
        if ($recordSize -lt 0) {
            throw "Maven 仓库文件大小无效：$relativePath"
        }
        if ($recordHash -notmatch '^[0-9a-fA-F]{64}$') {
            throw "Maven 仓库文件 SHA-256 格式无效：$relativePath"
        }
        $fullPath = Get-PackagePath $relativePath $verificationRoot "Maven 仓库文件"
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "Maven 仓库文件缺失：$relativePath"
        }
        $actualSize = (Get-Item -LiteralPath $fullPath -Force).Length
        if ($actualSize -ne $recordSize) {
            throw "Maven 仓库文件大小不符：$relativePath 预期=$recordSize 实际=$actualSize"
        }
        $actualHash = Get-Sha256 $fullPath
        if ($actualHash -ne $recordHash.ToLowerInvariant()) {
            throw "Maven 仓库文件 SHA-256 不符：$relativePath"
        }
        $manifestRepositorySet += $relativePath.ToLowerInvariant()
    }
    $actualRepositorySet = @(Get-TreeFiles $repositoryPath | ForEach-Object {
        (Get-RelativePathFromRoot $_.FullName $verificationRoot).ToLowerInvariant()
    } | Sort-Object)
    $expectedRepositorySet = @($manifestRepositorySet | Sort-Object)
    if (($actualRepositorySet -join "|") -ne ($expectedRepositorySet -join "|")) {
        $missing = @($expectedRepositorySet | Where-Object { $actualRepositorySet -notcontains $_ })
        $extra = @($actualRepositorySet | Where-Object { $expectedRepositorySet -notcontains $_ })
        throw "Maven 仓库文件清单不符。缺失=$($missing -join ',') 额外=$($extra -join ',')"
    }

    $checksumPath = Join-Path $verificationRoot "files.sha256"
    $checksumLines = @(Get-Content -Encoding UTF8 -LiteralPath $checksumPath)
    $checksumSeen = @{}
    $checksumSet = @()
    foreach ($line in $checksumLines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^(?<hash>[0-9a-fA-F]{64})  (?<path>.+)$') {
            throw "files.sha256 行格式损坏：$line"
        }
        $relativePath = $Matches.path
        Assert-UniqueRelativePath $relativePath $checksumSeen "files.sha256 路径"
        if ($relativePath -eq "files.sha256") {
            throw "files.sha256 不得记录自身。"
        }
        $expectedHash = $Matches.hash.ToLowerInvariant()
        $fullPath = Get-PackagePath $relativePath $verificationRoot "files.sha256 文件"
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            throw "files.sha256 记录的文件缺失：$relativePath"
        }
        $actualHash = Get-Sha256 $fullPath
        if ($actualHash -ne $expectedHash) {
            throw "files.sha256 记录的 SHA-256 不符：$relativePath"
        }
        $checksumSet += $relativePath.ToLowerInvariant()
    }
    if ($checksumSet.Count -eq 0) {
        throw "files.sha256 为空。"
    }
    $actualFileSet = @(Get-TreeFiles $verificationRoot | ForEach-Object {
        (Get-RelativePathFromRoot $_.FullName $verificationRoot).ToLowerInvariant()
    } | Where-Object { $_ -ne "files.sha256" } | Sort-Object)
    $expectedFileSet = @($checksumSet | Sort-Object)
    if (($actualFileSet -join "|") -ne ($expectedFileSet -join "|")) {
        $missing = @($expectedFileSet | Where-Object { $actualFileSet -notcontains $_ })
        $extra = @($actualFileSet | Where-Object { $expectedFileSet -notcontains $_ })
        throw "files.sha256 文件清单不符。缺失=$($missing -join ',') 额外=$($extra -join ',')"
    }

    $allowedDirectories = @{}
    foreach ($relativePath in $actualFileSet) {
        $segments = @($relativePath -split "/")
        for ($index = 1; $index -lt $segments.Count; $index++) {
            $allowedDirectories[(($segments[0..($index - 1)]) -join "/")] = $true
        }
    }
    foreach ($directory in @(Get-TreeDirectories $verificationRoot)) {
        $relativeDirectory = (
            Get-RelativePathFromRoot $directory.FullName $verificationRoot
        ).ToLowerInvariant()
        if (-not $allowedDirectories.ContainsKey($relativeDirectory)) {
            throw "离线包含未登记的空目录或额外目录：$relativeDirectory"
        }
    }

    $thirdPartyPath = Join-Path $verificationRoot "third-party-artifacts.json"
    try {
        $thirdPartyManifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $thirdPartyPath |
            ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "third-party-artifacts.json JSON 损坏：$($_.Exception.Message)"
    }
    if ([int](Get-RequiredProperty $thirdPartyManifest "schemaVersion" "third-party-artifacts.json") -ne 1) {
        throw "不支持 third-party-artifacts.json schemaVersion。"
    }
    if ([string](Get-RequiredProperty $thirdPartyManifest "licenseConclusion" "third-party-artifacts.json") -ne
            "NOT_DETERMINED") {
        throw "第三方制品清单不得伪造许可结论。"
    }
    if ([string]::IsNullOrWhiteSpace(
            [string](Get-RequiredProperty $thirdPartyManifest "warning" "third-party-artifacts.json")
        )) {
        throw "第三方制品清单缺少许可风险提示。"
    }
    $thirdPartySeen = @{}
    foreach ($artifact in @(Get-RequiredProperty $thirdPartyManifest "artifacts" "third-party-artifacts.json")) {
        $artifactPath = [string](Get-RequiredProperty $artifact "path" "third-party artifacts")
        Assert-UniqueRelativePath $artifactPath $thirdPartySeen "第三方制品路径"
        if (-not $artifactPath.StartsWith(
                "maven-repository/",
                [System.StringComparison]::Ordinal
            ) -or
                $artifactPath.StartsWith(
                    "maven-repository/com/orderly/",
                    [System.StringComparison]::Ordinal
                ) -or
                $artifactPath -notmatch '\.(?:jar|pom)$') {
            throw "第三方制品路径无效：$artifactPath"
        }
        $artifactFullPath = Get-PackagePath $artifactPath $verificationRoot "第三方制品"
        if (-not (Test-Path -LiteralPath $artifactFullPath -PathType Leaf)) {
            throw "第三方制品缺失：$artifactPath"
        }
        $artifactSize = [long](Get-RequiredProperty $artifact "size" "third-party artifacts")
        if ((Get-Item -LiteralPath $artifactFullPath -Force).Length -ne $artifactSize) {
            throw "第三方制品大小不符：$artifactPath"
        }
        $artifactHash = [string](Get-RequiredProperty $artifact "sha256" "third-party artifacts")
        if ((Get-Sha256 $artifactFullPath) -ne $artifactHash.ToLowerInvariant() -or
                [string](Get-RequiredProperty $artifact "licenseStatus" "third-party artifacts") -ne
                    "NOT_DETERMINED") {
            throw "第三方制品哈希或许可状态无效：$artifactPath"
        }
    }
    $actualThirdPartySet = @(Get-TreeFiles $repositoryPath | ForEach-Object {
        $repositoryRelativePath = Get-RelativePathFromRoot $_.FullName $repositoryPath
        if (-not $repositoryRelativePath.StartsWith(
                "com/orderly/",
                [System.StringComparison]::Ordinal
            ) -and
                $repositoryRelativePath -match '\.(?:jar|pom)$') {
            ("maven-repository/" + $repositoryRelativePath).ToLowerInvariant()
        }
    } | Where-Object { $null -ne $_ } | Sort-Object)
    $expectedThirdPartySet = @($thirdPartySeen.Keys | Sort-Object)
    if (($actualThirdPartySet -join "|") -ne ($expectedThirdPartySet -join "|")) {
        $missing = @($expectedThirdPartySet | Where-Object { $actualThirdPartySet -notcontains $_ })
        $extra = @($actualThirdPartySet | Where-Object { $expectedThirdPartySet -notcontains $_ })
        throw "第三方制品集合不符。缺失=$($missing -join ',') 额外=$($extra -join ',')"
    }

    Write-Host "包结构、清单、文件哈希与第三方风险声明校验通过。"
    $buildScript = Join-Path $verificationRoot "scripts\build.ps1"
    if (-not (Test-Path -LiteralPath $buildScript -PathType Leaf)) {
        throw "离线包缺少 scripts/build.ps1。"
    }
    Assert-NoReparsePoint $buildScript "离线包构建脚本"
    Push-Location $verificationRoot
    try {
        Write-Host "在包内执行严格离线 Maven 构建。"
        & $buildScript -Offline -MavenRepository $repositoryPath -MaxParallel $MaxParallel
        if (-not $? -or $LASTEXITCODE -ne 0) {
            throw "包内严格离线构建失败，退出码：$LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
    Write-Host "离线包验证通过：包内 Maven 模块与宿主已使用 -o 构建完成。"
}
catch {
    $failure = $_
}
finally {
    try {
        if (Test-Path -LiteralPath $verificationRoot) {
            $safeRoot = Assert-PathWithin $verificationRoot $outputRoot "离线验证临时目录"
            if ($safeRoot.Equals($outputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "拒绝清理离线包输出根目录。"
            }
            Assert-NoReparsePoint $safeRoot "离线验证临时目录"
            Remove-Item -LiteralPath $safeRoot -Recurse -Force
        }
    }
    catch {
        $cleanupFailure = $_
    }
}

if ($failure) {
    $message = "离线包验证失败：$($failure.Exception.Message)"
    if ($cleanupFailure) {
        $message += "；临时目录清理也失败：$($cleanupFailure.Exception.Message)"
    }
    throw $message
}
if ($cleanupFailure) {
    throw "离线包验证成功但临时目录清理失败：$($cleanupFailure.Exception.Message)"
}
