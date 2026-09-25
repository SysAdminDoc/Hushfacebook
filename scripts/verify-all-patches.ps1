<#
.SYNOPSIS
    Apply every patch in the bundle and reject partial or unsuccessful output.

.DESCRIPTION
    The per-patch check with --exclusive answers one patch at a time. This applies all of
    them in a single run and validates the desktop CLI process, result report, patch names,
    target version and saved APK. A result file can be written from a finally block after a
    failed compile, so a zero failed-patch count is not enough to call the run successful.

    The bundle declares one compatible version, and the CLI refuses any other APK unless it is
    told to go ahead with -f. -Force does that for a retained newer build: the stock APK's own
    version is read with aapt2 and the result is held to that instead of the catalog's, so the
    run answers "which patches still apply on this build" rather than being refused before it
    starts. The package still has to be the catalog's.

    A clean run then holds the patched APK's resource table to the stock one with
    ResourceTableCheck.java: every resource of every package has to resolve by its id with its
    type and each configuration's value, and every file and reference the patched values name
    has to be there. A failure names the id. What the patches rewrote, what the rebuild renamed
    and what was added go in a report beside the result file.

    Last, verify-injected-registers.ps1 holds the patched dex to the stock dex: register counts,
    branch targets, invoke registers, parameter kinds, try ranges and the one feed guard. Its
    report goes beside the result file too.

.EXAMPLE
    scripts/verify-all-patches.ps1 -Apk C:\path\to\native-fixture.apk `
        -DesktopJar C:\path\to\morphe-desktop.jar -WorkDir C:\path\to\scratch

.EXAMPLE
    scripts/verify-all-patches.ps1 -Apk C:\fixtures\facebook-older.apk -Force `
        -DesktopJar C:\path\to\morphe-desktop.jar -WorkDir C:\path\to\scratch
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$DesktopJar,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Bundle,
    [string]$PatchList,
    [string]$Java,
    [switch]$Force,
    [string]$Aapt2
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
$Java = Resolve-Java -Explicit $Java
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $Bundle) {
    $version = Get-BundleVersion -Root $root
    $Bundle = Get-ReleaseBundlePath -Root $root -Version $version
    if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
        throw "No bundle for version $version at $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
    }
}
if (-not $PatchList) { $PatchList = Join-Path $root 'patches-list.json' }
if (-not $Bundle -or -not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "No bundle found. Run :patches:buildAndroid first." }
if (-not (Test-Path -LiteralPath $PatchList -PathType Leaf)) { throw "No patch list found: $PatchList" }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) { throw "Desktop CLI jar not found: $DesktopJar" }

try {
    $catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
    $names = @($catalog.patches | ForEach-Object { $_.name })
} catch {
    throw "Could not read patch list ${PatchList}: $($_.Exception.Message)"
}
$expectedTarget = Get-PatchTarget -PatchList $catalog
if ($names.Count -eq 0 -or @($names | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {
    throw "No valid patches listed in $PatchList."
}
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $names)
Write-Host "[verify] $($names.Count) patches from $(Split-Path -Leaf $Bundle)"

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$runId = [guid]::NewGuid().ToString('N')
$runDir = Join-Path $workRoot "verify-$runId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

# Facebook ships split bundles. aapt2 and the resource check read one APK, the base, which holds
# the manifest and the app's own resource table.
$stockApk = Get-BaseApk -Apk $Apk -Destination (Resolve-WithinRoot -Path (Join-Path $runDir 'stock-base.apk') -Root $workRoot)

# The version the result is held to: the stock APK's own, read the same way the receipt reads
# it. The catalog declares more than one version, so a declared one is patched as a user's Manager
# patches it, and any other only with -Force, which tells the CLI to go ahead with -f.
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
$stock = Get-ApkManifestFacts -Apk $stockApk -Aapt2 $Aapt2
if ($stock.package -ne $expectedTarget.PackageName) {
    throw "$(Split-Path -Leaf $Apk) is $($stock.package), not the catalog's target $($expectedTarget.PackageName)."
}
if ([string]::IsNullOrWhiteSpace($stock.versionName)) {
    throw "$(Split-Path -Leaf $Apk) carries no versionName, so there is nothing to hold the result to."
}
$declared = [System.Collections.Generic.HashSet[string]]::new(
    [string[]]$expectedTarget.PackageVersions, [System.StringComparer]::Ordinal)
$expectedVersion = $stock.versionName
$forced = -not $declared.Contains([string]$stock.versionName)
if ($forced -and -not $Force) {
    throw ("$(Split-Path -Leaf $Apk) is $($stock.versionName), which the bundle does not declare " +
        "($($expectedTarget.PackageVersions -join ', ')). Pass -Force to patch it anyway.")
}
if ($forced) {
    Write-Host "[verify] forcing the bundle onto $($stock.package) $($stock.versionName); it declares $($expectedTarget.PackageVersions -join ', ')"
} else {
    Write-Host "[verify] $($stock.package) $($stock.versionName) is a declared target, so nothing is forced"
}

$out = Resolve-WithinRoot -Path (Join-Path $runDir 'verify-all.apk') -Root $workRoot
$temp = Resolve-WithinRoot -Path (Join-Path $runDir 'verify-all-tmp') -Root $workRoot
$result = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-result-$runId.json") -Root $workRoot
$enable = @()
foreach ($name in $names) { $enable += '-e'; $enable += $name }
$arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
    '-o', $out, '-t', $temp, '-r', $result)
