[CmdletBinding()]
param([int]$Port = 18081)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$moduleRoot = Join-Path $repoRoot "Module"
$themesRoot = Join-Path $repoRoot "themes"
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$stagingRoot = Join-Path $repoRoot "target\orderly-modules"
$backupRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot ("modularity-backup-" + [guid]::NewGuid().ToString("N"))))
$dataRoot = [System.IO.Path]::GetFullPath((Join-Path $targetRoot ("modularity-data-" + [guid]::NewGuid().ToString("N"))))
$script:process = $null
$script:movedModules = @()
$script:movedTheme = $null
$script:generatedTheme = $null
$script:generatedModule = $null
$script:succeeded = $false

function Assert-Java21 {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw "JAVA_HOME 未设置。" }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) { throw "未找到 JAVA_HOME Java：$javaExe" }
    $version = (& $javaExe -version 2>&1) | Out-String
    if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "(?<major>\d+)' -or [int]$Matches.major -ne 21) {
        throw "模块化验收需要 JDK 21：`n$version"
    }
    return $javaExe
}

function Invoke-Build {
    & (Join-Path $PSScriptRoot "build.ps1") -SkipTests
    if ($LASTEXITCODE -ne 0) { throw "模块化构建失败，退出码：$LASTEXITCODE" }
}

function Start-Orderly([string]$javaExe) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "app\host\target") -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw "未找到宿主 fat JAR。" }
    $stdout = Join-Path $backupRoot "server-out.log"
    $stderr = Join-Path $backupRoot "server-err.log"
    $script:process = Start-Process -FilePath $javaExe -ArgumentList @(
        "-Dorderly.modules-dir=$stagingRoot",
        "-Dorderly.themes-dir=$themesRoot",
        "-jar", $jar.FullName,
        "--server.port=$Port",
        "--orderly.data-dir=$dataRoot"
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        Start-Sleep -Milliseconds 500
        if ($script:process.HasExited) {
            $errorText = if (Test-Path -LiteralPath $stderr) { Get-Content -Raw $stderr } else { "" }
            throw "宿主提前退出，退出码：$($script:process.ExitCode)。`n$errorText"
        }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
            if ($health.status -eq "UP") { return }
        } catch {
            # 启动窗口内继续等待。
        }
    }
    throw "宿主未在限定时间内就绪。"
}

function Stop-Orderly {
    if ($script:process -and -not $script:process.HasExited) {
        Stop-Process -Id $script:process.Id -Force
        Wait-Process -Id $script:process.Id -Timeout 10 -ErrorAction SilentlyContinue
    }
    $script:process = $null
}

