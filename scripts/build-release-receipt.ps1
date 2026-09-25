<#
.SYNOPSIS
    Build the machine-readable release provenance receipt.

.DESCRIPTION
    Local patching puts three things together: the user's APK, this bundle, and a toolchain. A
    checksum can only say that one of them arrived unaltered. This writes down all of it as one
    JSON document: which commit and tag the bundle was built from, what the bundle weighs and
    hashes to, which extension payloads it carries, which APK each proof was run against, that
    every patch in the catalog applied to it, and what patching did to the Android manifest.

    Nothing here is asserted. Each fixture is patched with the real desktop CLI, the verdicts
    come out of the CLI's own result report, and both manifests are read back with aapt2. A
    patch that fails on any fixture stops the run with its name and no receipt is written: the
    receipt describes a bundle that fully applies, which is why the validator refuses any
    verdict of applied = false rather than reading it as a recorded failure. Every build the
    catalog declares needs a fixture of its own, patched without -f: the README says the
    patches were checked on each of them, and the receipt is refused without a run of every
    one. A fixture of any other build is patched under -f and recorded as forced. The fixtures'
    base manifests are read before anything is patched, so a declared build with no fixture
    stops the run at once instead of after the others, which for Facebook unpacks gigabytes.

    The patched APKs are working files and are deleted on the way out, including after a failure.

.EXAMPLE
    One -Fixture taking a comma separated list, not the switch repeated: PowerShell binds a
    parameter once and refuses the second.

    scripts/build-release-receipt.ps1 -WorkDir C:\scratch `
        -Fixture C:\fixtures\facebook-a.apk,C:\fixtures\facebook-b.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$Fixture,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Root,
    [string]$Bundle,
    [string]$PatchList,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

$Java = Resolve-Java -Explicit $Java
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root

if (-not $DesktopJar) { $DesktopJar = $env:HUSHFACEBOOK_DESKTOP_JAR }
if (-not $DesktopJar -or -not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) {
    throw ('No Morphe desktop CLI. Pass -DesktopJar or set HUSHFACEBOOK_DESKTOP_JAR: the receipt ' +
        'records real patch verdicts, so it has to actually patch each fixture.')
}

$releaseVersion = Get-BundleVersion -Root $Root
if (-not $Bundle) { $Bundle = Get-ReleaseBundlePath -Root $Root -Version $releaseVersion }
if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
    throw "No bundle for version ${releaseVersion}: $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
}
# Found in PowerShell's location; the ZipFile reads below would look in the process directory.
$Bundle = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Bundle)
if (-not $PatchList) { $PatchList = Join-Path $Root 'patches-list.json' }
if (-not $OutputPath) { $OutputPath = Join-Path $Root "release-receipt-$releaseVersion.json" }

$catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
$patchNames = @($catalog.patches | ForEach-Object { $_.name })
if ($patchNames.Count -eq 0) { throw "No patches listed in $PatchList." }
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $patchNames)
$expectedTarget = Get-PatchTarget -PatchList $catalog

$catalogText = Get-Content -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') -Raw
$patcherMatch = [regex]::Match($catalogText, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
$floorMatch = [regex]::Match($catalogText, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
if (-not $patcherMatch.Success -or -not $floorMatch.Success) {
    throw 'gradle/libs.versions.toml does not pin both morphe-patcher and manager-floor.'
}

$commit = (& git -C $Root rev-parse HEAD).Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') { throw "git did not answer with a commit: $commit" }
$commitTimestamp = [long](& git -C $Root log -1 --format=%ct).Trim()

# The bundle is read once, here, before any fixture is patched. Measuring it at the end instead
# would describe whatever the release path holds when the run finishes, which is not necessarily
# what the patch runs used: a run takes long enough for another buildAndroid to replace it.
$bundleManifest = Get-BundleManifestFacts -BundlePath $Bundle
$bundleSize = (Get-Item -LiteralPath $Bundle).Length
$bundleHash = Get-Sha256Hex -Path $Bundle

# Refused here rather than reported, because a receipt that records the mismatch would be a
# document saying its own subject cannot be rebuilt from the source it names.
$dirty = @(& git -C $Root status --porcelain)
if ($dirty.Count -gt 0) {
    # -join, not Join-String: the pre-push hook prefers pwsh and falls back to Windows
    # PowerShell 5.1, which has no Join-String, so a dirty tree would have stopped the run with
    # a command-not-found instead of the reason it stopped.
    $shown = @($dirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
    throw ("The working tree has uncommitted changes, so the commit this receipt would name is " +
        "not what was built: $shown")
}

function Get-ExtensionPayloads {
    <#
    .SYNOPSIS
        The extension DEX each bundle carries, by name, size and hash.
    .DESCRIPTION
        An .mpe is a bare Android DEX, checked here by its magic rather than by its extension,
        so a bundle that shipped something else in that slot cannot pass as an extension.
    #>
    param([string]$BundlePath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $payloads = New-Object System.Collections.Generic.List[object]
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } |
                Sort-Object FullName)) {
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $bytes = $memory.ToArray()
                $memory.Dispose()
            } finally { $stream.Dispose() }

            $magic = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(4, $bytes.Length))
            if ($magic -ne "dex`n") {
                throw "$($entry.FullName) in $BundlePath is not an Android DEX payload."
            }
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $hash = ($sha.ComputeHash($bytes) | ForEach-Object { '{0:X2}' -f $_ }) -join '' }
            finally { $sha.Dispose() }

            $payloads.Add([ordered]@{
                name      = $entry.FullName
                sizeBytes = [long]$bytes.Length
                sha256    = $hash
            })
        }
    } finally { $archive.Dispose() }

    if ($payloads.Count -eq 0) { throw "The bundle carries no extension payload: $BundlePath" }
    return ,$payloads.ToArray()
}

