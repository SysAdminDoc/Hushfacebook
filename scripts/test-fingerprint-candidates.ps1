<#
.SYNOPSIS
    Hold FingerprintCandidates.java to its calibration, to failing closed, and to writing no patch.

.DESCRIPTION
    FingerprintFixture.java writes small builds with Redex-style names: an old one with a target
    method and its caller, and three new ones. In "moved" the target was renamed into another class
    among decoys, and it has to rank first and stand out, with the tool still accepting nothing. In
    "twins" it is there twice, identical, and in "gone" it is missing: both runs have to fail closed
    and name no candidate on the console. A captured signature has to hold exactly the properties
    fingerprint-signature.schema.json declares, and one of another version has to be refused.

    Then fingerprint-candidates.ps1 -Calibrate runs over Facebook 577 and 580 from
    HUSHFACEBOOK_FIXTURE_DIR, and every case of fingerprint-calibration.txt has to rank its known 580
    method in the top five. The report has to show each candidate's prototype, strings, literals,
    opcode sketch, references and call neighbourhood. The AMOLED resolver that split in two has two
    real candidates, and that case has to fail closed.

    Through all of it, nothing under patches/ may change, and an output path there, or one ending in
    .kt or .java, has to be refused by both the tool and the wrapper.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java,
    [string]$DesktopJar
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Native calls run with Continue: Windows PowerShell 5.1 turns a program's stderr into a
# terminating error under Stop, and several cases here are meant to make the tool fail.
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $ErrorActionPreference = 'Continue'
    $output = @(& $Program @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) { throw "$Description exited $LASTEXITCODE.`n$($output -join "`n")" }
}

function Invoke-Tool {
    param([string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = -1
    $output = @(& $Java '-Xmx2g' '-cp' $classPath 'FingerprintCandidates' @Arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Text = $output -join "`n" }
}

