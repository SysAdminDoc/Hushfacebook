<#
.SYNOPSIS
    Rank where a method went when Facebook's next build renames it, from structural evidence.

.DESCRIPTION
    A patch finds its method by kept names, log literals and shapes. When a new Facebook build moves
    one of those anchors, the patch fails at patch time, and somebody has to find where the method
    went. This runs scripts/FingerprintCandidates.java to do the looking.

    It captures the method's signature from the build the patch works on: its strings, its literals
    with the version bytes Facebook changes masked, the references it makes with the obfuscated names
    taken out, an opcode sketch, its prototype, its class and its callers. Then it ranks every method
    of the new build against that and writes a report with the evidence for each candidate. The
    signature's format is scripts/fingerprint-signature.schema.json, so one captured today still
    ranks next month's build after the old APK is gone.

    It reports and nothing else. It never edits a patch and never accepts a candidate. When one
    candidate stands out it still says to check it by hand. When none does, the run fails closed
    (exit 1) and suggests none, and the report lists the closest for review.

    -Calibrate holds the ranking to scripts/fingerprint-calibration.txt: real transitions the patches
    resolve on both 577 and 580, each of which has to rank its known replacement in the top five.
    -CalibrationPath runs a list of your own the same way, and -OldApk and -NewApk name the two builds
    it describes. They default to 577 and 580, the builds the bundled list names methods of.

    An APK argument is a path to an .apk or .apkm, or a version that names exactly one fixture in the
    folder HUSHFACEBOOK_FIXTURE_DIR names, such as 577 or 580.0.0.51.74.

.EXAMPLE
    scripts/fingerprint-candidates.ps1 -OldApk 577 -Method 'LX/7f5;->A0g(LX/7ej;I)J' -NewApk 580

.EXAMPLE
    scripts/fingerprint-candidates.ps1 -OldApk 580 -Method '<descriptor>' -SignaturePath tick.json

    Captures the signature only, for a build that isn't out yet.

.EXAMPLE
    scripts/fingerprint-candidates.ps1 -Signature tick.json -NewApk C:\bundles\facebook-581.apkm

.EXAMPLE
    scripts/fingerprint-candidates.ps1 -Calibrate

.EXAMPLE
    scripts/fingerprint-candidates.ps1 -Calibrate -CalibrationPath 580-to-583.txt -OldApk 580 -NewApk 583

    Checks the ranking against transitions confirmed on a later pair of builds.