if ($forced) { $arguments += '-f' }
$arguments = $arguments + $enable + @($Apk)
$exitCode = 1

try {
    $cliOutput = @(& $Java '-jar' $DesktopJar @arguments 2>&1)
    $cliExitCode = $LASTEXITCODE
    $cliOutput | ForEach-Object {
        $line = [string]$_
        if ($line -match 'SEVERE|ERROR|Exception|result saved|Saved to') { Write-Host "[verify] $line" }
    }

    $report = $null
    if (Test-Path -LiteralPath $result -PathType Leaf) {
        try { $report = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json }
        catch { Write-Warning "Could not parse result JSON: $($_.Exception.Message)" }
    }
    $validation = Test-PatchingReport -Report $report -ExpectedNames $names `
        -AllowedDependencyNames $dependencyNames -OutputPath $out `
        -ExpectedPackageName $expectedTarget.PackageName -ExpectedPackageVersion $expectedVersion
    $reportApplied = if ($null -ne $report) { @($report.appliedPatches).Count } else { 0 }
    $reportFailed = if ($null -ne $report) { @($report.failedPatches).Count } else { 0 }
    $target = if ($null -ne $report) { "$($report.packageName) $($report.packageVersion)" } else { 'unknown target' }
    Write-Host "[verify] ${target}: applied $reportApplied, failed $reportFailed, CLI exit $cliExitCode"
    if ($null -ne $report) {
        foreach ($failure in @($report.failedPatches)) {
            $patchName = if ($null -ne $failure.patch) { $failure.patch.name } else { 'unknown patch' }
            Write-Host "[verify] FAILED ${patchName}: $($failure.reason -split "`n" | Select-Object -First 1)"
        }
    }
    Write-Host "[verify] result file: $result"
    if ($cliExitCode -ne 0) { Write-Warning "The desktop CLI exited with $cliExitCode." }
    if (-not $validation.Valid) { Write-Warning "[verify] $($validation.Reason)" }
    if ($cliExitCode -eq 0 -and $validation.Valid) {
        # The rebuilt resource table against the stock one. A resource patch has Morphe decode and
        # rebuild the app's whole table, and an id the rebuild loses only fails when the app
        # inflates it (Hushfeed upstream #84, a layout in one of TikTok's feature packages). A
        # split bundle's stock side is the merged APK the CLI wrote beside its output: that is the
        # table the patched APK was rebuilt from.
        $merged = @(Get-ChildItem -LiteralPath $runDir -Filter '*-merged.apk' -File -ErrorAction SilentlyContinue)
        $stockTable = if ($merged.Count -eq 1) { $merged[0].FullName } else { $stockApk }
        $resourceReport = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-resources-$runId.txt") -Root $workRoot
        $global:LASTEXITCODE = 0
        $resourceOutput = @(& $Java '-Xmx4g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'ResourceTableCheck.java') `
            $stockTable $out $resourceReport 2>&1)
        $resourceExitCode = $LASTEXITCODE
        $resourceOutput | ForEach-Object { Write-Host "[verify] $_" }
        Write-Host "[verify] resource report: $resourceReport"
        if ($resourceExitCode -eq 0) {
            # The injected code against Meta's: registers, branches, invokes, parameters and try
            # ranges, the shapes that pass the CLI and fail on a device. The one-feed-guard rule
            # rides along from injected-mutation-contracts.txt; it's a project contract, which
            # the device verifier doesn't check.
            $registerReport = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-registers-$runId.txt") -Root $workRoot
            $global:LASTEXITCODE = 0
            & (Join-Path $PSScriptRoot 'verify-injected-registers.ps1') -CleanApk $stockApk -PatchedApk $out `
                -ReportPath $registerReport -Java $Java -DesktopJar $DesktopJar -Aapt2 $Aapt2
            $registerExitCode = $LASTEXITCODE
            Write-Host "[verify] register report: $registerReport"
            if ($registerExitCode -eq 0) {
                Write-Host ('[verify] success: every requested patch applied to a valid APK whose resource table ' +
                    'holds every stock resource and whose injected code passes the structural checks.')
                $exitCode = 0
            } else {
                Write-Warning "[verify] the injected code failed its structural checks (exit $registerExitCode)."
            }
        } else {
            Write-Warning "[verify] the patched resource table failed its check against the stock one (exit $resourceExitCode)."
        }
    }
} finally {
    Remove-GeneratedPath -Path $runDir -Root $workRoot
}

exit $exitCode
