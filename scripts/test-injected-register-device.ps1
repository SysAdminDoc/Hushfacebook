<#
.SYNOPSIS
    Exercise ADB failure cleanup for injected-register verification.

.DESCRIPTION
    Every stage of a device verify can fail, and each failure has to be named and still remove
    what was pushed. Two of them are about evidence rather than errors: Meta's build raises no
    verifier message, so a tally only counts once dex2oat has read a file of the pushed size, and
    dex2oat exits 0 while logging that the file it was given doesn't exist.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# The helper reads the local file's size, so the fixture is a real file.
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-device-fixture-" + [guid]::NewGuid().ToString('N') + '.apk')
[System.IO.File]::WriteAllBytes($fixture, [byte[]](1..200 | ForEach-Object { $_ % 256 }))
$fixtureSize = (Get-Item -LiteralPath $fixture).Length
try {
    function New-FakeAdb {
        param(
            [string]$FailureStage,
            [int]$CleanupFailureNumber = 0,
            [int]$CleanupThrowNumber = 0,
            # A parameter, so GetNewClosure captures it: the closure sees this function's own
            # variables, not the script's, once the suite runs nested inside the push gate.
            [long]$Size = $fixtureSize
        )

        $state = [pscustomobject]@{
            Calls = [System.Collections.Generic.List[string]]::new()
            RemotePaths = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::Ordinal)
            CleanupCalls = 0
        }
        $invoker = {
            param([string]$Executable, [string[]]$Arguments)
            $line = $Arguments -join ' '
            [void]$state.Calls.Add($line)
            $exitCode = 0
            $output = @()
            $operation = $Arguments[2]

            if ($operation -eq 'push') {
                [void]$state.RemotePaths.Add($Arguments[4])
                if ($FailureStage -eq 'push') { $exitCode = 11 }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like '*&& mkdir -p*') {
                $directory = ($Arguments[3] -split 'mkdir -p ', 2)[1]
                [void]$state.RemotePaths.Add($directory)
                if ($FailureStage -eq 'setup') { $exitCode = 12 }
            } elseif ($operation -eq 'logcat' -and $Arguments[3] -eq '-c') {
                if ($FailureStage -eq 'clear') { $exitCode = 13 }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'dex2oat64*') {
                $read = if ($FailureStage -eq 'short-read') { 5 } else { $Size }
                $output = if ($FailureStage -eq 'dex2oat') { @('exit=14', "size=$read") } else { @('exit=0', "size=$read") }
            } elseif ($operation -eq 'logcat' -and $Arguments[3] -eq '-d') {
                if ($FailureStage -eq 'log-read-throw') {
                    throw 'fake ADB threw while reading logcat'
                } elseif ($FailureStage -eq 'log-read') {
                    $exitCode = 15
                } elseif ($FailureStage -eq 'unread') {
                    $output = @("W dex2oat64: Skipping non-existent dex file '/data/local/tmp/hushfacebook-verify-case.apk'")
                } else {
                    $output = @(
                        'I dex2oat64: Verification error in Lfixture/Host;',
                        'I dex2oat64: Verification error in Lfixture/Host;',
                        'W dex2oat: VerifyError in Lfixture/Other;'
                    )
                }
            } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'rm -rf *') {
                $state.CleanupCalls++
                $path = $Arguments[3].Substring('rm -rf '.Length)
                if ($CleanupThrowNumber -eq $state.CleanupCalls) {
                    throw "fake ADB threw while removing $path"
                } elseif ($CleanupFailureNumber -eq $state.CleanupCalls) {
                    $exitCode = 91
                } else {
                    [void]$state.RemotePaths.Remove($path)
                }
            } else {
                throw "Unexpected fake ADB call through ${Executable}: $line"
            }
            [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
        }.GetNewClosure()
        [pscustomobject]@{ State = $state; Invoker = $invoker }
    }

    function Assert-BothCleanupCalls {
        param($State, [string]$Context)
        $expected = @(
            '-s SERIAL shell rm -rf /data/local/tmp/hushfacebook-verify-case',
            '-s SERIAL shell rm -rf /data/local/tmp/hushfacebook-verify-case.apk'
        )
        foreach ($call in $expected) {
            Assert-True (@($State.Calls | Where-Object { $_ -eq $call }).Count -eq 1) `
                "$Context did not attempt cleanup exactly once: $call"
        }
    }

    $failures = [ordered]@{
        push = 'Could not push case to SERIAL (ADB exit 11).'
        setup = 'Could not prepare the verifier output directory for case on SERIAL (ADB exit 12).'
        clear = 'Could not clear logcat on SERIAL before verifying case (ADB exit 13).'
        dex2oat = 'dex2oat on case exited 14.'
        'log-read' = 'Could not read logcat on SERIAL after verifying case (ADB exit 15).'
        'short-read' = "dex2oat on case read a file of 5 bytes on SERIAL, not the $fixtureSize bytes pushed."
        unread = "dex2oat did not read case on SERIAL: Skipping non-existent dex file '/data/local/tmp/hushfacebook-verify-case.apk'"
    }
    foreach ($stage in $failures.Keys) {
        $fake = New-FakeAdb -FailureStage $stage
        $caught = $null
        try {
            [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
                -Local $fixture -Label 'case' -AdbInvoker $fake.Invoker)
        } catch {
            $caught = $_
        }
        Assert-True ($null -ne $caught) "$stage failure was accepted."
        Assert-True ($caught.Exception.Message -eq $failures[$stage]) `
            "$stage returned a different failure: $($caught.Exception.Message)"
        Assert-BothCleanupCalls -State $fake.State -Context $stage
        Assert-True ($fake.State.RemotePaths.Count -eq 0) "$stage left fake remote files behind."
    }

    $thrownFailure = New-FakeAdb -FailureStage 'log-read-throw'
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $thrownFailure.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq 'fake ADB threw while reading logcat') `
        'A thrown ADB error was replaced or accepted.'
    Assert-BothCleanupCalls -State $thrownFailure.State -Context 'thrown log retrieval failure'
    Assert-True ($thrownFailure.State.RemotePaths.Count -eq 0) `
        'A thrown ADB error left fake remote files behind.'

    $primaryWithCleanupFailure = New-FakeAdb -FailureStage 'clear' -CleanupThrowNumber 1
    $warnings = @()
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $primaryWithCleanupFailure.Invoker `
            -WarningVariable warnings)
    } catch {
        $caught = $_
    }
    Assert-True ($caught.Exception.Message -eq $failures.clear) `
        'A cleanup failure replaced the original verification failure.'
    Assert-BothCleanupCalls -State $primaryWithCleanupFailure.State -Context 'secondary cleanup failure'
    Assert-True (($warnings | ForEach-Object { "$_" }) -join "`n" -match 'cleanup also failed') `
        'A cleanup failure beside a primary failure produced no secondary diagnostic.'

    $success = New-FakeAdb
    $tally = Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
        -Local $fixture -Label 'case' -AdbInvoker $success.Invoker
    Assert-True ($tally.Count -eq 2 -and $tally['Verification error in Lfixture/Host;'] -eq 2 -and
        $tally['VerifyError in Lfixture/Other;'] -eq 1) 'The successful fake verifier tally was wrong.'
    Assert-BothCleanupCalls -State $success.State -Context 'success'
    Assert-True ($success.State.RemotePaths.Count -eq 0) 'The successful run left fake remote files behind.'

    $successWithCleanupFailure = New-FakeAdb -CleanupFailureNumber 1
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local $fixture -Label 'case' -AdbInvoker $successWithCleanupFailure.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($null -ne $caught -and $caught.Exception.Message -like 'Verifier cleanup failed.*') `
        'A cleanup failure after successful verification was accepted.'
    Assert-BothCleanupCalls -State $successWithCleanupFailure.State -Context 'success cleanup failure'

    # As the verifier runs them (script-wiring.ps1), so help text, log lines, functions nothing
    # calls and dead branches can't stand in for the calls: the helper dot-sourced, and a tally of
    # the clean APK and one of the patched APK, compared with each other.
    $verifier = Join-Path $PSScriptRoot 'verify-injected-registers.ps1'
    $prePush = Join-Path $PSScriptRoot 'pre-push.ps1'
    Assert-True ((Test-DotSourcesFile $verifier 'injected-register-device.ps1') -and (Test-TalliesBothSides $verifier)) `
        'The device verifier does not use the cleanup helper for both sides.'
    # The line that adds the suite to the run, not the path list that only decides when to run it.
    Assert-True (Test-PushGateRunsSuite $prePush 'scripts/test-injected-register-device.ps1') `
        'The push gate does not run the verifier cleanup fixtures.'

    # The checks themselves, on copies with the wiring taken out in ways that leave its text
    # behind. Each copy has to fail the check it was made for, and an untouched copy has to pass.
    $wiringCopy = Join-Path ([System.IO.Path]::GetTempPath()) `
        ("hushfacebook-device-wiring-" + [guid]::NewGuid().ToString('N') + '.ps1')
    try {
        $verifierText = [System.IO.File]::ReadAllText($verifier)
        $prePushSource = [System.IO.File]::ReadAllText($prePush)
        $dotSourcesDevice = { param($Path) Test-DotSourcesFile $Path 'injected-register-device.ps1' }
        $talliesBothSides = { param($Path) Test-TalliesBothSides $Path }
        $gateRunsSuite = { param($Path) Test-PushGateRunsSuite $Path 'scripts/test-injected-register-device.ps1' }
        $deviceDotSource = { param($Node)
            $Node -is [System.Management.Automation.Language.CommandAst] -and
            $Node.InvocationOperator -eq [System.Management.Automation.Language.TokenKind]::Dot -and
            $Node.Extent.Text -like '*injected-register-device.ps1*' }
        # The device half's first statement, which the copies below put a statement in front of.
        $deviceHalfStart = { param($Node)
            $Node -is [System.Management.Automation.Language.CommandAst] -and
            $Node.Extent.Text -like '*running the device verifier on*' }
        $beforeDeviceHalf = { param([string]$Statement)
            Edit-ScriptNode $verifierText $deviceHalfStart { param($Text) "$Statement`n    $Text" }.GetNewClosure() }
        # And the other places the tally copies put a statement: before the compare, and after
        # the helpers are dot-sourced.
        $compareStatement = { param($Node)
            $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $Node.Extent.Text -like '$comparison = Compare-VerifierTallies*' }
        $beforeCompare = { param([string]$Statement)
            Edit-ScriptNode $verifierText $compareStatement { param($Text) "$Statement`n    $Text" }.GetNewClosure() }
        $afterHelpers = { param([string]$Statement)
            Edit-ScriptNode $verifierText $deviceDotSource { param($Text) "$Text`n$Statement" }.GetNewClosure() }
        $approveAll = '[pscustomobject]@{ Valid = $true; CleanTotal = 0; PatchedTotal = 0; Deltas = @() }'
        $wiringCases = @(
            @{ Name = 'the untouched verifier'; Check = $dotSourcesDevice; Expect = $true; Text = $verifierText }
            @{ Name = 'the untouched verifier'; Check = $talliesBothSides; Expect = $true; Text = $verifierText }
            @{ Name = 'the untouched pre-push.ps1'; Check = $gateRunsSuite; Expect = $true; Text = $prePushSource }
            @{ Name = 'both tallies taken of the patched APK'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.CommandAst] -and
                    $Node.GetCommandName() -eq 'Invoke-AndroidVerifierTally' -and $Node.Extent.Text -like '*$cleanBase*'
                } { param($Text) $Text.Replace('$cleanBase', '$PatchedApk') } }
            @{ Name = 'both tallies taken into one variable'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                    $Node.Clauses[0].Item1.Extent.Text -eq '$Serial' } { param($Text)
                    $Text.Replace('$cleanTally', '$tally').Replace('$patchedTally', '$tally') } }
            @{ Name = 'the clean tally written over with the patched one before the compare'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$comparison = Compare-VerifierTallies*' } { param($Text)
                    "`$cleanTally = `$patchedTally`n    $Text" } }
            @{ Name = 'Compare-VerifierTallies defined in the verifier itself'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text)
                    "$Text`nfunction Compare-VerifierTallies { param(`$Clean, `$Patched)`n" +
                    "    [pscustomobject]@{ Valid = `$true; CleanTotal = 0; PatchedTotal = 0; Deltas = @() } }" } }
            @{ Name = 'Invoke-AndroidVerifierTally defined in the verifier itself'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text)
                    "$Text`nfunction Invoke-AndroidVerifierTally { @{} }" } }
            @{ Name = 'the device half in a function nothing calls'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.IfStatementAst] -and
                    $Node.Clauses[0].Item1.Extent.Text -eq '$Serial' } { param($Text) "function Invoke-DeviceHalf {`n$Text`n}" } }
            @{ Name = 'the device helper dot-sourced in a dead branch'; Check = $dotSourcesDevice
                Text = Edit-ScriptNode $verifierText $deviceDotSource { param($Text) "if (`$false) { $Text }" } }
            # Statements that end their block with the exit nested inside them. First in the device
            # half, each leaves every -Serial run passing with no tally taken. The copies expected
            # to pass keep those rules off statements that can still carry on: a catch that
            # finishes, a clause that matches and finishes, a break out of the do, and a return
            # out of the script block.
            @{ Name = 'the device half after try { exit 0 } finally { }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { exit 0 } finally { }' }
            @{ Name = 'the device half after try { exit 0 } catch { }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { exit 0 } catch { }' }
            @{ Name = "the device half after try { throw 'skip' } catch { exit 0 }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf "try { throw 'skip' } catch { exit 0 }" }
            @{ Name = 'the device half after try { } finally { exit 0 }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'try { } finally { exit 0 }' }
            @{ Name = "the device half after try { throw 'skip' } catch { }"; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf "try { throw 'skip' } catch { }" }
            @{ Name = 'the device half after switch (1) { default { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'switch (1) { default { exit 0 } }' }
            @{ Name = 'the device half after switch (1) { 1 { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'switch (1) { 1 { exit 0 } }' }
            @{ Name = "the device half after switch (2) { 1 { 'one' } default { exit 0 } }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf "switch (2) { 1 { 'one' } default { exit 0 } }" }
            @{ Name = "the device half after switch (1) { 1 { 'one' } default { exit 0 } }"; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf "switch (1) { 1 { 'one' } default { exit 0 } }" }
            @{ Name = 'the device half after a switch with no default'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (1) { 2 { exit 0 } }' }
            @{ Name = 'the device half after a switch a break can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (1) { default { if ($Serial) { break }; exit 0 } }' }
            @{ Name = 'the device half after a switch over @()'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'switch (@()) { default { exit 0 } }' }
            @{ Name = 'the device half after do { exit 0 } while ($false)'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'do { exit 0 } while ($false)' }
            @{ Name = 'the device half after do { exit 0 } until ($true)'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'do { exit 0 } until ($true)' }
            @{ Name = 'the device half after a do a break can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf 'do { if ($Serial) { break }; exit 0 } while ($false)' }
            @{ Name = 'the device half after & { exit 0 }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { exit 0 }' }
            @{ Name = "the device half after . { throw 'skip' }"; Check = $talliesBothSides
                Text = & $beforeDeviceHalf ". { throw 'skip' }" }
            @{ Name = 'the device half after & { begin { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { begin { exit 0 } }' }
            @{ Name = 'the device half after & { process { exit 0 } }'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '& { process { exit 0 } }' }
            @{ Name = 'the device half after @() | & { process { exit 0 } }, which runs no process block'
                Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf '@() | & { process { exit 0 } }' }
            @{ Name = 'the device half after a script block a return can leave'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeDeviceHalf '& { if ($Serial) { return }; exit 0 }' }
            # A tally input or a tally written in a way that isn't an assignment to the tally
            # variable, or a helper replaced without a function statement. Each leaves every
            # -Serial run comparing a build with itself, or not comparing at all. The copies
            # expected to pass write other names the same ways.
            @{ Name = 'the clean input set to the patched APK in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '$cleanBase = $PatchedApk' }
            @{ Name = 'the patched input set to the clean one in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf '$PatchedApk = $cleanBase' }
            @{ Name = 'the clean input taken from the patched APK instead of Get-BaseApk'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$cleanBase = Get-BaseApk*' } { param($Text) '$cleanBase = $PatchedApk' } }
            @{ Name = 'the clean input taken again, from the patched APK, before the DexDiff run'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -eq '$diff = Invoke-DexDiff' } { param($Text)
                    "`$cleanBase = Get-BaseApk -Apk `$PatchedApk -Destination (Join-Path `$work 'again.apk')`n$Text" } }
            @{ Name = 'the clean input taken from a command other than Get-BaseApk'; Check = $talliesBothSides
                Text = Edit-ScriptNode $verifierText { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Extent.Text -like '$cleanBase = Get-BaseApk*' } { param($Text) '$cleanBase = Write-Output $PatchedApk' } }
            @{ Name = 'the patched input set to the clean one by a function the device half calls'; Check = $talliesBothSides
                Text = Edit-ScriptNode (& $afterHelpers 'function Reset-Input { $script:PatchedApk = $script:cleanBase }') `
                    $deviceHalfStart { param($Text) "Reset-Input`n    $Text" } }
            @{ Name = 'the clean input written with -OutVariable in the device half'; Check = $talliesBothSides
                Text = & $beforeDeviceHalf 'Write-Output $PatchedApk -OutVariable cleanBase | Out-Null' }
            @{ Name = 'the clean tally written over with Set-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Set-Variable -Name cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over with sv'; Check = $talliesBothSides
                Text = & $beforeCompare 'sv cleanTally $patchedTally' }
            @{ Name = 'the clean tally written over with Microsoft.PowerShell.Utility\Set-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Microsoft.PowerShell.Utility\Set-Variable -Name cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over with New-Variable -Force'; Check = $talliesBothSides
                Text = & $beforeCompare 'New-Variable -Name cleanTally -Value $patchedTally -Force' }
            @{ Name = 'the clean tally emptied with Clear-Variable'; Check = $talliesBothSides
                Text = & $beforeCompare 'Clear-Variable -Name cleanTally' }
            @{ Name = 'the clean tally written over through the variable: drive'; Check = $talliesBothSides
                Text = & $beforeCompare 'Set-Item -Path variable:cleanTally -Value $patchedTally' }
            @{ Name = 'the clean tally written over by a foreach over the patched one'; Check = $talliesBothSides
                Text = & $beforeCompare 'foreach ($cleanTally in @($patchedTally)) { }' }
            @{ Name = 'a tally written over with Set-Variable of a name built at run time'; Check = $talliesBothSides
                Text = & $beforeCompare "Set-Variable -Name ('clean' + 'Tally') -Value `$patchedTally" }
            @{ Name = 'Compare-VerifierTallies replaced through ${function:}'; Check = $talliesBothSides
                Text = & $afterHelpers "`${function:Compare-VerifierTallies} = { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'Compare-VerifierTallies replaced with Set-Item function:'; Check = $talliesBothSides
                Text = & $afterHelpers "Set-Item -Path function:Compare-VerifierTallies -Value { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'Invoke-AndroidVerifierTally replaced with New-Item function:'; Check = $talliesBothSides
                Text = & $afterHelpers 'New-Item -Path function: -Name Invoke-AndroidVerifierTally -Value { @{} } -Force | Out-Null' }
            @{ Name = 'Compare-VerifierTallies defined in the verifier as script:Compare-VerifierTallies'; Check = $talliesBothSides
                Text = & $afterHelpers "function script:Compare-VerifierTallies { param(`$Clean, `$Patched) $approveAll }" }
            @{ Name = 'the clean tally written over through $script:cleanTally'; Check = $talliesBothSides
                Text = & $beforeCompare '$script:cleanTally = $patchedTally' }
            @{ Name = 'Compare-VerifierTallies made an alias of a function of the script'; Check = $talliesBothSides
                Text = & $afterHelpers "function Approve-Tallies { param(`$Clean, `$Patched) $approveAll }`nSet-Alias -Name Compare-VerifierTallies -Value Approve-Tallies" }
            @{ Name = 'another variable set with Set-Variable before the compare'; Check = $talliesBothSides; Expect = $true
                Text = & $beforeCompare 'Set-Variable -Name note -Value $patchedTally' }
            @{ Name = 'another function set up through the function: drive'; Check = $talliesBothSides; Expect = $true
                Text = & $afterHelpers "Set-Item -Path function:Show-Note -Value { 'note' }" }
            @{ Name = 'an alias of another name'; Check = $talliesBothSides; Expect = $true
                Text = & $afterHelpers 'Set-Alias -Name Show-Note -Value Write-Host' }
            @{ Name = 'the suite line in a block comment'; Check = $gateRunsSuite
                Text = Edit-ScriptNode $prePushSource { param($Node)
                    $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                    $Node.Left.Extent.Text -eq '$suites' -and
                    $Node.Extent.Text -like "*'scripts/test-injected-register-device.ps1'*" } { param($Text) "<#`n$Text`n#>" } }
        )
        $wiringFailures = @()
        foreach ($case in $wiringCases) {
            [System.IO.File]::WriteAllText($wiringCopy, $case.Text)
            $expect = $case.ContainsKey('Expect') -and $case.Expect
            if ((& $case.Check $wiringCopy) -ne $expect) {
                $wiringFailures += "$($case.Name): the check said $(-not $expect)"
            }
        }
        if ($wiringFailures.Count -ne 0) { throw ("The wiring checks misjudged:`n" + ($wiringFailures -join "`n")) }
    } finally {
        [System.IO.File]::Delete($wiringCopy)
    }

} finally {
    [System.IO.File]::Delete($fixture)
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] injected-register device cleanup contracts passed'
