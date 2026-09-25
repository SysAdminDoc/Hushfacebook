<#
.SYNOPSIS
    Patch the vendor APK with every Hushfacebook patch, sign it, and optionally install it.

.DESCRIPTION
    verify-all-patches.ps1 answers whether the patches apply and throws its APK away. This
    keeps one, signed with the sideload keystore so it installs on a phone, and installs it
    over adb when a serial is given. The stock Facebook on the phone has a different signer, so
    it has to be uninstalled first; that is what -Replace does, and it wipes Facebook's data on
    that phone.

    The signing password comes from HUSHFACEBOOK_SIDELOAD_KEYSTORE_PASSWORD. When it is unset, the
    local test keystore's documented password, sideload, is used. The Morphe arguments travel
    through a temporary Java argument file so the password value is not in the child process
    command line. The file is deleted when patching exits.

    The vendor APK defaults to the newest declared build in the folder HUSHFACEBOOK_FIXTURE_DIR
    names. -Apk takes any build the catalog declares, and the result is held to that APK's own
    version, read with aapt2. A build the catalog doesn't declare is refused before anything is
    patched. The desktop CLI is found through -DesktopJar, HUSHFACEBOOK_DESKTOP_JAR or
    HUSHFACEBOOK_WORKDIR, Java through -Java, HUSHFACEBOOK_JAVA or JAVA_HOME, and aapt2 through
    -Aapt2, HUSHFACEBOOK_AAPT2 or the SDK. None of them has a machine-specific default.

.EXAMPLE
    scripts/patch-for-device.ps1 -Serial $env:HUSHFACEBOOK_DEVICE_SERIAL -Replace
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$Replace,
    # Print every line the desktop CLI writes, not only errors.
    [switch]$ShowPatchLog,
    # Patch names to leave out of this build. The catalog applies everything, the patches that
    # are off by default included (AMOLED black theme, Download any reel), so a check of what
    # Facebook does without one of them needs a build that leaves it out.
    [string[]]$Exclude = @(),
    # Patch with the release bundle even when a source file is newer than it, for replaying
    # an earlier build on purpose. Without it a stale bundle stops the run.
    [switch]$AllowStaleBundle,
    [string]$Apk,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeyAlias = 'sideload',
    [string]$OutDir = (Join-Path $env:TEMP 'hushfacebook-device'),
    # The checkout whose catalog and release bundle are used, the one holding this script unless
    # given. The contract tests point it at a fixture.
    [string]$Root
)

