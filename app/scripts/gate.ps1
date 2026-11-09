[CmdletBinding()]
param(
    [switch]$Fast
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appRoot = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $appRoot
$controlRoot = Join-Path $appRoot 'control'
$contractsRoot = Join-Path $appRoot 'contracts'
$webRoot = Join-Path $appRoot 'web'
$mavenRepo = Join-Path $env:TEMP 'orderly-m2\repository'
$buildRoot = Join-Path $env:TEMP 'orderly-control-build'
$script:Failures = [System.Collections.Generic.List[string]]::new()
$script:BuildOutput = @()
$script:JarPath = $null
$timer = [System.Diagnostics.Stopwatch]::StartNew()

function Add-Pass([string]$Check, [string]$Message) {
    Write-Host ("{0} PASS {1}" -f $Check, $Message)
}

function Add-Failure([string]$Check, [string]$Location, [string]$Problem, [string]$Suggestion) {
    $entry = "{0} {1} -> {2} | {3}" -f $Check, $Location, $Problem, $Suggestion
    $script:Failures.Add($entry)
    Write-Host $entry
}

function Get-PropertyValue([object]$Object, [string]$Name) {
    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Test-HasProperty([object]$Object, [string]$Name) {
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function New-JsonPointer([string]$Path, [string]$Name) {
    $segment = $Name.Replace('~', '~0').Replace('/', '~1')
    if ([string]::IsNullOrEmpty($Path)) {
        return "/$segment"
    }
    return "$Path/$segment"
}

function Test-JsonValueEqual([object]$Left, [object]$Right) {
    if ($null -eq $Left -and $null -eq $Right) {
        return $true
    }
    if ($null -eq $Left -or $null -eq $Right) {
        return $false
    }
    $leftJson = $Left | ConvertTo-Json -Depth 100 -Compress
    $rightJson = $Right | ConvertTo-Json -Depth 100 -Compress
    return $leftJson -ceq $rightJson
}

function Test-JsonType([object]$Value, [string]$Type) {
    switch ($Type) {
        'object' { return $Value -is [pscustomobject] }
        'array' { return $Value -is [System.Collections.IList] -and $Value -isnot [string] }
        'string' { return $Value -is [string] }
        'boolean' { return $Value -is [bool] }
        'integer' { return ($Value -is [sbyte] -or $Value -is [byte] -or $Value -is [int16] -or
            $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or
            $Value -is [int64] -or $Value -is [uint64]) }
        'number' { return ($Value -is [sbyte] -or $Value -is [byte] -or $Value -is [int16] -or
            $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or
            $Value -is [int64] -or $Value -is [uint64] -or $Value -is [single] -or
            $Value -is [double] -or $Value -is [decimal]) }
        'null' { return $null -eq $Value }
        default { return $false }
    }
}

function Get-JsonType([object]$Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [pscustomobject]) { return 'object' }
    if ($Value -is [System.Collections.IList] -and $Value -isnot [string]) { return 'array' }
    if ($Value -is [string]) { return 'string' }
    if ($Value -is [bool]) { return 'boolean' }
    if ($Value -is [sbyte] -or $Value -is [byte] -or $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64]) {
        return 'integer'
    }
    if ($Value -is [single] -or $Value -is [double] -or $Value -is [decimal]) { return 'number' }
    return 'unknown'
}

function Resolve-JsonSchemaReference([string]$Reference, [object]$ModuleSchema, [object]$RegistrySchema) {
    if ($Reference -eq 'module.schema.json') { return $ModuleSchema }
    if ($Reference -eq 'registry.schema.json') { return $RegistrySchema }
    if (-not $Reference.StartsWith('#/')) { return $null }
    $current = $ModuleSchema
    foreach ($part in $Reference.Substring(2).Split('/')) {
        $decoded = $part.Replace('~1', '/').Replace('~0', '~')
        if ($current -is [System.Collections.IList]) {
            $current = $current[[int]$decoded]
        } else {
            $current = Get-PropertyValue $current $decoded
        }
    }
    return $current
}

function Test-JsonSchemaNode(
    [object]$Value,
    [object]$Schema,
    [string]$Path = '',
    [object]$ModuleSchema = $null,
    [object]$RegistrySchema = $null
) {
    $errors = @()
    if ($null -eq $Schema) { return $errors }
    if (Test-HasProperty $Schema '$ref') {
        $resolved = Resolve-JsonSchemaReference $Schema.'$ref' $ModuleSchema $RegistrySchema
        if ($null -eq $resolved) {
            return @((New-JsonPointer '' '') + " -> unresolved schema reference: $($Schema.'$ref')")
        }
        $errors += @(Test-JsonSchemaNode $Value $resolved $Path $ModuleSchema $RegistrySchema)
    }

    if (Test-HasProperty $Schema 'const') {
        if (-not (Test-JsonValueEqual $Value $Schema.const)) {
            return @("$(if ($Path) { $Path } else { '/' }) -> expected constant $($Schema.const | ConvertTo-Json -Compress)")
        }
    }

    if (Test-HasProperty $Schema 'enum') {
        $matched = $false
        foreach ($candidate in @($Schema.enum)) {
            if (Test-JsonValueEqual $Value $candidate) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            return @("$(if ($Path) { $Path } else { '/' }) -> value must be one of $($Schema.enum | ConvertTo-Json -Compress)")
        }
    }

    if (Test-HasProperty $Schema 'type') {
        $types = @($Schema.type)
        $matchedType = $false
        foreach ($typeName in $types) {
            if (Test-JsonType $Value ([string]$typeName)) {
                $matchedType = $true
                break
            }
        }
        if (-not $matchedType) {
            $location = if ($Path) { $Path } else { '/' }
            return @("$location -> expected type $($Schema.type | ConvertTo-Json -Compress), got $(Get-JsonType $Value)")
        }
    }

    if (Test-HasProperty $Schema 'oneOf') {
        $matchingBranches = 0
        $branchErrors = @()
        foreach ($branch in @($Schema.oneOf)) {
            $current = @(Test-JsonSchemaNode $Value $branch $Path $ModuleSchema $RegistrySchema)
            if ($current.Count -eq 0) {
                $matchingBranches++
            } else {
                $branchErrors += $current
            }
        }
        if ($matchingBranches -ne 1) {
            $location = if ($Path) { $Path } else { '/' }
            $errors += "$location -> value must match exactly one oneOf branch"
            $errors += $branchErrors
        }
    }

    if ($Value -is [pscustomobject]) {
        if (Test-HasProperty $Schema 'required') {
            foreach ($field in @($Schema.required)) {
                if (-not (Test-HasProperty $Value ([string]$field))) {
                    $errors += "$(New-JsonPointer $Path ([string]$field)) -> required field is missing"
                }
            }
        }
        if (Test-HasProperty $Schema 'properties') {
            foreach ($property in $Value.PSObject.Properties) {
                if (Test-HasProperty $Schema.properties $property.Name) {
                    $errors += @(Test-JsonSchemaNode $property.Value $Schema.properties.($property.Name) (New-JsonPointer $Path $property.Name) $ModuleSchema $RegistrySchema)
                }
            }
        }
        if (Test-HasProperty $Schema 'additionalProperties') {
            $additional = $Schema.additionalProperties
            foreach ($property in $Value.PSObject.Properties) {
                $declared = (Test-HasProperty $Schema 'properties') -and
                    (Test-HasProperty $Schema.properties $property.Name)
                if (-not $declared) {
                    if ($additional -is [bool] -and -not $additional) {
                        $errors += "$(New-JsonPointer $Path $property.Name) -> additional property is not allowed"
                    } elseif ($additional -is [pscustomobject]) {
                        $errors += @(Test-JsonSchemaNode $property.Value $additional (New-JsonPointer $Path $property.Name) $ModuleSchema $RegistrySchema)
                    }
                }
            }
        }
    }

    if ($Value -is [System.Collections.IList] -and $Value -isnot [string]) {
        if ((Test-HasProperty $Schema 'minItems') -and $Value.Count -lt [int]$Schema.minItems) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> expected at least $($Schema.minItems) items"
        }
        if (Test-HasProperty $Schema 'items') {
            for ($index = 0; $index -lt $Value.Count; $index++) {
                $errors += @(Test-JsonSchemaNode $Value[$index] $Schema.items "$Path/$index" $ModuleSchema $RegistrySchema)
            }
        }
        if ((Test-HasProperty $Schema 'uniqueItems') -and $Schema.uniqueItems) {
            for ($left = 0; $left -lt $Value.Count; $left++) {
                for ($right = $left + 1; $right -lt $Value.Count; $right++) {
                    if (Test-JsonValueEqual $Value[$left] $Value[$right]) {
                        $errors += "$(if ($Path) { $Path } else { '/' }) -> items must be unique"
                        $left = $Value.Count
                        break
                    }
                }
            }
        }
    } elseif ($Value -is [string]) {
        $minLength = Get-PropertyValue $Schema 'minLength'
        if ($null -ne $minLength -and $Value.Length -lt [int]$minLength) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> string is shorter than $minLength"
        }
        $maxLength = Get-PropertyValue $Schema 'maxLength'
        if ($null -ne $maxLength -and $Value.Length -gt [int]$maxLength) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> string is longer than $maxLength"
        }
        $pattern = Get-PropertyValue $Schema 'pattern'
        if ($null -ne $pattern -and -not [regex]::IsMatch($Value, [string]$pattern)) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> string does not match pattern $pattern"
        }
    } elseif ((Get-JsonType $Value) -in @('number', 'integer')) {
        $number = [decimal]$Value
        if ((Test-HasProperty $Schema 'minimum') -and $number -lt [decimal]$Schema.minimum) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> number is less than minimum"
        }
        if ((Test-HasProperty $Schema 'exclusiveMinimum') -and $number -le [decimal]$Schema.exclusiveMinimum) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> number must be greater than exclusive minimum"
        }
        if ((Test-HasProperty $Schema 'maximum') -and $number -gt [decimal]$Schema.maximum) {
            $errors += "$(if ($Path) { $Path } else { '/' }) -> number is greater than maximum"
        }
    }

    return $errors
}

