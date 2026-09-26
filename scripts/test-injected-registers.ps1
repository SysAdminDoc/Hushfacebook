<#
.SYNOPSIS
    Exercise DexDiff.java against dex files that break one check each.

.DESCRIPTION
    BadDexFixture.java writes a clean host, a patched build of it that passes every check, and one
    patched build per check that breaks that check and nothing else: a branch into an instruction,
    a branch to itself, a switch case into an instruction, a switch pointed at the other kind of
    table, a branch or switch case onto a payload (a packed or sparse switch's, or
    fill-array-data's) or a move-result, flow that falls into a payload, starts at one or runs off
    the end of the code, an invoke with too few registers, a wide argument split across two
    registers, the static off-by-one, the upper half of a wide parameter read as an object, a
    narrow constant read as a long and the reverse (the AMOLED sweep's bug on 580), either half of
    a live long overwritten and the other half still read, a broken pair or a narrow constant on
    one arm of a branch or on a loop's back edge, either half of a pair broken on one arm moved
    where the arms meet, a zero on one arm read as a long, a conflict (an object on one arm, an
    int on the other) read by each instruction and in each register that takes a value, or read
    through a copy, a wide move of a conflict, a long and a lone upper half tested against zero,
    an int and an object tested for equality in either order, a move-result the patch separated
    from its invoke, bad try ranges and handlers (a handler at a switch or array payload among
    them), a move-exception the method's entry reaches, the one feed guard doubled, moved or
    missing, the reels hook deleted from the pre-EOF injector or put after a branch, and the
    showcase stub left unfilled, calling another class, or calling a class that isn't the only one
    answering its type name. The good build carries the joins, copies and reads ART accepts, a zero tested against
    an object among them, so a check made stricter still has to pass them. Each bad build has to
    fail with findings of its own category only, so a check that fires for the wrong reason fails
    here too. Removed methods and DEX entries, the removal allowlist, and the device tally
    comparison are held to what they did before.
    verify-injected-registers.ps1 also runs end to end with stand-in tools, and its wiring is read
    through the parser (script-wiring.ps1), with the wiring checks themselves tried on copies that
    drop the calls but keep their text, at least one for each rule of what a script can't reach.
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
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Native calls run with Continue: Windows PowerShell 5.1, which the push hook uses when pwsh isn't
# on git's PATH, turns any line a program writes to stderr into a terminating error under Stop,
# and several cases here are meant to make a program fail. The exit code is what gets judged.
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $ErrorActionPreference = 'Continue'
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
    $ErrorActionPreference = 'Continue'
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

# The calls themselves, as the scripts run them (script-wiring.ps1). Help text, comments, log
# lines, functions nothing calls and dead branches can all name these files too.
$verifier = Join-Path $PSScriptRoot 'verify-injected-registers.ps1'
$allPatches = Join-Path $PSScriptRoot 'verify-all-patches.ps1'
$prePush = Join-Path $PSScriptRoot 'pre-push.ps1'
Assert-True ((Test-DotSourcesFile $verifier 'injected-register-contracts.ps1') -and (Test-TalliesBothSides $verifier)) `
    'The device verifier does not use the exact tally comparison contract.'
Assert-True (Test-RunsDexDiffWithContracts $verifier) 'The verifier does not hold the patched APK to the mutation contracts.'
Assert-True (Test-VerifiesWhatItPatched $allPatches) `
    'verify-all-patches.ps1 does not run the structural checks on the APK it patched.'
Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-injected-registers.ps1') `
    'The push gate does not run the injected-register fixture test.'
Assert-True ((Get-Content -LiteralPath $prePush -Raw) -notmatch 'so it has nothing to run there') `
    'The push gate still skips a suite it expects when the file is missing.'

# The checks themselves, on copies of those scripts with the wiring taken out in the ways that
# leave its text behind. Each copy has to fail the check it was made for, and an untouched copy
# has to pass it, so a check can't pass here by failing everything.
$wiringCopies = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) `
    ("hushfacebook-wiring-" + [guid]::NewGuid().ToString('N'))))
