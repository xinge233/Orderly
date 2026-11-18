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
$aiEnvScript = Join-Path $repoRoot "ai\scripts\ensure-env.ps1"

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

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        0
    )
    $listener.Start()
    try {
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function New-IpcToken {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Wait-AiService {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [System.Diagnostics.Process]$Process
    )
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            throw "Python AI 服务启动失败，退出码：$($Process.ExitCode)"
        }
        try {
            $health = Invoke-RestMethod -Uri "$BaseUrl/v1/health" -Method Get `
                -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 2
            if ($health.status -eq "UP") {
                return
            }
        } catch {
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for Python AI service health: $BaseUrl"
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

if (-not (Test-Path -LiteralPath $aiEnvScript -PathType Leaf)) {
    throw "未找到 Python AI 环境脚本：$aiEnvScript"
}
$python = & $aiEnvScript
if (-not $python -or -not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Python AI 环境准备失败。"
}

Push-Location $repoRoot
$aiProcess = $null
$previousToken = $env:ORDERLY_AI_IPC_TOKEN
$previousAiPort = $env:ORDERLY_AI_PORT
try {
    $aiPort = Get-FreeTcpPort
    $aiBaseUrl = "http://127.0.0.1:$aiPort"
    $aiToken = New-IpcToken
    $env:ORDERLY_AI_IPC_TOKEN = $aiToken
    $env:ORDERLY_AI_PORT = [string]$aiPort

    Write-Host "使用 Java：$javaExe"
    Write-Host "模块目录：$modulesRoot"
    Write-Host "AI 服务：$aiBaseUrl"
    Write-Host "本地地址：http://127.0.0.1:$Port/"
    $aiProcess = Start-Process -FilePath $python `
        -ArgumentList @("-m", "orderly_ai", "--port", [string]$aiPort) `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -PassThru
    Wait-AiService -BaseUrl $aiBaseUrl -Token $aiToken -Process $aiProcess

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $javaExe `
            "-Dorderly.modules-dir=$modulesRoot" `
            "-Dorderly.themes-dir=$themesRoot" `
            "-Dorderly.ai-service.url=$aiBaseUrl" `
            -jar $jar.FullName `
            "--server.port=$Port"
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
    if ($aiProcess -and -not $aiProcess.HasExited) {
        Stop-Process -Id $aiProcess.Id -Force
        $aiProcess.WaitForExit(5000) | Out-Null
    }
    $env:ORDERLY_AI_IPC_TOKEN = $previousToken
    $env:ORDERLY_AI_PORT = $previousAiPort
    Pop-Location
}
