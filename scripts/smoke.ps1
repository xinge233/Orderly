[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"

$health = Invoke-RestMethod -Uri "$BaseUrl/api/health" -Method Get
if ($health.status -ne "UP") {
    throw "健康检查失败：$($health | ConvertTo-Json -Compress)"
}
if ([int]$health.javaVersion -ne 21) {
    throw "宿主未运行在 JDK 21：$($health.javaVersion)"
}

$modulesResponse = Invoke-RestMethod -Uri "$BaseUrl/api/modules" -Method Get
$modules = @($modulesResponse | ForEach-Object { $_ })
$moduleIds = @($modules | ForEach-Object { $_.moduleId })
if ($moduleIds | Where-Object { [string]::IsNullOrWhiteSpace($_) }) {
    throw "模块目录返回了缺少 moduleId 的条目。"
}
if (($moduleIds | Sort-Object -Unique).Count -ne $moduleIds.Count) {
    throw "模块目录返回了重复 moduleId。"
}

$themesResponse = Invoke-RestMethod -Uri "$BaseUrl/api/themes" -Method Get
$themes = @($themesResponse.items | ForEach-Object { $_ })
$themeIds = @($themes | ForEach-Object { $_.id })
if (($themeIds | Sort-Object -Unique).Count -ne $themeIds.Count) {
    throw "主题目录返回了重复 id。"
}
if ($null -eq $themesResponse.issues) {
    throw "主题目录响应缺少 issues。"
}
if ($null -eq $themesResponse.builtInFallbackActive) {
    throw "主题目录响应缺少 builtInFallbackActive。"
}
foreach ($themeSummary in $themes) {
    if ([int]$themeSummary.schemaVersion -ne 1) {
        throw "主题摘要 schemaVersion 不是 1：$($themeSummary.id)"
    }
    $encodedThemeId = [uri]::EscapeDataString([string]$themeSummary.id)
    $themeDetail = Invoke-RestMethod -Uri "$BaseUrl/api/themes/$encodedThemeId" -Method Get
    if ([int]$themeDetail.schemaVersion -ne 1 -or $themeDetail.id -ne $themeSummary.id) {
        throw "主题详情与摘要不一致：$($themeSummary.id)"
    }
    if (@($themeDetail.styles).Count -lt 2 -or
            @($themeDetail.styles) -notcontains "tokens.css" -or
            @($themeDetail.styles) -notcontains "motion.css") {
        throw "主题详情缺少清单登记的 tokens.css 或 motion.css：$($themeSummary.id)"
    }
    if ($null -ne $themeDetail.rootPath) {
        throw "主题详情不得暴露宿主 rootPath：$($themeSummary.id)"
    }
    foreach ($style in @($themeDetail.styles)) {
        $encodedStyle = (($style -split '/') | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
        $styleResponse = Invoke-WebRequest -Uri "$BaseUrl/api/themes/$encodedThemeId/assets/$encodedStyle" `
            -Method Get -UseBasicParsing
        $contentType = [string]($styleResponse.Headers["Content-Type"] -join ";")
        $cacheControl = [string]($styleResponse.Headers["Cache-Control"] -join ";")
        if ($contentType -notmatch '^text/css(?:;|$)') {
            throw "主题 CSS Content-Type 错误：$($themeSummary.id)/$style => $contentType"
        }
        if ($cacheControl -notmatch 'no-cache') {
            throw "主题 CSS 缺少 Cache-Control: no-cache：$($themeSummary.id)/$style"
        }
    }
    foreach ($asset in @($themeDetail.assets)) {
        $encodedAsset = (($asset -split '/') | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
        Invoke-WebRequest -Uri "$BaseUrl/api/themes/$encodedThemeId/assets/$encodedAsset" `
            -Method Get -UseBasicParsing | Out-Null
    }
}

$queryCount = 0
$firstExample = $null
foreach ($module in $modules) {
    $moduleId = [uri]::EscapeDataString([string]$module.moduleId)
    $ui = Invoke-RestMethod -Uri "$BaseUrl/api/modules/$moduleId/ui/module.json" -Method Get
    if ([string]::IsNullOrWhiteSpace([string]$ui.view.id) -or
            [string]::IsNullOrWhiteSpace([string]$ui.script) -or
            [string]::IsNullOrWhiteSpace([string]$ui.style)) {
        throw "模块 $($module.moduleId) 的 UI 清单不完整。"
    }
    Invoke-WebRequest -Uri "$BaseUrl/api/modules/$moduleId/ui/$([uri]::EscapeDataString([string]$ui.script))" -Method Get -UseBasicParsing | Out-Null
    Invoke-WebRequest -Uri "$BaseUrl/api/modules/$moduleId/ui/$([uri]::EscapeDataString([string]$ui.style))" -Method Get -UseBasicParsing | Out-Null

    if (-not $firstExample -and @($ui.examples).Count -gt 0 -and $ui.examples[0].text) {
        $firstExample = [string]$ui.examples[0].text
    }
    foreach ($query in @($module.queries)) {
        $required = @($query.parameters | Where-Object { $_.required })
        if ($required.Count -eq 0) {
            Invoke-RestMethod -Uri "$BaseUrl/api/modules/$moduleId/query/$([uri]::EscapeDataString([string]$query.id))" -Method Get | Out-Null
            $queryCount++
        }
    }
}

$goal = if ($firstExample) { $firstExample } else { "当前没有可识别的模块目标" }
$plan = Invoke-RestMethod -Uri "$BaseUrl/api/plans" -Method Post `
    -ContentType "application/json" `
    -Body (@{ goal = $goal } | ConvertTo-Json)
if ($modules.Count -eq 0 -and $plan.kind -ne "QUESTION") {
    throw "零模块状态下计划器没有返回 QUESTION。"
}
if ($modules.Count -gt 0 -and $plan.kind -notin @("PLAN", "QUESTION")) {
    throw "计划生成响应不符合预期：$($plan | ConvertTo-Json -Compress -Depth 8)"
}

if ($plan.kind -eq "PLAN") {
    $unconfirmedStatus = $null
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/plans/$([uri]::EscapeDataString([string]$plan.planId))/confirm" -Method Post `
            -ContentType "application/json" `
            -Body (@{ confirmed = $false; confirmation = "" } | ConvertTo-Json) | Out-Null
    } catch {
        $unconfirmedStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($unconfirmedStatus -ne 409) {
        throw "未确认计划没有被 409 拒绝，实际状态：$unconfirmedStatus"
    }
}

Write-Host "HTTP 烟测通过：JDK 21、动态模块目录、模块 UI、通用查询、目录主题 schemaVersion/详情/CSS 资源、安全回退字段和未执行计划确认；未产生业务数据变更。"
Write-Host "已执行无必填参数的通用查询数量：$queryCount"

exit 0