try {
    New-Item -ItemType Directory -Path $wiringCopies | Out-Null
    $verifierText = [System.IO.File]::ReadAllText($verifier)
    $allPatchesText = [System.IO.File]::ReadAllText($allPatches)
    $prePushSource = [System.IO.File]::ReadAllText($prePush)
    $runsDexDiff = { param($Path) Test-RunsDexDiffWithContracts $Path }
    $dotSourcesContracts = { param($Path) Test-DotSourcesFile $Path 'injected-register-contracts.ps1' }
    $talliesBothSides = { param($Path) Test-TalliesBothSides $Path }
    $verifiesPatched = { param($Path) Test-VerifiesWhatItPatched $Path }
    $gateRunsSuite = { param($Path) Test-PushGateRunsSuite $Path 'scripts/test-injected-registers.ps1' }
    # The nodes the copies take the wiring out around: the & $Java DexDiff call in Invoke-DexDiff,
    # the call to Invoke-DexDiff, the contracts helper's dot-source, verify-all-patches' verifier
    # call, and the pre-push line that runs this suite.
    $dexDiffCall = { param($Node)
        $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Extent.Text -like '*DexDiff.java*' }
    $dexDiffRun = { param($Node)
        $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Extent.Text -eq '$diff = Invoke-DexDiff' }
    $contractsDotSource = { param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        $Node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Dot -and
        $Node.Extent.Text -like '*injected-register-contracts.ps1*' }
    $verifierCall = { param($Node)
        $Node -is [System.Management.Automation.Language.CommandAst] -and
        $Node.Extent.Text -like '*verify-injected-registers.ps1*' }
    $suiteLine = { param($Node)
        $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
        $Node.Left.Extent.Text -eq '$suites' -and
        $Node.Extent.Text -like "*'scripts/test-injected-registers.ps1'*" }
    $dexDiffFunction = { param($Node)
        $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq 'Invoke-DexDiff' }
    $contractsArgument = "(Join-Path `$PSScriptRoot 'injected-mutation-contracts.txt')"
    # The DexDiff call without the contracts, and the old call kept after it between -Open and
    # -Close: code that can't run for the copies expected to fail, code that can for the rest.
    $parkOldCall = { param([string]$Open, [string]$Close)
        # A local copy, so GetNewClosure takes it along with the two parameters.
        $contracts = $contractsArgument
        Edit-ScriptNode $verifierText $dexDiffCall { param($Text)
            $Text.Replace($contracts, '') + "`n    $Open`n    $Text`n    $Close" }.GetNewClosure() }
    $wiringCases = @(
        @{ Name = 'the untouched verifier'; Check = $runsDexDiff; Expect = $true; Text = $verifierText }
        @{ Name = 'the untouched verifier'; Check = $dotSourcesContracts; Expect = $true; Text = $verifierText }
        @{ Name = 'the untouched verifier'; Check = $talliesBothSides; Expect = $true; Text = $verifierText }
        @{ Name = 'the untouched verify-all-patches.ps1'; Check = $verifiesPatched; Expect = $true; Text = $allPatchesText }
        @{ Name = 'the untouched pre-push.ps1'; Check = $gateRunsSuite; Expect = $true; Text = $prePushSource }
        @{ Name = 'the contracts dropped from the DexDiff call and named in a log line'; Check = $runsDexDiff
            Text = (Edit-ScriptNode $verifierText { param($Node)
                $Node -is [System.Management.Automation.Language.ParenExpressionAst] -and
                $Node.Extent.Text -like '*injected-mutation-contracts.txt*' } { param($Text) '' }) +
                "`nWrite-Host '[registers] DexDiff.java runs without injected-mutation-contracts.txt now'`n" }
        @{ Name = 'the DexDiff function left uncalled'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText { param($Node)
                $Node -is [System.Management.Automation.Language.CommandAst] -and $Node.GetCommandName() -eq 'Invoke-DexDiff'
            } { param($Text) '[pscustomobject]@{ ExitCode = 0; Output = @() }' } }
        @{ Name = 'the DexDiff call behind a return'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "return`n    $Text" } }
        @{ Name = 'the contracts helper dot-sourced in a dead branch'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (`$false) { $Text }" } }
        @{ Name = 'the patched tally compared with itself'; Check = $talliesBothSides
            Text = Edit-ScriptNode $verifierText { param($Node)
                $Node -is [System.Management.Automation.Language.CommandAst] -and
                $Node.GetCommandName() -eq 'Compare-VerifierTallies'
            } { param($Text) 'Compare-VerifierTallies -Clean $patchedTally -Patched $patchedTally' } }
        @{ Name = 'the verifier call replaced by a log line naming it'; Check = $verifiesPatched
            Text = Edit-ScriptNode $allPatchesText $verifierCall { param($Text)
                "Write-Host '[verify] skipped verify-injected-registers.ps1 -PatchedApk `$out'" } }
        @{ Name = 'the verifier run on the stock APK'; Check = $verifiesPatched
            Text = Edit-ScriptNode $allPatchesText $verifierCall { param($Text)
                $Text.Replace('-PatchedApk $out', '-PatchedApk $stockApk') } }
        @{ Name = 'the suite line in a block comment'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource $suiteLine { param($Text) "<#`n$Text`n#>" } }
        # One copy for each rule of what a script can't reach, each taking the wiring out by that
        # rule alone, so none of them can be dropped without a copy here passing its check.
        @{ Name = 'the contracts helper dot-sourced in a while ($false) body'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "while (`$false) { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced in the else of if ($true)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (`$true) { } else { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced behind if (-not $true)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (-not `$true) { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced behind if (0)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (0) { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced inside a function the script calls'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text)
                "function Import-Contracts { $Text }`nImport-Contracts" } }
        # A script block run with & has a scope of its own too, even around a . { } block, while
        # . { } and ForEach-Object run in the script's.
        @{ Name = 'the contracts helper dot-sourced inside & { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "& { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced inside . { } inside & { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "& { . { $Text } }" } }
        @{ Name = 'the contracts helper dot-sourced inside & { } inside . { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) ". { & { $Text } }" } }
        @{ Name = 'the contracts helper dot-sourced behind if ($false) inside . { }'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) ". { if (`$false) { $Text } }" } }
        @{ Name = 'the contracts helper dot-sourced inside . { }'; Check = $dotSourcesContracts; Expect = $true
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) ". { $Text }" } }
        @{ Name = 'the contracts helper dot-sourced inside ForEach-Object { }'; Check = $dotSourcesContracts; Expect = $true
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "1 | ForEach-Object { $Text }" } }
        @{ Name = 'the DexDiff call behind exit'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "exit 0`n    $Text" } }
        @{ Name = 'the DexDiff call behind throw'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "throw 'stopped'`n    $Text" } }
        @{ Name = 'the DexDiff call behind break'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "break`n    $Text" } }
        @{ Name = 'the DexDiff call behind continue'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text) "continue`n    $Text" } }
        @{ Name = 'the DexDiff function called only in a dead branch'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "if (`$false) { $Text }`n`$diff = [pscustomobject]@{ ExitCode = 0; Output = @() }" } }
        @{ Name = 'the patched APK moved off its place in the DexDiff call'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text)
                $Text.Replace('$cleanBase $PatchedApk', '$PatchedApk $cleanBase') } }
        @{ Name = 'the verifier run on the stock APK, which follows -o only in a dead branch'; Check = $verifiesPatched
            Text = Edit-ScriptNode (Edit-ScriptNode $allPatchesText $verifierCall { param($Text)
                $Text.Replace('-PatchedApk $out', '-PatchedApk $stockApk') }) { param($Node)
                $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                $Node.Extent.Text -like "`$arguments = @('patch'*" } { param($Text)
                "$Text`nif (`$false) { `$arguments = @('-o', `$stockApk) }" } }
        @{ Name = 'the suite line behind if ($false)'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource $suiteLine { param($Text) "if (`$false) { $Text }" } }
        @{ Name = 'the suite line in an elseif after if ($true)'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource { param($Node)
                $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                $Node.Clauses[0].Item1.Extent.Text -eq '$touchesInjectedRegisterVerifier' } { param($Text)
                "if (`$true) { } else$Text" } }
        # Code that never runs though no constant or return right before it says so: a script block
        # nothing runs, an exit in a function that never returns or in an if or a loop that always
        # reaches it, and a condition that compares two constants. The copies expected to pass keep
        # those rules off code that does run: a block run by & or by ForEach-Object, a function
        # that can return, and a loop a break leaves.
        @{ Name = 'the DexDiff call without the contracts, and the old one kept in a script block nothing runs'
            Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffCall { param($Text)
                $Text.Replace("(Join-Path `$PSScriptRoot 'injected-mutation-contracts.txt')", '') +
                "`n    `$previous = { $Text }" } }
        @{ Name = 'the DexDiff function run through & { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = & { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff call after a function that ends in one that exits'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "function Stop-Early { Stop-Now }`nfunction Stop-Now { exit 0 }`n`$stopped = Stop-Early`n$Text" } }
        @{ Name = 'the DexDiff call after a function that can return before its exit'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "function Stop-Early { if (`$Serial) { return }`n    exit 0 }`nStop-Early`n$Text" } }
        @{ Name = 'the DexDiff call after an exit behind if ($true)'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "if (`$true) { exit 0 }`n$Text" } }
        @{ Name = 'the DexDiff call after an exit on every arm that can run'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text)
                "if (`$false) { } elseif (`$Serial) { exit 1 } else { exit 0 }`n$Text" } }
        @{ Name = 'the DexDiff call after while ($true) { exit 0 }'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "while (`$true) { exit 0 }`n$Text" } }
        @{ Name = 'the DexDiff call after a while ($true) a break leaves'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "while (`$true) { break }`n$Text" } }
        @{ Name = 'the contracts helper dot-sourced behind if (1 -gt 2)'; Check = $dotSourcesContracts
            Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if (1 -gt 2) { $Text }" } }
        @{ Name = 'the suite line behind if (0 -eq 1)'; Check = $gateRunsSuite
            Text = Edit-ScriptNode $prePushSource $suiteLine { param($Text) "if (0 -eq 1) { $Text }" } }
        # The old call kept where a constant settles that it can't run: an -and or -or one side
        # settles, the right of an -and or -or the left side settles, a switch clause that can't
        # match or a default a clause always takes, a foreach over nothing, a catch of a try that
        # can't throw, and a function defined a second time, whose first body never runs.
        @{ Name = 'the old DexDiff call kept behind if ($false -and $Serial)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($false -and $Serial) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($Serial -and $false)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($Serial -and $false) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if (-not ($Serial -or $true))'; Check = $runsDexDiff
            Text = & $parkOldCall 'if (-not ($Serial -or $true)) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($true -xor $true)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($true -xor $true) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if (-not ($true -and $true))'; Check = $runsDexDiff
            Text = & $parkOldCall 'if (-not ($true -and $true)) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($false -or $false)'; Check = $runsDexDiff
            Text = & $parkOldCall 'if ($false -or $false) {' '}' }
        @{ Name = 'the old DexDiff call kept on the right of $false -and'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = $false -and $(' ')' }
        @{ Name = 'the old DexDiff call kept on the right of $true -or'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = $true -or $(' ')' }
        @{ Name = "the old DexDiff call kept in switch ('current') { 'previous' { } }"; Check = $runsDexDiff
            Text = & $parkOldCall "switch ('current') { 'previous' {" '} }' }
        @{ Name = 'the old DexDiff call kept in the default of switch (1) { 1 { } }'; Check = $runsDexDiff
            Text = & $parkOldCall 'switch (1) { 1 { } default {' '} }' }
        @{ Name = 'the old DexDiff call kept in the default of a switch over @()'; Check = $runsDexDiff
            Text = & $parkOldCall 'switch (@()) { default {' '} }' }
        @{ Name = 'the old DexDiff call kept in a foreach over @()'; Check = $runsDexDiff
            Text = & $parkOldCall 'foreach ($unused in @()) {' '}' }
        @{ Name = 'the old DexDiff call kept in a foreach over $null'; Check = $runsDexDiff
            Text = & $parkOldCall 'foreach ($unused in $null) {' '}' }
        @{ Name = 'the old DexDiff call kept in the catch of an empty try'; Check = $runsDexDiff
            Text = & $parkOldCall 'try { } catch {' '}' }
        @{ Name = 'the old DexDiff call kept in the catch of try { return }'; Check = $runsDexDiff
            Text = & $parkOldCall 'try { return } catch {' '}' }
        @{ Name = 'Invoke-DexDiff defined again below it, without the contracts'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text) "$Text`n`n" + $Text.Replace($contractsArgument, '') } }
        @{ Name = 'Invoke-DexDiff defined again below it as script:Invoke-DexDiff, without the contracts'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text)
                "$Text`n`n" + $Text.Replace($contractsArgument, '').Replace('function Invoke-DexDiff', 'function script:Invoke-DexDiff') } }
        @{ Name = 'Invoke-DexDiff set again through ${function:}, without the contracts'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text)
                "$Text`n`n" + $Text.Replace($contractsArgument, '').Replace('function Invoke-DexDiff {', '${function:Invoke-DexDiff} = {') } }
        @{ Name = 'the DexDiff call after a call to function script:Stop-Now { exit 0 }'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "function script:Stop-Now { exit 0 }`nStop-Now`n$Text" } }
        # And where it still runs, so none of those rules reaches code that does.
        @{ Name = 'the old DexDiff call kept behind if ($true -and $Serial)'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'if ($true -and $Serial) {' '}' }
        @{ Name = 'the old DexDiff call kept behind if ($false -or $Serial)'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'if ($false -or $Serial) {' '}' }
        @{ Name = 'the old DexDiff call kept on the right of $Serial -and'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall '$null = $Serial -and $(' ')' }
        @{ Name = "the old DexDiff call kept in switch ('current') { 'current' { } }"; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall "switch ('current') { 'current' {" '} }' }
        @{ Name = 'the old DexDiff call kept in the default of switch (1) { 2 { } }'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'switch (1) { 2 { } default {' '} }' }
        @{ Name = "the old DexDiff call kept in switch (`$Serial) { 'previous' { } }"; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall "switch (`$Serial) { 'previous' {" '} }' }
        @{ Name = 'the old DexDiff call kept in a foreach over @(1)'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'foreach ($once in @(1)) {' '}' }
        @{ Name = 'the old DexDiff call kept in the catch of a try that can throw'; Check = $runsDexDiff; Expect = $true
            Text = & $parkOldCall 'try { Get-Item -LiteralPath $PatchedApk | Out-Null } catch {' '}' }
        @{ Name = 'Invoke-DexDiff defined once, as script:Invoke-DexDiff'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffFunction { param($Text)
                $Text.Replace('function Invoke-DexDiff', 'function script:Invoke-DexDiff') } }
        # The rules the copies above leave untried: every comparison the parser folds, $null as a
        # constant, an ending call past the first element of a pipeline, a for with no condition
        # at all, and the other ways ForEach-Object and Where-Object get written. The copies
        # expected to pass keep a loop and an if to ending only when they certainly do.
        foreach ($condition in '1 -ne 1', '0 -ge 1', '2 -lt 1', '1 -le 0', "'a' -ceq 'A'", "'a' -cne 'a'",
                "'a' -cgt 'b'", "'a' -cge 'b'", "'b' -clt 'a'", "'b' -cle 'a'", '$null') {
            @{ Name = "the contracts helper dot-sourced behind if ($condition)"; Check = $dotSourcesContracts
                Text = Edit-ScriptNode $verifierText $contractsDotSource { param($Text) "if ($condition) { $Text }" } }
        }
        @{ Name = 'the DexDiff call after $null | Stop-Now, a function that exits'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "function Stop-Now { exit 0 }`n`$null | Stop-Now`n$Text" } }
        @{ Name = 'the DexDiff call after for (;;) { exit 0 }'; Check = $runsDexDiff
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "for (;;) { exit 0 }`n$Text" } }
        # ForEach-Object and Where-Object run only the blocks they're given to run, and only for
        # an item: a block handed to -ArgumentList is a value, and @() gives them nothing.
        @{ Name = 'the old DexDiff call kept in a block handed to ForEach-Object -ArgumentList'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = @(1) | ForEach-Object -ArgumentList {' '} -Process { }' }
        @{ Name = 'the old DexDiff call kept in ForEach-Object over @()'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = @() | ForEach-Object {' '}' }
        @{ Name = 'the old DexDiff call kept in Where-Object over @()'; Check = $runsDexDiff
            Text = & $parkOldCall '$null = @() | Where-Object {' '}' }
        @{ Name = 'the DexDiff function run through ForEach-Object -Process { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Process { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object -Begin { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Begin { Invoke-DexDiff } -Process { }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object -End { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Process { } -End { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through Where-Object -FilterScript { }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | Where-Object -FilterScript { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object over $null, which is one item'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = $null | ForEach-Object { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through ForEach-Object -Process:{ }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | ForEach-Object -Process:{ Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through Where-Object'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | Where-Object { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff function run through %'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) '$diff = @(1) | % { Invoke-DexDiff }' } }
        @{ Name = 'the DexDiff call after while ($Serial) { exit 0 }'; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "while (`$Serial) { exit 0 }`n$Text" } }
        @{ Name = "the DexDiff call after if (`$Serial) { 'x' } else { exit 0 }"; Check = $runsDexDiff; Expect = $true
            Text = Edit-ScriptNode $verifierText $dexDiffRun { param($Text) "if (`$Serial) { 'x' } else { exit 0 }`n$Text" } }
    )
    $wiringFailures = @()
    $copy = Join-Path $wiringCopies 'copy.ps1'
    foreach ($case in $wiringCases) {
        [System.IO.File]::WriteAllText($copy, $case.Text)
        $expect = $case.ContainsKey('Expect') -and $case.Expect
        if ((& $case.Check $copy) -ne $expect) {
            $wiringFailures += "$($case.Name): the check said $(-not $expect)"
        }
    }
    if ($wiringFailures.Count -ne 0) { throw ("The wiring checks misjudged:`n" + ($wiringFailures -join "`n")) }
} finally {
    Remove-Item -LiteralPath $wiringCopies -Recurse -Force -ErrorAction SilentlyContinue
}