function Get-PatchVerdicts {
    <#
    .SYNOPSIS
        One entry per catalog patch, in catalog order, from the CLI's own report.
    .DESCRIPTION
        Every requested patch has to appear in exactly one of the report's two lists. A patch in
        neither was not decided, and recording it as applied would be the receipt inventing a
        verdict, which is the one thing it exists not to do.
    #>
    param([object]$Report, [string[]]$Names)

    $applied = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@(Get-ReportPatchNames -Entries $Report.appliedPatches),
        [System.StringComparer]::Ordinal)
    $failures = @{}
    foreach ($failure in @($Report.failedPatches)) {
        $name = if ($null -ne $failure.patch) { [string]$failure.patch.name } else { $null }
        if (-not $name) { continue }
        $reason = ([string]$failure.reason -split "`n" | Select-Object -First 1).Trim()
        $failures[$name] = $reason
    }

    $verdicts = New-Object System.Collections.Generic.List[object]
    foreach ($name in $Names) {
        if ($applied.Contains($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $true; reason = $null })
        } elseif ($failures.ContainsKey($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $false; reason = $failures[$name] })
        } else {
            throw "The result report decided nothing about patch $name."
        }
    }
    return ,$verdicts.ToArray()
}

# Read with the hash and size above, and for the same reason.
$extensionPayloads = Get-ExtensionPayloads -BundlePath $Bundle

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$targets = New-Object System.Collections.Generic.List[object]

# Every declared build needs its own run without -f, and the receipt check refuses a receipt
# without one. That check comes after the patch runs, and a Facebook patch run unpacks the whole
# bundle, while a base manifest is cheap to read. So the fixtures are held to the catalog here,
# before anything is patched: a run given only the newest build patched it and then refused the
# receipt it had produced.
$stockFacts = @{}
foreach ($apk in $Fixture) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Fixture not found: $apk" }
    $label = Split-Path -Leaf $apk
    $readDir = Resolve-WithinRoot -Path (Join-Path $workRoot ("manifest-" + [guid]::NewGuid().ToString('N'))) -Root $workRoot
    try {
        # Facebook ships as a split bundle, which aapt2 can't read; its manifest is the base APK's.
        $stockApk = Get-BaseApk -Apk $apk -Destination (Join-Path $readDir 'stock-base.apk')
        $stock = Get-ApkManifestFacts -Apk $stockApk -Aapt2 $Aapt2
    } finally {
        Remove-GeneratedPath -Path $readDir -Root $workRoot
    }
    if ($stock.package -ne $expectedTarget.PackageName) {
        throw "$label is $($stock.package), not the catalog's target $($expectedTarget.PackageName)."
    }
    $stockFacts[$apk] = $stock
}
$fixtureVersions = @($Fixture | ForEach-Object { [string]$stockFacts[$_].versionName })
$unfixed = @($expectedTarget.PackageVersions | Where-Object { $fixtureVersions -notcontains $_ })
if ($unfixed.Count -gt 0) {
    throw ("No fixture is the declared $($expectedTarget.PackageName) $($unfixed -join ', '), and the " +
        "receipt needs a run of every declared build without -f. The fixtures given are " +
        "$($fixtureVersions -join ', '). Nothing was patched.")
}

