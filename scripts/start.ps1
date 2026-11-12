[CmdletBinding()]
param(
    [int]$Port = 8080,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$hostRoot = Join-Path $repoRoot "app\host"
$modulesRoot = Join-Path $repoRoot "target\orderly-modules"
$themesRoot = Join-Path $repoRoot "themes"

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
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

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build.ps1") -SkipTests
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "JAVA_HOME 未设置；启动必须显式使用 JDK 21。"
}
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path -LiteralPath $javaExe -PathType Leaf)) {
    throw "JAVA_HOME 下未找到 java.exe：$javaExe"
}
$javaVersion = Invoke-NativeCapture -FilePath $javaExe -Arguments @("-version")
if ($javaVersion.ExitCode -ne 0 -or $javaVersion.Output -notmatch 'version "(?<major>\d+)' -or [int]$Matches.major -ne 21) {
    throw "启动需要 JDK 21，当前 JAVA_HOME Java 输出：`n$($javaVersion.Output)"
}

$jar = Get-ChildItem -LiteralPath (Join-Path $hostRoot "target") -Filter "*.jar" -File |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "未找到可运行的 host JAR。"
}

Push-Location $repoRoot
try {
    Write-Host "使用 Java：$javaExe"
    Write-Host "模块目录：$modulesRoot"
    Write-Host "本地地址：http://127.0.0.1:$Port/"
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $javaExe "-Dorderly.modules-dir=$modulesRoot" "-Dorderly.themes-dir=$themesRoot" -jar $jar.FullName "--server.port=$Port"
        $appExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($appExitCode -ne 0) {
        throw "应用退出码：$appExitCode"
    }
}
finally {
    Pop-Location
}
