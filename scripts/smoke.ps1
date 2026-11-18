[CmdletBinding()]
# 用途：
#   对已启动的 Orderly 宿主执行端到端 HTTP 烟测，覆盖模块/主题目录、通用查询、AI Action 和计划确认门禁。
#
# 输入：
#   -BaseUrl 指定目标宿主，默认 http://127.0.0.1:8080；目标实例必须已配置并启动 Python AI 服务。
#
# 副作用：
#   会切换宿主的计划功能开关，并在应用沙箱中顺序创建、列出和移入回收站一个随机目录，
#   随后通过执行记录回滚。脚本结束前会恢复初始计划开关；若中途失败，服务端可能保留当时的状态。
#
# 安全边界：
#   默认只访问本机回环实例，但脚本不自行验证自定义 BaseUrl 是否回环。
#   所有写动作仍必须由宿主风险确认、沙箱、快照、审计和回滚规则担保，烟测不绕过这些门禁。

param(
    [string]$BaseUrl = "http://127.0.0.1:8080"
)

$ErrorActionPreference = "Stop"

# 先验证健康快照，避免后续测试把“服务不可达”误报成单个业务接口错误。
$health = Invoke-RestMethod -Uri "$BaseUrl/api/health" -Method Get
if ($health.status -ne "UP") {
    throw "健康检查失败：$($health | ConvertTo-Json -Compress)"
}
if ([int]$health.javaVersion -ne 21) {
    throw "宿主未运行在 JDK 21：$($health.javaVersion)"
}
if ($health.aiService -ne "UP") {
    throw "Python AI 服务未就绪：$($health | ConvertTo-Json -Compress)"
}
if ($health.aiConfigured -isnot [bool]) {
    throw "/api/health 缺少布尔 aiConfigured。"
}

$features = Invoke-RestMethod -Uri "$BaseUrl/api/system/features" -Method Get
if ($features.planningEnabled -isnot [bool]) {
    throw "/api/system/features 缺少布尔 planningEnabled。"
}
if ($features.aiPlanEnabled -isnot [bool]) {
    throw "/api/system/features 缺少布尔 aiPlanEnabled。"
}
# 记录初始开关，后面临时关闭旧计划功能测试 409，并在正常完成路径恢复原值。
$initialPlanningEnabled = [bool]$features.planningEnabled
Invoke-RestMethod -Uri "$BaseUrl/api/system/features/planning" -Method Put `
    -ContentType "application/json" `
    -Body (@{ enabled = $false } | ConvertTo-Json) | Out-Null

