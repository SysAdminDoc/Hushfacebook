<#
.SYNOPSIS
    Check what the bundle injected into Facebook against the shapes that fail on a device.

.DESCRIPTION
    A patch that writes into a register beyond the method's register count, branches into the
    middle of an instruction, passes an invoke the wrong registers or reads a parameter as the
    wrong kind assembles without complaint and applies without complaint. The desktop CLI reports
    it as applied. It fails only when a device verifies the class, and then it fails as a
    VerifyError on whatever screen happens to reach it first.

    Two checks, either of which catches that:

    Static. scripts/DexDiff.java lists every method the patched APK does not share with the
    clean build it came from, and holds each line that was not in the clean body, and every
    line of the bundle's own added methods, to that method's register count. Every changed and
    added method is also checked for branch and switch targets, invoke registers, the static and
    wide parameter layout, move-result placement and try ranges, and the whole APK is held to
    scripts/injected-mutation-contracts.txt: one feed guard, in addNewEdgeToCollection.

    On a device. The Android runtime's own verifier is the authority, so with -Serial the
    clean APK and the patched APK are both put through dex2oat with the verify filter and the
    verifier's messages are compared. The patched build has to raise exactly the same message
    multiset as the clean build, which for Meta's builds so far is none. The emulator works: its
    x86_64 dex2oat verifies for arm64.

    Removed host methods and DEX entries fail the static check. The allowlist beside this script
    accepts only exact reviewed removals, and a stale entry fails so an old exception cannot mask
    a later regression.

    Both halves refuse to pass on absent evidence. A dex comparison that found no difference at
    all compared the wrong things, and a device run only counts once dex2oat has read a file of
    the size that was pushed (it exits 0 on a missing one). The clean side has to be Meta's own
    build of the same version as the patched one: a patched APK on the clean side would compare
    two bundles instead of the bundle against Facebook.

.EXAMPLE
    scripts/verify-injected-registers.ps1 -PatchedApk C:\work\patched.apk

    The clean side is the fixture of the same version in the folder HUSHFACEBOOK_FIXTURE_DIR
    names, an .apk or the .apkm bundle Facebook ships as.

.EXAMPLE
    scripts/verify-injected-registers.ps1 -FromDevice -Serial $env:HUSHFACEBOOK_DEVICE_SERIAL
#>
[CmdletBinding()]
param(
    [string]$CleanApk,
    [string]$PatchedApk,
    [switch]$FromDevice,
    [string]$Serial,
    [string]$Adb,
    [string]$ReportPath,
    [string]$Java,
    [string]$DesktopJar,
    [string]$Aapt2
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
$Java = Resolve-Java -Explicit $Java
# dexlib2 comes from the desktop CLI. Without it the comparison cannot run at all, and saying so
# beats a stack trace about a missing class.
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root

function Resolve-Adb {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:HUSHFACEBOOK_ADB) { return $env:HUSHFACEBOOK_ADB }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $sdkAdb = Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $Aapt2))) 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) { return $sdkAdb }
    throw 'No adb found. Pass -Adb or set HUSHFACEBOOK_ADB.'
}

# The certificate digests of every signer apksigner reports for an APK.
function Get-SignerDigests {
    param([string]$Apk)
    $apksigner = Join-Path (Split-Path -Parent $Aapt2) 'apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "No apksigner beside aapt2: $apksigner" }
    # A newer JDK prints warnings on stderr, which Windows PowerShell 5.1 turns into a terminating
    # error under Stop. The exit code is what gets judged.
    $ErrorActionPreference = 'Continue'
    $lines = @(& $apksigner verify --print-certs $Apk 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) { throw "apksigner could not verify ${Apk}: $($lines -join ' ')" }
    return @($lines | Where-Object { $_ -match 'certificate SHA-256 digest: ([0-9a-f]{64})' } |
        ForEach-Object { ($_ -replace '^.*certificate SHA-256 digest: ', '').Trim() } | Sort-Object -Unique)
}

