#requires -Version 5.1
# 用途：
#   为开发启动、构建清单和预构建运行包提供统一的内容指纹、产物校验与构建决策。
#
# 输入：
#   RepoRoot、ManifestPath、目标路径；由调用脚本传入，不读取凭据或产品数据。
#
# 副作用：
#   本文件只定义函数；写入函数仅接受仓库 target 下的构建清单路径，并使用原子替换。
#
# 安全边界：
#   指纹只读取显式列出的运行输入，拒绝重解析点；构建清单中的路径必须留在仓库内，
#   任何缺失、格式错误或哈希不符都返回需要构建，而不是静默信任旧产物。

$script:OrderlyBuildManifestSchemaVersion = 1
$script:OrderlySystemModulePrefix = "module_system_"

function Get-OrderlySha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "计算 SHA-256 的文件不存在：$Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-OrderlyStringSha256 {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-OrderlyRelativePath {
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
    if ($fullPath.Equals($fullRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        return "."
    }
    if (-not $fullPath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "路径不在预期仓库根目录内：$fullPath"
    }
    return $fullPath.Substring($prefix.Length).Replace("\", "/")
}

function Assert-OrderlyPathWithin {
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

function Get-OrderlyPathFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Inputs,
        [string[]]$ExcludePrefixes = @()
    )

    $fullRoot = [System.IO.Path]::GetFullPath($Root)
    $files = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
    foreach ($input in $Inputs) {
        $fullInput = Assert-OrderlyPathWithin `
            (Join-Path $fullRoot $input) `
            $fullRoot `
            "构建输入"
        if (-not (Test-Path -LiteralPath $fullInput)) {
            throw "构建输入不存在：$input"
        }
        $item = Get-Item -LiteralPath $fullInput -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "构建输入不能是重解析点：$input"
        }
        if (-not $item.PSIsContainer) {
            $files.Add($item)
            continue
        }

        $stack = [System.Collections.Generic.Stack[string]]::new()
        $stack.Push($item.FullName)
        while ($stack.Count -gt 0) {
            $directory = $stack.Pop()
            foreach ($entry in Get-ChildItem -LiteralPath $directory -Force) {
                if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "构建输入扫描拒绝重解析点：$($entry.FullName)"
                }
                $relative = Get-OrderlyRelativePath $entry.FullName $fullRoot
                $excluded = @($ExcludePrefixes | Where-Object {
                    $relative.Equals($_, [System.StringComparison]::OrdinalIgnoreCase) -or
                    $relative.StartsWith($_.TrimEnd("/") + "/", [System.StringComparison]::OrdinalIgnoreCase)
                }).Count -gt 0
                if ($excluded) {
                    continue
                }
                if ($entry.PSIsContainer) {
                    $stack.Push($entry.FullName)
                }
                else {
                    $files.Add($entry)
                }
            }
        }
    }
    return @($files | Sort-Object FullName -Unique)
}

function Get-OrderlyInputFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string[]]$Inputs,
        [string[]]$ExcludePrefixes = @()
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($file in @(Get-OrderlyPathFiles $RepoRoot $Inputs $ExcludePrefixes)) {
        $relative = Get-OrderlyRelativePath $file.FullName $RepoRoot
        $lines.Add("$relative`t$($file.Length)`t$(Get-OrderlySha256 $file.FullName)")
    }
    $lines.Sort([System.StringComparer]::Ordinal)
    $canonical = if ($lines.Count -eq 0) {
        "empty`n"
    }
    else {
        ($lines -join "`n") + "`n"
    }
    return Get-OrderlyStringSha256 $canonical
}

function Get-OrderlySharedInputFingerprint {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    return Get-OrderlyInputFingerprint `
        -RepoRoot $RepoRoot `
        -Inputs @(
            "pom.xml",
            "app/kernel/pom.xml",
            "app/kernel/src/main",
            "contracts"
        )
}

