#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
$sourceRoot = Join-Path $repoRoot "app\desktop\webview2-host\src"
$sdkRoot = Join-Path $repoRoot "app\desktop\webview2-sdk"
$frameworkRoot = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319"
$compiler = Join-Path $frameworkRoot "csc.exe"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $targetRoot "desktop-webview2"
}
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not $outputRoot.StartsWith(
        $targetRoot.TrimEnd('\') + '\',
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    throw "WebView2 宿主输出目录必须位于 target 下：$outputRoot"
}
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw "缺少 Windows .NET Framework C# 编译器：$compiler"
}

$coreAssembly = Join-Path $sdkRoot "lib\net462\Microsoft.Web.WebView2.Core.dll"
$winFormsAssembly = Join-Path $sdkRoot "lib\net462\Microsoft.Web.WebView2.WinForms.dll"
$loaderAssembly = Join-Path $sdkRoot "runtimes\win-x64\native\WebView2Loader.dll"
foreach ($required in @(
    $coreAssembly,
    $winFormsAssembly,
    $loaderAssembly,
    (Join-Path $sdkRoot "LICENSE.txt"),
    (Join-Path $sdkRoot "NOTICE.txt")
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "WebView2 SDK 文件缺失：$required"
    }
}

if (Test-Path -LiteralPath $outputRoot) {
    Remove-Item -LiteralPath $outputRoot -Recurse -Force
}
[System.IO.Directory]::CreateDirectory($outputRoot) | Out-Null

$sourceFiles = @(
    Get-ChildItem -LiteralPath $sourceRoot -Filter "*.cs" -File |
        Sort-Object Name |
        ForEach-Object { $_.FullName }
)
if ($sourceFiles.Count -eq 0) {
    throw "WebView2 宿主源码为空：$sourceRoot"
}

$outputAssembly = Join-Path $outputRoot "OrderlyWebView2Host.exe"
$manifest = Join-Path $sourceRoot "app.manifest"
$frameworkAssemblies = @(
    "System.dll",
    "System.Core.dll",
    "System.Drawing.dll",
    "System.Net.Http.dll",
    "System.Windows.Forms.dll"
) | ForEach-Object { Join-Path $frameworkRoot $_ }

$arguments = @(
    "/nologo",
    "/target:winexe",
    "/platform:x64",
    "/langversion:5",
    "/optimize+",
    "/warn:4",
    "/utf8output",
    ("/out:" + $outputAssembly),
    ("/win32manifest:" + $manifest),
    ("/reference:" + ($frameworkAssemblies -join ",")),
    ("/reference:" + $coreAssembly),
    ("/reference:" + $winFormsAssembly)
) + $sourceFiles

& $compiler @arguments
if ($LASTEXITCODE -ne 0) {
    throw "WebView2 宿主编译失败，退出码：$LASTEXITCODE"
}

Copy-Item -LiteralPath $coreAssembly -Destination $outputRoot -Force
Copy-Item -LiteralPath $winFormsAssembly -Destination $outputRoot -Force
Copy-Item -LiteralPath $loaderAssembly -Destination $outputRoot -Force
Copy-Item -LiteralPath (Join-Path $sourceRoot "OrderlyWebView2Host.exe.config") -Destination $outputRoot -Force
Copy-Item -LiteralPath (Join-Path $sdkRoot "LICENSE.txt") -Destination $outputRoot -Force
Copy-Item -LiteralPath (Join-Path $sdkRoot "NOTICE.txt") -Destination $outputRoot -Force

Write-Host "WebView2 宿主构建完成：$outputAssembly"
Write-Output $outputRoot