function Assert-PathWithin([string]$Path, [string]$Root, [string]$Description) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    if (-not $fullPath.Equals($fullRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            -not $fullPath.StartsWith($fullRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description 超出允许目录：$fullPath"
    }
    return $fullPath
}

function Remove-TargetArtifact([string]$Path) {
    $safePath = Assert-PathWithin $Path $targetRoot "验收临时路径"
    if (Test-Path -LiteralPath $safePath -PathType Leaf) {
        Remove-Item -LiteralPath $safePath -Force
    } elseif (Test-Path -LiteralPath $safePath -PathType Container) {
        Remove-Item -LiteralPath $safePath -Recurse -Force
    }
}

function Remove-ModularityTargetArtifacts {
    if (-not (Test-Path -LiteralPath $targetRoot -PathType Container)) {
        return
    }
    Get-ChildItem -LiteralPath $targetRoot -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "modularity-backup-*" -or $_.Name -like "modularity-data-*" } |
        ForEach-Object { Remove-TargetArtifact $_.FullName }
}
function Write-Utf8File([string]$Path, [string]$Content) {
    $parent = Split-Path -Parent $Path
    [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Move-ModuleToBackup([System.IO.DirectoryInfo]$module) {
    $source = Assert-PathWithin $module.FullName $moduleRoot "模块源目录"
    $destination = Assert-PathWithin (Join-Path $backupRoot $module.Name) $targetRoot "模块验收备份"
    Move-Item -LiteralPath $source -Destination $destination
    $script:movedModules += [pscustomobject]@{ Source = $source; Backup = $destination }
}

function Restore-MovedSources {
    foreach ($entry in $script:movedModules) {
        if ((Test-Path -LiteralPath $entry.Backup) -and -not (Test-Path -LiteralPath $entry.Source)) {
            Move-Item -LiteralPath $entry.Backup -Destination $entry.Source
        }
    }
    if ($script:movedTheme -and (Test-Path -LiteralPath $script:movedTheme.Backup) -and -not (Test-Path -LiteralPath $script:movedTheme.Source)) {
        Move-Item -LiteralPath $script:movedTheme.Backup -Destination $script:movedTheme.Source
    }
    foreach ($entry in $script:movedModules) {
        if (-not (Test-Path -LiteralPath $entry.Source)) { throw "模块源目录未恢复：$($entry.Source)" }
    }
    if ($script:movedTheme -and -not (Test-Path -LiteralPath $script:movedTheme.Source)) {
        throw "主题源文件未恢复：$($script:movedTheme.Source)"
    }
}

function Remove-GeneratedTheme {
    if (-not $script:generatedTheme) {
        return
    }
    $source = Assert-PathWithin $script:generatedTheme.Source $themesRoot "临时主题源文件"
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        $destination = Assert-PathWithin (Join-Path $backupRoot $script:generatedTheme.Name) $targetRoot "临时主题备份"
        if (Test-Path -LiteralPath $destination) {
            throw "临时主题备份路径已存在：$destination"
        }
        Move-Item -LiteralPath $source -Destination $destination
        $script:generatedTheme.Backup = $destination
    }
    if ($script:generatedTheme.Backup -and (Test-Path -LiteralPath $script:generatedTheme.Backup)) {
        Remove-TargetArtifact $script:generatedTheme.Backup
    }
    $script:movedTheme = $null
    $script:generatedTheme = $null
}

function Move-GeneratedModuleToBackup {
    if (-not $script:generatedModule) {
        return
    }
    $source = Assert-PathWithin $script:generatedModule.Source $moduleRoot "临时模块源目录"
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "临时模块源目录不存在：$source"
    }
    $destination = Assert-PathWithin (Join-Path $backupRoot $script:generatedModule.ModuleId) $targetRoot "临时模块备份"
    if (Test-Path -LiteralPath $destination) {
        throw "临时模块备份路径已存在：$destination"
    }
    Move-Item -LiteralPath $source -Destination $destination
    $script:generatedModule.Backup = $destination
}

function Restore-GeneratedModule {
    if (-not $script:generatedModule) {
        return
    }
    $source = Assert-PathWithin $script:generatedModule.Source $moduleRoot "临时模块源目录"
    if ($script:generatedModule.Backup -and
            (Test-Path -LiteralPath $script:generatedModule.Backup -PathType Container) -and
            -not (Test-Path -LiteralPath $source)) {
        Move-Item -LiteralPath $script:generatedModule.Backup -Destination $source
        $script:generatedModule.Backup = $null
    }
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "临时模块源目录未恢复：$source"
    }
}

function Remove-GeneratedModule {
    if (-not $script:generatedModule) {
        return
    }
    if (Test-Path -LiteralPath $script:generatedModule.Source -PathType Container) {
        Move-GeneratedModuleToBackup
    }
    if ($script:generatedModule.Backup -and (Test-Path -LiteralPath $script:generatedModule.Backup)) {
        Remove-TargetArtifact $script:generatedModule.Backup
    }
    $script:generatedModule = $null
}

