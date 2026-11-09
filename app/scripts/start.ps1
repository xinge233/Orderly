[CmdletBinding()]
param(
    [int]$Port = 8080,
    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appRoot = Split-Path -Parent $scriptDir
$controlRoot = Join-Path $appRoot 'control'
$contractsRoot = Join-Path $appRoot 'contracts'
$webRoot = Join-Path $appRoot 'web'
$mavenRepo = Join-Path $env:TEMP 'orderly-m2\repository'
$buildRoot = Join-Path $env:TEMP 'orderly-control-build'
$jarPath = Join-Path $buildRoot 'orderly-control-0.1.0-SNAPSHOT.jar'

$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $maven) {
    [Console]::Error.WriteLine('status=error')
    [Console]::Error.WriteLine('reason=maven_not_found')
    exit 1
}

$pomPath = Join-Path $controlRoot 'pom.xml'
$buildOutput = & $maven.Source '-f' $pomPath "-Dmaven.repo.local=$mavenRepo" "-Dorderly.build.dir=$buildRoot" '-q' '-DskipTests' 'package' 2>&1
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jarPath)) {
    [Console]::Error.WriteLine('status=error')
    [Console]::Error.WriteLine('reason=build_failed')
    $buildOutput | Select-Object -Last 20 | ForEach-Object { [Console]::Error.WriteLine($_) }
    exit 1
}

$selectedPort = $null
for ($candidate = $Port; $candidate -lt ($Port + 100); $candidate++) {
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $candidate)
        $listener.Start()
        $selectedPort = $candidate
        break
    } catch {
        Write-Host "port=$candidate status=occupied"
    } finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

if ($null -eq $selectedPort) {
    [Console]::Error.WriteLine('status=error')
    [Console]::Error.WriteLine('reason=no_available_port')
    exit 1
}

$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$stdout = Join-Path $buildRoot 'start-stdout.log'
$stderr = Join-Path $buildRoot 'start-stderr.log'
$process = Start-Process -FilePath $java -ArgumentList @(
    '-jar', "`"$jarPath`"",
    "--server.port=$selectedPort",
    "--orderly.contracts-root=`"$contractsRoot`"",
    "--orderly.web-root=`"$webRoot`"",
    '--spring.main.banner-mode=off',
    '--logging.level.root=WARN'
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr

$health = $null
for ($attempt = 0; $attempt -lt 80; $attempt++) {
    Start-Sleep -Milliseconds 250
    if ($process.HasExited) {
        break
    }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$selectedPort/api/health" -TimeoutSec 1
        break
    } catch {
        $health = $null
    }
}

if ($null -eq $health) {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    [Console]::Error.WriteLine('status=error')
    [Console]::Error.WriteLine("reason=startup_timeout port=$selectedPort pid=$($process.Id)")
    if (Test-Path -LiteralPath $stderr) {
        Get-Content -LiteralPath $stderr -Tail 20 | ForEach-Object { [Console]::Error.WriteLine($_) }
    }
    exit 1
}

$url = "http://127.0.0.1:$selectedPort/"
Write-Host 'status=ok'
Write-Host "port=$selectedPort"
Write-Host "modules=$($health.modules)"
Write-Host "version=$($health.version)"
Write-Host "pid=$($process.Id)"
Write-Host "url=$url"
Write-Host "logs=$stderr"

if (-not $NoBrowser) {
    Start-Process -FilePath $url
}
