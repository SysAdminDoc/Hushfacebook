<#
.SYNOPSIS
    Exercise ResourceTableCheck.java against small APKs that aapt2 builds.

.DESCRIPTION
    The stock APK is Facebook's own package, id 0x7f: two colors (one with a night value), a theme
    on a base theme, and one layout. A resource patch has Morphe rebuild the whole table, and a
    layout the rebuild drops only fails when the app inflates it (Hushfeed upstream #84, where
    this check came from). Each patched APK moves one thing. An unchanged table, a rewritten color
    and a renamed entry pass and are reported. A lost layout, a layout file missing from the
    archive, a changed layout file, a lost style item, a changed parent, a lost night value, a
    reference to nothing, a renamed type and a missing package fail, and each failure names what
    it lost. Facebook ships as a split bundle, and merging it into one APK drops the split
    descriptor bundletool wrote (xml/splits0): that loss passes and is reported, and any other
    lost xml resource still fails.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java,
    [string]$DesktopJar,
    [string]$Aapt2
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Native calls run with Continue: under Stop, Windows PowerShell 5.1 (the hook's fallback host)
# turns any stderr line, a JDK warning included, into a terminating error. The exit code is judged.
function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $ErrorActionPreference = 'Continue'
    $output = @(& $Program @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        throw "$Description exited $LASTEXITCODE.`n$($output -join "`n")"
    }
}

$package = 'com.facebook.katana'
$manifest = @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$package">
    <application android:theme="@style/SearchTheme" />
</manifest>
"@
$colors = @'
<resources>
    <color name="accent">#ffff3b5c</color>
    <color name="surface">#ff121212</color>
</resources>
'@
$nightColors = @'
<resources>
    <color name="surface">#ff000000</color>
</resources>
'@
$styles = @'
<resources>
    <style name="BaseTheme">
        <item name="android:windowNoTitle">true</item>
    </style>
    <style name="SearchTheme" parent="BaseTheme">
        <item name="android:windowBackground">@color/surface</item>
        <item name="android:textColorPrimary">@color/accent</item>
    </style>
</resources>
'@
$layout = @'
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:background="@color/surface" />
'@

<#
    Links one APK from the files given, keyed by path under res/. With -EmitIds the ids aapt2
    chose are written out; with -StableIds every build keeps the stock build's ids.
#>
function New-ResourceApk {
    param([string]$Name, [System.Collections.IDictionary]$Files, [string]$EmitIds, [string]$StableIds, [string]$PackageId = '0x7f')
    $dir = Join-Path $caseRoot $Name
    foreach ($entry in $Files.GetEnumerator()) {
        $path = Join-Path $dir ($entry.Key -replace '/', [System.IO.Path]::DirectorySeparatorChar)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        [System.IO.File]::WriteAllText($path, $entry.Value, [System.Text.UTF8Encoding]::new($false))
    }
    $manifestPath = Join-Path $dir 'AndroidManifest.xml'
    [System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))
    $compiled = Join-Path $dir 'compiled.zip'
    Invoke-Checked -Program $Aapt2 -Arguments @('compile', '--dir', (Join-Path $dir 'res'), '-o', $compiled) `
        -Description "aapt2 compile for $Name"
    $apk = Join-Path $caseRoot "$Name.apk"
    $link = @('link', '-o', $apk, '-I', $androidJar, '--manifest', $manifestPath,
        '--package-id', $PackageId, '--allow-reserved-package-id')
    if ($EmitIds) { $link += @('--emit-ids', $EmitIds) }
    if ($StableIds) { $link += @('--stable-ids', $StableIds) }
    Invoke-Checked -Program $Aapt2 -Arguments ($link + @($compiled)) -Description "aapt2 link for $Name"
    return $apk
}

function Copy-ApkWith {
    param([string]$From, [string]$Name, [scriptblock]$Change)
    $apk = Join-Path $caseRoot "$Name.apk"
    Copy-Item -LiteralPath $From -Destination $apk
    $archive = [System.IO.Compression.ZipFile]::Open($apk, [System.IO.Compression.ZipArchiveMode]::Update)
    try { & $Change $archive } finally { $archive.Dispose() }
    return $apk
}

function Edit-Table {
    param([string]$Name, [byte[]]$Find, [byte[]]$Replace, [string]$What)
    return Copy-ApkWith -From $stockApk -Name $Name -Change {
        param($archive)
        $entry = $archive.GetEntry('resources.arsc')
        $stream = $entry.Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose() }
        $hits = @()
        for ($i = 0; $i -le $bytes.Length - $Find.Length; $i++) {
            $match = $true
            for ($j = 0; $j -lt $Find.Length; $j++) { if ($bytes[$i + $j] -ne $Find[$j]) { $match = $false; break } }
            if ($match) { $hits += $i }
        }
        if ($hits.Count -ne 1) { throw "expected one $What in the table, found $($hits.Count)" }
        $Replace.CopyTo($bytes, $hits[0])
        $entry.Delete()
        $replacement = $archive.CreateEntry('resources.arsc', [System.IO.Compression.CompressionLevel]::NoCompression)
        $out = $replacement.Open()
        try { $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
    }
}

function Invoke-Check {
    param([string]$Patched, [string]$Name, [string]$Stock = $stockApk)
    $report = Join-Path $caseRoot "$Name-report.txt"
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    $output = @(& $Java '-Xmx1g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'ResourceTableCheck.java') `
        $Stock $Patched $report 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output -join "`n"); Report = $report }
}