function New-AcceptanceModule {
    $suffix = [guid]::NewGuid().ToString("N")
    $moduleId = "module_acceptance_$suffix"
    $actionId = "acceptance.$suffix"
    $javaSuffix = "m$suffix"
    $packageName = "com.orderly.module.acceptance.$javaSuffix"
    $className = "AcceptanceModule"
    $source = Assert-PathWithin (Join-Path $moduleRoot $moduleId) $moduleRoot "临时模块源目录"
    if (Test-Path -LiteralPath $source) {
        throw "临时模块目录已存在：$source"
    }

    $pom = @"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.orderly</groupId>
        <artifactId>orderly-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>
    <artifactId>$moduleId</artifactId>
    <name>Orderly Acceptance Module</name>
    <dependencies>
        <dependency>
            <groupId>com.orderly</groupId>
            <artifactId>orderly-kernel</artifactId>
            <version>`${project.version}</version>
        </dependency>
    </dependencies>
</project>
"@

    $javaSource = @"
package $packageName;

import com.orderly.kernel.ModuleInvocation;
import com.orderly.kernel.ModuleResult;
import com.orderly.kernel.ModuleRuntime;
import com.orderly.kernel.OrderlyModule;

import java.util.Map;

public final class $className implements OrderlyModule {

    public static final String MODULE_ID = "$moduleId";
    public static final String ACTION_ID = "$actionId";

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public Map<String, Object> prepareSnapshot(
            ModuleInvocation invocation,
            ModuleRuntime runtime
    ) {
        return Map.of();
    }

    @Override
    public ModuleResult execute(ModuleInvocation invocation, ModuleRuntime runtime) {
        if (!ACTION_ID.equals(invocation.actionId())) {
            throw new IllegalArgumentException("Unknown acceptance action: " + invocation.actionId());
        }
        return ModuleResult.completed("Acceptance action completed");
    }

    @Override
    public ModuleResult rollback(
            ModuleInvocation invocation,
            Map<String, Object> snapshot,
            Map<String, Object> executionOutput,
            ModuleRuntime runtime
    ) {
        return ModuleResult.completed("Acceptance action rolled back");
    }
}
"@

    $contract = [ordered]@{
        moduleId = $moduleId
        title = "模块化验收"
        description = "由 modularity.ps1 临时创建并自动发现的只读验收模块。"
        order = 999
        actions = @(
            [ordered]@{
                id = $actionId
                title = "模块发现检查"
                description = "仅用于验证外部模块自动发现。"
                risk = "READ_ONLY"
                confirmationRequired = $false
                parameters = @()
            }
        )
    } | ConvertTo-Json -Depth 8

    $javaPath = Join-Path $source "src\main\java\com\orderly\module\acceptance\$javaSuffix\$className.java"
    $servicePath = Join-Path $source "src\main\resources\META-INF\services\com.orderly.kernel.OrderlyModule"
    $contractPath = Join-Path $source "src\main\resources\orderly-modules\$moduleId.json"
    Write-Utf8File (Join-Path $source "pom.xml") ($pom.Trim() + "`n")
    Write-Utf8File $javaPath ($javaSource.Trim() + "`n")
    Write-Utf8File $servicePath ("$packageName.$className`n")
    Write-Utf8File $contractPath ($contract + "`n")

    Write-Host "创建临时验收模块：$moduleId"
    return [pscustomobject]@{
        Source = $source
        Backup = $null
        ModuleId = $moduleId
        ActionId = $actionId
    }
}