function Get-OrderlyHostInputFingerprint {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    return Get-OrderlyInputFingerprint `
        -RepoRoot $RepoRoot `
        -Inputs @(
            "app/host/pom.xml",
            "app/host/src/main",
            "app/frontend/index.html",
            "app/frontend/src",
            "app/frontend/styles"
        )
}

function Get-OrderlyModuleSourceDirectories {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $moduleRoot = Join-Path $RepoRoot "Module"
    if (-not (Test-Path -LiteralPath $moduleRoot -PathType Container)) {
        throw "模块源码目录不存在：$moduleRoot"
    }
    return @(Get-ChildItem -LiteralPath $moduleRoot -Directory -Filter "module_*" -Force |
        Sort-Object Name)
}

function Get-OrderlyModuleInputFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$SourceName
    )

    if ($SourceName -notmatch '^module_[a-z0-9_]+$') {
        throw "模块源码名非法：$SourceName"
    }
    return Get-OrderlyInputFingerprint `
        -RepoRoot $RepoRoot `
        -Inputs @(
            "Module/$SourceName/pom.xml",
            "Module/$SourceName/src/main"
        )
}

function Get-OrderlyJdkMajor {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        return 0
    }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
        return 0
    }
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& $javaExe -version 2>&1) | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0 -or $output -notmatch 'version "(?<major>\d+)') {
        return 0
    }
    return [int]$Matches.major
}

function Get-OrderlyProjectVersion {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $pomPath = Join-Path $RepoRoot "pom.xml"
    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $false
    $xml.Load($pomPath)
    $version = [string]$xml.project.version
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "根 pom.xml 未声明项目版本。"
    }
    return $version
}

function Get-OrderlyGitState {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $commit = (& git -C $RepoRoot rev-parse HEAD 2>&1) | Out-String
        $commitExit = $LASTEXITCODE
        $status = (& git -C $RepoRoot status --porcelain=v1 --untracked-files=normal 2>&1) | Out-String
        $statusExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($commitExit -ne 0) {
        throw "读取 Git 提交失败：$($commit.Trim())"
    }
    if ($statusExit -ne 0) {
        throw "读取 Git 工作树状态失败：$($status.Trim())"
    }
    $trimmedCommit = $commit.Trim().ToLowerInvariant()
    if ($trimmedCommit -notmatch '^[0-9a-f]{40,64}$') {
        throw "Git 提交格式无效：$trimmedCommit"
    }
    return [pscustomobject]@{
        Commit = $trimmedCommit
        Dirty = -not [string]::IsNullOrWhiteSpace($status)
    }
}

function Get-OrderlyHostJar {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $hostTargetRoot = Join-Path $RepoRoot "app\host\target"
    $jars = @(Get-ChildItem -LiteralPath $hostTargetRoot -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object Name)
    if ($jars.Count -ne 1) {
        throw "宿主构建应产生且仅产生一个 JAR，实际 $($jars.Count) 个。"
    }
    return $jars[0]
}

function Read-OrderlyBuildManifest {
    param([Parameter(Mandatory = $true)][string]$ManifestPath)

    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        return $null
    }
    try {
        $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $ManifestPath |
            ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        return $null
    }
    if ($null -eq $manifest -or
            [int]$manifest.schemaVersion -ne $script:OrderlyBuildManifestSchemaVersion) {
        return $null
    }
    $requiredProperties = @(
        "projectVersion",
        "gitCommit",
        "gitDirty",
        "jdkMajor",
        "sharedInputSha256",
        "host",
        "modules"
    )
    $presentProperties = @($manifest.PSObject.Properties | ForEach-Object { $_.Name })
    foreach ($propertyName in $requiredProperties) {
        if ($presentProperties -notcontains $propertyName) {
            return $null
        }
    }
    $hostProperties = @($manifest.host.PSObject.Properties | ForEach-Object { $_.Name })
    if ($hostProperties -notcontains "inputSha256" -or
            $hostProperties -notcontains "artifact") {
        return $null
    }
    return $manifest
}