foreach ($apk in $Fixture) {
    $label = Split-Path -Leaf $apk
    Write-Host "[receipt] patching $label with $($patchNames.Count) patches"

    $runId = [guid]::NewGuid().ToString('N')
    $runDir = Resolve-WithinRoot -Path (Join-Path $workRoot "receipt-$runId") -Root $workRoot
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    try {
        $stock = $stockFacts[$apk]

        $out = Resolve-WithinRoot -Path (Join-Path $runDir 'patched.apk') -Root $workRoot
        $temp = Resolve-WithinRoot -Path (Join-Path $runDir 'tmp') -Root $workRoot
        $resultPath = Resolve-WithinRoot -Path (Join-Path $runDir 'result.json') -Root $workRoot

        # A fixture at a version the bundle does not declare is patched under -f, and the
        # receipt says so rather than letting a forced run read like a declared-compatible one.
        $forced = -not ([System.Collections.Generic.HashSet[string]]::new(
            [string[]]$expectedTarget.PackageVersions, [System.StringComparer]::Ordinal)).Contains([string]$stock.versionName)

        $enable = @()
        foreach ($name in $patchNames) { $enable += '-e'; $enable += $name }
        $arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
            '-o', $out, '-t', $temp, '-r', $resultPath)
        if ($forced) { $arguments += '-f' }
        $arguments = $arguments + $enable + @($apk)
        & $Java '-jar' $DesktopJar @arguments 2>&1 | Out-Null
        $cliExitCode = $LASTEXITCODE

        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
            throw "The desktop CLI wrote no result report for $label (exit $cliExitCode)."
        }
        $report = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        $validation = Test-PatchingReport -Report $report -ExpectedNames $patchNames `
            -AllowedDependencyNames $dependencyNames -OutputPath $out `
            -ExpectedPackageName $expectedTarget.PackageName `
            -ExpectedPackageVersion $stock.versionName
        if (-not $validation.Valid) { throw "$label did not patch cleanly: $($validation.Reason)" }
        if ($cliExitCode -ne 0) { throw "The desktop CLI exited with $cliExitCode on $label." }

        $patched = Get-ApkManifestFacts -Apk $out -Aapt2 $Aapt2
        # The CLI merges a bundle's splits into one APK before it patches, and leaves that merge
        # beside its output. Its manifest, not the base APK's, is what the patches started from,
        # so the delta against it is the patches' own and not the merge's.
        $merged = Get-ChildItem -LiteralPath $runDir -Filter '*-merged.apk' -File | Select-Object -First 1
        $baseline = if ($merged) { Get-ApkManifestFacts -Apk $merged.FullName -Aapt2 $Aapt2 } else { $stock }
        $delta = Get-ManifestDelta -Stock $baseline -Patched $patched
        $verdicts = Get-PatchVerdicts -Report $report -Names $patchNames
        $changes = @(ConvertTo-ManifestDeltaEntries -Delta $delta)
        Write-Host ("[receipt] $label" + ": $(@($verdicts | Where-Object { $_.applied }).Count)/" +
            "$($patchNames.Count) applied, $($changes.Count) manifest changes")
        foreach ($change in $changes) { Write-Host "[receipt]   $change" }

        $targets.Add([ordered]@{
            source        = [ordered]@{
                file        = $label
                package     = $stock.package
                versionName = $stock.versionName
                versionCode = $stock.versionCode
                sha256      = Get-Sha256Hex -Path $apk
                forced      = $forced
            }
            patches       = $verdicts
            manifestDelta = [ordered]@{
                permissionsAdded          = @($delta.permissionsAdded)
                permissionsRemoved        = @($delta.permissionsRemoved)
                exportedComponentsAdded   = @($delta.exportedComponentsAdded)
                exportedComponentsRemoved = @($delta.exportedComponentsRemoved)
            }
        })
    } finally {
        if ($runDir.StartsWith($workRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $runDir)) {
            Remove-Item -LiteralPath $runDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$receipt = [ordered]@{
    schemaVersion = Get-ReleaseReceiptSchemaVersion
    generatedAt   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    release       = [ordered]@{
        version         = $releaseVersion
        tag             = "v$releaseVersion"
        commit          = $commit
        commitTimestamp = $commitTimestamp
        patchCount      = $patchNames.Count
    }
    bundle        = [ordered]@{
        file      = Split-Path -Leaf $Bundle
        sizeBytes = $bundleSize
        sha256    = $bundleHash
        timestamp = $bundleManifest.timestamp
    }
    toolchain     = [ordered]@{
        patcherVersion = $patcherMatch.Groups[1].Value
        managerFloor   = $floorMatch.Groups[1].Value
    }
    extension     = [ordered]@{ dexPayloads = $extensionPayloads }
    targets       = $targets.ToArray()
}

# The receipt is checked before it is written. A file that fails the gate it exists to pass is
# worse than no file, because the next reader has to work out which half to believe.
$approved = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
$check = Test-ReleaseReceipt -Receipt ($receipt | ConvertTo-Json -Depth 12 | ConvertFrom-Json) `
    -ExpectedVersion $releaseVersion -ExpectedPatchNames $patchNames `
    -ExpectedPatcherVersion $patcherMatch.Groups[1].Value `
    -ExpectedManagerFloor $floorMatch.Groups[1].Value `
    -ExpectedPackageName $expectedTarget.PackageName `
    -ExpectedPackageVersions $expectedTarget.PackageVersions -BundlePath $Bundle `
    -ApprovedManifestDelta $approved
if (-not $check.Valid) { throw "The receipt this run produced does not pass validation: $($check.Reason)" }

$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "[receipt] wrote $OutputPath"
exit 0
