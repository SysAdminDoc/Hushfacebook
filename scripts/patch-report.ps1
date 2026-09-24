<#
.SYNOPSIS
    The checks that say whether a morphe-desktop run actually patched anything.

.DESCRIPTION
    Dot-sourced by verify-all-patches.ps1, patch-for-device.ps1 and measure-patch-heap.ps1. They
    ask the same question of the same CLI, and two used to carry their own copy of these functions. The
    copies drifted: the verification script learned on 2026-09-08 that morphe-desktop 1.15.0
    omits the top-level success field when it is true, and the heap script did not, so every
    successful run it measured came back invalid. One copy is why that cannot happen again.

    The CLI writes its result file from a finally block, so a file exists even after a failed
    compile or save. Nothing here treats the file's existence as the answer.
#>

function Test-ApkFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try {
            $dex = @($archive.Entries | Where-Object { $_.FullName -match '(^|/)classes\d*\.dex$' })
            $manifest = @($archive.Entries | Where-Object { $_.FullName -eq 'AndroidManifest.xml' })
            return $dex.Count -gt 0 -and $manifest.Count -gt 0
        } finally {
            $archive.Dispose()
        }
    } catch {
        return $false
    }
}

function Get-ReportPatchNames {
    param([object]$Entries)
    $names = New-Object System.Collections.Generic.List[string]
    foreach ($entry in @($Entries)) {
        if ($null -eq $entry) { continue }
        if ($entry -is [string]) {
            $names.Add([string]$entry)
            continue
        }
        $name = $entry.PSObject.Properties['name']
        if ($null -ne $name -and $null -ne $name.Value) {
            $names.Add([string]$name.Value)
        }
    }
    return $names.ToArray()
}

function Get-PatchDependencyNames {
    param([object]$PatchList, [string[]]$RequestedNames)

    $patchesProperty = $PatchList.PSObject.Properties['patches']
    if ($null -eq $patchesProperty) { throw 'The patch list has no patches.' }

    $patchesByName = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($patch in @($patchesProperty.Value)) {
        if ($null -eq $patch) { throw 'The patch list contains a null patch.' }
        $nameProperty = $patch.PSObject.Properties['name']
        if ($null -eq $nameProperty -or [string]::IsNullOrWhiteSpace([string]$nameProperty.Value)) {
            throw 'The patch list contains a patch without a name.'
        }
        $name = [string]$nameProperty.Value
        if ($patchesByName.ContainsKey($name)) { throw "The patch list repeats patch name $name." }
        $patchesByName.Add($name, $patch)
    }

    $requested = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $pending = [System.Collections.Generic.Queue[string]]::new()
    foreach ($name in @($RequestedNames)) {
        if ([string]::IsNullOrWhiteSpace($name)) { throw 'A requested patch has no name.' }
        if (-not $patchesByName.ContainsKey($name)) { throw "Requested patch $name is not in the patch list." }
        if ($requested.Add($name)) { $pending.Enqueue($name) }
    }

    $visited = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $dependencySet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $dependencies = New-Object System.Collections.Generic.List[string]
    while ($pending.Count -gt 0) {
        $name = $pending.Dequeue()
        if (-not $visited.Add($name) -or -not $patchesByName.ContainsKey($name)) { continue }
        $patch = $patchesByName[$name]
        $dependencyProperty = $patch.PSObject.Properties['dependencies']
        if ($null -eq $dependencyProperty) { continue }
        foreach ($value in @($dependencyProperty.Value)) {
            $dependency = [string]$value
            if ([string]::IsNullOrWhiteSpace($dependency)) {
                throw "Patch $name contains a dependency without a name."
            }
            if (-not $requested.Contains($dependency) -and $dependencySet.Add($dependency)) {
                $dependencies.Add($dependency)
            }
            if ($patchesByName.ContainsKey($dependency)) { $pending.Enqueue($dependency) }
        }
    }
    return $dependencies.ToArray()
}

