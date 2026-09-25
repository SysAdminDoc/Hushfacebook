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
