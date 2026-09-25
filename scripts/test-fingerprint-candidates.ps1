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

    A getter that only its caller tells apart hides among 250 getters just like it. Callers are only
    compared for a shortlist, so in "crowd" the real one, last of a tie at the shortlist's end, has
    to rank first, and a getter whose caller shares one of the two markers keeps it from standing
    out. In "crowd-behind" it scores just under the crowd until its callers count, and the shortlist
    has to widen until it stands out.

    Then fingerprint-candidates.ps1 -Calibrate runs over Facebook 577 and 580 from
    HUSHFACEBOOK_FIXTURE_DIR, and every case of fingerprint-calibration.txt has to rank its known 580
    method in the top five. The report has to show each candidate's prototype, strings, literals,
    opcode sketch, references and call neighbourhood. The AMOLED resolver that split in two has two
    real candidates, and that case has to fail closed.

    Through all of it, nothing under patches/ may change, and an output path there, or one ending in
    .kt or .java, has to be refused by both the tool and the wrapper. The tool has to refuse it
    however the path spells or reaches it: in another case, with a trailing dot, through a
    junction, or on a subst drive standing for patches/ or a folder inside it, directly or through
    another subst drive. An output name that is a hard link to patch source is replaced, and the
    source it shared stays as it was.
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
    param([string[]]$Arguments, [string[]]$JavaOptions = @())
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = -1
    $output = @(& $Java '-Xmx2g' @JavaOptions '-cp' $classPath 'FingerprintCandidates' @Arguments 2>&1 | ForEach-Object { "$_" })
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
# The files a guard that failed would leave in patches/: gone again after the run, pass or fail.
$patchProbes = @('fingerprint-probe.json', 'src\fingerprint-probe.json', 'fingerprint-report.txt' |
    ForEach-Object { Join-Path $patches $_ } | Where-Object { -not (Test-Path -LiteralPath $_) })

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("hushfacebook-fingerprint-test-" + [guid]::NewGuid().ToString('N'))))
$junction = Join-Path $caseRoot 'reports'
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

    # A getter only its caller tells apart, among 250 just like it. Callers are compared for a
    # shortlist only, so the shortlist mustn't cut a tie by dex order, and a method it leaves off
    # that its callers could still lift past the best has to widen it or fail the run closed.
    $getterApk = New-DexApk 'getter'
    $getterSignature = Join-Path $caseRoot 'getter.json'
    $getterCaptured = Invoke-Tool @('capture', $getterApk, 'LX/Ab1;->A00()I', $getterSignature)
    Assert-True ($getterCaptured.ExitCode -eq 0) "The getter capture failed.`n$($getterCaptured.Text)"
    $crowdApk = New-DexApk 'crowd'
    $crowdReport = Join-Path $caseRoot 'crowd.txt'
    $crowd = Invoke-Tool @('rank', $getterSignature, $crowdApk, $crowdReport)
    Assert-True ($crowd.ExitCode -eq 1 -and $crowd.Text -match 'no candidate stands out' -and $crowd.Text -match 'fails closed' -and
        $crowd.Text -notmatch 'LX/(Da\d+|Zz9);') `
        "The crowd's tie was cut by dex order, and a getter only one marker away stood out.`n$($crowd.Text)"
    $crowdText = [System.IO.File]::ReadAllText($crowdReport)
    Assert-True ($crowdText -match '(?m)^\s+#1\s+[0-9.]+\s+LX/Zz9;->A00\(\)I') `
        "The report of the crowd did not rank the real getter first.`n$crowdText"
    # All 251 that tie go on together, and nothing else needs to: the tie isn't cut, then widened.
    Assert-True ($crowdText -match '(?m)^   callers compared for 251 methods; no other could score over 0\.[0-3]') `
        "The shortlist of the crowd did not carry its whole tie, and only it.`n$crowdText"
    $behind = Invoke-Tool @('rank', $getterSignature, (New-DexApk 'crowd-behind'), (Join-Path $caseRoot 'crowd-behind.txt'))
    Assert-True ($behind.ExitCode -eq 0 -and
        ($behind.Output | Where-Object { $_ -like '*#1 *' } | Select-Object -First 1) -like '*LX/Zz9;->A00()I*') `
        "The real getter, just behind the shortlist until its callers count, was left off it.`n$($behind.Text)"
    # A copy of the old getter stands out among the shortlisted, but a method left off could come
    # within the margin of it, and does.
    $rivalReport = Join-Path $caseRoot 'crowd-rival.txt'
    $rival = Invoke-Tool @('rank', $getterSignature, (New-DexApk 'crowd-rival'), $rivalReport)
    $rivalText = [System.IO.File]::ReadAllText($rivalReport)
    Assert-True ($rival.ExitCode -eq 1 -and $rival.Text -match 'fails closed' -and $rival.Text -notmatch 'LX/(Da\d+|St1|Zz9);' -and
        $rivalText -match '(?m)^\s+#1\s+[0-9.]+\s+LX/St1;->A00\(\)I' -and $rivalText -match '(?m)^\s+#2\s+[0-9.]+\s+LX/Zz9;->A00\(\)I') `
        "A candidate stood out although a method left off the shortlist came within the margin of it.`n$($rival.Text)`n$rivalText"
    # A calibration holds the known method's rank to the same bound.
    $crowdCalibration = Join-Path $caseRoot 'crowd-calibration.txt'
    [System.IO.File]::WriteAllLines($crowdCalibration, [string[]]@('case crowd', '  patch Fixture', '  old LX/Ab1;->A00()I',
        '  new LX/Zz9;->A00()I', '  evidence Its caller is the only one holding both markers.'))
    $crowdCalibrated = Invoke-Tool @('calibrate', $crowdCalibration, $getterApk, $crowdApk, (Join-Path $caseRoot 'crowd-calibration-report.txt'))
    Assert-True ($crowdCalibrated.ExitCode -eq 0 -and
        @($crowdCalibrated.Output | Where-Object { $_ -match '^\[fingerprint\] case crowd rank 1 score [0-9.]+ ok fails-closed$' }).Count -eq 1) `
        "The calibration of the crowd did not rank the real getter first and fail closed.`n$($crowdCalibrated.Text)"
    # The star stands out whatever the shortlist leaves off, but the getter named as known ranks
    # second among the methods compared and seventh once five left off count their callers.
    $starCalibration = Join-Path $caseRoot 'star-calibration.txt'
    [System.IO.File]::WriteAllLines($starCalibration, [string[]]@('case star', '  patch Fixture', '  old LX/Ab1;->A00()I',
        '  new LX/Da000;->A00()I', '  evidence None: five methods the first shortlist leaves off outrank it.'))
    $starCalibrated = Invoke-Tool @('calibrate', $starCalibration, $getterApk, (New-DexApk 'crowd-star'),
        (Join-Path $caseRoot 'star-calibration-report.txt'))
    Assert-True ($starCalibrated.ExitCode -eq 1 -and
        @($starCalibrated.Output | Where-Object { $_ -match '^\[fingerprint\] case star rank 7 score [0-9.]+ FAIL stands-out$' }).Count -eq 1) `
        "A calibration ranked a known method second while methods left off the shortlist outranked it.`n$($starCalibrated.Text)"

    # The wrapper's -Calibrate takes -OldApk as it takes -NewApk, as the two builds a -CalibrationPath
    # list describes. A list that names no method of the old build given is refused as such, and
    # -OldApk still names nothing beside -Signature.
    $wrapperCalibrated = Invoke-Wrapper @('-Calibrate', '-OldApk', $getterApk, '-NewApk', $crowdApk,
        '-CalibrationPath', $crowdCalibration, '-ReportPath', (Join-Path $caseRoot 'wrapper-calibration.txt'),
        '-Java', $Java, '-DesktopJar', $DesktopJar, '-Root', $Root)
    Assert-True ($wrapperCalibrated.ExitCode -eq 0 -and
        @($wrapperCalibrated.Output | Where-Object { $_ -match '^\[fingerprint\] case crowd rank 1 score [0-9.]+ ok fails-closed$' }).Count -eq 1) `
        "The wrapper did not calibrate against the old build -OldApk named.`n$($wrapperCalibrated.Text)"
    $otherOld = Invoke-Wrapper @('-Calibrate', '-OldApk', $oldApk, '-NewApk', $crowdApk, '-CalibrationPath', $crowdCalibration,
        '-ReportPath', (Join-Path $caseRoot 'other-old.txt'), '-Java', $Java, '-DesktopJar', $DesktopJar, '-Root', $Root)
    Assert-True ($otherOld.ExitCode -eq 2 -and $otherOld.Text -match 'does not describe this old build') `
        "A calibration list ran against an old build it names no method of.`n$($otherOld.Text)"
    $strayOld = Invoke-Wrapper @('-OldApk', $getterApk, '-Signature', $getterSignature, '-NewApk', $crowdApk,
        '-Java', $Java, '-DesktopJar', $DesktopJar, '-Root', $Root)
    Assert-True ($strayOld.ExitCode -ne 0 -and $strayOld.Text -match '-OldApk goes with -Method, or with -Calibrate') `
        "The wrapper took -OldApk beside -Signature, where it names nothing.`n$($strayOld.Text)"

    # A signature of another version is refused rather than read the new way.
    $future = Join-Path $caseRoot 'future.json'
    [System.IO.File]::WriteAllText($future, $signatureText.Replace('"version": 1', '"version": 2'))
    $refused = Invoke-Tool @('rank', $future, (Join-Path $caseRoot 'moved.apk'), (Join-Path $caseRoot 'future.txt'))
    Assert-True ($refused.ExitCode -eq 2 -and $refused.Text -match 'version 2') "A version 2 signature was read.`n$($refused.Text)"

    # Nothing is ever written as patch source: not under patches/ however the path spells it, and not
    # as a .kt or .java file. NTFS reads PATCHES and patches. as patches, and a junction reaches a
    # patches/ under any name; this one leads into a copy of the layout in the temp folder.
    $fakePatches = Join-Path $caseRoot 'checkout\patches'
    New-Item -ItemType Directory -Path (Join-Path $fakePatches 'src') | Out-Null
    New-Item -ItemType Junction -Path $junction -Target $fakePatches | Out-Null
    $spellings = [ordered]@{
        (Join-Path $patches 'fingerprint-probe.json') = Join-Path $patches 'fingerprint-probe.json'
        (Join-Path $Root 'PATCHES\src\fingerprint-probe.json') = Join-Path $patches 'src\fingerprint-probe.json'
        (Join-Path $Root 'Patches.\fingerprint-probe.json') = Join-Path $patches 'fingerprint-probe.json'
        (Join-Path $junction 'fingerprint-probe.json') = Join-Path $fakePatches 'fingerprint-probe.json'
    }
    foreach ($spelling in $spellings.Keys) {
        $blocked = Invoke-Tool @('capture', $oldApk, $target, $spelling)
        Assert-True ($blocked.ExitCode -eq 2 -and $blocked.Text -match 'never edits a patch' -and
            -not (Test-Path -LiteralPath $spellings[$spelling])) "The tool wrote under patches/ as $spelling.`n$($blocked.Text)"
    }
    # A subst drive is a root of its own to toRealPath, so the tool swaps it for its folder. The
    # listing stands in for subst's own, on a letter no drive uses, so no case makes a real drive.
    $free = @([char[]]'QRSTUVWXYZ' | Where-Object { -not (Test-Path -LiteralPath "$($_):\") })
    $letter = $free[-1]
    $outer = $free[-2]
    $landing = Join-Path $fakePatches 'src\fingerprint-probe.json'
    $substCases = [ordered]@{
        "$($letter):\src\fingerprint-probe.json" = "$($letter):\: => $fakePatches"
        "$($letter):\fingerprint-probe.json" = "$($letter):\: => $(Join-Path $fakePatches 'src')"
        # A subst drive for a folder on another subst drive.
        "$($outer):\fingerprint-probe.json" = "$($outer):\: => $($letter):\src`n$($letter):\: => $fakePatches"
        # Windows drops a '..' at a drive's root, so these land where the drive stands for too.
        "$($letter):\..\src\fingerprint-probe.json" = "$($letter):\: => $fakePatches"
        "$($outer):\..\..\fingerprint-probe.json" = "$($outer):\: => $($letter):\src`n$($letter):\: => $fakePatches"
    }
    foreach ($through in $substCases.Keys) {
        $blocked = Invoke-Tool @('capture', $oldApk, $target, $through) -JavaOptions @("-Dhushfacebook.subst=$($substCases[$through])")
        Assert-True ($blocked.ExitCode -eq 2 -and $blocked.Text -match 'never edits a patch' -and -not (Test-Path -LiteralPath $landing)) `
            "The tool wrote through the subst drive in $through ($($substCases[$through])).`n$($blocked.Text)"
    }
    $lost = Invoke-Tool @('capture', $oldApk, $target, "$($letter):\fingerprint-probe.json") `
        -JavaOptions @("-Dhushfacebook.subst=$($letter):\: => $(Join-Path $caseRoot 'no-such-folder')")
    Assert-True ($lost.ExitCode -eq 2 -and $lost.Text -match 'is a subst drive for') "A subst drive whose folder can't be found was passed.`n$($lost.Text)"
    # An output name that is a hard link to patch source is replaced, never written through.
    $sharedSource = Join-Path $fakePatches 'src\Shared.kt'
    [System.IO.File]::WriteAllText($sharedSource, 'ORIGINAL PATCH SOURCE')
    $linkedReport = Join-Path $caseRoot 'linked-signature.json'
    New-Item -ItemType HardLink -Path $linkedReport -Target $sharedSource | Out-Null
    $linked = Invoke-Tool @('capture', $oldApk, $target, $linkedReport)
    Assert-True ($linked.ExitCode -eq 0 -and [System.IO.File]::ReadAllText($sharedSource) -ceq 'ORIGINAL PATCH SOURCE' -and
        [System.IO.File]::ReadAllText($linkedReport) -match 'hushfacebook-fingerprint-signature') `
        "A signature written to a hard link changed the patch source it shared.`n$($linked.Text)"
    foreach ($name in 'Candidate.kt', 'Candidate.JAVA', 'Candidate.kt.') {
        $blockedSource = Invoke-Tool @('rank', $signature, (Join-Path $caseRoot 'moved.apk'), (Join-Path $caseRoot $name))
        Assert-True ($blockedSource.ExitCode -eq 2 -and $blockedSource.Text -match 'only reports' -and
            @(Get-ChildItem -LiteralPath $caseRoot -File -Filter 'Candidate.*').Count -eq 0) `
            "The tool wrote a report as source, named '$name'.`n$($blockedSource.Text)"
    }
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
    # Each line is matched whole from its indent, because a bare 'references:' also sits inside the
    # 'obfuscated references:' line every candidate has, so the check passed with the kept
    # references gone from the report.
    $reportLines = [ordered]@{
        'prototype' = '(?m)^ {7}prototype: \S'
        'strings' = '(?m)^ {7}strings: \d+ shared '
        'literals' = '(?m)^ {7}literals: \d+ shared '
        'opcode sketch' = '(?m)^ {7}opcodes: old \d+ .*\r?\n {16}new \d+ '
        'references' = '(?m)^ {7}references: \d+ shared '
        'call neighbourhood' = '(?m)^ {7}call neighbourhood: \d+ shared '
        'known replacement' = '(?m)^ {3}#\d+ {2}[0-9.]+ {2}\S+ {3}<- the known replacement\r?$'
    }
    foreach ($id in $caseIds) {
        $section = [regex]::Match($reportText, "(?s)== case $([regex]::Escape($id)) .*?(?=\r?\n== case |\r?\nCalibration: )").Value
        foreach ($line in $reportLines.Keys) {
            Assert-True ($section -match $reportLines[$line]) "The report of case $id does not show the $line line."
        }
    }

    $after = Get-TreeSnapshot $patches
    Assert-True (@(Compare-Object $before $after).Count -eq 0) `
        ("Something under patches/ changed while the tool ran:`n" + (@(Compare-Object $before $after) | Out-String))
} finally {
    foreach ($probe in $patchProbes) {
        if (Test-Path -LiteralPath $probe) { Remove-Item -LiteralPath $probe -Force }
    }
    # The junction goes first, on its own: a recursive delete can follow it into its target.
    if (Test-Path -LiteralPath $junction) { [System.IO.Directory]::Delete($junction) }
    if ($caseRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host "[scripts] fingerprint candidates passed ($($caseIds.Count) calibrated cases in the top five; ties and misses fail closed)"