function Test-ReportedPatchNames {
    param(
        [string[]]$Expected,
        [string[]]$Actual,
        [string[]]$AllowedDependencies = @()
    )
    $expectedCounts = [System.Collections.Generic.Dictionary[string, int]]::new([System.StringComparer]::Ordinal)
    foreach ($name in @($Expected)) {
        if ([string]::IsNullOrWhiteSpace($name)) { return $false }
        if (-not $expectedCounts.ContainsKey($name)) { $expectedCounts[$name] = 0 }
        $expectedCounts[$name]++
    }
    $allowed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($name in @($AllowedDependencies)) {
        if ([string]::IsNullOrWhiteSpace($name)) { return $false }
        [void]$allowed.Add($name)
    }
    $actualCounts = [System.Collections.Generic.Dictionary[string, int]]::new([System.StringComparer]::Ordinal)
    foreach ($name in @($Actual)) {
        if ([string]::IsNullOrWhiteSpace($name)) { return $false }
        if (-not $actualCounts.ContainsKey($name)) { $actualCounts[$name] = 0 }
        $actualCounts[$name]++
    }
    foreach ($name in $expectedCounts.Keys) {
        if (-not $actualCounts.ContainsKey($name) -or $actualCounts[$name] -ne $expectedCounts[$name]) {
            return $false
        }
    }
    foreach ($name in $actualCounts.Keys) {
        if ($expectedCounts.ContainsKey($name)) { continue }
        if (-not $allowed.Contains($name) -or $actualCounts[$name] -ne 1) { return $false }
    }
    return $true
}

function Test-TrueBoolean {
    param([object]$Value)
    return $Value -is [bool] -and [bool]$Value
}

function Test-PatchingReport {
    param(
        [object]$Report,
        [string[]]$ExpectedNames,
        [string[]]$AllowedDependencyNames = @(),
        [string]$OutputPath,
        [string]$ExpectedPackageName,
        [string]$ExpectedPackageVersion
    )
    if ($null -eq $Report) { return [pscustomobject]@{ Valid = $false; Reason = 'missing or invalid result JSON' } }

    # The top-level success field defaults to true in morphe-desktop 1.15.0, and kotlinx
    # serialization omits a field that equals its default, so a successful run has no such field
    # while a failed one writes "success": false. It is therefore checked when present and not
    # demanded when absent. If a later CLI changes that default, absence stops meaning success:
    # read PatchingResult before relying on this. The step check below is the independent one,
    # and it is what catches the case these scripts exist for, a result file written from a
    # finally block after a failed compile, because the step that failed reports success false.
    $success = $Report.PSObject.Properties['success']
    $steps = @($Report.patchingSteps)
    $stepsOk = $steps.Count -gt 0 -and @($steps | Where-Object {
        $property = $_.PSObject.Properties['success']
        $null -eq $property -or -not (Test-TrueBoolean $property.Value)
    }).Count -eq 0
    $failed = @($Report.failedPatches)
    $applied = Get-ReportPatchNames $Report.appliedPatches
    $namesOk = Test-ReportedPatchNames -Expected $ExpectedNames -Actual $applied `
        -AllowedDependencies $AllowedDependencyNames
    $targetOk = $null -ne $Report.PSObject.Properties['packageName'] -and
        $null -ne $Report.PSObject.Properties['packageVersion'] -and
        [string]::Equals([string]$Report.packageName, $ExpectedPackageName, [System.StringComparison]::Ordinal) -and
        [string]::Equals([string]$Report.packageVersion, $ExpectedPackageVersion, [System.StringComparison]::Ordinal)
    $outputOk = Test-ApkFile $OutputPath
    $successOk = $null -eq $success -or (Test-TrueBoolean $success.Value)
    $valid = $successOk -and $stepsOk -and
        $failed.Count -eq 0 -and $namesOk -and $targetOk -and $outputOk
    $reason = if ($valid) { 'ok' } else {
        $parts = New-Object System.Collections.Generic.List[string]
        if (-not $successOk) { $parts.Add('report.success is present and is not true') }
        if (-not $stepsOk) { $parts.Add('a patching step failed or is missing') }
        if ($failed.Count -ne 0) { $parts.Add("$($failed.Count) failed patches") }
        if (-not $namesOk) {
            $parts.Add('an expected patch is missing, duplicated, or joined by an undeclared dependency')
        }
        if (-not $targetOk) { $parts.Add('unexpected package or version') }
        if (-not $outputOk) { $parts.Add('saved APK is missing or invalid') }
        $parts -join '; '
    }
    return [pscustomobject]@{
        Valid = $valid
        Reason = $reason
        Applied = $applied.Count
        Failed = $failed.Count
    }
}