# The verifier run end to end, with stand-ins for the tools it starts: a java that answers the
# version probe and plays DexDiff with the given exit code, an aapt2 that describes Facebook 580,
# and an apksigner that reports Meta's signer. With -JavaGone the apksigner also deletes that java,
# which leaves it unable to start by the time DexDiff runs, as a JDK replaced mid-run would. A
# Continue preference around the DexDiff call once turned exactly that into '[registers] success.'.
$standIns = [System.IO.Path]::GetFullPath((Join-Path ([System.IO.Path]::GetTempPath()) `
    ("hushfacebook-verifier-standins-" + [guid]::NewGuid().ToString('N'))))
$metaSigner = @((Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'patches-list.json') -Raw |
    ConvertFrom-Json).patches | ForEach-Object { $_.compatibility } |
    Where-Object { $_.packageName -eq 'com.facebook.katana' } | ForEach-Object { $_.signatures } |
    Sort-Object -Unique | Select-Object -First 1)
Assert-True ($metaSigner.Count -eq 1) 'patches-list.json declares no Facebook signer for the stand-in apksigner.'

function Write-StandIn {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, (($Text -replace "`r`n", "`n") -replace "`n", "`r`n"),
        [System.Text.Encoding]::ASCII)
}

function Invoke-VerifierWithStandIns {
    param([string]$Name, [int]$DexDiffExit, [switch]$JavaGone)
    $case = Join-Path $standIns $Name
    New-Item -ItemType Directory -Path $case -Force | Out-Null
    $javaStandIn = Join-Path $case 'java.cmd'
    Write-StandIn $javaStandIn @"
@echo off
if "%~1"=="-version" (
  echo openjdk version "21.0.0"
  exit /b 0
)
echo [diff] structural findings: 0
exit /b $DexDiffExit
"@
    Write-StandIn (Join-Path $case 'aapt2.cmd') @'
@echo off
echo   E: manifest (line=2)
echo     A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=475019344
echo     A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="580.0.0.51.74" (Raw: "580.0.0.51.74")
echo     A: package="com.facebook.katana" (Raw: "com.facebook.katana")
exit /b 0
'@
    $delete = if ($JavaGone) { "del /f /q `"$javaStandIn`"" } else { 'rem' }
    Write-StandIn (Join-Path $case 'apksigner.bat') @"
@echo off
$delete
echo Signer #1 certificate SHA-256 digest: $($metaSigner[0])
exit /b 0
"@
    foreach ($file in 'clean.apk', 'patched.apk', 'desktop.jar') {
        [System.IO.File]::WriteAllText((Join-Path $case $file), 'stand-in')
    }
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    $output = @(& (Get-Process -Id $PID).Path -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $verifier `
        -CleanApk (Join-Path $case 'clean.apk') -PatchedApk (Join-Path $case 'patched.apk') `
        -ReportPath (Join-Path $case 'report.txt') -Java $javaStandIn -DesktopJar (Join-Path $case 'desktop.jar') `
        -Aapt2 (Join-Path $case 'aapt2.cmd') 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Text = $output -join "`n" }
}

try {
    New-Item -ItemType Directory -Path $standIns | Out-Null
    # Each run has to reach the DexDiff call, or a stand-in that broke early would pass the checks below.
    $reached = '(?m)^\[registers\] patched '
    $passed = Invoke-VerifierWithStandIns -Name 'passed' -DexDiffExit 0
    Assert-True ($passed.ExitCode -eq 0 -and $passed.Text -match $reached -and
        $passed.Output -contains '[registers] success.') `
        "The verifier did not pass a comparison DexDiff passed.`n$($passed.Text)"
    $refused = Invoke-VerifierWithStandIns -Name 'refused' -DexDiffExit 1
    Assert-True ($refused.ExitCode -eq 1 -and $refused.Text -match $reached -and
        $refused.Text -match 'FAIL: the dex comparison exited 1' -and
        $refused.Output -notcontains '[registers] success.') `
        "The verifier did not fail a comparison DexDiff failed.`n$($refused.Text)"
    $gone = Invoke-VerifierWithStandIns -Name 'java-gone' -DexDiffExit 0 -JavaGone
    Assert-True ($gone.ExitCode -ne 0 -and $gone.Text -match $reached -and
        $gone.Output -notcontains '[registers] success.' -and $gone.Text -notmatch '\[registers\] static: ') `
        "The verifier passed a run whose java could not start for DexDiff (exit $($gone.ExitCode)).`n$($gone.Text)"
} finally {
    Remove-Item -LiteralPath $standIns -Recurse -Force -ErrorAction SilentlyContinue
}

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
    foreach ($stub in 'GenAiLabel;->detectedInfo', 'RecommendationLabel;->recommendationContext') {
        Assert-True (($good.Output -join "`n") -match ([regex]::Escape("$stub(Ljava/lang/Object;)Ljava/lang/Object;: calls " +
            'Lcom/facebook/graphql/model/GraphQLStory;->A0X()Lfixture/Model; before its first return'))) `
            "The good build's $stub was not reported calling the story's accessor.`n$($good.Output -join "`n")"
    }
    foreach ($adapter in @('NewsFeedAdapterConfiguration.addStoriesAdapter: first in Lfixture/Adapters;->addStoriesAdapter(',
            'stories_tray_create_adapter_stop: first in Lfixture/Adapters;->addUnifiedTray(')) {
        Assert-True (($good.Output -join "`n") -match [regex]::Escape("hideStoriesTray(I)Z holding $adapter")) `
            "The good build's tray hook was not reported first in its adapter: $adapter`n$($good.Output -join "`n")"
    }
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'hidePreEofReels()Z holding PreEofIfuSectionAdapter: first in Lfixture/PreEof;->injectPreEofIfuEdge$fixture(')) `
        "The good build's reels hook was not reported first in the pre-EOF injector.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'ShowcaseType;->storyType(Ljava/lang/Object;)Ljava/lang/Object; on-type-named ShowcaseFeedUnit: calls ' +
        'Lfixture/Showcase;->A01()Lfixture/StoryType; before its first return')) `
        "The good build's showcase stub was not reported calling the showcase unit's accessor.`n$($good.Output -join "`n")"
    Assert-True (($good.Output -join "`n") -match [regex]::Escape(
        'ReturnRefresh;->skip()Z holding FeedRefreshTriggerController: first in Lfixture/ReturnController;->resumeAfterBackground(')) `
        "The good build's background-return guard was not first in the resume callback.`n$($good.Output -join "`n")"

    $bad = [ordered]@{
        'bad-branch' = 'branch'
        'bad-branch-self' = 'branch'
        'bad-switch-case' = 'branch'
        'bad-switch-to-payload' = 'branch'
        'bad-switch-to-result' = 'branch'
        'bad-goto-to-payload' = 'branch'
        'bad-goto-to-array-payload' = 'branch'
        'bad-switch-to-array-payload' = 'branch'
        'bad-sparse-case-to-payload' = 'branch'
        'bad-packed-switch-sparse-table' = 'branch'
        'bad-sparse-switch-packed-table' = 'branch'
        'bad-fallthrough-into-payload' = 'branch'
        'bad-payload-at-entry' = 'branch'
        'bad-branch-to-result' = 'branch'
        'bad-walk-off-end' = 'branch'
        'bad-goto-to-handler' = 'branch'
        'bad-fallthrough-handler' = 'try'
        'bad-move-exception-entry' = 'try'
        'bad-wide-high-clobber' = 'width'
        'bad-wide-high-clobber-branch' = 'width'
        'bad-wide-high-clobber-loop' = 'width'
        'bad-wide-low-clobber' = 'width'
        'bad-wide-below-pair' = 'width'
        'bad-invoke-count' = 'invoke'
        'bad-wide-split' = 'invoke'
        'bad-static-parameter' = 'parameter'
        'bad-wide-parameter' = 'parameter'
        'bad-narrow-for-wide' = 'width'
        'bad-narrow-for-wide-branch' = 'width'
        'bad-narrow-shift' = 'width'
        'bad-wide-for-narrow' = 'width'
        'bad-conflict-if-eqz' = 'width'
        'bad-conflict-if-ne' = 'width'
        'bad-conflict-if-lt' = 'width'
        'bad-conflict-switch' = 'width'
        'bad-conflict-monitor-enter' = 'width'
        'bad-conflict-throw' = 'width'
        'bad-conflict-check-cast' = 'width'
        'bad-conflict-instance-of' = 'width'
        'bad-conflict-array-length' = 'width'
        'bad-conflict-new-array' = 'width'
        'bad-conflict-filled-new-array' = 'width'
        'bad-conflict-fill-array-data' = 'width'
        'bad-conflict-if-nez' = 'width'
        'bad-conflict-if-eq' = 'width'
        'bad-conflict-if-eq-second' = 'width'
        'bad-conflict-if-gez' = 'width'
        'bad-conflict-if-lt-second' = 'width'
        'bad-conflict-sparse-switch' = 'width'
        'bad-conflict-monitor-exit' = 'width'
        'bad-conflict-filled-new-array-range' = 'width'
        'bad-broken-high-if-eqz' = 'width'
        'bad-if-eq-int-object' = 'width'
        'bad-if-ne-object-int' = 'width'
        'bad-wide-if-eqz' = 'width'
        'bad-broken-low-move' = 'width'
        'bad-broken-high-move' = 'width'
        'bad-conflict-object-copy' = 'width'
        'bad-conflict-plain-copy' = 'width'
        'bad-zero-for-wide-branch' = 'width'
        'bad-move-wide-conflict' = 'width'
        'bad-move-result' = 'result'
        'bad-try-range' = 'try'
        'bad-try-handler' = 'try'
        'bad-try-handler-result' = 'try'
        'bad-try-handler-payload' = 'try'
        'bad-try-handler-array-payload' = 'try'
        'bad-double-guard' = 'contract'
        'bad-guard-elsewhere' = 'contract'
        'bad-no-guard' = 'contract'
        'bad-stub-not-filled' = 'contract'
        'bad-stub-other-class' = 'contract'
        'bad-stub-call-after-return' = 'contract'
        'bad-tray-hook-missing' = 'contract'
        'bad-tray-hook-late' = 'contract'
        'bad-preeof-hook-missing' = 'contract'
        'bad-preeof-hook-late' = 'contract'
        'bad-showcase-stub-not-filled' = 'contract'
        'bad-showcase-stub-other-class' = 'contract'
        'bad-showcase-two-classes' = 'contract'
        'bad-return-refresh-hook-missing' = 'contract'
        'bad-return-refresh-hook-late' = 'contract'
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
    # A first-call rule needs its method, "on" and a class descriptor.
    foreach ($line in @(
            'first-call Lapp/morphe/extension/facebook/feed/GenAiLabel;->detectedInfo(Ljava/lang/Object;)Ljava/lang/Object; on GraphQLStory',
            'first-call Lapp/morphe/extension/facebook/feed/GenAiLabel;->detectedInfo(Ljava/lang/Object;)Ljava/lang/Object; in Lcom/facebook/graphql/model/GraphQLStory;',
            'first-call detectedInfo on Lcom/facebook/graphql/model/GraphQLStory;',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z in addStoriesAdapter',
            'start-call hideStoriesTray holding stories_tray_create_adapter_stop',
            'start-call Lapp/morphe/extension/facebook/feed/FeedFilter;->hideStoriesTray(I)Z holding',
            'first-call Lapp/morphe/extension/facebook/feed/ShowcaseType;->storyType(Ljava/lang/Object;)Ljava/lang/Object; on-type-named Lfixture/Showcase;',
            'first-call Lapp/morphe/extension/facebook/feed/ShowcaseType;->storyType(Ljava/lang/Object;)Ljava/lang/Object; on-type-named',
            'first-call storyType on-type-named ShowcaseFeedUnit')) {
        [System.IO.File]::WriteAllText($badContract, "# a comment line first`n$line`n")
        $unreadableFirstCall = Invoke-DexDiff -Clean $cleanApk -Patched (Join-Path $caseRoot 'good.apk') `
            -Allowlist $emptyAllowlist -Name 'bad-first-call-contract' -Contracts $badContract
        Assert-True ($unreadableFirstCall.ExitCode -ne 0 -and ($unreadableFirstCall.Output -join "`n") -match 'Invalid contract line 2') `
            "A malformed first-call line was accepted: $line`n$($unreadableFirstCall.Output -join "`n")"
    }

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
