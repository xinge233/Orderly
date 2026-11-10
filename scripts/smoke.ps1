[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"

$health = Invoke-RestMethod -Uri "$BaseUrl/api/health" -Method Get
if ($health.status -ne "UP") {
    throw "健康检查失败：$($health | ConvertTo-Json -Compress)"
}

$files = Invoke-RestMethod -Uri "$BaseUrl/api/files" -Method Get
if ($null -eq $files.entries) {
    throw "文件目录响应缺少 entries。"
}

$plan = Invoke-RestMethod -Uri "$BaseUrl/api/plans" -Method Post `
    -ContentType "application/json" `
    -Body (@{ goal = "创建文件夹 烟测目录" } | ConvertTo-Json)
if ($plan.kind -ne "PLAN" -or -not $plan.planId) {
    throw "计划生成响应不符合预期：$($plan | ConvertTo-Json -Compress -Depth 8)"
}

$unconfirmed = $null
try {
    $unconfirmed = Invoke-RestMethod -Uri "$BaseUrl/api/plans/$($plan.planId)/confirm" -Method Post `
        -ContentType "application/json" `
        -Body (@{ confirmed = $false; confirmation = "" } | ConvertTo-Json)
} catch {
    $unconfirmed = $_.ErrorDetails.Message
}
if ($null -eq $unconfirmed) {
    throw "未确认计划没有被拒绝。"
}

$confirmed = Invoke-RestMethod -Uri "$BaseUrl/api/plans/$($plan.planId)/confirm" -Method Post `
    -ContentType "application/json" `
    -Body (@{ confirmed = $true; confirmation = "" } | ConvertTo-Json)
if ($confirmed.status -notin @("COMPLETED", "SUCCEEDED")) {
    throw "确认执行未完成：$($confirmed | ConvertTo-Json -Compress -Depth 8)"
}

$rolledBack = Invoke-RestMethod -Uri "$BaseUrl/api/plans/$($plan.planId)/rollback" -Method Post `
    -ContentType "application/json" -Body "{}"
if ($rolledBack.status -notin @("ROLLED_BACK", "PARTIALLY_ROLLED_BACK")) {
    throw "回滚响应不符合预期：$($rolledBack | ConvertTo-Json -Compress -Depth 8)"
}

Write-Host "HTTP 烟测通过：健康、目录、计划、未确认拒绝、确认执行、回滚。"
