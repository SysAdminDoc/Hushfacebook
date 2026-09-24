function Invoke-HushfacebookAdbCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [scriptblock]$Invoker
    )

    if ($Invoker) {
        $result = & $Invoker $Adb $Arguments
        if ($null -eq $result -or $null -eq $result.ExitCode) {
            throw 'The ADB invoker returned no exit code.'
        }
        return [pscustomobject]@{
            ExitCode = [int]$result.ExitCode
            Output = @($result.Output | ForEach-Object { "$_" })
        }
    }

    $PSNativeCommandUseErrorActionPreference = $false
    $output = @(& $Adb @Arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

function Format-HushfacebookAdbFailure {
    param([string]$Message, $Result)

    $detail = @($Result.Output | Select-Object -First 3) -join '; '
    $suffix = if ([string]::IsNullOrWhiteSpace($detail)) { '' } else { " Output: $detail" }
    "$Message (ADB exit $($Result.ExitCode)).$suffix"
}

function Invoke-AndroidVerifierTally {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Local,
        [Parameter(Mandatory = $true)][string]$Label,
        [scriptblock]$AdbInvoker
    )

    if ($Label -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$') {
        throw "Invalid verifier label: $Label"
    }

    $remote = "/data/local/tmp/hushfacebook-verify-$Label.apk"
    $directory = "/data/local/tmp/hushfacebook-verify-$Label"
    $primaryFailure = $null
    try {
        $push = Invoke-HushfacebookAdbCommand -Adb $Adb -Invoker $AdbInvoker `
            -Arguments @('-s', $Serial, 'push', $Local, $remote)
        if ($push.ExitCode -ne 0) {
            throw (Format-HushfacebookAdbFailure -Message "Could not push $Label to $Serial" -Result $push)
        }

        $setup = Invoke-HushfacebookAdbCommand -Adb $Adb -Invoker $AdbInvoker `
            -Arguments @('-s', $Serial, 'shell', "rm -rf $directory && mkdir -p $directory")
        if ($setup.ExitCode -ne 0) {
            throw (Format-HushfacebookAdbFailure `
                -Message "Could not prepare the verifier output directory for $Label on $Serial" `
                -Result $setup)
        }

        $clear = Invoke-HushfacebookAdbCommand -Adb $Adb -Invoker $AdbInvoker `
            -Arguments @('-s', $Serial, 'logcat', '-c')
        if ($clear.ExitCode -ne 0) {
            throw (Format-HushfacebookAdbFailure `
                -Message "Could not clear logcat on $Serial before verifying $Label" `
                -Result $clear)
        }

        $dexCommand = "dex2oat64 --dex-file=$remote --oat-file=$directory/out.oat " +
            "--output-vdex=$directory/out.vdex --instruction-set=arm64 " +
            '--compiler-filter=verify --runtime-arg -Xmx1024m -j4; echo exit=$?'
        $dex = Invoke-HushfacebookAdbCommand -Adb $Adb -Invoker $AdbInvoker `
            -Arguments @('-s', $Serial, 'shell', $dexCommand)
        if ($dex.ExitCode -ne 0) {
            throw (Format-HushfacebookAdbFailure `
                -Message "Could not run dex2oat on $Label on $Serial" -Result $dex)
        }
        $dexExit = $null
        foreach ($line in $dex.Output) {
            if ($line -match 'exit=(-?\d+)') { $dexExit = [int]$Matches[1] }
        }
        if ($null -eq $dexExit) {
            throw "dex2oat on $Label reported no exit code; the shell did not finish."
        }
        if ($dexExit -ne 0) { throw "dex2oat on $Label exited $dexExit." }

        $logResult = Invoke-HushfacebookAdbCommand -Adb $Adb -Invoker $AdbInvoker `
            -Arguments @('-s', $Serial, 'logcat', '-d')
        if ($logResult.ExitCode -ne 0) {
            throw (Format-HushfacebookAdbFailure `
                -Message "Could not read logcat on $Serial after verifying $Label" `
                -Result $logResult)
        }

        # The method in the verifier line is its identity. Timestamps and process IDs differ
        # between runs and are stripped. Counts remain significant.
        $messages = @($logResult.Output |
            Where-Object { $_ -match 'dex2oat' } |
            Where-Object { $_ -match '(failed lock verification|Verification error|Rejecting class|VerifyError)' } |
            ForEach-Object { ($_ -replace '^.*dex2oat[0-9]*:\s*', '').Trim() })
        $tally = [System.Collections.Generic.Dictionary[string, int]]::new(
            [System.StringComparer]::Ordinal)
        foreach ($message in $messages) {
            if ($tally.ContainsKey($message)) { $tally[$message]++ }
            else { $tally.Add($message, 1) }
        }
        return $tally
    } catch {
        $primaryFailure = $_
        throw
    } finally {
        $cleanupFailures = [System.Collections.Generic.List[string]]::new()
        foreach ($remotePath in @($directory, $remote)) {
            try {
                $cleanup = Invoke-HushfacebookAdbCommand -Adb $Adb -Invoker $AdbInvoker `
                    -Arguments @('-s', $Serial, 'shell', "rm -rf $remotePath")
                if ($cleanup.ExitCode -ne 0) {
                    $cleanupFailures.Add((Format-HushfacebookAdbFailure `
                        -Message "Could not remove $remotePath" -Result $cleanup))
                }
            } catch {
                $cleanupFailures.Add("Could not remove ${remotePath}: $($_.Exception.Message)")
            }
        }
        if ($cleanupFailures.Count -ne 0) {
            $message = $cleanupFailures -join ' '
            if ($null -ne $primaryFailure) {
                Write-Warning -WarningAction Continue `
                    "Verifier cleanup also failed after the original error. $message"
            } else {
                throw "Verifier cleanup failed. $message"
            }
        }
    }
}