#>
[CmdletBinding()]
param(
    [string]$Method,
    [string]$OldApk,
    [string]$NewApk,
    [string]$Signature,
    [string]$SignaturePath,
    [switch]$Calibrate,
    [string]$CalibrationPath,
    [ValidateRange(1, 50)][int]$Top = 5,
    [string]$ReportPath,
    [string]$Java,
    [string]$DesktopJar,
    [string]$Root
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

function Resolve-FullPath {
    param([string]$Path)
    return $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
}

# A path the tool may write: never under the patch sources, which this only reads about.
function Assert-OutsidePatches {
    param([string]$Path, [string]$What)
    $full = [System.IO.Path]::GetFullPath((Resolve-FullPath $Path))
    $patches = [System.IO.Path]::GetFullPath((Join-Path $Root 'patches')).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if ($full.StartsWith($patches, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to write the $What to $full, inside patches/: this tool never edits a patch."
    }
    return $full
}

# An .apk or .apkm path, or a version naming exactly one fixture.
function Resolve-Build {
    param([string]$Value, [string]$What)
    if (Test-Path -LiteralPath $Value -PathType Leaf) { return (Resolve-FullPath $Value) }
    $fixtures = if ($env:HUSHFACEBOOK_FIXTURE_DIR) { $env:HUSHFACEBOOK_FIXTURE_DIR } else { Join-Path $Root 'fixtures' }
    $matching = @(Get-ChildItem -LiteralPath $fixtures -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.apk', '.apkm' -and $_.Name.Contains($Value) })
    if ($matching.Count -ne 1) {
        throw ("The $What '$Value' is not a file, and it names $($matching.Count) fixtures in $fixtures, not one. " +
            'Pass a path to an .apk or .apkm, or set HUSHFACEBOOK_FIXTURE_DIR.')
    }
    return $matching[0].FullName
}

# The tool, with Continue only in here: Windows PowerShell 5.1 turns a program's stderr into a
# terminating error under Stop. A java that can't start still throws out of here into the script's
# Stop, and the exit code starts at -1 so a run that never started can't read as a pass.
function Invoke-Candidates {
    param([string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = -1
    & $script:JavaPath '-Xmx8g' '-cp' $script:DesktopJarPath (Join-Path $PSScriptRoot 'FingerprintCandidates.java') @Arguments 2>&1 |
        ForEach-Object { Write-Host "$_" }
    return $LASTEXITCODE
}

# -Method picks the capture mode. -OldApk names the build of the method there, and with -Calibrate
# the old build the calibration list describes, so it counts toward neither.
if ($OldApk -and -not ($Method -or $Calibrate)) { throw '-OldApk goes with -Method, or with -Calibrate.' }
$modes = @($Calibrate.IsPresent, [bool]$Signature, [bool]$Method) | Where-Object { $_ }
if (@($modes).Count -ne 1) {
    throw 'Pass -Calibrate, or -Signature with -NewApk, or -OldApk and -Method (with -NewApk, -SignaturePath or both).'
}
if ($Method -and -not $OldApk) { throw '-OldApk and -Method go together.' }
if ($Signature -and -not $NewApk) { throw '-Signature needs -NewApk to rank against.' }
if ($Method -and -not $NewApk -and -not $SignaturePath) { throw 'Pass -NewApk to rank, -SignaturePath to keep the signature, or both.' }

$script:JavaPath = Resolve-Java -Explicit $Java
$script:DesktopJarPath = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
if (-not $ReportPath) {
    $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $ReportPath = Join-Path ([System.IO.Path]::GetTempPath()) "hushfacebook-fingerprint-$stamp.txt"
}
$ReportPath = Assert-OutsidePatches $ReportPath 'report'
if ($SignaturePath) { $SignaturePath = Assert-OutsidePatches $SignaturePath 'signature' }

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-fingerprint-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$exitCode = 2
try {
    if ($Calibrate) {
        if (-not $CalibrationPath) { $CalibrationPath = Join-Path $PSScriptRoot 'fingerprint-calibration.txt' }
        $old = Get-BaseApk -Apk (Resolve-Build $(if ($OldApk) { $OldApk } else { '577.0.0.50.72' }) 'old build') `
            -Destination (Join-Path $work 'old-base.apk')
        $new = Get-BaseApk -Apk (Resolve-Build $(if ($NewApk) { $NewApk } else { '580.0.0.51.74' }) 'new build') `
            -Destination (Join-Path $work 'new-base.apk')
        $exitCode = Invoke-Candidates @('calibrate', (Resolve-FullPath $CalibrationPath), $old, $new, $ReportPath, '--top', "$Top")
    } else {
        $captured = 0
        if (-not $Signature) {
            $old = Get-BaseApk -Apk (Resolve-Build $OldApk 'old build') -Destination (Join-Path $work 'old-base.apk')
            $Signature = if ($SignaturePath) { $SignaturePath } else { Join-Path $work 'signature.json' }
            $captured = Invoke-Candidates @('capture', $old, $Method, $Signature)
            $exitCode = $captured
        }
        # A signature that wasn't captured has nothing to rank, and the capture's failure stands.
        if ($captured -eq 0 -and $NewApk) {
            $new = Get-BaseApk -Apk (Resolve-Build $NewApk 'new build') -Destination (Join-Path $work 'new-base.apk')
            $exitCode = Invoke-Candidates @('rank', (Resolve-FullPath $Signature), $new, $ReportPath, '--top', "$Top")
        }
    }
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
exit $exitCode
