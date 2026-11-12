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
$themes = @($themesResponse | ForEach-Object { $_ })
$themeIds = @($themes | ForEach-Object { $_.id })
if (($themeIds | Sort-Object -Unique).Count -ne $themeIds.Count) {
    throw "主题目录返回了重复 id。"
}
if ($themes.Count -gt 0) {
    $firstTheme = Invoke-RestMethod -Uri "$BaseUrl/api/themes/$([uri]::EscapeDataString($themes[0].id))" -Method Get
    if ($null -eq $firstTheme.tokens) {
        throw "主题详情缺少 tokens。"
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

Write-Host "HTTP 烟测通过：JDK 21、动态模块目录、模块 UI、通用查询、主题和未执行计划确认；未产生业务数据变更。"
Write-Host "已执行无必填参数的通用查询数量：$queryCount"

exit 0
