#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$outputRoot = Join-Path $targetRoot "desktop-webview2"
$hostPath = Join-Path $outputRoot "OrderlyWebView2Host.exe"

if (-not $SkipBuild) {
    $buildRoot = & (Join-Path $PSScriptRoot "build-webview2-host.ps1")
    $outputRoot = [string]$buildRoot
    $hostPath = Join-Path $outputRoot "OrderlyWebView2Host.exe"
}
if (-not (Test-Path -LiteralPath $hostPath -PathType Leaf)) {
    throw "WebView2 宿主不存在：$hostPath"
}
foreach ($required in @(
    "Microsoft.Web.WebView2.Core.dll",
    "Microsoft.Web.WebView2.WinForms.dll",
    "WebView2Loader.dll"
)) {
    $path = Join-Path $outputRoot $required
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "WebView2 宿主依赖缺失：$path"
    }
}

$resultPath = Join-Path $targetRoot "webview2-host-self-test.txt"
if (Test-Path -LiteralPath $resultPath) {
    Remove-Item -LiteralPath $resultPath -Force
}
$process = Start-Process `
    -FilePath $hostPath `
    -ArgumentList @("--self-test", $resultPath) `
    -WorkingDirectory $outputRoot `
    -WindowStyle Hidden `
    -Wait `
    -PassThru
if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "WebView2 宿主自检未生成结果，退出码：$($process.ExitCode)"
}
$result = Get-Content -LiteralPath $resultPath -Encoding UTF8
if ($process.ExitCode -ne 0 -or $result[0] -ne "PASS") {
    throw "WebView2 宿主自检失败：$($result -join ' ')"
}

Write-Host "WebView2 宿主自检通过：$hostPath"