function Test-OrderlyManifestArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)]$Artifact
    )

    $relativePath = [string]$Artifact.path
    if ([string]::IsNullOrWhiteSpace($relativePath) -or
            [System.IO.Path]::IsPathRooted($relativePath)) {
        return $false
    }
    try {
        $fullPath = Assert-OrderlyPathWithin `
            (Join-Path $RepoRoot $relativePath) `
            $RepoRoot `
            "构建产物路径"
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
            return $false
        }
        $item = Get-Item -LiteralPath $fullPath -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            return $false
        }
        if ([long]$Artifact.size -ne $item.Length) {
            return $false
        }
        return [string]$Artifact.sha256 -eq (Get-OrderlySha256 $fullPath)
    }
    catch {
        return $false
    }
}

function Resolve-OrderlyBuildPlan {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$ManifestPath
    )

    $reasons = [System.Collections.Generic.List[string]]::new()
    $manifest = Read-OrderlyBuildManifest $ManifestPath
    if ($null -eq $manifest) {
        $reasons.Add("构建清单缺失、损坏或版本不兼容")
        $sourceDirectories = @(Get-OrderlyModuleSourceDirectories $RepoRoot)
        return [pscustomobject]@{
            BuildRequired = $true
            FullBuildRequired = $true
            HostBuildRequired = $true
            SystemModuleNames = @($sourceDirectories |
                Where-Object { $_.Name.StartsWith($script:OrderlySystemModulePrefix, [System.StringComparison]::Ordinal) } |
                ForEach-Object { $_.Name })
            BusinessModuleNames = @($sourceDirectories |
                Where-Object { -not $_.Name.StartsWith($script:OrderlySystemModulePrefix, [System.StringComparison]::Ordinal) } |
                ForEach-Object { $_.Name })
            Reasons = @($reasons)
        }
    }

    $jdkMajor = Get-OrderlyJdkMajor
    if ($jdkMajor -ne 21 -or [int]$manifest.jdkMajor -ne $jdkMajor) {
        $reasons.Add("JDK 主版本变化或不可确认")
    }
    if ([string]$manifest.projectVersion -ne (Get-OrderlyProjectVersion $RepoRoot)) {
        $reasons.Add("项目版本变化")
    }

    $sharedFingerprint = Get-OrderlySharedInputFingerprint $RepoRoot
    if ([string]$manifest.sharedInputSha256 -ne $sharedFingerprint) {
        $reasons.Add("共享 kernel、根 POM 或契约输入变化")
    }
    $hostFingerprint = Get-OrderlyHostInputFingerprint $RepoRoot
    if ([string]$manifest.host.inputSha256 -ne $hostFingerprint) {
        $reasons.Add("宿主或前端运行输入变化")
    }
    if (-not (Test-OrderlyManifestArtifact -RepoRoot $RepoRoot -Artifact $manifest.host.artifact)) {
        $reasons.Add("宿主 JAR 缺失或哈希不符")
    }

    $sourceDirectories = @(Get-OrderlyModuleSourceDirectories $RepoRoot)
    $sourceNames = @($sourceDirectories | ForEach-Object { $_.Name })
    $manifestModules = @($manifest.modules)
    $manifestNames = @($manifestModules | ForEach-Object { [string]$_.sourceName })
    $removedModules = @($manifestNames | Where-Object { $sourceNames -notcontains $_ })
    if ($removedModules.Count -gt 0) {
        $reasons.Add("模块源码已移除：" + ($removedModules -join ", "))
    }

    $systemModuleNames = [System.Collections.Generic.List[string]]::new()
    $businessModuleNames = [System.Collections.Generic.List[string]]::new()
    foreach ($sourceName in $sourceNames) {
        $record = $manifestModules |
            Where-Object { [string]$_.sourceName -eq $sourceName } |
            Select-Object -First 1
        $currentFingerprint = Get-OrderlyModuleInputFingerprint $RepoRoot $sourceName
        $requiresBuild = $null -eq $record -or
            [string]$record.inputSha256 -ne $currentFingerprint -or
            -not (Test-OrderlyManifestArtifact -RepoRoot $RepoRoot -Artifact $record.artifact)
        if ($requiresBuild) {
            if ($sourceName.StartsWith($script:OrderlySystemModulePrefix, [System.StringComparison]::Ordinal)) {
                $systemModuleNames.Add($sourceName)
            }
            else {
                $businessModuleNames.Add($sourceName)
            }
            $reasons.Add("模块需要构建：$sourceName")
        }
    }

    $sharedChanged = [string]$manifest.sharedInputSha256 -ne $sharedFingerprint
    $hostChanged = [string]$manifest.host.inputSha256 -ne $hostFingerprint -or
        -not (Test-OrderlyManifestArtifact -RepoRoot $RepoRoot -Artifact $manifest.host.artifact)
    $fullBuildRequired = $sharedChanged -or $removedModules.Count -gt 0 -or $jdkMajor -ne 21 -or
        [int]$manifest.jdkMajor -ne $jdkMajor -or
        [string]$manifest.projectVersion -ne (Get-OrderlyProjectVersion $RepoRoot)
    if ($fullBuildRequired) {
        $systemModuleNames.Clear()
        $businessModuleNames.Clear()
        foreach ($sourceName in $sourceNames) {
            if ($sourceName.StartsWith($script:OrderlySystemModulePrefix, [System.StringComparison]::Ordinal)) {
                $systemModuleNames.Add($sourceName)
            }
            else {
                $businessModuleNames.Add($sourceName)
            }
        }
    }

    return [pscustomobject]@{
        BuildRequired = $fullBuildRequired -or $hostChanged -or
            $systemModuleNames.Count -gt 0 -or $businessModuleNames.Count -gt 0
        FullBuildRequired = $fullBuildRequired
        HostBuildRequired = $hostChanged -or $systemModuleNames.Count -gt 0
        SystemModuleNames = @($systemModuleNames)
        BusinessModuleNames = @($businessModuleNames)
        Reasons = @($reasons)
    }
}

function Write-OrderlyBuildManifest {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$ManifestPath,
        [Parameter(Mandatory = $true)][ValidateSet("full", "bootstrap", "modules-only")]
        [string]$Mode,
        [string[]]$BuiltModuleNames = @(),
        [switch]$PreserveBusinessModules
    )

    $targetRoot = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot "target"))
    $safeManifestPath = Assert-OrderlyPathWithin $ManifestPath $targetRoot "构建清单"
    $existing = Read-OrderlyBuildManifest $safeManifestPath
    $hostJar = Get-OrderlyHostJar $RepoRoot
    $hostRelative = Get-OrderlyRelativePath $hostJar.FullName $RepoRoot
    $hostArtifact = [ordered]@{
        path = $hostRelative
        size = [long]$hostJar.Length
        sha256 = Get-OrderlySha256 $hostJar.FullName
    }
    if ($Mode -eq "modules-only") {
        if ($null -eq $existing -or
                -not (Test-OrderlyManifestArtifact -RepoRoot $RepoRoot -Artifact $existing.host.artifact)) {
            throw "仅构建模块时缺少有效宿主清单；请先完成宿主构建。"
        }
        $hostArtifact = $existing.host.artifact
    }

    $sourceDirectories = @(Get-OrderlyModuleSourceDirectories $RepoRoot)
    $sourceNames = @($sourceDirectories | ForEach-Object { $_.Name })
    $existingModules = if ($null -eq $existing) { @() } else { @($existing.modules) }
    $moduleRecords = [System.Collections.Generic.List[object]]::new()
    foreach ($sourceName in $sourceNames) {
        $artifactPath = Join-Path $RepoRoot "target\orderly-modules\$sourceName.jar"
        $currentInput = Get-OrderlyModuleInputFingerprint $RepoRoot $sourceName
        $existingModule = $existingModules |
            Where-Object { [string]$_.sourceName -eq $sourceName } |
            Select-Object -First 1
        $wasBuilt = $BuiltModuleNames -contains $sourceName
        $mustPreserve = $Mode -eq "bootstrap" -and
            $PreserveBusinessModules -and
            -not $sourceName.StartsWith($script:OrderlySystemModulePrefix, [System.StringComparison]::Ordinal)
        if ($mustPreserve) {
            if ($null -eq $existingModule -or
                    [string]$existingModule.inputSha256 -ne $currentInput -or
                    -not (Test-OrderlyManifestArtifact -RepoRoot $RepoRoot -Artifact $existingModule.artifact)) {
                throw "拒绝保留输入已变化或产物无效的业务模块：$sourceName"
            }
        }
        if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
            if ($Mode -eq "full" -or $wasBuilt) {
                throw "模块构建产物缺失：$artifactPath"
            }
            continue
        }
        $artifact = Get-Item -LiteralPath $artifactPath -Force
        if (($artifact.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "模块构建产物不能是重解析点：$artifactPath"
        }
        $artifactRecord = [ordered]@{
            path = Get-OrderlyRelativePath $artifact.FullName $RepoRoot
            size = [long]$artifact.Length
            sha256 = Get-OrderlySha256 $artifact.FullName
        }
        if (-not $wasBuilt -and -not $mustPreserve -and $Mode -ne "full") {
            if ($null -eq $existingModule -or
                    [string]$existingModule.inputSha256 -ne $currentInput -or
                    -not (Test-OrderlyManifestArtifact -RepoRoot $RepoRoot -Artifact $existingModule.artifact)) {
                continue
            }
        }
        $moduleRecords.Add([ordered]@{
            sourceName = $sourceName
            moduleId = $sourceName
            inputSha256 = $currentInput
            artifact = $artifactRecord
        })
    }

    $gitState = Get-OrderlyGitState $RepoRoot
    $manifest = [ordered]@{
        schemaVersion = $script:OrderlyBuildManifestSchemaVersion
        projectVersion = Get-OrderlyProjectVersion $RepoRoot
        gitCommit = $gitState.Commit
        gitDirty = [bool]$gitState.Dirty
        jdkMajor = Get-OrderlyJdkMajor
        createdAt = (Get-Date).ToUniversalTime().ToString("o")
        sharedInputSha256 = Get-OrderlySharedInputFingerprint $RepoRoot
        host = [ordered]@{
            inputSha256 = Get-OrderlyHostInputFingerprint $RepoRoot
            artifact = $hostArtifact
        }
        modules = @($moduleRecords | Sort-Object { $_.sourceName })
    }
    if ($manifest.jdkMajor -ne 21) {
        throw "构建清单要求 JDK 21，实际：$($manifest.jdkMajor)"
    }

    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $safeManifestPath)) | Out-Null
    $temporary = "$safeManifestPath.tmp-$([guid]::NewGuid().ToString('N'))"
    try {
        [System.IO.File]::WriteAllText(
            $temporary,
            (($manifest | ConvertTo-Json -Depth 12) + [Environment]::NewLine),
            [System.Text.UTF8Encoding]::new($false)
        )
        if (Test-Path -LiteralPath $safeManifestPath -PathType Leaf) {
            $backup = "$safeManifestPath.backup-$([guid]::NewGuid().ToString('N'))"
            try {
                [System.IO.File]::Replace($temporary, $safeManifestPath, $backup, $true)
            }
            finally {
                if (Test-Path -LiteralPath $backup) {
                    Remove-Item -LiteralPath $backup -Force
                }
            }
        }
        else {
            [System.IO.File]::Move($temporary, $safeManifestPath)
        }
    }
    finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    Write-Host "构建清单已更新：$safeManifestPath"
}
