<#
.SYNOPSIS
    Exercise DexDiff.java against dex files that break one check each.

.DESCRIPTION
    BadDexFixture.java writes a clean host, a patched build of it that passes every check, and one
    patched build per check that breaks that check and nothing else: a branch into an instruction,
    a branch to itself, a switch case into an instruction, an invoke with too few registers, a
    wide argument split across two registers, the static off-by-one, the upper half of a wide
    parameter read as an object, a narrow constant read as a long and the reverse (the AMOLED
    sweep's bug on 580), a move-result the patch separated from its invoke, three bad try ranges
    or handlers, and the one feed guard doubled, moved or missing. Each bad build has to
    fail with findings of its own category only, so a check that fires for the wrong reason fails
    here too. Removed methods and DEX entries, the removal allowlist, and the device tally
    comparison are held to what they did before.
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
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $output = @(& $Program @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        throw "$Description exited $LASTEXITCODE.`n$($output -join "`n")"
    }
}

function New-DexApk {
    param([string]$Name, [System.Collections.IDictionary]$Entries)
    $path = Join-Path $caseRoot "$Name.apk"
    $archive = [System.IO.Compression.ZipFile]::Open(
        $path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $Entries.GetEnumerator()) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $entry.Value, $entry.Key,
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }
    return $path
}

function Invoke-DexDiff {
    param([string]$Clean, [string]$Patched, [string]$Allowlist, [string]$Name, [string]$Contracts)
    $report = Join-Path $caseRoot "$Name-report.txt"
    $arguments = @('-Xmx1g', '-cp', $classPath, 'DexDiff', $Clean, $Patched, $report, $Allowlist)
    if ($Contracts) { $arguments += $Contracts }
    $global:LASTEXITCODE = 0
    $output = @(& $Java @arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Report = $report }
}

# The category of every finding line, and every FAIL line at all, so a fixture that fails for a
# second reason is caught as well as one that fails for none.
function Get-Findings {
    param($Result)
    $fails = @($Result.Output | Where-Object { $_ -like '`[diff`] FAIL*' })
    $categories = @($fails | ForEach-Object {
        if ($_ -match '^\[diff\] FAIL: (branch|invoke|parameter|width|try|result|contract): ') { $Matches[1] } else { '?' }
    })
    [pscustomobject]@{ Fails = $fails; Categories = $categories }
}

$equal = Compare-VerifierTallies -Clean @{ lock = 2; class = 1 } -Patched @{ class = 1; lock = 2 }
Assert-True $equal.Valid 'Equal verifier message multisets were rejected.'
$extra = Compare-VerifierTallies -Clean @{ lock = 2 } -Patched @{ lock = 2; verify = 1 }
Assert-True (-not $extra.Valid -and $extra.Deltas.Count -eq 1 -and `
    $extra.Deltas[0].Kind -eq 'extra') 'An extra verifier message was accepted.'
$missing = Compare-VerifierTallies -Clean @{ lock = 2; verify = 1 } -Patched @{ lock = 2 }
Assert-True (-not $missing.Valid -and $missing.Deltas.Count -eq 1 -and `
    $missing.Deltas[0].Kind -eq 'missing') 'A missing verifier message was accepted.'
$reduced = Compare-VerifierTallies -Clean @{ lock = 3 } -Patched @{ lock = 1 }
Assert-True (-not $reduced.Valid -and $reduced.Deltas[0].Difference -eq 2 -and `
    $reduced.Deltas[0].Kind -eq 'missing') 'A reduced verifier message count was accepted.'
$caseChanged = Compare-VerifierTallies -Clean @{ VerifyError = 1 } -Patched @{ verifyerror = 1 }
Assert-True (-not $caseChanged.Valid -and $caseChanged.Deltas.Count -eq 2) `
    'A case-changed verifier message was treated as the same message.'
# Meta's 580 raises no verifier message on the emulator, and a patched build that adds none is the
# pass. The device helper proves each run read its file, so the tallies don't have to.
$bothEmpty = Compare-VerifierTallies -Clean @{} -Patched @{}
Assert-True ($bothEmpty.Valid -and $bothEmpty.Deltas.Count -eq 0) 'Two empty verifier tallies were rejected.'
$newError = Compare-VerifierTallies -Clean @{} -Patched @{ 'Verification error in void X.jMJ.<clinit>()' = 1 }
Assert-True (-not $newError.Valid -and $newError.Deltas[0].Kind -eq 'extra') `
    'A verifier error the clean build does not raise was accepted.'

# The call lines themselves, not any mention: help text names these files too.
$verifierText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-injected-registers.ps1') -Raw
Assert-True ($verifierText -match 'injected-register-contracts\.ps1' -and `
    $verifierText -match 'Compare-VerifierTallies') `
    'The device verifier does not use the exact tally comparison contract.'
Assert-True ($verifierText -match '(?m)^[^#\r\n]*DexDiff\.java[^\r\n]*' -and `
    $verifierText -match '(?m)^[^#\r\n]*injected-mutation-contracts\.txt') `
    'The verifier does not hold the patched APK to the mutation contracts.'
$verifyAllText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-all-patches.ps1') -Raw
Assert-True ($verifyAllText -match '(?m)^[^#\r\n]*verify-injected-registers\.ps1') `
    'verify-all-patches.ps1 does not run the structural checks on the APK it patched.'
$prePushText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pre-push.ps1') -Raw
Assert-True ($prePushText -match "(?m)^\s*\`$suites \+= , @\('scripts/test-injected-registers\.ps1'") `
    'The push gate does not run the injected-register fixture test.'
Assert-True ($prePushText -notmatch 'so it has nothing to run there') `
    'The push gate still skips a suite it expects when the file is missing.'

$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
$javac = Join-Path (Split-Path -Parent $Java) 'javac.exe'
if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { throw "Required tool not found: $javac" }
$contracts = Join-Path $PSScriptRoot 'injected-mutation-contracts.txt'

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase `
    ("hushfacebook-register-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + `
    [System.IO.Path]::DirectorySeparatorChar
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    # Compiled once rather than launched from source per case: the same code, a fraction of the time.
    $classes = Join-Path $caseRoot 'classes'
    Invoke-Checked -Program $javac -Arguments @('-encoding', 'UTF-8', '-cp', $DesktopJar, '-d', $classes,
        (Join-Path $PSScriptRoot 'DexDiff.java'), (Join-Path $PSScriptRoot 'BadDexFixture.java')) `
        -Description 'javac for DexDiff and its fixtures'
    $classPath = $DesktopJar + [System.IO.Path]::PathSeparator + $classes
    $dexDir = Join-Path $caseRoot 'dex'
    Invoke-Checked -Program $Java -Arguments @('-cp', $classPath, 'BadDexFixture', $dexDir) `
        -Description 'BadDexFixture'
    function Get-Dex([string]$Name) { Join-Path $dexDir "$Name.dex" }

    $emptyAllowlist = Join-Path $caseRoot 'empty-allowlist.txt'
    [System.IO.File]::WriteAllText($emptyAllowlist, "# nothing reviewed`n")
    $cleanApk = New-DexApk -Name 'clean' -Entries ([ordered]@{ 'classes.dex' = (Get-Dex 'clean') })

    $good = Invoke-DexDiff -Clean $cleanApk -Patched (New-DexApk -Name 'good' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'good') })) -Allowlist $emptyAllowlist -Name 'good' -Contracts $contracts
    Assert-True ($good.ExitCode -eq 0) "The patched build that breaks nothing failed.`n$($good.Output -join "`n")"
    Assert-True ((Get-Findings $good).Fails.Count -eq 0) "The good build printed a FAIL line.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'FeedFilter;->hideEdge(Ljava/lang/Object;Ljava/lang/Object;)Z: 1 call site, in Lfixture/Feed;->addNewEdgeToCollection(')) `
        "The good build's guard was not reported at its one call site.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match 'structural findings: 0') `
        "The good build did not report its structural count.`n$($good.Output -join "`n")"

    $bad = [ordered]@{
        'bad-branch' = 'branch'
        'bad-branch-self' = 'branch'
        'bad-switch-case' = 'branch'
        'bad-invoke-count' = 'invoke'
        'bad-wide-split' = 'invoke'
        'bad-static-parameter' = 'parameter'
        'bad-wide-parameter' = 'parameter'
        'bad-narrow-for-wide' = 'width'
        'bad-narrow-shift' = 'width'
        'bad-wide-for-narrow' = 'width'
        'bad-move-result' = 'result'
        'bad-try-range' = 'try'
        'bad-try-handler' = 'try'
        'bad-try-handler-result' = 'try'
        'bad-double-guard' = 'contract'
        'bad-guard-elsewhere' = 'contract'
        'bad-no-guard' = 'contract'
    }
    $failures = @()
    foreach ($case in $bad.GetEnumerator()) {
        $apk = New-DexApk -Name $case.Key -Entries ([ordered]@{ 'classes.dex' = (Get-Dex $case.Key) })
        $result = Invoke-DexDiff -Clean $cleanApk -Patched $apk -Allowlist $emptyAllowlist `
            -Name $case.Key -Contracts $contracts
        $findings = Get-Findings $result
        $others = @($findings.Categories | Where-Object { $_ -ne $case.Value })
        if ($result.ExitCode -eq 0) {
            $failures += "$($case.Key) was accepted"
        } elseif ($findings.Categories.Count -eq 0) {
            $failures += "$($case.Key) failed without a $($case.Value) finding: $($result.Output -join ' | ')"
        } elseif ($others.Count -ne 0) {
            $failures += "$($case.Key) failed for more than $($case.Value): $($findings.Fails -join ' | ')"
        } elseif ((Get-Content -LiteralPath $result.Report -Raw) -notmatch "FAIL  $($case.Value): ") {
            $failures += "$($case.Key)'s report did not list its finding"
        }
    }
    if ($failures.Count -ne 0) { throw ($failures -join "`n") }

    # Without a contract file the structural checks still run; only the call-site rule is off.
    $noContract = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'bad-no-guard.apk') `
        -Allowlist $emptyAllowlist -Name 'no-contract-file'
    Assert-True ($noContract.ExitCode -eq 0) "A build with no guard failed with no contract to hold it to.`n$($noContract.Output -join "`n")"
    $noContractBranch = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'bad-branch.apk') `
        -Allowlist $emptyAllowlist -Name 'no-contract-branch'
    Assert-True ($noContractBranch.ExitCode -ne 0) 'A bad branch passed when no contract file was given.'

    $badContract = Join-Path $caseRoot 'bad-contract.txt'
    [System.IO.File]::WriteAllText($badContract, "single-call hideEdge`n")
    $unreadable = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'good.apk') `
        -Allowlist $emptyAllowlist -Name 'bad-contract' -Contracts $badContract
    Assert-True ($unreadable.ExitCode -ne 0 -and ($unreadable.Output -join "`n") -match 'Invalid contract line 1') `
        "A malformed contract line was accepted.`n$($unreadable.Output -join "`n")"

    $removedMethodApk = New-DexApk -Name 'removed-method' -Entries ([ordered]@{ 'classes.dex' = (Get-Dex 'removed-method') })
    $methodResult = Invoke-DexDiff -Clean $cleanApk -Patched $removedMethodApk `
        -Allowlist $emptyAllowlist -Name 'removed-method' -Contracts $contracts
    Assert-True ($methodResult.ExitCode -ne 0) 'A removed host method was accepted.'
    Assert-True (($methodResult.Output -join "`n") -match 'Lfixture/Feed;->removable\(\)V') `
        'The removed-method failure did not name the method.'

    $methodAllowlist = Join-Path $caseRoot 'method-allowlist.txt'
    [System.IO.File]::WriteAllText($methodAllowlist, "method Lfixture/Feed;->removable()V`n")
    $methodAllowed = Invoke-DexDiff -Clean $cleanApk -Patched $removedMethodApk `
        -Allowlist $methodAllowlist -Name 'allowed-method' -Contracts $contracts
    Assert-True ($methodAllowed.ExitCode -eq 0) "An exact reviewed method removal was rejected.`n$($methodAllowed.Output -join "`n")"

    $staleAllowlist = Join-Path $caseRoot 'stale-allowlist.txt'
    [System.IO.File]::WriteAllText($staleAllowlist, "method Lfixture/Feed;->notRemoved()V`n")
    $staleResult = Invoke-DexDiff -Clean $cleanApk -Patched $removedMethodApk `
        -Allowlist $staleAllowlist -Name 'stale-allowlist' -Contracts $contracts
    Assert-True ($staleResult.ExitCode -ne 0) 'A stale removal allowlist entry was accepted.'
    Assert-True (($staleResult.Output -join "`n") -match 'notRemoved') `
        'The stale allowlist failure did not name the stale entry.'
    Assert-True ((Get-Content -LiteralPath $staleResult.Report -Raw) -match 'notRemoved') `
        'The stale allowlist report did not name the stale entry.'

    $dexCleanApk = New-DexApk -Name 'dex-clean' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'clean')
        'classes2.dex' = (Get-Dex 'secondary')
    })
    $dexPatchedApk = New-DexApk -Name 'dex-patched' -Entries ([ordered]@{
        'classes.dex' = (Get-Dex 'good-with-secondary')
    })
    $dexResult = Invoke-DexDiff -Clean $dexCleanApk -Patched $dexPatchedApk `
        -Allowlist $emptyAllowlist -Name 'removed-dex' -Contracts $contracts
    Assert-True ($dexResult.ExitCode -ne 0) 'A removed DEX was accepted.'
    Assert-True (($dexResult.Output -join "`n") -match 'classes2\.dex') `
        'The removed-DEX failure did not name the DEX.'
    $dexAllowlist = Join-Path $caseRoot 'dex-allowlist.txt'
    [System.IO.File]::WriteAllText($dexAllowlist, "dex classes2.dex`n")
    $dexAllowed = Invoke-DexDiff -Clean $dexCleanApk -Patched $dexPatchedApk `
        -Allowlist $dexAllowlist -Name 'allowed-dex' -Contracts $contracts
    Assert-True ($dexAllowed.ExitCode -eq 0) "An exact reviewed DEX removal was rejected.`n$($dexAllowed.Output -join "`n")"

    $same = Invoke-DexDiff -Clean $cleanApk -Patched $cleanApk -Allowlist $emptyAllowlist `
        -Name 'same-file' -Contracts $contracts
    Assert-True ($same.ExitCode -ne 0 -and ($same.Output -join "`n") -match 'no host method differs') `
        'One APK compared with itself was accepted.'
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and `
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host "[scripts] injected-register and mutation contracts passed ($($bad.Count) bad fixtures, each refused for its own check)"