function Invoke-MavenBuild() {
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($null -eq $maven) {
        Add-Failure 'G4' 'app/control/pom.xml:1' 'Maven executable is unavailable' 'Install Maven and rerun app\scripts\gate.cmd'
        return
    }
    $arguments = @(
        '-f',
        $pomPath,
        "-Dmaven.repo.local=$mavenRepo",
        "-Dorderly.build.dir=$buildRoot",
        '-q',
        'package'
    )
    if ($Fast) {
        $arguments = @(
            '-f',
            $pomPath,
            "-Dmaven.repo.local=$mavenRepo",
            "-Dorderly.build.dir=$buildRoot",
            '-q',
            '-DskipTests',
            'package'
        )
    }
    $output = & $maven.Source @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $script:BuildOutput = @($output)
    if ($exitCode -ne 0) {
        $first = $output | Where-Object { $_ -match '(\.java:\d+|\[ERROR\]|Failures:)' } | Select-Object -First 1
        if ($null -eq $first) { $first = 'Maven package failed' }
        Add-Failure 'G4' 'app/control/pom.xml:1' ([string]$first) 'Fix the first compiler/test error and rerun the gate'
    } else {
        $script:JarPath = Join-Path $buildRoot 'orderly-control-0.1.0-SNAPSHOT.jar'
        Add-Pass 'G4' ($(if ($Fast) { 'compile passed; tests skipped by -Fast' } else { 'compile and tests passed' }))
    }
}

