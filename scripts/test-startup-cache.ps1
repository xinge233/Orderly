#requires -Version 5.1
[CmdletBinding()]
# 用途：
#   验证源码未变化时 start.ps1 跳过 Maven，并在后台模式下加载已有模块。
#
# 输入：
#   无；要求目标 target/build-manifest.json 和运行产物已存在。
#
# 副作用：
#   在随机回环端口启动测试实例，写入 target/startup-cache-* 日志，结束后受控关闭。
#
# 安全边界：
#   不修改源码和 JAR；失败时只终止本次创建的启动进程树。

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$manifestPath = Join-Path $targetRoot "build-manifest.json"
. (Join-Path $PSScriptRoot "build-plan.ps1")

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "缺少构建清单；请先执行 scripts/build.ps1。"
}
$plan = Resolve-OrderlyBuildPlan -RepoRoot $repoRoot -ManifestPath $manifestPath
if ($plan.BuildRequired) {
    throw "启动缓存测试要求当前构建产物有效：$($plan.Reasons -join '；')"
}

$listener = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0
)
$listener.Start()
try {
    $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
}
finally {
    $listener.Stop()
}

$stdout = Join-Path $targetRoot "startup-cache-$port.out.log"
$stderr = Join-Path $targetRoot "startup-cache-$port.err.log"
$arguments = @(
    "-NoProfile",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    (Join-Path $PSScriptRoot "start.ps1"),
    "-BackgroundModules",
    "-Port",
    [string]$port
)
$argumentLine = (@($arguments | ForEach-Object {
    if ([string]$_ -match '\s') {
        '"' + ([string]$_ -replace '"', '\"') + '"'
    }
    else {
        [string]$_
    }
}) -join " ")

$process = $null
try {
    $process = Start-Process `
        -FilePath (Get-Process -Id $PID).Path `
        -ArgumentList $argumentLine `
        -WorkingDirectory $repoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -PassThru
    $baseUrl = "http://127.0.0.1:$port"
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) {
            throw "启动测试进程提前退出：$((Get-Content -Raw -LiteralPath $stderr) + (Get-Content -Raw -LiteralPath $stdout))"
        }
        try {
            $health = Invoke-RestMethod -Uri "$baseUrl/api/health/live" -TimeoutSec 2
            if ([string]$health.status -eq "UP") {
                $ready = $true
                break
            }
        }
        catch {
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        throw "启动缓存测试等待宿主超时。"
    }
    $moduleResponse = Invoke-RestMethod -Uri "$baseUrl/api/modules" -TimeoutSec 5
    $modules = @($moduleResponse | ForEach-Object { $_ })
    $expectedModules = @(Get-OrderlyModuleSourceDirectories $repoRoot |
        Where-Object {
            -not $_.Name.StartsWith("module_system_", [System.StringComparison]::Ordinal)
        })
    if ($modules.Count -ne $expectedModules.Count) {
        throw "启动缓存命中的业务模块数量不符。预期=$($expectedModules.Count) 实际=$($modules.Count)；实际模块=$(@($modules | ForEach-Object { $_.moduleId }) -join ',')"
    }
    $log = Get-Content -Raw -LiteralPath $stdout
    if ($log -notmatch "ORDERLY_BUILD_SKIPPED=1") {
        throw "启动日志未证明跳过 Maven：`n$log"
    }
    Invoke-RestMethod `
        -Uri "$baseUrl/api/system/shutdown" `
        -Method Post `
        -Headers @{ "X-Orderly-Action" = "shutdown" } `
        -TimeoutSec 10 | Out-Null
    if (-not $process.WaitForExit(60000)) {
        throw "启动缓存测试实例未在 60 秒内退出。"
    }
    Write-Host "启动缓存测试通过：跳过 Maven，加载 $($modules.Count) 个业务模块。"
}
finally {
    if ($process -and -not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
        $process.WaitForExit(5000) | Out-Null
    }
}