# DexDiff over the pair, with Continue only in here, as in Get-SignerDigests. Called inside the try
# below, a java that can't start throws out of here into the script's Stop. Called outside one, it
# wouldn't, and this would read the exit code apksigner left, so the code starts at -1.
function Invoke-DexDiff {
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = -1
    $output = @(& $Java '-Xmx8g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'DexDiff.java') `
        $cleanBase $PatchedApk $ReportPath `
        (Join-Path $PSScriptRoot 'injected-register-removal-allowlist.txt') `
        (Join-Path $PSScriptRoot 'injected-mutation-contracts.txt') 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-regs-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
# The report stays where the caller can read it after the run: at -ReportPath when one is given,
# and otherwise in the working directory, which is kept whenever the run failed or stopped short,
# because that is when the FAIL line points at the report. The pulled and extracted APKs never
# outlive the run, because a full Facebook is hundreds of megabytes. Worked out before the default
# path is filled in: reading [bool]$ReportPath after that was always true, and read before it, it
# was false exactly when the report lived in the directory the cleanup removed.
$reportInWork = -not $ReportPath
if ($reportInWork) { $ReportPath = Join-Path $work 'injected-registers.txt' }
$failed = $false
$completed = $false
try {

$adbPath = $null
if ($FromDevice -or $Serial) { $adbPath = Resolve-Adb -Explicit $Adb }

if ($FromDevice) {
    if (-not $Serial) { throw '-FromDevice needs -Serial so it cannot pull from somebody else''s phone.' }
    $paths = @(& $adbPath -s $Serial shell pm path com.facebook.katana 2>&1 |
        ForEach-Object { "$_" } | Where-Object { $_ -match '^package:' })
    if ($paths.Count -eq 0) { throw "Facebook is not installed on $Serial." }
    if ($paths.Count -ne 1) {
        # A split install has no single APK to compare, and quietly taking the first would
        # compare the base against a whole clean build and call the rest missing.
        throw "The install on $Serial is split across $($paths.Count) APKs; pass -PatchedApk instead."
    }
    $onDevice = ($paths[0] -replace '^package:', '').Trim()
    $PatchedApk = Join-Path $work 'patched-installed.apk'
    Write-Host "[registers] pulling $onDevice"
    & $adbPath -s $Serial pull $onDevice $PatchedApk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not pull the installed APK from $Serial." }
}

if (-not $PatchedApk -or -not (Test-Path -LiteralPath $PatchedApk -PathType Leaf)) {
    throw 'No patched APK. Pass -PatchedApk, or -FromDevice -Serial <serial>.'
}
$patched = Get-ApkManifestFacts -Apk $PatchedApk -Aapt2 $Aapt2
if ($patched.package -ne 'com.facebook.katana' -or [string]::IsNullOrWhiteSpace($patched.versionName)) {
    throw "$PatchedApk is $($patched.package) $($patched.versionName), not a Facebook build."
}

if (-not $CleanApk) {
    $fixtures = if ($env:HUSHFACEBOOK_FIXTURE_DIR) { $env:HUSHFACEBOOK_FIXTURE_DIR } else { Join-Path $root 'fixtures' }
    $matching = @(Get-ChildItem -LiteralPath $fixtures -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.apk', '.apkm' -and $_.Name -like "*$($patched.versionName)*" })
    if ($matching.Count -ne 1) {
        throw ("No single fixture for Facebook $($patched.versionName) in $fixtures (found $($matching.Count)). " +
            'Pass -CleanApk with Meta''s build of that version.')
    }
    $CleanApk = $matching[0].FullName
}
if (-not (Test-Path -LiteralPath $CleanApk -PathType Leaf)) { throw "No clean APK at $CleanApk." }
# Facebook ships as a split bundle; every dex lives in its base APK.
$cleanBase = Get-BaseApk -Apk $CleanApk -Destination (Join-Path $work 'clean-base.apk')

# The clean side has to be Meta's own build of the version that was patched, not a patched build
# or another version: two patched files differ from each other, both halves report differences,
# and the run would pass while comparing nothing against a clean baseline.
$clean = Get-ApkManifestFacts -Apk $cleanBase -Aapt2 $Aapt2
if ($clean.package -ne $patched.package -or $clean.versionName -ne $patched.versionName -or
        $clean.versionCode -ne $patched.versionCode) {
    throw ("The clean APK is $($clean.package) $($clean.versionName) ($($clean.versionCode)) and the " +
        "patched one $($patched.package) $($patched.versionName) ($($patched.versionCode)); they have to be the same build.")
}
$catalog = Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json
$metaSigners = @($catalog.patches | ForEach-Object { $_.compatibility } |
    Where-Object { $_.packageName -eq 'com.facebook.katana' } |
    ForEach-Object { $_.signatures } | Sort-Object -Unique)
if ($metaSigners.Count -eq 0) { throw 'patches-list.json declares no Facebook signer to hold the clean APK to.' }
$cleanSigners = @(Get-SignerDigests -Apk $cleanBase)
if (@($cleanSigners | Where-Object { $_ -in $metaSigners }).Count -eq 0) {
    throw "The clean APK at $CleanApk is signed by $($cleanSigners -join ', '), not by Meta; it is not a vendor build."
}

Write-Host "[registers] clean   $CleanApk (Facebook $($clean.versionName), signed by Meta)"
Write-Host "[registers] patched $PatchedApk"

$diff = Invoke-DexDiff
$diff.Output | ForEach-Object { Write-Host "[registers] $_" }

if ($diff.ExitCode -ne 0) {
    Write-Host "[registers] FAIL: the dex comparison exited $($diff.ExitCode); see $ReportPath"
    $failed = $true
} else {
    Write-Host '[registers] static: every injected instruction stays inside its method, and the structure and contracts hold.'
}

if ($Serial) {
    Write-Host "[registers] running the device verifier on $Serial"
    $cleanTally = Invoke-AndroidVerifierTally -Adb $adbPath -Serial $Serial `
        -Local $cleanBase -Label 'clean'
    $patchedTally = Invoke-AndroidVerifierTally -Adb $adbPath -Serial $Serial `
        -Local $PatchedApk -Label 'patched'
    $comparison = Compare-VerifierTallies -Clean $cleanTally -Patched $patchedTally
    Write-Host "[registers] verifier messages: clean $($comparison.CleanTotal), patched $($comparison.PatchedTotal)"

    # Each tally is only returned once dex2oat has read a file of the pushed size, so two empty
    # tallies are an answer: Meta's build raises nothing, and the patched one must raise nothing more.
    if (-not $comparison.Valid) {
        $missing = @($comparison.Deltas | Where-Object Kind -eq 'missing')
        $extra = @($comparison.Deltas | Where-Object Kind -eq 'extra')
        if ($missing.Count -ne 0) {
            Write-Host "[registers] FAIL: the patched build is missing $($missing.Count) verifier message tally entry or count:"
            $missing | Select-Object -First 20 | ForEach-Object {
                Write-Host ("[registers]   {0}  (clean {1}, patched {2})" -f $_.Message, $_.Clean, $_.Patched)
            }
        }
        if ($extra.Count -ne 0) {
            Write-Host "[registers] FAIL: the patched build has $($extra.Count) extra verifier message tally entry or count:"
            $extra | Select-Object -First 20 | ForEach-Object {
                Write-Host ("[registers]   {0}  (clean {1}, patched {2})" -f $_.Message, $_.Clean, $_.Patched)
            }
        }
        $failed = $true
    } else {
        Write-Host '[registers] device: the clean and patched verifier message tallies are identical.'
    }
}

$completed = $true
} finally {
    $keepReport = $reportInWork -and ($failed -or -not $completed) -and
        (Test-Path -LiteralPath $ReportPath -PathType Leaf)
    if ($keepReport) {
        Get-ChildItem -LiteralPath $work -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host "[registers] the report is kept at $ReportPath"
    } else {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Success needs the try above to have run to its end. A launch error once left it early under a
# script-wide Continue, and the run fell through to the success line with nothing checked.
if ($failed -or -not $completed) { exit 1 }
Write-Host '[registers] success.'
exit 0