try {
    $allowedRootEntries = @('AGENTS.md', 'prompts', 'tasks', 'docs', 'app')
    $rootEntries = @(Get-ChildItem -LiteralPath $repoRoot -Force | Where-Object { -not $_.Name.StartsWith('.') })
    foreach ($entry in $rootEntries) {
        if ($entry.Name -notin $allowedRootEntries) {
            Add-Failure 'G1' (Join-Path $repoRoot $entry.Name) "unexpected repository entry: $($entry.Name)" 'Move it into app/ or apply an RFC'
        }
    }
    $allowedAppEntries = @('contracts', 'control', 'web', 'docs', 'scripts')
    $appEntries = @(Get-ChildItem -LiteralPath $appRoot -Force | Where-Object { -not $_.Name.StartsWith('.') })
    foreach ($required in $allowedAppEntries) {
        if (-not (Test-Path -LiteralPath (Join-Path $appRoot $required))) {
            Add-Failure 'G1' (Join-Path $appRoot $required) "required program entry is missing: $required" 'Create the entry before running the gate'
        }
    }
    foreach ($entry in $appEntries) {
        if ($entry.Name -notin $allowedAppEntries) {
            Add-Failure 'G1' (Join-Path $appRoot $entry.Name) "unexpected program entry: $($entry.Name)" 'Move it into an approved part of app/ or apply an RFC'
        }
    }
    if ((@($script:Failures | Where-Object { $_.StartsWith('G1 ') })).Count -eq 0) {
        Add-Pass 'G1' 'repository and program directory whitelists passed'
    }

    $forbiddenPaths = @(
        (Join-Path $appRoot 'ai'),
        (Join-Path $webRoot 'node_modules'),
        (Join-Path $webRoot 'package.json'),
        (Join-Path $webRoot 'package-lock.json')
    )
    foreach ($forbidden in $forbiddenPaths) {
        if (Test-Path -LiteralPath $forbidden) {
            Add-Failure 'G2' $forbidden 'forbidden S1 path exists' 'Remove it; S1 has no AI service or frontend build dependency'
        }
    }
    $pythonFiles = @(Get-ChildItem -LiteralPath $appRoot -Recurse -File -Filter '*.py' -ErrorAction SilentlyContinue)
    foreach ($file in $pythonFiles) {
        Add-Failure 'G2' $file.FullName 'Python file is forbidden in S1' 'Move Python work to S4'
    }
    $pomPath = Join-Path $controlRoot 'pom.xml'
    if (Test-Path -LiteralPath $pomPath) {
        try {
            [xml]$pom = Get-Content -LiteralPath $pomPath -Raw
            $allowedDependencies = @('spring-boot-starter-web', 'spring-boot-starter-test')
            foreach ($dependency in @($pom.project.dependencies.dependency)) {
                if ($null -ne $dependency -and $dependency.artifactId -notin $allowedDependencies) {
                    Add-Failure 'G2' "${pomPath}:1" "unexpected Maven dependency: $($dependency.groupId):$($dependency.artifactId)" 'Remove the dependency or obtain an explicit architecture decision'
                }
            }
        } catch {
            Add-Failure 'G2' "${pomPath}:1" "pom.xml cannot be parsed: $($_.Exception.Message)" 'Repair pom.xml before build'
        }
    }
    if ((@($script:Failures | Where-Object { $_.StartsWith('G2 ') })).Count -eq 0) {
        Add-Pass 'G2' 'no AI, Python, frontend package, or unexpected dependency detected'
    }

    $moduleSchemaPath = Join-Path $contractsRoot 'module.schema.json'
    $registrySchemaPath = Join-Path $contractsRoot 'registry.schema.json'
    $registryPath = Join-Path $contractsRoot 'registry.json'
    try {
        $moduleSchema = Get-Content -LiteralPath $moduleSchemaPath -Raw | ConvertFrom-Json
        $registrySchema = Get-Content -LiteralPath $registrySchemaPath -Raw | ConvertFrom-Json
        $registry = Get-Content -LiteralPath $registryPath -Raw | ConvertFrom-Json
        if ($moduleSchema.'$schema' -ne 'https://json-schema.org/draft/2020-12/schema') {
            Add-Failure 'G3' "${moduleSchemaPath}:1" 'module schema is not declared as JSON Schema 2020-12' 'Restore the frozen schema declaration'
        }
        if ($moduleSchema.additionalProperties -ne $false) {
            Add-Failure 'G3' "${moduleSchemaPath}:1" 'module schema must reject additional root properties' 'Set additionalProperties to false'
        }
        if (@($moduleSchema.required).Count -ne 11) {
            Add-Failure 'G3' "${moduleSchemaPath}:1" "expected 11 frozen fields, found $(@($moduleSchema.required).Count)" 'Use the guardian-frozen 11-field contract'
        }
        $registryErrors = @(Test-JsonSchemaNode $registry $registrySchema '' $moduleSchema $registrySchema)
        foreach ($violation in $registryErrors) {
            Add-Failure 'G3' $registryPath $violation 'Fix registry.json to match registry.schema.json'
        }
        if (@($registry.modules).Count -ne 0) {
            Add-Failure 'G3' "${registryPath}:1" "S1 registry must contain zero modules, found $(@($registry.modules).Count)" 'Keep registry.json modules empty in S1'
        }
        $validSamplePath = Join-Path $contractsRoot 'samples\valid\move-items.json'
        $validSample = Get-Content -LiteralPath $validSamplePath -Raw | ConvertFrom-Json
        $validErrors = @(Test-JsonSchemaNode $validSample $moduleSchema '' $moduleSchema $registrySchema)
        foreach ($violation in $validErrors) {
            Add-Failure 'G3' $validSamplePath "valid sample rejected: $violation" 'Fix the sample or the frozen schema'
        }
        $invalidSamples = @(
            @{ Name = 'missing-risk.json'; Pointer = '/risk' },
            @{ Name = 'type-error.json'; Pointer = '/name' },
            @{ Name = 'extra-field.json'; Pointer = '/confirmCount' },
            @{ Name = 'invalid-risk.json'; Pointer = '/risk' },
            @{ Name = 'irreversible-risk-conflict.json'; Pointer = '/irreversible' },
            @{ Name = 'inverse-null-reversible.json'; Pointer = '/inverse' }
        )
        foreach ($sample in $invalidSamples) {
            $samplePath = Join-Path $contractsRoot "samples\invalid\$($sample.Name)"
            $sampleValue = Get-Content -LiteralPath $samplePath -Raw | ConvertFrom-Json
            $sampleErrors = @(Test-JsonSchemaNode $sampleValue $moduleSchema '' $moduleSchema $registrySchema)
            if ($sampleErrors.Count -eq 0) {
                Add-Failure 'G3' $samplePath 'invalid sample was accepted' 'Repair schema validation until this sample is rejected'
            } elseif (-not ($sampleErrors -join "`n").Contains($sample.Pointer)) {
                Add-Failure 'G3' $samplePath "rejection did not identify $($sample.Pointer)" 'Return a JSON pointer for the failing field'
            }
        }
        if ((@($script:Failures | Where-Object { $_.StartsWith('G3 ') })).Count -eq 0) {
            Add-Pass 'G3' 'schema, empty registry, valid sample, and invalid samples passed'
        }
    } catch {
        Add-Failure 'G3' $contractsRoot "contract files could not be validated: $($_.Exception.Message)" 'Repair JSON or contract files before implementation'
    }

    Invoke-MavenBuild

    $apiSources = @(Get-ChildItem -LiteralPath (Join-Path $controlRoot 'src\main\java\com\orderly\api') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    $kernelSources = @(Get-ChildItem -LiteralPath (Join-Path $controlRoot 'src\main\java\com\orderly\kernel') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    foreach ($file in $kernelSources) {
        $reverseImport = Select-String -LiteralPath $file.FullName -Pattern '^\s*import\s+com\.orderly\.api\.' -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $reverseImport) {
            Add-Failure 'G5' "${($file.FullName)}:$($reverseImport.LineNumber)" 'kernel imports api (reverse dependency)' 'Depend from api to kernel only'
        }
    }
    $moduleFiles = @(Get-ChildItem -LiteralPath (Join-Path $controlRoot 'src\main\java\com\orderly\module') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    foreach ($file in $moduleFiles) {
        $parts = $file.FullName -split '\\'
        $moduleIndex = [array]::IndexOf($parts, 'module')
        if ($moduleIndex -ge 0 -and $moduleIndex + 1 -lt $parts.Count) {
            $currentModule = $parts[$moduleIndex + 1]
            $imports = Select-String -LiteralPath $file.FullName -Pattern '^\s*import\s+com\.orderly\.module\.([a-z0-9_]+)\.' -AllMatches
            foreach ($import in $imports) {
                foreach ($match in $import.Matches) {
                    if ($match.Groups[1].Value -ne $currentModule) {
                        Add-Failure 'G5' "${($file.FullName)}:$($import.LineNumber)" "module $currentModule imports module $($match.Groups[1].Value)" 'Remove direct module-to-module dependency'
                    }
                }
            }
        }
    }
    if ((@($script:Failures | Where-Object { $_.StartsWith('G5 ') })).Count -eq 0) {
        Add-Pass 'G5' 'api-to-kernel layering passed'
    }

    if ($null -eq $script:JarPath -or -not (Test-Path -LiteralPath $script:JarPath)) {
        Add-Failure 'G6' $buildRoot 'application jar is unavailable because build did not succeed' 'Fix G4 before checking startup'
    } else {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
        $listener.Stop()
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
        $stdout = Join-Path $buildRoot 'gate-stdout.log'
        $stderr = Join-Path $buildRoot 'gate-stderr.log'
        $process = $null
        try {
            $process = Start-Process -FilePath $java -ArgumentList @(
                '-jar', "`"$($script:JarPath)`"",
                "--server.port=$port",
                "--orderly.contracts-root=`"$contractsRoot`"",
                "--orderly.web-root=`"$webRoot`"",
                '--spring.main.banner-mode=off',
                '--logging.level.root=WARN'
            ) -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            $health = $null
            for ($attempt = 0; $attempt -lt 60; $attempt++) {
                Start-Sleep -Milliseconds 250
                if ($process.HasExited) { break }
                try {
                    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 1
                    break
                } catch {
                    $health = $null
                }
            }
            if ($null -eq $health) {
                $logTail = @()
                if (Test-Path -LiteralPath $stderr) {
                    $logTail = @(Get-Content -LiteralPath $stderr -Tail 12)
                }
                Add-Failure 'G6' $script:JarPath "application did not become healthy: $($logTail -join ' ')" 'Inspect startup logs and the empty registry path'
            } elseif ($health.status -ne 'ok' -or [int]$health.modules -ne 0) {
                Add-Failure 'G6' $script:JarPath "health returned status=$($health.status), modules=$($health.modules)" 'Application must start with exactly zero modules'
            } else {
                Add-Pass 'G6' "application started with status=ok, modules=0 on port $port"
            }
        } catch {
            Add-Failure 'G6' $script:JarPath "startup check failed: $($_.Exception.Message)" 'Run start.cmd and inspect the structured startup output'
        } finally {
            if ($null -ne $process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        }
    }

    if (Test-Path -LiteralPath $webRoot) {
        $webFiles = @(Get-ChildItem -LiteralPath $webRoot -Recurse -File |
            Where-Object { $_.Extension -in @('.css', '.html', '.js') -and $_.FullName -notmatch '[\\/]themes[\\/]' })
        foreach ($file in $webFiles) {
            $lineNumber = 0
            foreach ($line in [System.IO.File]::ReadLines($file.FullName)) {
                $lineNumber++
                if ($line -match '(?i)#(?:[0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})\b') {
                    Add-Failure 'G7' "$($file.FullName):$lineNumber" "hex color in non-theme file ($($Matches[0]))" 'Move the value to themes and use var(--semantic-token)'
                }
                if ($line -match '(?i)\b(?:rgba?|hsla?)\s*\(') {
                    Add-Failure 'G7' "$($file.FullName):$lineNumber" "color function in non-theme file ($($Matches[0]))" 'Move the value to themes and use a semantic variable'
                }
                if ($line -match '(?i)(?<![\w-])\d+(?:\.\d+)?(?:ms|s)\b' -and
                    $Matches[0] -notmatch '^0(?:\.0+)?(?:ms|s)$') {
                    Add-Failure 'G7' "$($file.FullName):$lineNumber" "hard-coded duration in non-theme file ($($Matches[0]))" 'Use a duration variable'
                }
                if ($line -match '(?i)font-family\s*:\s*([^;]+)') {
                    $fontValue = $Matches[1].Trim()
                    if ($fontValue -notmatch '^var\(--[a-z0-9-]+\)$') {
                        Add-Failure 'G7' "$($file.FullName):$lineNumber" "font family in non-theme file ($($Matches[0].Trim()))" 'Use a font variable supplied by the theme layer'
                    }
                }
            }
        }
    }
    if ((@($script:Failures | Where-Object { $_.StartsWith('G7 ') })).Count -eq 0) {
        Add-Pass 'G7' 'non-theme style files contain no literal colors, durations, or font families'
    }

    $indexPath = Join-Path $webRoot 'index.html'
    if (-not (Test-Path -LiteralPath $indexPath)) {
        Add-Failure 'G8' $indexPath 'web/index.html is missing' 'Build the UI skeleton before merging'
    } else {
        $index = Get-Content -LiteralPath $indexPath -Raw
        foreach ($anchor in @('sidebar', 'workspace', 'dock', 'settings-trigger', 'settings-dialog', 'theme-toggle')) {
            if ($index -notmatch ('data-o-anchor\s*=\s*["'']' + [regex]::Escape($anchor) + '["'']')) {
                Add-Failure 'G8' "${indexPath}:1" "missing frozen UI anchor: $anchor" "Add data-o-anchor=`"$anchor`""
            }
        }
        if ($index -notmatch '(?i)(type\s*=\s*["'']search["'']|role\s*=\s*["'']searchbox["''])') {
            Add-Failure 'G8' "${indexPath}:1" 'sidebar search control is missing' 'Add the search input defined by UI-SPEC'
        }
        if ($index -notmatch '(?i)(data-o-(list|module-list|items)|data-o-anchor\s*=\s*["'']module-list["'']|id\s*=\s*["'']sidebar-items["'']|class\s*=\s*["''][^"'']*module-list[^"'']*["''])') {
            Add-Failure 'G8' "${indexPath}:1" 'sidebar item list marker is missing' 'Add a stable data-o-list marker for the module list'
        }
        if ($index -notmatch '(?i)(data-o-close|aria-label\s*=\s*["''][^"'']*关闭[^"'']*["''])') {
            Add-Failure 'G8' "${indexPath}:1" 'settings close control is missing' 'Add a data-o-close marker or accessible close label'
        }
        if ($index -notmatch '(?i)(data-o-empty-state|data-o-anchor\s*=\s*["'']empty-state["'']|id\s*=\s*["''][^"'']*empty[^"'']*["'']|class\s*=\s*["''][^"'']*empty[^"'']*["''])') {
            Add-Failure 'G8' "${indexPath}:1" 'empty-state marker is missing' 'Add a stable empty-state node for zero-module rendering'
        }
    }
    if ((@($script:Failures | Where-Object { $_.StartsWith('G8 ') })).Count -eq 0) {
        Add-Pass 'G8' 'three主体 anchors, settings controls, theme entry, and empty state are present'
    }
} catch {
    Add-Failure 'GATE' 'app/scripts/gate.ps1:1' $_.Exception.Message 'Repair the gate exception before trusting its result'
} finally {
    $timer.Stop()
    Write-Host ''
    if ($script:Failures.Count -eq 0) {
        Write-Host ("GATE PASS mode={0} elapsed={1:N1}s" -f ($(if ($Fast) { 'fast' } else { 'full' })), $timer.Elapsed.TotalSeconds)
        $exitCode = 0
    } else {
        Write-Host ("GATE FAIL mode={0} failures={1} elapsed={2:N1}s" -f ($(if ($Fast) { 'fast' } else { 'full' })), $script:Failures.Count, $timer.Elapsed.TotalSeconds)
        $exitCode = 1
    }
    $resolvedBuild = [System.IO.Path]::GetFullPath($buildRoot)
    $resolvedTemp = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
    if ($resolvedBuild.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedBuild)) {
        Remove-Item -LiteralPath $resolvedBuild -Recurse -Force -ErrorAction SilentlyContinue
    }
}

exit $exitCode