function Assert-ModuleIds([string[]]$Expected) {
    $actual = @(Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/modules" -TimeoutSec 5 | ForEach-Object { $_.moduleId })
    $expectedSorted = @($Expected | Sort-Object)
    $actualSorted = @($actual | Sort-Object)
    if (($expectedSorted -join "|") -ne ($actualSorted -join "|")) {
        throw "模块列表不符合预期。预期=$($expectedSorted -join ',') 实际=$($actualSorted -join ',')"
    }
}

Push-Location $repoRoot
try {
    $javaExe = Assert-Java21
    New-Item -ItemType Directory -Path $backupRoot | Out-Null
    New-Item -ItemType Directory -Path $dataRoot | Out-Null
    $baselineModules = @(Get-ChildItem -LiteralPath $moduleRoot -Directory -Filter "module_*" | Sort-Object Name)
    if ($baselineModules.Count -eq 0) { throw "模块化验收至少需要一个模块源目录。" }
    $baselineIds = @($baselineModules | ForEach-Object { $_.Name })

    Write-Host "[1/6] 全模块启动"
    Invoke-Build
    Start-Orderly $javaExe
    Assert-ModuleIds -Expected $baselineIds
    Stop-Orderly

    Write-Host "[2/6] 移出一个模块"
    $removedOne = $baselineModules[-1]
    Move-ModuleToBackup $removedOne
    Invoke-Build
    Start-Orderly $javaExe
    $remainingIds = @($baselineIds | Where-Object { $_ -ne $removedOne.Name })
    Assert-ModuleIds -Expected $remainingIds
    $removedGoal = switch ($removedOne.Name) {
        "module_fs" { "创建目录 被移除模块目录" }
        "module_todo" { "创建待办 被移除模块事项" }
        "module_calendar" { "把日程 项目评审 改到 2026-12-01 10:00" }
        default { throw "缺少 $($removedOne.Name) 的验收目标映射。" }
    }
    $questionBody = @{ goal = $removedGoal } | ConvertTo-Json -Compress
    $question = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/plans" -Method Post -ContentType "application/json" -Body $questionBody -TimeoutSec 5
    if ($question.kind -ne "QUESTION") { throw "缺少模块时计划未安全转为 QUESTION。" }
    Stop-Orderly

    Write-Host "[3/6] 移出全部模块"
    $left = @(Get-ChildItem -LiteralPath $moduleRoot -Directory -Filter "module_*" | Sort-Object Name)
    foreach ($module in $left) { Move-ModuleToBackup $module }
    Invoke-Build
    Start-Orderly $javaExe
    Assert-ModuleIds -Expected @()
    $zeroQuestion = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/plans" -Method Post -ContentType "application/json" -Body '{"goal":"创建目录 无模块目录"}' -TimeoutSec 5
    if ($zeroQuestion.kind -ne "QUESTION") { throw "零模块计划未安全转为 QUESTION。" }
    Stop-Orderly

    Write-Host "[4/6] 恢复全部模块"
    Restore-MovedSources
    Invoke-Build
    Start-Orderly $javaExe
    Assert-ModuleIds -Expected $baselineIds
    Stop-Orderly

    Write-Host "[5/6] 增加唯一有效模块并验证自动发现"
    $protectedFiles = [ordered]@{
        RootPom = Join-Path $repoRoot "pom.xml"
        HostPom = Join-Path $repoRoot "app\host\pom.xml"
        FrontendModuleIndex = Join-Path $repoRoot "app\frontend\src\app.js"
    }
    $protectedHashesBefore = @{}
    foreach ($entry in $protectedFiles.GetEnumerator()) {
        $protectedHashesBefore[$entry.Key] = Get-Sha256 $entry.Value
    }
    $script:generatedModule = New-AcceptanceModule
    Invoke-Build
    Start-Orderly $javaExe
    $idsWithAcceptance = @($baselineIds + $script:generatedModule.ModuleId)
    Assert-ModuleIds -Expected $idsWithAcceptance
    $moduleCatalog = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/modules" -TimeoutSec 5
    $acceptanceEntry = @($moduleCatalog | Where-Object { $_.moduleId -eq $script:generatedModule.ModuleId })
    if ($acceptanceEntry.Count -ne 1) {
        throw "临时验收模块未自动出现在 /api/modules。"
    }
    $acceptanceActions = @($acceptanceEntry[0].actions | ForEach-Object { $_.id })
    if ($acceptanceActions -notcontains $script:generatedModule.ActionId) {
        throw "临时验收模块动作未出现在 /api/modules。"
    }
    foreach ($entry in $protectedFiles.GetEnumerator()) {
        $after = Get-Sha256 $entry.Value
        if ($protectedHashesBefore[$entry.Key] -ne $after) {
            throw "自动模块发现不应修改受保护登记文件：$($entry.Value)"
        }
    }
    Stop-Orderly

    Move-GeneratedModuleToBackup
    Invoke-Build
    Start-Orderly $javaExe
    Assert-ModuleIds -Expected $baselineIds
    Stop-Orderly
    Restore-GeneratedModule
    Move-GeneratedModuleToBackup
    Write-Host "临时模块已验证可逆移出，最终位于 target 验收备份中等待安全清理。"

    Write-Host "[6/6] 增加主题并验证删除已选主题后的回退"
    $themeId = "modularity-temp-" + [guid]::NewGuid().ToString("N")
    $themeSource = Join-Path $themesRoot "$themeId.json"
    $themeObject = [ordered]@{
        id = $themeId
        label = "模块化验收主题"
        note = "仅用于安全可逆验收"
        order = 999
        default = $false
        colorScheme = "light"
        browserColor = "Canvas"
        tokens = [ordered]@{ "--color-accent" = "LinkText" }
    }
    $themeJson = $themeObject | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText($themeSource, $themeJson, [System.Text.UTF8Encoding]::new($false))
    $script:generatedTheme = [pscustomobject]@{
        Source = (Assert-PathWithin $themeSource $themesRoot "临时主题源文件")
        Name = "$themeId.json"
        Backup = $null
    }
    Start-Orderly $javaExe
    $themesBeforeResponse = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/themes" -TimeoutSec 5
    $themesBefore = @($themesBeforeResponse | ForEach-Object { $_ })
    $beforeMatch = @($themesBefore | Where-Object { $_.id -eq $themeId })
    if ($beforeMatch.Count -ne 1) { throw "新增主题未出现在 /api/themes。" }
    Stop-Orderly

    $themeBackup = Assert-PathWithin (Join-Path $backupRoot $script:generatedTheme.Name) $targetRoot "临时主题备份"
    Move-Item -LiteralPath $themeSource -Destination $themeBackup
    $script:movedTheme = [pscustomobject]@{ Source = $themeSource; Backup = $themeBackup }
    Start-Orderly $javaExe
    $themesAfterResponse = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/themes" -TimeoutSec 5
    $themesAfter = @($themesAfterResponse | ForEach-Object { $_ })
    $afterMatch = @($themesAfter | Where-Object { $_.id -eq $themeId })
    if ($afterMatch.Count -ne 0) { throw "删除源主题后仍返回旧主题，可能回退到了陈旧 classpath 产物。" }
    $themeJsonArg = $themesAfter | ConvertTo-Json -Compress -Depth 6
    $nodeCode = "import { resolveThemeSelection } from './app/frontend/src/settings.js';`nconst themes = " + $themeJsonArg + ";`nconst fallback = resolveThemeSelection('" + $themeId + "', themes);`nif (fallback !== 'paper') throw new Error('已选主题删除后回退错误：' + fallback);"
    & node --input-type=module --eval $nodeCode
    if ($LASTEXITCODE -ne 0) { throw "前端主题失效回退检查失败，退出码：$LASTEXITCODE" }
    Stop-Orderly

    Write-Host "模块化验收通过：全模块、单模块移除、零模块、恢复、临时模块自动发现与可逆移出、主题新增与已选主题失效回退。"
    $script:succeeded = $true
}
finally {
    Stop-Orderly
    try {
        Restore-MovedSources
        Remove-GeneratedTheme
        Remove-GeneratedModule
        if (Test-Path -LiteralPath (Join-Path $repoRoot "Module")) { Invoke-Build }
        if ($script:succeeded) {
            Remove-ModularityTargetArtifacts
            Write-Host "模块化验收临时路径已清理。"
        }
    } finally {
        Pop-Location
    }
}