function Invoke-Wrapper {
    param([string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = -1
    $output = @(& (Get-Process -Id $PID).Path -NoProfile -NonInteractive -ExecutionPolicy Bypass `
        -File (Join-Path $PSScriptRoot 'fingerprint-candidates.ps1') @Arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Text = $output -join "`n" }
}

function New-DexApk {
    param([string]$Name)
    $path = Join-Path $caseRoot "$Name.apk"
    $archive = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $dexDir "$Name.dex"),
            'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    } finally {
        $archive.Dispose()
    }
    return $path
}

# Every file under patches/, with its size and write time: a write the tool made there shows up.
function Get-TreeSnapshot {
    param([string]$Path)
    return @(Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue | ForEach-Object {
        '{0}|{1}|{2}' -f $_.FullName.Substring($Path.Length), $_.Length, $_.LastWriteTimeUtc.Ticks
    } | Sort-Object)
}

# The subset of JSON Schema that fingerprint-signature.schema.json uses: type, const, required,
# properties, additionalProperties false, items, pattern and minimum. Each violation is one line
# of output.
function Get-SchemaViolations {
    param($Value, $Schema, [string]$Where)
    if ($Schema.PSObject.Properties['const'] -and -not ($Value -ceq $Schema.const)) {
        "${Where}: '$Value' is not '$($Schema.const)'"
    }
    if ($Schema.type -eq 'object') {
        if ($Value -isnot [System.Management.Automation.PSCustomObject]) { return "${Where}: not an object" }
        $names = @($Value.PSObject.Properties | ForEach-Object { $_.Name })
        foreach ($required in @($Schema.required)) {
            if ($names -cnotcontains $required) { "${Where}: $required is missing" }
        }
        foreach ($name in $names) {
            $property = $Schema.properties.PSObject.Properties[$name]
            if ($null -eq $property) {
                if ($Schema.additionalProperties -eq $false) { "${Where}: $name is not in the schema" }
                continue
            }
            Get-SchemaViolations $Value.PSObject.Properties[$name].Value $property.Value "$Where.$name"
        }
    } elseif ($Schema.type -eq 'array') {
        if ($Value -isnot [array]) { return "${Where}: not an array" }
        for ($k = 0; $k -lt $Value.Count; $k++) { Get-SchemaViolations $Value[$k] $Schema.items "$Where[$k]" }
    } elseif ($Schema.type -eq 'string') {
        if ($Value -isnot [string]) { return "${Where}: not a string" }
        if ($Schema.pattern -and $Value -cnotmatch $Schema.pattern) { "${Where}: '$Value' does not match $($Schema.pattern)" }
    } elseif ($Schema.type -eq 'integer') {
        if (-not ($Value -is [int] -or $Value -is [long])) { return "${Where}: not an integer" }
        if ($null -ne $Schema.minimum -and $Value -lt $Schema.minimum) { "${Where}: below $($Schema.minimum)" }
    } elseif ($Schema.type -eq 'boolean') {
        if ($Value -isnot [bool]) { "${Where}: not a boolean" }
    }
}

$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
$javac = Join-Path (Split-Path -Parent $Java) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw "Required tool not found: $javac" }
$patches = Join-Path $Root 'patches'
$before = Get-TreeSnapshot $patches
Assert-True ($before.Count -gt 0) "No files under $patches, so the no-write check would compare nothing."

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("hushfacebook-fingerprint-test-" + [guid]::NewGuid().ToString('N'))))
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $classes = Join-Path $caseRoot 'classes'
    Invoke-Checked -Program $javac -Arguments @('-encoding', 'UTF-8', '-nowarn', '-cp', $DesktopJar, '-d', $classes,
        (Join-Path $PSScriptRoot 'FingerprintCandidates.java'), (Join-Path $PSScriptRoot 'FingerprintFixture.java')) `
        -Description 'javac for FingerprintCandidates and its fixture'
    $classPath = $DesktopJar + [System.IO.Path]::PathSeparator + $classes
    $dexDir = Join-Path $caseRoot 'dex'
    Invoke-Checked -Program $Java -Arguments @('-cp', $classPath, 'FingerprintFixture', $dexDir) -Description 'FingerprintFixture'
    $oldApk = New-DexApk 'old'
    $target = 'LX/Ab1;->A0q(Ljava/lang/String;I)Ljava/lang/String;'
    $moved = 'LX/Zz9;->B1c(Ljava/lang/String;I)Ljava/lang/String;'

    # The signature, and the schema it has to follow exactly.
    $signature = Join-Path $caseRoot 'signature.json'
    $captured = Invoke-Tool @('capture', $oldApk, $target, $signature)
    Assert-True ($captured.ExitCode -eq 0 -and (Test-Path -LiteralPath $signature)) "The capture failed.`n$($captured.Text)"
    $signatureText = [System.IO.File]::ReadAllText($signature)
    Assert-True ($signatureText -cnotmatch '[^\x09\x0a\x0d\x20-\x7e]') 'The signature holds more than printable ASCII.'
    $schema = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fingerprint-signature.schema.json') -Raw | ConvertFrom-Json
    $parsed = $signatureText | ConvertFrom-Json
    $violations = @(Get-SchemaViolations $parsed $schema 'signature')
    Assert-True ($violations.Count -eq 0) ("The signature breaks its schema:`n" + ($violations -join "`n"))
    Assert-True ($parsed.method.descriptor -ceq $target -and $parsed.callers.count -eq 1 -and
        @($parsed.strings) -contains 'fingerprint_fixture_marker' -and @($parsed.literals) -contains 'N:74565') `
        "The signature does not describe the target and its one caller.`n$signatureText"
    # The schema check itself, on a signature with a property the schema doesn't declare.
    $extra = @(Get-SchemaViolations ($signatureText.Replace('"schema":', '"unknown": 1, "schema":') | ConvertFrom-Json) $schema 'signature')
    Assert-True ($extra.Count -eq 1 -and $extra[0] -like '*unknown is not in the schema*') 'The schema check let an undeclared property through.'

    # One candidate stands out: it is named, ranked first, and still not accepted.
    $clearReport = Join-Path $caseRoot 'moved.txt'
    $clear = Invoke-Tool @('rank', $signature, (New-DexApk 'moved'), $clearReport)
    Assert-True ($clear.ExitCode -eq 0 -and $clear.Text -match [regex]::Escape("#1 ") -and
        ($clear.Output | Where-Object { $_ -like '*#1 *' } | Select-Object -First 1) -like "*$moved*" -and
        $clear.Text -match 'Nothing was accepted') "The moved target did not rank first and stand out.`n$($clear.Text)"
    $clearText = [System.IO.File]::ReadAllText($clearReport)
    Assert-True ($clearText -match 'one candidate stands out' -and $clearText -match 'accepts none') `
        "The report of the clear case did not say it stands out and accepts nothing.`n$clearText"

    # Two identical candidates: the run fails closed and names neither.
    $twinsReport = Join-Path $caseRoot 'twins.txt'
    $twins = Invoke-Tool @('rank', $signature, (New-DexApk 'twins'), $twinsReport)
    Assert-True ($twins.ExitCode -eq 1 -and $twins.Text -match 'fails closed' -and $twins.Text -notmatch 'LX/Zz[79];') `
        "Two identical candidates did not fail closed without naming one.`n$($twins.Text)"
    Assert-True ([System.IO.File]::ReadAllText($twinsReport) -match 'no candidate stands out') `
        'The report of the tie did not say that no candidate stands out.'

    # Nothing like the target: the run fails closed on the score.
    $gone = Invoke-Tool @('rank', $signature, (New-DexApk 'gone'), (Join-Path $caseRoot 'gone.txt'))
    Assert-True ($gone.ExitCode -eq 1 -and $gone.Text -match 'no candidate scores' -and $gone.Text -match 'fails closed') `
        "A build without the target did not fail closed.`n$($gone.Text)"

    # A signature of another version is refused rather than read the new way.
    $future = Join-Path $caseRoot 'future.json'
    [System.IO.File]::WriteAllText($future, $signatureText.Replace('"version": 1', '"version": 2'))
    $refused = Invoke-Tool @('rank', $future, (Join-Path $caseRoot 'moved.apk'), (Join-Path $caseRoot 'future.txt'))
    Assert-True ($refused.ExitCode -eq 2 -and $refused.Text -match 'version 2') "A version 2 signature was read.`n$($refused.Text)"

    # Nothing is ever written as patch source: not under patches/, and not as a .kt or .java file.
    $intoPatches = Join-Path $patches 'fingerprint-probe.json'
    $blocked = Invoke-Tool @('capture', $oldApk, $target, $intoPatches)
    Assert-True ($blocked.ExitCode -eq 2 -and $blocked.Text -match 'never edits a patch' -and
        -not (Test-Path -LiteralPath $intoPatches)) "The tool wrote under patches/.`n$($blocked.Text)"
    $asSource = Join-Path $caseRoot 'Candidate.kt'
    $blockedSource = Invoke-Tool @('rank', $signature, (Join-Path $caseRoot 'moved.apk'), $asSource)
    Assert-True ($blockedSource.ExitCode -eq 2 -and -not (Test-Path -LiteralPath $asSource)) `
        "The tool wrote a report as a Kotlin file.`n$($blockedSource.Text)"
    $wrapperIntoPatches = Join-Path $patches 'fingerprint-report.txt'
    $wrapperBlocked = Invoke-Wrapper @('-Signature', $signature, '-NewApk', (Join-Path $caseRoot 'moved.apk'),
        '-ReportPath', $wrapperIntoPatches, '-Java', $Java, '-DesktopJar', $DesktopJar, '-Root', $Root)
    # The wrapper's own refusal, before it unpacks a build or starts java, not the tool's behind it.
    Assert-True ($wrapperBlocked.ExitCode -ne 0 -and $wrapperBlocked.Text -match 'Refusing to write the report to' -and
        $wrapperBlocked.Text -match 'never edits a patch' -and -not (Test-Path -LiteralPath $wrapperIntoPatches)) `
        "The wrapper did not refuse a report under patches/ itself.`n$($wrapperBlocked.Text)"

    # The calibration: real transitions of Facebook 577 to 580, run the way a maintainer runs it.
    $calibrationFile = Join-Path $PSScriptRoot 'fingerprint-calibration.txt'
    $caseIds = @(Get-Content -LiteralPath $calibrationFile | Where-Object { $_ -match '^case (\S+)$' } | ForEach-Object { $Matches[1] })
    Assert-True ($caseIds.Count -ge 30) "The calibration holds $($caseIds.Count) cases."
    foreach ($id in 'reels-ad-break-tick', 'reels-state-name', 'reel-button-factory', 'amoled-fds-litho-resolver') {
        Assert-True ($caseIds -contains $id) "The calibration lost the case $id, one of the transitions that moved."
    }
    $fixtures = if ($env:HUSHFACEBOOK_FIXTURE_DIR) { $env:HUSHFACEBOOK_FIXTURE_DIR } else { Join-Path $Root 'fixtures' }
    foreach ($version in '577.0.0.50.72', '580.0.0.51.74') {
        Assert-True (@(Get-ChildItem -LiteralPath $fixtures -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name.Contains($version) }).Count -eq 1) `
            ("The calibration needs Facebook $version in $fixtures, the folder HUSHFACEBOOK_FIXTURE_DIR names. " +
                'Without it the top-five claim is not checked, so this fails rather than skipping.')
    }
    $calibrationReport = Join-Path $caseRoot 'calibration.txt'
    $calibrated = Invoke-Wrapper @('-Calibrate', '-ReportPath', $calibrationReport, '-Java', $Java, '-DesktopJar', $DesktopJar, '-Root', $Root)
    $lines = @($calibrated.Output | Where-Object { $_ -match '^\[fingerprint\] case \S+ rank \S+ score' })
    Assert-True ($calibrated.ExitCode -eq 0 -and $lines.Count -eq $caseIds.Count) `
        "The calibration did not run every case and pass.`n$($calibrated.Text)"
    $ranks = @{}
    foreach ($line in $lines) {
        Assert-True ($line -match '^\[fingerprint\] case (\S+) rank (\d+) score [0-9.]+ ok (stands-out|fails-closed)$') `
            "A calibrated case ranked its replacement outside the top five: $line"
        Assert-True ([int]$Matches[2] -le 5) "A calibrated case ranked its replacement below five: $line"
        $ranks[$Matches[1]] = $Matches[3]
    }
    Assert-True ($ranks['amoled-fds-litho-resolver'] -eq 'fails-closed') `
        'The AMOLED resolver that split in two has two real candidates, and its case did not fail closed.'
    $reportText = [System.IO.File]::ReadAllText($calibrationReport)
    foreach ($id in $caseIds) {
        $section = [regex]::Match($reportText, "(?s)== case $([regex]::Escape($id)) .*?(?=\r?\n== case |\r?\nCalibration: )").Value
        foreach ($label in 'prototype:', 'strings:', 'literals:', 'opcodes: old', 'references:', 'call neighbourhood:', '<- the known replacement') {
            Assert-True ($section.Contains($label)) "The report of case $id does not show '$label'."
        }
    }

    $after = Get-TreeSnapshot $patches
    Assert-True (@(Compare-Object $before $after).Count -eq 0) `
        ("Something under patches/ changed while the tool ran:`n" + (@(Compare-Object $before $after) | Out-String))
} finally {
    if ($caseRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host "[scripts] fingerprint candidates passed ($($caseIds.Count) calibrated cases in the top five; ties and misses fail closed)"
