$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$aiRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$venvRoot = Join-Path $aiRoot ".venv"
$venvPython = Join-Path $venvRoot "Scripts\python.exe"
$markerPath = Join-Path $venvRoot ".orderly-ai-dependencies"

if (-not (Test-Path -LiteralPath $venvPython)) {
    py -3.14 -m venv $venvRoot
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $venvPython)) {
        throw "无法创建 Python 3.14 virtualenv：$venvRoot"
    }
}

$dependencyFiles = @(
    (Join-Path $aiRoot "pyproject.toml"),
    (Join-Path $aiRoot "requirements.txt")
)
$fingerprint = ($dependencyFiles | ForEach-Object {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash
}) -join ":"
$installedFingerprint = if (Test-Path -LiteralPath $markerPath) {
    (Get-Content -Raw -LiteralPath $markerPath).Trim()
} else {
    ""
}

if ($installedFingerprint -ne $fingerprint) {
    & $venvPython -m pip install --disable-pip-version-check --upgrade pip |
        Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "升级 pip 失败。"
    }
    & $venvPython -m pip install --disable-pip-version-check `
        -e "$aiRoot[dev]" |
        Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "安装 Orderly AI 依赖失败。"
    }
    Set-Content -NoNewline -LiteralPath $markerPath -Value $fingerprint
}

Write-Output $venvPython
