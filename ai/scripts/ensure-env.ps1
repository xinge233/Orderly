# 用途：
#   为根目录 ai/ 服务准备 Python 3.14 隔离环境，并在依赖清单变化后同步安装依赖。
#
# 输入：
#   读取 ai/pyproject.toml、ai/requirements.txt；无需参数，也不要求预先激活虚拟环境。
#
# 副作用：
#   可能创建 ai/.venv，联网升级 pip 和安装 editable 开发依赖，并写入依赖指纹文件。
#   成功时只向标准输出返回虚拟环境 python.exe 的路径，供调用方继续使用。
#
# 安全边界：
#   所有环境文件都位于仓库内 ai/.venv。脚本不读取 API Key，不把凭据写入标记文件或日志；
#   安装失败必须抛错，避免调用方误用半成品环境。

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$aiRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$venvRoot = Join-Path $aiRoot ".venv"
$venvPython = Join-Path $venvRoot "Scripts\python.exe"
$markerPath = Join-Path $venvRoot ".orderly-ai-dependencies"

# 固定请求 Python 3.14，避免 PATH 中其他解释器造成不可复现的运行环境。
if (-not (Test-Path -LiteralPath $venvPython)) {
    py -3.14 -m venv $venvRoot
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $venvPython)) {
        throw "无法创建 Python 3.14 virtualenv：$venvRoot"
    }
}

# 依赖文件哈希用于判断环境是否过期；只有内容变化时才重新安装，减少每次启动的网络开销。
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

# pip 升级或依赖安装失败时立即终止。失败后不会更新指纹，下次启动会重试，而不是把坏环境记为就绪。
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