$ErrorActionPreference = 'Stop'
# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File. $root below is this same variable.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
# The argument file is written with [IO.File], which reads a relative path against the process
# directory, while java is handed the same path from PowerShell's location. Absolute, they agree.
$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
$catalogPath = Join-Path $root 'patches-list.json'
if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) { throw "No patch list found: $catalogPath" }
try { $catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json }
catch { throw "Could not read patch list ${catalogPath}: $($_.Exception.Message)" }
$target = Get-PatchTarget -PatchList $catalog
if (-not $Apk -and $env:HUSHFACEBOOK_FIXTURE_DIR -and (Test-Path -LiteralPath $env:HUSHFACEBOOK_FIXTURE_DIR -PathType Container)) {
    $Apk = (Get-ChildItem -LiteralPath $env:HUSHFACEBOOK_FIXTURE_DIR `
        -File | Where-Object { $_.Name -like "*$($target.PackageVersion)*" -and $_.Extension -in '.apk', '.apkm' } |
        Select-Object -First 1).FullName
}
if (-not $Apk -or -not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw ("No vendor APK. Pass -Apk with the $($target.PackageVersion) build, or set " +
        'HUSHFACEBOOK_FIXTURE_DIR to the folder that holds it.')
}
# The version the result is held to: the vendor APK's own, read the way verify-all-patches.ps1
# and the receipt read it. The catalog declares more than one build and the CLI reports the
# version of the APK it was given, so holding every run to the newest build failed a 577 build
# after it had been patched. A build the catalog doesn't declare is refused here: without -f the
# CLI refuses it too, but only after unpacking it, and this is the APK that goes on a phone.
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
$stockBase = Join-Path $OutDir 'stock-base.apk'
try {
    $stock = Get-ApkManifestFacts -Apk (Get-BaseApk -Apk $Apk -Destination $stockBase) -Aapt2 $Aapt2
} finally {
    # Only the copy taken out of a bundle. A plain APK is read where it is and stays there.
    Remove-Item -LiteralPath $stockBase -Force -ErrorAction SilentlyContinue
}
if ($stock.package -ne $target.PackageName) {
    throw "$(Split-Path -Leaf $Apk) is $($stock.package), not the catalog's target $($target.PackageName)."
}
if ($target.PackageVersions -notcontains [string]$stock.versionName) {
    throw ("$(Split-Path -Leaf $Apk) is $($stock.package) $($stock.versionName), which the bundle does not " +
        "declare ($($target.PackageVersions -join ', ')). Build for a phone from a declared build; " +
        'scripts/verify-all-patches.ps1 -Force shows what still applies on another one.')
}
$passwordVariable = 'HUSHFACEBOOK_SIDELOAD_KEYSTORE_PASSWORD'
$keystorePassword = [Environment]::GetEnvironmentVariable(
    $passwordVariable, [EnvironmentVariableTarget]::Process)
if ([string]::IsNullOrEmpty($keystorePassword)) {
    $keystorePassword = 'sideload'
    Write-Host "[device] $passwordVariable is unset; using the documented local test-key fallback"
}
$version = Get-BundleVersion -Root $root
$bundle = Get-ReleaseBundlePath -Root $root -Version $version
if (-not (Test-Path $bundle)) { throw "No bundle at $bundle. Build it first: :patches:generatePatchesList then :patches:buildAndroid, through the governor." }
# Only buildAndroid writes this bundle; a test run after a patch change leaves it behind, and the
# phone would get the previous hooks while the result reads as a verdict on the new ones.
if (-not $AllowStaleBundle) {
    $newerSources = @(Get-SourcesNewerThanBundle -Root $root -Bundle $bundle)
    if ($newerSources.Count -gt 0) {
        throw ("The bundle at $bundle is older than $($newerSources.Count) source file(s), the newest " +
            "$($newerSources[0].FullName). Rebuild it first: :patches:buildAndroid through the governor " +
            '(or pass -AllowStaleBundle to patch with it as it is).')
    }
}
$names = @($catalog.patches | ForEach-Object { $_.name } | Where-Object { $_ -notin $Exclude })
foreach ($excluded in $Exclude) {
    if ($excluded -notin ($catalog.patches | ForEach-Object { $_.name })) { throw "No patch named '$excluded' to exclude." }
}
if ($Exclude.Count -gt 0) { Write-Host "[device] leaving out: $($Exclude -join ', ')" }
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $names)

New-Item -ItemType Directory -Force $OutDir | Out-Null
$out = Join-Path $OutDir "hushfacebook-$version-signed.apk"
$temp = Join-Path $OutDir 'tmp'
$result = Join-Path $OutDir 'result.json'
if (Test-Path $out) { Remove-Item $out -Force }

Write-Host "[device] $($names.Count) patches from $(Split-Path -Leaf $bundle) onto $(Split-Path -Leaf $Apk)"
$enable = @()
foreach ($name in $names) { $enable += '-e'; $enable += $name }
$arguments = @('patch', '--exclusive', '-p', $bundle, '-o', $out, '-t', $temp, '-r', $result,
    '--keystore', $Keystore, '--keystore-password', $keystorePassword,
    '--keystore-entry-alias', $KeyAlias, '--keystore-entry-password', $keystorePassword) + $enable + @($Apk)
$argumentFile = Join-Path $OutDir 'morphe-patch.args'
$argumentFileLines = @($arguments | ForEach-Object {
    $value = [string]$_
    if ($value.IndexOfAny([char[]]"`r`n") -ge 0) {
        throw 'A Morphe command argument contains a newline and cannot be written safely.'
    }
    '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"'
})
[System.IO.File]::WriteAllLines(
    $argumentFile,
    $argumentFileLines,
    (New-Object System.Text.UTF8Encoding($false)))
try {
    & $Java -jar $DesktopJar "@$argumentFile" 2>&1 | ForEach-Object {
        $line = [string]$_
        if ($ShowPatchLog -or $line -match 'SEVERE|ERROR|Exception|Saved to') { Write-Host "[device] $line" }
    }
    if ($LASTEXITCODE -ne 0) { throw "The desktop CLI exited with $LASTEXITCODE" }
} finally {
    Remove-Item -LiteralPath $argumentFile -Force -ErrorAction SilentlyContinue
    # The CLI unpacks the whole APK here and a run against Facebook leaves gigabytes behind.
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue }
}
# The same report check the throwaway verification applies: every requested patch, every
# step, the target, and a real APK. The build that goes onto a phone deserves no less.
$report = $null
if (Test-Path -LiteralPath $result -PathType Leaf) { $report = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json }
$validation = Test-PatchingReport -Report $report -ExpectedNames $names `
    -AllowedDependencyNames $dependencyNames -OutputPath $out `
    -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $stock.versionName
if (-not $validation.Valid) { throw "Patching did not produce a complete APK: $($validation.Reason)" }
Write-Host "[device] applied $(@($report.appliedPatches).Count), failed $(@($report.failedPatches).Count), target $($report.packageName) $($report.packageVersion)"
Write-Host "[device] $out"

if (-not $Serial) { return }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName }
if (-not $adb) { throw 'No adb found. Put it on the PATH or install the platform tools.' }
if ($Replace) {
    . (Join-Path $PSScriptRoot 'device-install.ps1')
    [void](Remove-AndroidPackageIfInstalled -Adb $adb -Serial $Serial -PackageName $target.PackageName)
}
Write-Host "[device] installing on $Serial"
# adb prints Failure [...] and exits non-zero on a refused install; without this the script
# went on to print the version of whatever was already on the phone, as if it were this build.
& $adb -s $Serial install -r -g $out | Out-Host
if ($LASTEXITCODE -ne 0) { throw "adb install failed on $Serial. The output above says why." }
& $adb -s $Serial shell dumpsys package $target.PackageName | Select-String 'versionName' | Out-Host