# 失败响应状态要单独捕获：预期 409 是测试结果，不应被 Invoke-RestMethod 当成脚本崩溃。
$disabledPlanStatus = $null
try {
    Invoke-RestMethod -Uri "$BaseUrl/api/plans" -Method Post `
        -ContentType "application/json" `
        -Body (@{ goal = "创建目录 planning-disabled-smoke" } | ConvertTo-Json) | Out-Null
} catch {
    $disabledPlanStatus = [int]$_.Exception.Response.StatusCode
}
if ($disabledPlanStatus -ne 409) {
    throw "关闭计划功能后 /api/plans 未被 409 拒绝，实际状态：$disabledPlanStatus"
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
foreach ($module in $modules) {
    if ($module.enabled -isnot [bool]) {
        throw "业务模块缺少布尔 enabled：$($module.moduleId)"
    }
}

# 系统模块与业务模块分开断言，防止 module_control 同时泄漏到可停用/可排序的业务目录。
$systemModulesResponse = Invoke-RestMethod -Uri "$BaseUrl/api/system-modules" -Method Get
$systemModules = @($systemModulesResponse | ForEach-Object { $_ })
$controlModules = @($systemModules | Where-Object { $_.moduleId -eq "module_control" })
if ($controlModules.Count -ne 1 -or $controlModules[0].enabled -ne $true -or
        $controlModules[0].system.role -ne "MODULE_CONTROL") {
    throw "/api/system-modules 未唯一返回启用状态的 module_control。"
}
if ($moduleIds -contains "module_control") {
    throw "/api/modules 不得返回系统模块 module_control。"
}

$preferences = Invoke-RestMethod -Uri "$BaseUrl/api/system/module-preferences" -Method Get
if ($null -eq $preferences.sidebarOrder -or $null -eq $preferences.managementOrder -or
        $null -eq $preferences.moduleSettings) {
    throw "模块偏好响应缺少 sidebarOrder、managementOrder 或 moduleSettings。"
}
if (@($preferences.sidebarOrder) -notcontains "module_control") {
    throw "sidebarOrder 未包含系统模块 module_control。"
}

# 主题检查覆盖摘要、详情、声明资源可达性、缓存语义和路径隐藏，验证目录主题而非 classpath 静态列表。
$themesResponse = Invoke-RestMethod -Uri "$BaseUrl/api/themes" -Method Get
$themes = @($themesResponse.items | ForEach-Object { $_ })
$themeIds = @($themes | ForEach-Object { $_.id })
if ($themeIds -notcontains "study") {
    throw "目录主题缺少浅色研习蓝主题 study。"
}
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

# 通用查询只自动执行无必填参数的接口；有参数的查询由模块测试和调用方负责，避免烟测伪造业务输入。
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

# 学习界面模块是可选只读扩展：存在时校验负载诚实性和引用完整性，不在受测安装中时跳过。
$optionalStudyQueries = [ordered]@{
    module_fs = "fs.materials"
    module_study_assistant = "study.assistant.read"
    module_study_map = "study.map.read"
    module_study_review = "study.review.read"
}
foreach ($entry in $optionalStudyQueries.GetEnumerator()) {
    if ($moduleIds -notcontains $entry.Key) {
        continue
    }
    $moduleId = [uri]::EscapeDataString([string]$entry.Key)
    $queryId = [uri]::EscapeDataString([string]$entry.Value)
    $payload = Invoke-RestMethod -Uri "$BaseUrl/api/modules/$moduleId/query/$queryId" -Method Get
    if ($null -eq $payload -or $null -eq $payload.payload) {
        throw "学习数据查询缺少 payload：$($entry.Key)/$($entry.Value)"
    }
    if ($payload.readOnly -ne $true -or $payload.ownerModule -ne $entry.Key) {
        throw "学习数据查询未声明正确的只读归属：$($entry.Key)/$($entry.Value)"
    }
    switch ($entry.Key) {
        "module_fs" {
            if ($payload.kind -ne "materials" -or
                    [int]$payload.count -ne @($payload.payload.materials).Count) {
                throw "资料管理查询未返回完整 materials 负载。"
            }
        }
        "module_study_assistant" {
            if ($payload.kind -ne "assistant" -or
                    $payload.payload.planner -ne "local-rules" -or
                    $payload.payload.externalModelConnected -ne $false -or
                    [int]$payload.count -ne @($payload.payload.tasks).Count) {
                throw "学习工作台查询未保持本地规则和未连接外部模型。"
            }
        }
        "module_study_map" {
            $nodeIds = @($payload.payload.nodes | ForEach-Object { [string]$_.id })
            foreach ($edge in @($payload.payload.edges)) {
                if ($nodeIds -notcontains [string]$edge.source -or
                        $nodeIds -notcontains [string]$edge.target) {
                    throw "知识图谱查询包含无效边端点：$($edge.id)"
                }
            }
            if ([int]$payload.count -ne $nodeIds.Count) {
                throw "知识图谱查询的 count 与节点数量不一致。"
            }
        }
        "module_study_review" {
            if ($payload.kind -ne "review" -or
                    $payload.payload.gradingMode -ne "none" -or
                    [int]$payload.count -ne @($payload.payload.questions).Count) {
                throw "复习自测查询未保持只读且未接入阅卷。"
            }
        }
    }
}

# AI 设置响应不得包含凭据；这里只验证固定默认值和布尔能力，不输入或持久化 API Key。
$aiExecutionId = $null
$aiSettings = Invoke-RestMethod -Uri "$BaseUrl/api/ai/settings" -Method Get
if ($aiSettings.PSObject.Properties.Name -contains "apiKey") {
    throw "AI 设置响应泄露了 API Key。"
}
if ($aiSettings.mode -ne "DEEPSEEK" -or
        $aiSettings.baseUrl -ne "https://api.deepseek.com" -or
        $aiSettings.model -ne "deepseek-flash") {
    throw "AI 设置未返回 DeepSeek 固定默认值。"
}
if ($aiSettings.allowSendQueryResultsToModel -isnot [bool]) {
    throw "AI 设置缺少布尔 allowSendQueryResultsToModel。"
}

# 文件模块存在时构造真实但隔离的写入/回滚链；模块缺失时由通用模块测试负责零模块场景。
if ($moduleIds -contains "module_fs") {
    $smokeDirectory = "smoke-" + [guid]::NewGuid().ToString("N")

    # 先证明未确认 WRITE 和静态禁止的 Action 都被宿主拒绝，再执行允许的三步 Action 数组。
    $unconfirmedAiStatus = $null
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/ai/actions/execute" -Method Post `
            -ContentType "application/json" `
            -Body (@{
                confirmed = $false
                actions = @(@{
                    moduleId = "module_fs"
                    actionId = "fs.mkdir"
                    arguments = @{ path = $smokeDirectory }
                })
            } | ConvertTo-Json -Depth 12) | Out-Null
    } catch {
        $unconfirmedAiStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($unconfirmedAiStatus -ne 409) {
        throw "未确认 WRITE Action 未被 409 拒绝，实际状态：$unconfirmedAiStatus"
    }

    $blockedAiStatus = $null
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/ai/actions/execute" -Method Post `
            -ContentType "application/json" `
            -Body (@{
                confirmed = $true
                actions = @(@{
                    moduleId = "module_fs"
                    actionId = "fs.restore"
                    arguments = @{ trashId = "smoke-not-used" }
                })
            } | ConvertTo-Json -Depth 12) | Out-Null
    } catch {
        $blockedAiStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($blockedAiStatus -ne 409) {
        throw "禁止 AI 调用的 Action 未被 409 拒绝，实际状态：$blockedAiStatus"
    }

    # 数组使用前序 Action 输出引用，按创建目录、列出、移入回收站顺序执行；随后验证持久化和逆序回滚。
    $aiResult = Invoke-RestMethod -Uri "$BaseUrl/api/ai/actions/execute" -Method Post `
        -ContentType "application/json" `
        -Body (@{
            confirmed = $true
            actions = @(
                @{
                    moduleId = "module_fs"
                    actionId = "fs.mkdir"
                    arguments = @{ path = $smokeDirectory }
                },
                @{
                    moduleId = "module_fs"
                    actionId = "fs.list"
                    arguments = @{
                        path = @{
                            '$fromAction' = 0
                            path = @("output", "path")
                        }
                    }
                },
                @{
                    moduleId = "module_fs"
                    actionId = "fs.trash"
                    arguments = @{
                        path = @{
                            '$fromAction' = 0
                            path = @("output", "path")
                        }
                    }
                }
            )
        } | ConvertTo-Json -Depth 12)
    if ($aiResult.origin -ne "AI_DIRECT" -or
            $aiResult.status -ne "COMPLETED" -or
            @($aiResult.steps).Count -ne 3) {
        throw "AI 直执 Action 数组结果不符合预期：$($aiResult | ConvertTo-Json -Compress -Depth 12)"
    }
    $aiExecutionId = [string]$aiResult.executionId
    if ([string]::IsNullOrWhiteSpace($aiExecutionId)) {
        throw "AI 直执响应缺少 executionId。"
    }
    $persistedExecution = Invoke-RestMethod `
        -Uri "$BaseUrl/api/executions/$([uri]::EscapeDataString($aiExecutionId))" -Method Get
    if ($persistedExecution.origin -ne "AI_DIRECT" -or
            $persistedExecution.status -ne "COMPLETED") {
        throw "AI 直执记录未正确持久化。"
    }
    $executionList = @(Invoke-RestMethod -Uri "$BaseUrl/api/executions" -Method Get)
    if (($executionList | ForEach-Object { $_.executionId }) -notcontains $aiExecutionId) {
        throw "执行记录列表缺少 AI 直执记录。"
    }
    $rolledBackExecution = Invoke-RestMethod `
        -Uri "$BaseUrl/api/executions/$([uri]::EscapeDataString($aiExecutionId))/rollback" -Method Post
    if ($rolledBackExecution.status -ne "ROLLED_BACK") {
        throw "AI 直执记录回滚未返回 ROLLED_BACK。"
    }
}

# AI 场景完成后重新启用计划，测试旧计划 API；最终再恢复调用前的用户设置。
Invoke-RestMethod -Uri "$BaseUrl/api/system/features/planning" -Method Put `
    -ContentType "application/json" `
    -Body (@{ enabled = $true } | ConvertTo-Json) | Out-Null

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
    # 只发送未确认请求验证 409，不确认执行计划，避免烟测产生计划说明之外的业务变更。
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

# 正常路径恢复原开关，避免烟测改变用户下一次启动看到的功能状态。
Invoke-RestMethod -Uri "$BaseUrl/api/system/features/planning" -Method Put `
    -ContentType "application/json" `
    -Body (@{ enabled = $initialPlanningEnabled } | ConvertTo-Json) | Out-Null

# 刷新只验证响应契约；当前烟测不替换、卸载或热加载已有模块。
$refresh = Invoke-RestMethod -Uri "$BaseUrl/api/system/modules/refresh" -Method Post
if ($null -eq $refresh.added -or $null -eq $refresh.issues -or
        [string]::IsNullOrWhiteSpace([string]$refresh.revision)) {
    throw "模块刷新响应缺少 added、issues 或 revision。"
}

# 到达这里说明所有断言和回滚均成功；明确的零退出码供门禁脚本判断。
Write-Host "HTTP 烟测通过：JDK 21、计划开关、AI Action 数组顺序执行与输出引用、风险确认、Action AI 调用限制、持久化记录与回滚、业务/系统模块目录、模块偏好、模块 UI、通用查询、目录主题、刷新响应和未执行计划确认；业务测试数据已回滚。"
Write-Host "已执行无必填参数的通用查询数量：$queryCount"

exit 0