# The call lines themselves, not any mention: the help text names the check too, and the gate's
# path list names this suite without running it.
$verifyText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-all-patches.ps1') -Raw
Assert-True ($verifyText -match '(?m)^[^#\r\n]*&\s*\$Java\b[^\r\n]*ResourceTableCheck\.java') `
    'verify-all-patches.ps1 does not run ResourceTableCheck.java.'
$prePushText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pre-push.ps1') -Raw
Assert-True ($prePushText -match "(?m)^\s*\`$suites \+= , @\('scripts/test-resource-table-check\.ps1'") `
    'The push gate does not run the resource table check fixtures.'

$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root
# The platform jar beside the SDK's build-tools: sdk/build-tools/<version>/aapt2 -> sdk/platforms.
$sdk = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $Aapt2))
$androidJar = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -Last 1
if (-not $androidJar) { throw "No platforms/*/android.jar in the SDK at $sdk for aapt2 to link against." }

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase `
    ('hushfacebook-resource-check-' + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + `
    [System.IO.Path]::DirectorySeparatorChar
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $ids = Join-Path $caseRoot 'stock-ids.txt'
    $stockFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values-night/colors.xml' = $nightColors
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
    }
    $stockApk = New-ResourceApk -Name 'stock' -Files $stockFiles -EmitIds $ids
    $idOf = @{}
    foreach ($line in Get-Content -LiteralPath $ids) {
        if ($line -match '^[^:]+:(\S+) = (0x[0-9a-f]{8})$') { $idOf[$Matches[1]] = $Matches[2] }
    }
    Assert-True ($idOf['layout/feed_story'] -like '0x7f*' -and $idOf['color/surface'] -like '0x7f*') `
        "aapt2 did not build package 0x7f: $($idOf.Keys -join ', ')"

    $same = Invoke-Check -Patched $stockApk -Name 'unchanged'
    Assert-True ($same.ExitCode -eq 0) "An unchanged table failed.`n$($same.Output)"
    Assert-True ($same.Output -match 'resolves in the patched table') "An unchanged table was not called whole.`n$($same.Output)"

    $blackFiles = [ordered]@{
        'res/values/colors.xml' = $colors -replace '#ffff3b5c', '#ff000000'
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
        'res/values-night/colors.xml' = $nightColors
    }
    $black = Invoke-Check -Patched (New-ResourceApk -Name 'rewritten' -Files $blackFiles -StableIds $ids) -Name 'rewritten'
    Assert-True ($black.ExitCode -eq 0) "A rewritten color failed the check.`n$($black.Output)"
    Assert-True ($black.Output -match [regex]::Escape("$($idOf['color/accent']) color/accent [default] #ffff3b5c -> #ff000000")) `
        "The rewritten color was not reported with its id and both values.`n$($black.Output)"

    $renamedIds = Join-Path $caseRoot 'renamed-ids.txt'
    [System.IO.File]::WriteAllText($renamedIds, ((Get-Content -LiteralPath $ids -Raw) -replace ':color/accent =', ':color/accent_renamed ='))
    $renamedFiles = [ordered]@{
        'res/values/colors.xml' = $colors -replace '"accent"', '"accent_renamed"'
        'res/values/styles.xml' = $styles -replace '@color/accent<', '@color/accent_renamed<'
        'res/layout/feed_story.xml' = $layout
        'res/values-night/colors.xml' = $nightColors
    }
    $renamed = Invoke-Check -Patched (New-ResourceApk -Name 'renamed' -Files $renamedFiles -StableIds $renamedIds) -Name 'renamed'
    Assert-True ($renamed.ExitCode -eq 0) "A renamed entry that keeps its id failed.`n$($renamed.Output)"
    Assert-True ($renamed.Output -match [regex]::Escape("$($idOf['color/accent']) color/accent is color/accent_renamed")) `
        "The renamed entry was not reported.`n$($renamed.Output)"

    $noLayoutFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values/styles.xml' = $styles
        'res/values-night/colors.xml' = $nightColors
    }
    $lost = Invoke-Check -Patched (New-ResourceApk -Name 'lost-layout' -Files $noLayoutFiles -StableIds $ids) -Name 'lost-layout'
    Assert-True ($lost.ExitCode -eq 1) "A table that lost layout/feed_story passed.`n$($lost.Output)"
    Assert-True ($lost.Output -match [regex]::Escape("FAIL $($idOf['layout/feed_story']) layout/feed_story: not in the patched table")) `
        "The lost layout's failure did not name its id.`n$($lost.Output)"
    Assert-True ((Get-Content -LiteralPath $lost.Report -Raw) -match [regex]::Escape($idOf['layout/feed_story'])) `
        'The report file did not name the lost id.'

    $noFile = Copy-ApkWith -From $stockApk -Name 'lost-file' -Change {
        param($archive)
        $entry = $archive.GetEntry('res/layout/feed_story.xml')
        if (-not $entry) { throw 'the stock APK has no res/layout/feed_story.xml to remove' }
        $entry.Delete()
    }
    $file = Invoke-Check -Patched $noFile -Name 'lost-file'
    Assert-True ($file.ExitCode -eq 1) "A layout whose file left the archive passed.`n$($file.Output)"
    Assert-True ($file.Output -match [regex]::Escape("FAIL $($idOf['layout/feed_story']) layout/feed_story [default]: names res/layout/feed_story.xml, which is not in the patched archive")) `
        "The missing file's failure did not name the id and the path.`n$($file.Output)"

    $noItemFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values/styles.xml' = $styles -replace '(?m)^\s*<item name="android:textColorPrimary">@color/accent</item>\r?\n', ''
        'res/layout/feed_story.xml' = $layout
        'res/values-night/colors.xml' = $nightColors
    }
    $item = Invoke-Check -Patched (New-ResourceApk -Name 'lost-item' -Files $noItemFiles -StableIds $ids) -Name 'lost-item'
    Assert-True ($item.ExitCode -eq 1) "A style that lost an item passed.`n$($item.Output)"
    Assert-True ($item.Output -match ([regex]::Escape("FAIL $($idOf['style/SearchTheme']) style/SearchTheme [default] item 0x01010036") + '.*the item is gone')) `
        "The lost item's failure did not name the style and the attr.`n$($item.Output)"

    $noNightFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
    }
    $night = Invoke-Check -Patched (New-ResourceApk -Name 'lost-night' -Files $noNightFiles -StableIds $ids) -Name 'lost-night'
    Assert-True ($night.ExitCode -eq 1) "A color that lost its night value passed.`n$($night.Output)"
    Assert-True ($night.Output -match ([regex]::Escape("FAIL $($idOf['color/surface']) color/surface [night") + '[^\]]*\]: the value is gone')) `
        "The lost night value's failure did not name the id and the configuration.`n$($night.Output)"

    $orphanFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values-night/colors.xml' = $nightColors
        'res/values/styles.xml' = $styles -replace ' parent="BaseTheme"', ''
        'res/layout/feed_story.xml' = $layout
    }
    $orphan = Invoke-Check -Patched (New-ResourceApk -Name 'lost-parent' -Files $orphanFiles -StableIds $ids) -Name 'lost-parent'
    Assert-True ($orphan.ExitCode -eq 1) "A style that lost its parent passed.`n$($orphan.Output)"
    Assert-True ($orphan.Output -match ([regex]::Escape("FAIL $($idOf['style/SearchTheme']) style/SearchTheme [default]: its parent @$($idOf['style/BaseTheme']) became @null"))) `
        "The lost parent's failure did not name the style and both parents.`n$($orphan.Output)"

    $changedFile = Copy-ApkWith -From $stockApk -Name 'changed-file' -Change {
        param($archive)
        $entry = $archive.GetEntry('res/layout/feed_story.xml')
        $stream = $entry.Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose() }
        $entry.Delete()
        $replacement = $archive.CreateEntry('res/layout/feed_story.xml', [System.IO.Compression.CompressionLevel]::NoCompression)
        $out = $replacement.Open()
        try { $out.Write($bytes, 0, $bytes.Length); $out.Write([byte[]](0, 0, 0, 0), 0, 4) } finally { $out.Dispose() }
    }
    $changed = Invoke-Check -Patched $changedFile -Name 'changed-file'
    Assert-True ($changed.ExitCode -eq 1) "A layout file with other bytes passed.`n$($changed.Output)"
    Assert-True ($changed.Output -match [regex]::Escape("FAIL $($idOf['layout/feed_story']) layout/feed_story [default]: res/layout/feed_story.xml is not the file the stock archive holds for it")) `
        "The changed file's failure did not name the id and the path.`n$($changed.Output)"

    # A type renamed in the table's own type pool: every color resolves to a type called colox.
    # The pool is UTF-16 as aapt2 writes type names (length 5, then the characters); UTF-8 is the
    # other layout a pool can have.
    try {
        $typeRenamed = Edit-Table -Name 'renamed-type' -What 'UTF-16 type name "color"' `
            -Find ([byte[]](5, 0, 99, 0, 111, 0, 108, 0, 111, 0, 114, 0)) -Replace ([byte[]](5, 0, 99, 0, 111, 0, 108, 0, 111, 0, 120, 0))
    } catch {
        $typeRenamed = Edit-Table -Name 'renamed-type-utf8' -What 'UTF-8 type name "color"' `
            -Find ([byte[]](5, 5, 99, 111, 108, 111, 114)) -Replace ([byte[]](5, 5, 99, 111, 108, 111, 120))
    }
    $type = Invoke-Check -Patched $typeRenamed -Name 'renamed-type'
    Assert-True ($type.ExitCode -eq 1) "A table whose color type changed name passed.`n$($type.Output)"
    Assert-True ($type.Output -match ([regex]::Escape("FAIL $($idOf['color/accent']) color/accent: is colox/accent in the patched table"))) `
        "The changed type's failure did not name the id and both types.`n$($type.Output)"

    $otherPackage = New-ResourceApk -Name 'other-package' -Files $stockFiles -PackageId '0x7e'
    $package = Invoke-Check -Patched $otherPackage -Name 'other-package'
    Assert-True ($package.ExitCode -eq 1) "A table without the stock package passed.`n$($package.Output)"
    Assert-True ($package.Output -match 'FAIL package 0x7f com\.facebook\.katana: not in the patched table') `
        "The missing package's failure did not name the package.`n$($package.Output)"

    # A value that points at nothing: the theme's window background, as bytes, moved to an id the
    # table does not have.
    $surface = [Convert]::ToInt32($idOf['color/surface'].Substring(2), 16)
    $nowhere = $surface -bor 0xfff0
    $dangling = Copy-ApkWith -From $stockApk -Name 'dangling' -Change {
        param($archive)
        $entry = $archive.GetEntry('resources.arsc')
        $stream = $entry.Open()
        try { $buffer = [System.IO.MemoryStream]::new(); $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose() }
        $pattern = [byte[]](@(8, 0, 0, 1) + [BitConverter]::GetBytes([int]$surface))
        $hits = @()
        for ($i = 0; $i -le $bytes.Length - $pattern.Length; $i++) {
            $match = $true
            for ($j = 0; $j -lt $pattern.Length; $j++) { if ($bytes[$i + $j] -ne $pattern[$j]) { $match = $false; break } }
            if ($match) { $hits += $i }
        }
        if ($hits.Count -ne 1) { throw "expected one reference to color/surface in the table, found $($hits.Count)" }
        [BitConverter]::GetBytes([int]$nowhere).CopyTo($bytes, $hits[0] + 4)
        $entry.Delete()
        $replacement = $archive.CreateEntry('resources.arsc', [System.IO.Compression.CompressionLevel]::NoCompression)
        $out = $replacement.Open()
        try { $out.Write($bytes, 0, $bytes.Length) } finally { $out.Dispose() }
    }
    $pointer = Invoke-Check -Patched $dangling -Name 'dangling'
    $nowhereHex = '0x{0:x8}' -f $nowhere
    Assert-True ($pointer.ExitCode -eq 1) "A reference to nothing passed.`n$($pointer.Output)"
    Assert-True ($pointer.Output -match ([regex]::Escape("FAIL $($idOf['style/SearchTheme']) style/SearchTheme [default] item 0x01010054") + ".*points at $nowhereHex, which is not in the patched table")) `
        "The dangling reference's failure did not name the style, the attr and the id.`n$($pointer.Output)"

    # The split descriptor a merge drops on purpose, beside an xml resource it must not drop.
    $splitIds = Join-Path $caseRoot 'split-ids.txt'
    $splitFiles = [ordered]@{
        'res/values/colors.xml' = $colors
        'res/values-night/colors.xml' = $nightColors
        'res/values/styles.xml' = $styles
        'res/layout/feed_story.xml' = $layout
        'res/xml/splits0.xml' = '<splits><module name="base" /></splits>'
        'res/xml/feed_prefs.xml' = '<PreferenceScreen />'
    }
    $splitStock = New-ResourceApk -Name 'split-stock' -Files $splitFiles -EmitIds $splitIds
    $splitIdOf = @{}
    foreach ($line in Get-Content -LiteralPath $splitIds) {
        if ($line -match '^[^:]+:(\S+) = (0x[0-9a-f]{8})$') { $splitIdOf[$Matches[1]] = $Matches[2] }
    }
    $mergedFiles = [ordered]@{}
    foreach ($entry in $splitFiles.GetEnumerator()) { if ($entry.Key -ne 'res/xml/splits0.xml') { $mergedFiles[$entry.Key] = $entry.Value } }
    $merged = Invoke-Check -Stock $splitStock -Patched (New-ResourceApk -Name 'merged' -Files $mergedFiles -StableIds $splitIds) -Name 'merged'
    Assert-True ($merged.ExitCode -eq 0) "A merge that dropped only the split descriptor failed.`n$($merged.Output)"
    Assert-True ($merged.Output -match ('dropped with the split metadata when the bundle was merged: 1\s+' +
        [regex]::Escape("$($splitIdOf['xml/splits0']) xml/splits0"))) `
        "The dropped split descriptor was not reported with its id.`n$($merged.Output)"
    $lostXmlFiles = [ordered]@{}
    foreach ($entry in $splitFiles.GetEnumerator()) { if ($entry.Key -ne 'res/xml/feed_prefs.xml') { $lostXmlFiles[$entry.Key] = $entry.Value } }
    $lostXml = Invoke-Check -Stock $splitStock -Patched (New-ResourceApk -Name 'lost-xml' -Files $lostXmlFiles -StableIds $splitIds) -Name 'lost-xml'
    Assert-True ($lostXml.ExitCode -eq 1) "A table that lost xml/feed_prefs passed as a split descriptor.`n$($lostXml.Output)"
    Assert-True ($lostXml.Output -match [regex]::Escape("FAIL $($splitIdOf['xml/feed_prefs']) xml/feed_prefs: not in the patched table")) `
        "The lost xml resource's failure did not name its id.`n$($lostXml.Output)"
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and `
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] resource table check contracts passed'
