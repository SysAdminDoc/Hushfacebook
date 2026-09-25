<#
.SYNOPSIS
    Exercise the release and gate scripts: patch target and report, receipts, release facts,
    pre-push routing, the changelog, d8 and Java resolution, split bundles and the shared helpers.
.DESCRIPTION
    Ported from Hushfeed's suite on 2026-09-25 and held to Facebook's facts: two declared builds,
    both of Meta's signers, and .apkm split bundles. The pre-push hook runs it for changes under
    scripts/, assets/ or concepts/marketing/, and for README.md, patches-list.json or
    patches/build.gradle.kts. A missing suite stops the push.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'device-install.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'script-wiring.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message No error was raised."
}

function New-NotFoundAnswer {
    # What Invoke-WebRequest raises for a 404 in both shells, as far as Assert-UrlReachable reads
    # it: an error whose Response carries the status. A function named Invoke-WebRequest that
    # throws this stands in for the request, so no case here needs the network.
    $answer = New-Object System.Exception 'Response status code does not indicate success: 404 (Not Found).'
    Add-Member -InputObject $answer -NotePropertyName Response -NotePropertyValue ([pscustomobject]@{ StatusCode = 404 })
    return $answer
}

# --- patch-target.ps1 ------------------------------------------------------------------------
#
# Facebook ships a build a week, so the catalog declares the build the bundle was last proved on
# and keeps the one before it. Both come back newest first, by number and not by text, and every
# patch has to declare the same builds: a build only some patches declare is one the bundle can't
# fully patch, and the release scripts would take it for a declared target.
$catalog = Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json
$target = Get-PatchTarget -PatchList $catalog
Assert-True ($target.PackageName -eq 'com.facebook.katana') 'The catalog package was not resolved.'
Assert-True (@($target.PackageVersions).Count -ge 2 -and $target.PackageVersion -eq $target.PackageVersions[0]) `
    "The catalog's declared Facebook builds were not read: $($target.PackageVersions -join ', ')"
foreach ($patch in @($catalog.patches)) {
    Assert-True (((@($patch.compatiblePackages.'com.facebook.katana') | Sort-Object) -join ',') -eq
        ((@($target.PackageVersions) | Sort-Object) -join ',')) `
        "$($patch.name) declares other Facebook builds than the rest of the catalog."
}

$threeBuilds = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'one'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('577.0.0.50.72', '99.1.0.0.1', '580.0.0.51.74') } },
        [pscustomobject]@{ name = 'two'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74', '99.1.0.0.1', '577.0.0.50.72') } })
}
$three = Get-PatchTarget -PatchList $threeBuilds
Assert-True (($three.PackageVersions -join ',') -eq '580.0.0.51.74,577.0.0.50.72,99.1.0.0.1' -and
    $three.PackageVersion -eq '580.0.0.51.74') `
    "Declared builds were not ordered newest first by number: $($three.PackageVersions -join ', ')"

$uneven = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'both builds'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74', '577.0.0.50.72') } },
        [pscustomobject]@{ name = 'newest only'
            compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('580.0.0.51.74') } })
}
Assert-Throws { Get-PatchTarget -PatchList $uneven } '*newest only*' `
    'A build only some patches declare was accepted as a target of the whole bundle.'

# Both of Meta's signers, on every patch. Facebook rotated its key with a v3.1 lineage, so a
# phone on Android 13 or newer reports the new signer and an older one the old signer, and Morphe
# Manager refuses an APK whose signer the patch doesn't list.
$metaSigners = @('911d604446084ca7f4760b775bfc160fa8702441240a7258645d7a72c4312d27',
    'e3f9e1e0cf99d0e56a055ba65e241b3399f7cea524326b0cdd6ec1327ed0fdc1')
foreach ($patch in @($catalog.patches)) {
    $declaredSigners = @($patch.compatibility | Where-Object { $_.packageName -eq 'com.facebook.katana' } |
        ForEach-Object { @($_.signatures) } | Sort-Object -Unique)
    Assert-True (($declaredSigners -join ',') -eq ($metaSigners -join ',')) `
        "$($patch.name) declares Facebook signers $($declaredSigners -join ', '), not both of Meta's."
}

$allNames = @($catalog.patches | ForEach-Object { $_.name })
$allDependencies = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $allNames | Sort-Object)
Assert-True (($allDependencies -join ',') -eq 'BytecodePatch,ResourcePatch') `
    "The real catalog dependency closure is not the two internal patch types: $($allDependencies -join ', ')"
Assert-True (Test-ReportedPatchNames -Expected $allNames `
    -Actual @($allNames + $allDependencies) -AllowedDependencies $allDependencies) `
    'A result that included the real internal dependency was rejected.'

$dependencyCatalog = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'Root'; dependencies = @('Helper') },
        [pscustomobject]@{ name = 'Helper'; dependencies = @('Internal') },
        [pscustomobject]@{ name = 'Unrelated'; dependencies = @() }
    )
}
$dependencies = @(Get-PatchDependencyNames -PatchList $dependencyCatalog -RequestedNames @('Root'))
Assert-True ($dependencies.Count -eq 2 -and $dependencies -contains 'Helper' -and
    $dependencies -contains 'Internal') 'The transitive dependency closure was incomplete.'
Assert-True (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root') `
    -AllowedDependencies $dependencies) 'A report that omitted optional dependency rows was rejected.'
Assert-True (Test-ReportedPatchNames -Expected @('Root') -Actual @('Internal', 'Root', 'Helper') `
    -AllowedDependencies $dependencies) 'Declared dependency rows were rejected or order mattered.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Helper', 'Internal') `
    -AllowedDependencies $dependencies)) 'A report missing its requested root was accepted.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Unrelated') `
    -AllowedDependencies $dependencies)) 'An unrelated extra patch was accepted as a dependency.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Root') `
    -AllowedDependencies $dependencies)) 'A duplicated requested patch was accepted.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Helper', 'Helper') `
    -AllowedDependencies $dependencies)) 'A duplicated dependency patch was accepted.'

$futureCatalog = [pscustomobject]@{
    patches = @([pscustomobject]@{
        name = 'future target'
        compatiblePackages = [pscustomobject]@{ 'com.example.future' = @('99.4.2') }
    })
}
$futureTarget = Get-PatchTarget -PatchList $futureCatalog
Assert-True ($futureTarget.PackageName -eq 'com.example.future' -and
    $futureTarget.PackageVersion -eq '99.4.2') 'A synthetic future target was replaced by a pinned value.'

$twoPackages = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'one app'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0') } },
        [pscustomobject]@{ name = 'another app'; compatiblePackages = [pscustomobject]@{ 'com.example.other' = @('1.0.0') } })
}
Assert-Throws { Get-PatchTarget -PatchList $twoPackages } '*Expected one compatible package*' `
    'A catalog naming two apps was accepted.'
$missingTarget = [pscustomobject]@{ patches = @([pscustomobject]@{ name = 'missing target' }) }
Assert-Throws { Get-PatchTarget -PatchList $missingTarget } '*has no compatible package*' `
    'A patch without compatibility metadata was accepted.'

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("hushfacebook-script-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd('\') + '\'
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $reportApk = Join-Path $caseRoot 'report.apk'
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $reportApk, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entryName in @('AndroidManifest.xml', 'classes.dex')) {
            $stream = $archive.CreateEntry($entryName).Open()
            try { $stream.WriteByte(0) } finally { $stream.Dispose() }
        }
    } finally {
        $archive.Dispose()
    }
    $dependencyReport = [pscustomobject]@{
        patchingSteps = @([pscustomobject]@{ success = $true })
        appliedPatches = @($allNames + $allDependencies | ForEach-Object {
            [pscustomobject]@{ name = $_ }
        })
        failedPatches = @()
        packageName = $target.PackageName
        packageVersion = $target.PackageVersion
    }
    $reportValidation = Test-PatchingReport -Report $dependencyReport -ExpectedNames $allNames `
        -AllowedDependencyNames $allDependencies -OutputPath $reportApk `
        -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $target.PackageVersion
    Assert-True $reportValidation.Valid `
        "A complete result with a declared dependency was rejected: $($reportValidation.Reason)"

    $fakeAdb = Join-Path $caseRoot 'adb.cmd'
    $log = Join-Path $caseRoot 'adb.log'
    $mode = Join-Path $caseRoot 'mode.txt'
    $fakeBody = @'
@echo off
set /p FAKE_ADB_MODE=<"%~dp0mode.txt"
echo mode=%FAKE_ADB_MODE% args=%*>>"%~dp0adb.log"
if "%3|%4|%5"=="shell|pm|path" goto package_path
if "%3"=="uninstall" goto uninstall
exit /b 0
:package_path
if "%FAKE_ADB_MODE%"=="check-fail" goto check_fail
if "%FAKE_ADB_MODE%"=="present" echo package:/data/app/example/base.apk
if "%FAKE_ADB_MODE%"=="uninstall-fail" echo package:/data/app/example/base.apk
exit /b 0
:uninstall
if "%FAKE_ADB_MODE%"=="uninstall-fail" goto uninstall_fail
echo Success
exit /b 0
:check_fail
exit /b 17
:uninstall_fail
exit /b 19
'@
    [System.IO.File]::WriteAllText($fakeAdb, $fakeBody, [System.Text.Encoding]::ASCII)

    [System.IO.File]::WriteAllText($mode, 'absent', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'CLEAN' -PackageName 'com.example.app'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True (-not $removed) 'An absent package was reported as removed.'
    Assert-True ($calls.Count -eq 1 -and $calls[0] -like '*shell pm path com.example.app') `
        'The absent-package path attempted an uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'present', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'READY' -PackageName 'com.example.app'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True $removed 'An installed package was not removed.'
    Assert-True ($calls.Count -eq 2 -and $calls[0] -like '*shell pm path com.example.app' -and
        $calls[1] -like '*uninstall com.example.app') 'The installed-package path did not check then uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'check-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'BROKEN' -PackageName 'com.example.app'
    } '*could not check*' 'An ADB transport failure was treated as an absent package.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 1) 'The check-failure path continued after ADB failed.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'uninstall-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'LOCKED' -PackageName 'com.example.app'
    } '*uninstall failed*' 'An uninstall failure was accepted.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 2) 'The uninstall-failure path did not perform exactly a check and uninstall.'

    $emptyJdk = Join-Path $caseRoot 'empty-jdk'
    New-Item -ItemType Directory -Path $emptyJdk | Out-Null
    Assert-Throws {
        Resolve-Java -Explicit $emptyJdk
    } "*$emptyJdk*" 'An explicit directory without bin/java fell through to the PATH Java.'

    $pathJava = Resolve-Java
    $jdkRoot = Split-Path -Parent (Split-Path -Parent $pathJava)
    $resolvedJava = Resolve-Java -Explicit $jdkRoot
    Assert-True ([System.IO.Path]::GetFullPath($resolvedJava).Equals(
        [System.IO.Path]::GetFullPath($pathJava), [System.StringComparison]::OrdinalIgnoreCase)) `
        'A valid explicit JDK directory did not resolve its bin/java executable.'

    # A release bundle older than the sources it is built from. On 2026-09-23 a test run after a
    # patch change left it behind the new hook and the phone got the old one; patch-for-device
    # now stops on it. Build output beside a module's sources must not count.
    $staleRoot = Join-Path $caseRoot 'stale-bundle'
    $patchSource = Join-Path $staleRoot 'patches/src/main/kotlin/Hook.kt'
    $extensionSource = Join-Path $staleRoot 'extensions/app/library/src/main/java/Hook.java'
    $buildOutput = Join-Path $staleRoot 'extensions/app/build/intermediates/Hook.class'
    $gradleFile = Join-Path $staleRoot 'extensions/app/build.gradle.kts'
    $staleBundle = Join-Path $staleRoot 'patches/build/release/patches-1.0.0.mpp'
    $staleFiles = @($patchSource, $extensionSource, $buildOutput, $gradleFile, $staleBundle)
    foreach ($file in $staleFiles) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [System.IO.File]::WriteAllText($file, 'x', [System.Text.Encoding]::ASCII)
    }
    $then = [DateTime]::UtcNow.AddHours(-1)
    foreach ($file in $staleFiles) { [System.IO.File]::SetLastWriteTimeUtc($file, $then) }
    [System.IO.File]::SetLastWriteTimeUtc($staleBundle, $then.AddMinutes(5))
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 0) `
        'A bundle built after every source was called stale.'
    [System.IO.File]::SetLastWriteTimeUtc($buildOutput, $then.AddMinutes(9))
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 0) `
        'Build output written after the bundle was counted as a source.'
    [System.IO.File]::SetLastWriteTimeUtc($extensionSource, $then.AddMinutes(8))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 1 -and $newer[0].FullName -eq (Get-Item -LiteralPath $extensionSource).FullName) `
        "A submodule's source written after the bundle was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    [System.IO.File]::SetLastWriteTimeUtc($patchSource, $then.AddMinutes(6))
    [System.IO.File]::SetLastWriteTimeUtc($gradleFile, $then.AddMinutes(7))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 3 -and
        $newer[0].FullName -eq (Get-Item -LiteralPath $extensionSource).FullName -and
        $newer[1].FullName -eq (Get-Item -LiteralPath $gradleFile).FullName -and
        $newer[2].FullName -eq (Get-Item -LiteralPath $patchSource).FullName) `
        "Newer sources are not all listed, newest first: $(@($newer | ForEach-Object FullName) -join ', ')"
    # The R8 rules every extension's build reads, and a compile-only stub in a patches
    # submodule (its constants can be inlined into patch code), count too.
    $rules = Join-Path $staleRoot 'extensions/proguard-rules.pro'
    $stub = Join-Path $staleRoot 'patches/stub/src/main/java/android/os/Build.java'
    foreach ($file in @($rules, $stub)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [System.IO.File]::WriteAllText($file, 'x', [System.Text.Encoding]::ASCII)
        [System.IO.File]::SetLastWriteTimeUtc($file, $then)
    }
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 3) `
        'An R8 rules file or a stub written before the bundle was counted.'
    [System.IO.File]::SetLastWriteTimeUtc($rules, $then.AddMinutes(10))
    [System.IO.File]::SetLastWriteTimeUtc($stub, $then.AddMinutes(11))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 5 -and
        $newer[0].FullName -eq (Get-Item -LiteralPath $stub).FullName -and
        $newer[1].FullName -eq (Get-Item -LiteralPath $rules).FullName) `
        "The R8 rules or a patches submodule's stub was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    $deviceScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'patch-for-device.ps1') -Raw
    Assert-True ($deviceScript -match 'Get-SourcesNewerThanBundle' -and
        $deviceScript -match '\[switch\]\$AllowStaleBundle') `
        'patch-for-device.ps1 patches with a bundle without asking whether its sources are newer.'
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$consumerScripts = @('patch-for-device.ps1', 'verify-all-patches.ps1', 'build-release-receipt.ps1')
foreach ($name in $consumerScripts) {
    $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw
    Assert-True ($text -notmatch '(?m)expectedPackageVersion\s*=\s*[''\"]') `
        "$name pins an expected package version instead of reading the catalog."
    Assert-True ($text -match 'Get-PatchTarget') "$name does not read its target through Get-PatchTarget."
    Assert-True ($text -match 'Get-PatchDependencyNames') `
        "$name does not derive the selected patches' dependency closure."
    Assert-True ($text -match 'AllowedDependencyNames') `
        "$name does not pass declared dependency names into result validation."
}

# --- release receipt -------------------------------------------------------------------------

. (Join-Path $PSScriptRoot 'release-receipt.ps1')

$manifestLines = @(
    'N: android=http://schemas.android.com/apk/res/android (line=1)',
    '  E: manifest (line=1)',
    '    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=2024607030',
    '    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="46.7.3" (Raw: "46.7.3")',
    '    A: package="com.example.host" (Raw: "com.example.host")',
    '    A: platformBuildVersionCode=36',
    '      E: uses-permission (line=10)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")',
    '      E: uses-permission (line=11)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.CAMERA" (Raw: "android.permission.CAMERA")',
    '      E: application (line=20)',
    '        E: activity (line=21)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Main" (Raw: "com.example.host.Main")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true',
    '            E: intent-filter (line=22)',
    '              E: action (line=23)',
    '                A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")',
    '        E: activity (line=30)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Private" (Raw: "com.example.host.Private")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false',
    '        E: service (line=40)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)=".Sync" (Raw: ".Sync")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
)

$facts = ConvertFrom-ManifestXmlTree -Lines $manifestLines -Source 'fixture'
Assert-True ($facts.package -eq 'com.example.host') 'The manifest package was not read.'
Assert-True ($facts.versionName -eq '46.7.3') 'The manifest version name was not read.'
Assert-True ($facts.versionCode -eq '2024607030') 'The manifest version code was not read.'
Assert-True (($facts.permissions -join ',') -eq 'android.permission.CAMERA,android.permission.INTERNET') `
    'The requested permissions were not read and sorted.'
Assert-True (($facts.exported -join ',') -eq 'activity:com.example.host.Main,service:com.example.host.Sync') `
    "Exported components were misread: $($facts.exported -join ',')"
Assert-True ($facts.exported -notcontains 'activity:com.example.host.Private') `
    'A component marked exported=false was reported as exported.'
Assert-True ($facts.exported -notcontains 'action:android.intent.action.MAIN') `
    'An intent-filter action was counted as an exported component.'

Assert-Throws {
    ConvertFrom-ManifestXmlTree -Lines @('  E: manifest (line=1)') -Source 'nameless'
} '*no package name*' 'A manifest with no package was accepted.'

$patchedFacts = ConvertFrom-ManifestXmlTree -Source 'patched' -Lines (
    @($manifestLines | Where-Object { $_ -notlike '*android.permission.CAMERA*' }) + @(
        '      E: uses-permission (line=12)',
        '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.VIBRATE" (Raw: "android.permission.VIBRATE")',
        '      E: application (line=20)',
        '        E: receiver (line=50)',
        '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Probe" (Raw: "com.example.host.Probe")',
        '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'))
$delta = Get-ManifestDelta -Stock $facts -Patched $patchedFacts
Assert-True (($delta.permissionsAdded -join ',') -eq 'android.permission.VIBRATE') `
    'An added permission was not reported.'
Assert-True (($delta.permissionsRemoved -join ',') -eq 'android.permission.CAMERA') `
    'A removed permission was not reported.'
Assert-True (($delta.exportedComponentsAdded -join ',') -eq 'receiver:com.example.host.Probe') `
    'A newly exported component was not reported.'
Assert-True (@($delta.exportedComponentsRemoved).Count -eq 0) `
    'A component that stayed exported was reported as removed.'
$entries = ConvertTo-ManifestDeltaEntries -Delta $delta
Assert-True (($entries -join '; ') -eq (@(
    'exported-added receiver:com.example.host.Probe',
    'permission-added android.permission.VIBRATE',
    'permission-removed android.permission.CAMERA') -join '; ')) `
    "The delta did not flatten to allowlist lines: $($entries -join '; ')"

$unchanged = Get-ManifestDelta -Stock $facts -Patched $facts
Assert-True (@(ConvertTo-ManifestDeltaEntries -Delta $unchanged).Count -eq 0) `
    'An unchanged manifest produced a delta.'

$checkedInAllowlist = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
Assert-True (@($checkedInAllowlist | Where-Object { $_ }).Count -eq 0) `
    ('The checked-in manifest delta allowlist is no longer empty, so the patches now change the ' +
     "Android manifest: $(@($checkedInAllowlist) -join ', ')")

$allowlistRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("receipt-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $allowlistRoot | Out-Null
try {
    $good = Join-Path $allowlistRoot 'good.txt'
    Set-Content -LiteralPath $good -Encoding UTF8 -Value @(
        '# a comment', '', 'permission-added android.permission.VIBRATE',
        'exported-added receiver:com.example.host.Probe')
    Assert-True (@(Read-ManifestDeltaAllowlist -Path $good).Count -eq 2) `
        'The allowlist reader did not skip comments and blank lines.'

    $bad = Join-Path $allowlistRoot 'bad.txt'
    Set-Content -LiteralPath $bad -Encoding UTF8 -Value @('permission-added')
    Assert-Throws { Read-ManifestDeltaAllowlist -Path $bad } '*<kind> <value>*' `
        'A malformed allowlist line was accepted.'
    Assert-Throws { Read-ManifestDeltaAllowlist -Path (Join-Path $allowlistRoot 'absent.txt') } `
        '*allowlist is missing*' 'A missing allowlist was treated as an empty one.'

    # A stand-in bundle, so the size, hash and manifest checks compare against real bytes.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $commitSeconds = 1700000000L
    function New-TestBundle {
        param([string]$Path, [string]$Version = '9.9.9', [long]$Timestamp = 1700000000000L,
            [string]$Patcher = '1.12.0', [hashtable]$Entries = @{})
        if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
        $archive = [System.IO.Compression.ZipFile]::Open(
            $Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
            $writer = New-Object System.IO.StreamWriter($entry.Open())
            try {
                $writer.Write("Manifest-Version: 1.0`nVersion: $Version`n" +
                    "Timestamp: $Timestamp`nPatcher-Version: $Patcher`n`n")
            } finally { $writer.Dispose() }
            foreach ($name in @($Entries.Keys | Sort-Object)) {
                $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }

    # An SBOM in the shape :patches:releaseSbom writes, describing the bundle at -Bundle: its name,
    # hash, version and pinned stamp, the libraries given as package URLs, the patch module as the
    # first-party code, and each extension payload the bundle carries with its hash. -Mutate edits
    # the document before it's written.
    function New-TestSbom {
        param([string]$Path, [string]$Bundle,
            [string[]]$Libraries = @('pkg:maven/com.google.code.gson/gson@2.14.0'), [scriptblock]$Mutate)
        $bundleName = Split-Path -Leaf $Bundle
        $facts = Get-BundleManifestFacts -BundlePath $Bundle
        $components = New-Object System.Collections.Generic.List[object]
        foreach ($purl in @($Libraries | Where-Object { $_ })) {
            $parts = [regex]::Match($purl, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            $components.Add([ordered]@{ type = 'library'; 'bom-ref' = $purl; group = $parts.Groups[1].Value
                name = $parts.Groups[2].Value; version = $parts.Groups[3].Value; scope = 'required'; purl = $purl
                properties = @([ordered]@{ name = 'hushfacebook:carried-by'; value = $bundleName }) })
        }
        $components.Add([ordered]@{ type = 'library'; 'bom-ref' = 'project:patches'; name = ':patches'
            version = $facts.version; scope = 'required'
            properties = @([ordered]@{ name = 'hushfacebook:first-party'; value = 'built from this repository' }) })
        $archive = [System.IO.Compression.ZipFile]::OpenRead($Bundle)
        try {
            foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } | Sort-Object FullName)) {
                $stream = $entry.Open()
                $sha = [System.Security.Cryptography.SHA256]::Create()
                try { $digest = ($sha.ComputeHash($stream) | ForEach-Object { '{0:x2}' -f $_ }) -join '' } finally { $sha.Dispose(); $stream.Dispose() }
                $components.Add([ordered]@{ type = 'file'; 'bom-ref' = $entry.FullName; name = $entry.FullName
                    version = $facts.version; scope = 'required'; hashes = @([ordered]@{ alg = 'SHA-256'; content = $digest }) })
            }
        } finally { $archive.Dispose() }
        $document = [ordered]@{
            bomFormat = 'CycloneDX'; specVersion = '1.6'; serialNumber = "urn:uuid:$([guid]::NewGuid())"; version = 1
            metadata = [ordered]@{
                timestamp = [DateTimeOffset]::FromUnixTimeMilliseconds($facts.timestamp).UtcDateTime.ToString(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'", [Globalization.CultureInfo]::InvariantCulture)
                component = [ordered]@{ type = 'file'; 'bom-ref' = $bundleName; name = $bundleName; version = $facts.version
                    hashes = @([ordered]@{ alg = 'SHA-256'; content = (Get-Sha256Hex -Path $Bundle).ToLowerInvariant() }) }
            }
            components = $components.ToArray()
            dependencies = @()
        }
        if ($Mutate) { & $Mutate $document }
        Set-Content -LiteralPath $Path -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
    }

    $bundle = Join-Path $allowlistRoot 'patches-9.9.9.mpp'
    New-TestBundle -Path $bundle -Entries @{ 'extensions/facebook.mpe' = "dex`n035 payload" }
    $bundleHash = Get-Sha256Hex -Path $bundle
    $bundleSize = (Get-Item -LiteralPath $bundle).Length
    $sbomFile = Join-Path $allowlistRoot 'patches-9.9.9.cdx.json'
    New-TestSbom -Path $sbomFile -Bundle $bundle

    $manifestFacts = Get-BundleManifestFacts -BundlePath $bundle
    Assert-True ($manifestFacts.version -eq '9.9.9') 'The bundle manifest version was not read.'
    Assert-True ($manifestFacts.timestamp -eq 1700000000000L) 'The bundle timestamp was not read.'
    Assert-True ($manifestFacts.patcherVersion -eq '1.12.0') 'The bundle patcher stamp was not read.'

    # Two declared builds, the way the Facebook catalog declares the newest release and the one
    # before it, and a run of each.
    $declaredBuilds = @('46.7.3', '46.6.1')
    $template = [ordered]@{
        schemaVersion = Get-ReleaseReceiptSchemaVersion
        release   = [ordered]@{ version = '9.9.9'; tag = 'v9.9.9'
            commit = '0123456789abcdef0123456789abcdef01234567'
            commitTimestamp = $commitSeconds; patchCount = 2 }
        bundle    = [ordered]@{ file = 'patches-9.9.9.mpp'; sizeBytes = $bundleSize
            sha256 = $bundleHash; timestamp = 1700000000000L }
        sbom      = [ordered]@{ file = 'patches-9.9.9.cdx.json'; sha256 = (Get-Sha256Hex -Path $sbomFile); components = 3 }
        toolchain = [ordered]@{ patcherVersion = '1.12.0'; managerFloor = '1.29.0' }
        extension = [ordered]@{ dexPayloads = @([ordered]@{
            name = 'extensions/facebook.mpe'; sizeBytes = 10; sha256 = ('A' * 64) }) }
        targets   = @([ordered]@{
            source = [ordered]@{ file = 'stock.apkm'; package = 'com.example.host'
                versionName = '46.7.3'; versionCode = '2024607030'; sha256 = ('B' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
        }, [ordered]@{
            source = [ordered]@{ file = 'previous.apkm'; package = 'com.example.host'
                versionName = '46.6.1'; versionCode = '2024606010'; sha256 = ('D' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
        })
    }
    $templateJson = $template | ConvertTo-Json -Depth 12

    function New-TestReceipt {
        param([scriptblock]$Mutate)
        $copy = $templateJson | ConvertFrom-Json
        if ($Mutate) { & $Mutate $copy }
        return $copy
    }

    function Test-TestReceipt {
        param($Receipt, [string[]]$Approved = @())
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -ApprovedManifestDelta $Approved
    }

    $valid = Test-TestReceipt -Receipt (New-TestReceipt)
    Assert-True $valid.Valid "A complete receipt was refused: $($valid.Reason)"

    # Every fact the receipt exists to pin, put in front of the check one at a time. A gate that
    # has never been shown to fail is a gate nobody has tested.
    $mutations = [ordered]@{
        'a receipt from a different schema'     = { param($r) $r.schemaVersion = 99 }
        'a receipt for a different version'     = { param($r) $r.release.version = '9.9.8' }
        'a tag that does not match the version' = { param($r) $r.release.tag = 'v9.9.8' }
        'a short commit'                        = { param($r) $r.release.commit = '0123456' }
        'a patch count that is not the catalog' = { param($r) $r.release.patchCount = 3 }
        'a different patcher'                   = { param($r) $r.toolchain.patcherVersion = '1.13.0' }
        'a different Manager floor'             = { param($r) $r.toolchain.managerFloor = '1.30.0' }
        'a bundle size that is not the bundle'  = { param($r) $r.bundle.sizeBytes = 4 }
        'a bundle hash that is not the bundle'  = { param($r) $r.bundle.sha256 = ('C' * 64) }
        'an empty extension payload'            = { param($r) $r.extension.dexPayloads[0].sizeBytes = 0 }
        'an unhashed extension payload'         = { param($r) $r.extension.dexPayloads[0].sha256 = 'nope' }
        'no extension payload at all'           = { param($r) $r.extension.dexPayloads = @() }
        'no target at all'                      = { param($r) $r.targets = @() }
        'an unhashed source APK'                = { param($r) $r.targets[0].source.sha256 = '' }
        'fewer verdicts than patches'           = { param($r) $r.targets[0].patches = @($r.targets[0].patches[0]) }
        'a patch the catalog does not list'     = { param($r) $r.targets[0].patches[1].name = 'Gamma' }
        'the same patch reported twice'         = { param($r) $r.targets[0].patches[1].name = 'Alpha' }
        'a patch that did not apply'            = { param($r) $r.targets[0].patches[1].applied = $false }
        'a receipt with no commit time'         = { param($r) $r.release.commitTimestamp = 0 }
        'a stamp that is not the bundle stamp'  = { param($r) $r.bundle.timestamp = 1700000001000L }
        'a run against another package'         = { param($r) $r.targets[0].source.package = 'com.example.other' }
        'a run with no version name'            = { param($r) $r.targets[0].source.versionName = '' }
        'a receipt that omits the forced flag'  = { param($r) $r.targets[0].source.PSObject.Properties.Remove('forced') }
        'a forced flag that is not a boolean'   = { param($r) $r.targets[0].source.forced = 'false' }
        'a declared-version run marked forced'  = { param($r) $r.targets[0].source.forced = $true }
        'only forced runs past the target'      = { param($r) $r.targets[0].source.versionName = '46.8.3'; $r.targets[0].source.forced = $true; $r.targets = @($r.targets[0]) }
        'a newer build patched without -f'      = { param($r) $r.targets[0].source.versionName = '46.8.3' }
        'a run of the newest declared build only' = { param($r) $r.targets = @($r.targets[0]) }
        'the older declared build forced'       = { param($r) $r.targets[1].source.forced = $true }
        'both runs at the newest declared build' = { param($r) $r.targets[1].source.versionName = '46.7.3' }
        'a receipt that names no SBOM'          = { param($r) $r.PSObject.Properties.Remove('sbom') }
        'an SBOM named for another version'     = { param($r) $r.sbom.file = 'patches-9.9.8.cdx.json' }
        'an SBOM with no hash'                  = { param($r) $r.sbom.sha256 = 'nope' }
        'an SBOM hash in lower case'            = { param($r) $r.sbom.sha256 = ([string]$r.sbom.sha256).ToLowerInvariant() }
        'an SBOM counting no component'         = { param($r) $r.sbom.components = 0 }
    }
    foreach ($description in $mutations.Keys) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$description])
        Assert-True (-not $result.Valid) "Receipt validation accepted $description."
        Assert-True ([bool]$result.Reason) "Receipt validation refused $description without saying why."
    }

    # A receipt built only from forced runs against newer builds has to be refused for that
    # reason and name the target it is missing, not trip over some other field on the way.
    $onlyForced = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['only forced runs past the target'])
    Assert-True ($onlyForced.Reason -like '*No target*46.7.3*without -f*46.8.3*') `
        "A forced-only receipt was refused for the wrong reason: $($onlyForced.Reason)"
    # A release that ran only the newest build names the declared build it never patched.
    $newestOnly = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['a run of the newest declared build only'])
    Assert-True ($newestOnly.Reason -like '*No target*46.6.1*without -f*') `
        "A receipt missing a declared build was refused for the wrong reason: $($newestOnly.Reason)"
    $secondTarget = New-TestReceipt -Mutate {
        param($r)
        $newer = $r.targets[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $newer.source.versionName = '46.8.3'
        $newer.source.forced = $true
        $r.targets = @($r.targets[0], $r.targets[1], $newer)
    }
    $twoTargets = Test-TestReceipt -Receipt $secondTarget
    Assert-True $twoTargets.Valid "A receipt with the declared target beside a forced run was refused: $($twoTargets.Reason)"

    # The bundle the receipt is about, gone. Every fact above is checked against a file, and a
    # missing file is the one case where there is nothing to disagree with, so an unguarded
    # check would read it as agreement and pass the release.
    $absent = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath (Join-Path $allowlistRoot 'not-built.mpp')
    Assert-True (-not $absent.Valid) 'A receipt was accepted against a bundle that is not there.'
    Assert-True ($absent.Reason -like '*not there*') `
        "The missing bundle was refused for the wrong reason: $($absent.Reason)"

    # The bundle itself disagreeing with the receipt, which is the v0.28.0 failure: a bundle
    # built before its release commit existed carries the previous commit's pin, and nobody can
    # reproduce the published hash from the tag.
    $strayBundle = Join-Path $allowlistRoot 'patches-stray.mpp'
    New-TestBundle -Path $strayBundle -Timestamp 1699999999000L
    $strayReceipt = New-TestReceipt -Mutate {
        param($r)
        $r.bundle.sizeBytes = (Get-Item -LiteralPath $strayBundle).Length
        $r.bundle.sha256 = Get-Sha256Hex -Path $strayBundle
        $r.bundle.timestamp = 1699999999000L
    }
    $strayResult = Test-ReleaseReceipt -Receipt $strayReceipt -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $strayBundle
    Assert-True (-not $strayResult.Valid) 'A bundle built from another commit was accepted.'
    Assert-True ($strayResult.Reason -like '*different*commit*') `
        "The stale bundle pin was refused for the wrong reason: $($strayResult.Reason)"

    foreach ($wrong in @(
        @{ Name = 'a bundle stamped with another version'; Version = '9.9.8'; Patcher = '1.12.0'
            Pattern = '*manifest says version*' },
        @{ Name = 'a bundle stamped by another patcher'; Version = '9.9.9'; Patcher = '1.13.0'
            Pattern = '*stamped by patcher*' })) {
        $odd = Join-Path $allowlistRoot 'patches-odd.mpp'
        New-TestBundle -Path $odd -Version $wrong.Version -Patcher $wrong.Patcher
        $oddReceipt = New-TestReceipt -Mutate {
            param($r)
            $r.bundle.sizeBytes = (Get-Item -LiteralPath $odd).Length
            $r.bundle.sha256 = Get-Sha256Hex -Path $odd
        }
        $oddResult = Test-ReleaseReceipt -Receipt $oddReceipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $odd
        Assert-True (-not $oddResult.Valid) "Receipt validation accepted $($wrong.Name)."
        Assert-True ($oddResult.Reason -like $wrong.Pattern) `
            "$($wrong.Name) was refused for the wrong reason: $($oddResult.Reason)"
    }

    # The commit the receipt names, checked against something outside the receipt. Its own
    # timestamp field and the bundle stamp both come from the same document, so a receipt kept
    # from an earlier release agrees with itself and passes on that pair alone.
    $sameCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ActualCommitTimestamp $commitSeconds
    Assert-True $sameCommit.Valid "A receipt matching git was refused: $($sameCommit.Reason)"

    $movedCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ActualCommitTimestamp ($commitSeconds + 60)
    Assert-True (-not $movedCommit.Valid) `
        'A receipt whose commit time git disagrees with was accepted.'
    Assert-True ($movedCommit.Reason -like '*git says*') `
        "The stale receipt was refused for the wrong reason: $($movedCommit.Reason)"

    $otherCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle `
        -ExpectedCommit ('f' * 40)
    Assert-True (-not $otherCommit.Valid) 'A receipt for another commit was accepted on a release.'
    Assert-True ($otherCommit.Reason -like '*this release is*') `
        "The wrong-commit receipt was refused for the wrong reason: $($otherCommit.Reason)"

    # A truncated document, which is the shape @($null) turns into one null entry.
    foreach ($missing in @('targets', 'dexPayloads')) {
        $truncated = $templateJson | ConvertFrom-Json
        if ($missing -eq 'targets') {
            $truncated.PSObject.Properties.Remove('targets')
        } else {
            $truncated.extension.PSObject.Properties.Remove('dexPayloads')
        }
        $result = Test-TestReceipt -Receipt $truncated
        Assert-True (-not $result.Valid) "A receipt with no $missing was accepted."
        Assert-True ($result.Reason -like '*no target*' -or $result.Reason -like '*no extension*') `
            "A receipt with no $missing was refused for the wrong reason: $($result.Reason)"
    }

    $withDelta = New-TestReceipt -Mutate {
        param($r) $r.targets[0].manifestDelta.permissionsAdded = @('android.permission.VIBRATE')
    }
    $unreviewed = Test-TestReceipt -Receipt $withDelta
    Assert-True (-not $unreviewed.Valid) 'An unreviewed manifest change was accepted.'
    Assert-True ($unreviewed.Reason -like '*nobody reviewed*') `
        "The unreviewed manifest change was refused for the wrong reason: $($unreviewed.Reason)"

    $reviewed = Test-TestReceipt -Receipt $withDelta -Approved @('permission-added android.permission.VIBRATE')
    Assert-True $reviewed.Valid "A reviewed manifest change was refused: $($reviewed.Reason)"

    # The shape a real run hands over, rather than a literal @(). An allowlist file with no
    # entries reaches the validator as $null, and treating that null as an approved entry failed
    # every clean run with an empty list of changes nobody could read.
    $emptyFromFile = Join-Path $allowlistRoot 'empty.txt'
    Set-Content -LiteralPath $emptyFromFile -Encoding UTF8 -Value @('# nothing approved', '')
    $fromFile = Read-ManifestDeltaAllowlist -Path $emptyFromFile
    $clean = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $fromFile
    Assert-True $clean.Valid `
        "A receipt with no manifest change failed against an empty allowlist: $($clean.Reason)"
    $cleanNull = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $null
    Assert-True $cleanNull.Valid `
        "A receipt with no manifest change failed against a null allowlist: $($cleanNull.Reason)"

    $stale = Test-TestReceipt -Receipt (New-TestReceipt) -Approved @('permission-added android.permission.VIBRATE')
    Assert-True (-not $stale.Valid) 'An allowlist entry no patch produces was accepted.'
    Assert-True ($stale.Reason -like '*any more*') `
        "The stale allowlist entry was refused for the wrong reason: $($stale.Reason)"

    # The SBOM a receipt names. Each refusal has to name the SBOM fact that failed rather than trip
    # over the next field, or a check that went missing would pass unseen behind the one after it.
    foreach ($named in @(
            @{ Name = 'a receipt that names no SBOM'; Pattern = '*names no SBOM*' },
            @{ Name = 'an SBOM named for another version'; Pattern = '*names the SBOM patches-9.9.8.cdx.json; the one for 9.9.9 is patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM with no hash'; Pattern = '*no SHA-256 for patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM hash in lower case'; Pattern = '*no SHA-256 for patches-9.9.9.cdx.json*' },
            @{ Name = 'an SBOM counting no component'; Pattern = '*counts no component in patches-9.9.9.cdx.json*' })) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$named.Name])
        Assert-True ($result.Reason -like $named.Pattern) "$($named.Name) was refused for the wrong reason: $($result.Reason)"
    }

    # Read back, the SBOM gives what it was written with, and it describes the bundle beside it.
    $sbomRead = Read-ReleaseSbom -Path $sbomFile
    Assert-True ($sbomRead.BundleName -eq 'patches-9.9.9.mpp' -and $sbomRead.BundleVersion -eq '9.9.9' -and
        $sbomRead.BundleSha256 -ceq $bundleHash.ToLowerInvariant() -and $sbomRead.Timestamp -eq '2023-11-14T22:13:20Z' -and
        @($sbomRead.Components).Count -eq 3 -and @($sbomRead.Libraries).Count -eq 1 -and
        $sbomRead.Libraries[0].Purl -eq 'pkg:maven/com.google.code.gson/gson@2.14.0' -and $sbomRead.Libraries[0].Name -eq 'gson' -and
        @($sbomRead.Payloads).Count -eq 1 -and $sbomRead.Payloads[0].Ref -eq 'extensions/facebook.mpe' -and
        $sbomRead.Sha256 -eq (Get-Sha256Hex -Path $sbomFile)) `
        "The SBOM was misread: $($sbomRead.BundleName) $($sbomRead.BundleVersion) $($sbomRead.Timestamp), $(@($sbomRead.Components).Count) components"
    $bound = Test-ReleaseSbom -Sbom $sbomRead -BundlePath $bundle -BundleName 'patches-9.9.9.mpp'
    Assert-True $bound.Valid "An SBOM of the bundle was refused: $($bound.Reason)"

    # The receipt against the SBOM file itself: its hash and its count, and through it the bundle.
    function Test-ReceiptWithSbom($Receipt, [string]$Sbom = $sbomFile, [int]$Schema = (Get-ReleaseReceiptSchemaVersion)) {
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
            -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -SbomPath $Sbom -ExpectedSchemaVersion $Schema
    }
    $withSbom = Test-ReceiptWithSbom (New-TestReceipt)
    Assert-True $withSbom.Valid "A receipt was refused against the SBOM it names: $($withSbom.Reason)"
    foreach ($wrong in @(
            @{ Name = 'an SBOM hash that is not the SBOM'; Mutate = { param($r) $r.sbom.sha256 = ('C' * 64) }; Pattern = '*says patches-9.9.9.cdx.json hashes to CCCC*' },
            @{ Name = 'a count that is not the SBOM''s'; Mutate = { param($r) $r.sbom.components = 4 }; Pattern = '*counts 4 components in patches-9.9.9.cdx.json; it lists 3*' })) {
        $result = Test-ReceiptWithSbom (New-TestReceipt -Mutate $wrong.Mutate)
        Assert-True (-not $result.Valid -and $result.Reason -like $wrong.Pattern) "A receipt with $($wrong.Name) was not refused for it: $($result.Reason)"
    }
    $missingSbom = Test-ReceiptWithSbom (New-TestReceipt) -Sbom (Join-Path $allowlistRoot 'absent.cdx.json')
    Assert-True ($missingSbom.Reason -like '*an SBOM that is not there*') "A receipt was checked against an SBOM that isn't there: $($missingSbom.Reason)"
    # An SBOM that isn't this bundle's, each way it can differ, with the receipt recording its hash
    # so that the difference is what refuses it.
    $variant = Join-Path $allowlistRoot 'variant\patches-9.9.9.cdx.json'
    New-Item -ItemType Directory -Path (Split-Path -Parent $variant) -Force | Out-Null
    foreach ($other in @(
            @{ Name = 'another bundle''s hash'; Mutate = { param($d) $d.metadata.component.hashes[0].content = ('0' * 64) }
                Pattern = '*describes a patches-9.9.9.mpp that hashes to 0000*written for another build*' },
            @{ Name = 'a date from the clock'; Mutate = { param($d) $d.metadata.timestamp = '2026-09-25T10:00:00Z' }
                Pattern = '*is dated 2026-09-25T10:00:00Z, and patches-9.9.9.mpp is stamped 2023-11-14T22:13:20Z*' },
            @{ Name = 'another version'; Mutate = { param($d) $d.metadata.component.version = '9.9.8' }; Pattern = '*says patches-9.9.9.mpp is version 9.9.8*' },
            @{ Name = 'another bundle''s name'; Mutate = { param($d) $d.metadata.component.name = 'patches-9.9.8.mpp' }; Pattern = '*describes patches-9.9.8.mpp, not patches-9.9.9.mpp*' },
            @{ Name = 'no payload'; Mutate = { param($d) $d.components = @($d.components | Where-Object { $_.type -ne 'file' }) }
                Pattern = '*carries extensions/facebook.mpe, which patches-9.9.9.cdx.json doesn''t describe*' },
            @{ Name = 'another payload hash'; Mutate = { param($d) @($d.components | Where-Object { $_.type -eq 'file' })[0].hashes[0].content = ('1' * 64) }
                Pattern = '*describes extensions/facebook.mpe hashing to 1111*' },
            @{ Name = 'a payload the bundle lacks'; Mutate = { param($d) $d.components = @($d.components) + @([ordered]@{ type = 'file'; 'bom-ref' = 'extensions/shared.mpe'
                    name = 'extensions/shared.mpe'; version = '9.9.9'; scope = 'required'; hashes = @([ordered]@{ alg = 'SHA-256'; content = ('2' * 64) }) }) }
                Pattern = '*describes extensions/shared.mpe, which patches-9.9.9.mpp doesn''t carry*' })) {
        New-TestSbom -Path $variant -Bundle $bundle -Mutate $other.Mutate
        $variantCount = @((Get-Content -LiteralPath $variant -Raw | ConvertFrom-Json).components).Count
        $result = Test-ReceiptWithSbom (New-TestReceipt -Mutate {
            param($r) $r.sbom.sha256 = Get-Sha256Hex -Path $variant; $r.sbom.components = $variantCount }) -Sbom $variant
        Assert-True (-not $result.Valid -and $result.Reason -like $other.Pattern) `
            "An SBOM with $($other.Name) was taken for the bundle's: $($result.Reason)"
    }

    # The SBOM's own shape. OSV answers {} for a package URL it can't read, so a library that isn't
    # asked about under its own group, name and version would read as having no advisory.
    foreach ($broken in @(
            @{ Name = 'another format'; Mutate = { param($d) $d.bomFormat = 'SPDX' }; Pattern = '*is not a CycloneDX 1.6 SBOM*' },
            @{ Name = 'another spec version'; Mutate = { param($d) $d.specVersion = '1.5' }; Pattern = '*is not a CycloneDX 1.6 SBOM*' },
            @{ Name = 'no bundle hash'; Mutate = { param($d) $d.metadata.component.Remove('hashes') }; Pattern = '*does not name the bundle it describes*' },
            @{ Name = 'a package URL for another version'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.code.gson/gson@2.8.8' }
                Pattern = '*with the package URL pkg:maven/com.google.code.gson/gson@2.8.8, which doesn''t name its own group, name and version*' },
            @{ Name = 'a package URL with no group'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/gson@2.14.0' }
                Pattern = '*with the package URL pkg:maven/gson@2.14.0, which doesn''t name*' },
            @{ Name = 'a package URL for another group'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.gson/gson@2.14.0' }
                Pattern = '*with the package URL pkg:maven/com.google.gson/gson@2.14.0, which doesn''t name*' },
            @{ Name = 'a package URL for another name'; Mutate = { param($d) $d.components[0].purl = 'pkg:maven/com.google.code.gson/gson-extras@2.14.0' }
                Pattern = '*with the package URL pkg:maven/com.google.code.gson/gson-extras@2.14.0, which doesn''t name*' },
            @{ Name = 'something that is not a package URL, beside empty fields'
                Mutate = { param($d) $d.components[0].purl = 'not-a-package-url'; $d.components[0].group = ''; $d.components[0].name = ''; $d.components[0].version = '' }
                Pattern = '*with the package URL not-a-package-url, which doesn''t name*' },
            @{ Name = 'a library with no package URL'; Mutate = { param($d) $d.components[0].Remove('purl') }; Pattern = '*the library pkg:maven/com.google.code.gson/gson@2.14.0 with no package URL*' },
            @{ Name = 'the same component twice'; Mutate = { param($d) $d.components = @($d.components) + @($d.components[0]) }; Pattern = '*lists pkg:maven/com.google.code.gson/gson@2.14.0 twice*' },
            @{ Name = 'a component of another type'; Mutate = { param($d) $d.components[0].type = 'framework' }; Pattern = '*as a framework, which a release SBOM doesn''t hold*' },
            @{ Name = 'a payload with no hash'; Mutate = { param($d) @($d.components | Where-Object { $_.type -eq 'file' })[0].Remove('hashes') }; Pattern = '*the file extensions/facebook.mpe with no SHA-256*' },
            @{ Name = 'no component'; Mutate = { param($d) $d.components = @() }; Pattern = '*lists no component*' })) {
        New-TestSbom -Path $variant -Bundle $bundle -Mutate $broken.Mutate
        Assert-Throws { Read-ReleaseSbom -Path $variant } $broken.Pattern "An SBOM with $($broken.Name) was read without complaint."
    }
    Set-Content -LiteralPath $variant -Encoding ASCII -Value 'not json'
    Assert-Throws { Read-ReleaseSbom -Path $variant } '*patches-9.9.9.cdx.json is not JSON*' 'Text that is not JSON was read as an SBOM.'

    # A receipt cut before schema 2 names no SBOM, and is read as its own commit wrote it; each
    # schema is refused where the other is expected.
    $schemaOne = New-TestReceipt -Mutate { param($r) $r.schemaVersion = 1; $r.PSObject.Properties.Remove('sbom') }
    $oneAtOne = Test-ReleaseReceipt -Receipt $schemaOne -ExpectedVersion '9.9.9' -ExpectedPatchNames @('Alpha', 'Beta') `
        -ExpectedPatcherVersion '1.12.0' -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
        -ExpectedPackageVersions $declaredBuilds -BundlePath $bundle -ExpectedSchemaVersion 1
    Assert-True $oneAtOne.Valid "A schema 1 receipt was refused at schema 1: $($oneAtOne.Reason)"
    $oneAtTwo = Test-TestReceipt -Receipt $schemaOne
    Assert-True ($oneAtTwo.Reason -like '*schema version 1; its release is read at version 2*') `
        "A schema 1 receipt was not refused where schema 2 is expected: $($oneAtTwo.Reason)"
    $twoAtOne = Test-ReceiptWithSbom (New-TestReceipt) -Schema 1
    Assert-True ($twoAtOne.Reason -like '*schema version 2; its release is read at version 1*') `
        "A schema 2 receipt was not refused where schema 1 is expected: $($twoAtOne.Reason)"
    $oneWithSbom = Test-ReceiptWithSbom $schemaOne -Schema 1
    Assert-True ($oneWithSbom.Reason -like '*schema 1 receipt names no SBOM to hold*') `
        "A schema 1 receipt was held to an SBOM it can't name: $($oneWithSbom.Reason)"
} finally {
    Remove-Item -LiteralPath $allowlistRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Which catalog a receipt is judged against. A receipt describes a release that has shipped, so
# moving the patcher pin afterwards must not turn it into a failure; a release, whose receipt is
# cut against the catalog it was built from, must still be held to that catalog exactly.
#
# Driven against a real two-commit repository, because the whole question is what `git show` says
# at a commit and a fake cannot answer that.
#
# Every git call below goes through Invoke-FixtureGit, and that is not tidiness. On 2026-09-15
# this block ran from inside the pre-push hook, which is a git child process, so GIT_DIR and
# GIT_WORK_TREE were in its environment. `git -C <tempdir>` changes the working directory and
# does not override GIT_DIR, so `init` reused the real repository, `add -A` read the two-file
# temp tree through it and staged every other tracked file as deleted, and three fixture commits
# authored by Contracts landed on the branch and were pushed to main, where the tip deleted all
# 703 files. Clearing the environment is the fix; the assertion after init is what would have
# stopped it in the second it happened.
$toolchainRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-toolchain-" + [guid]::NewGuid().ToString('N'))
$toolchainRemote = "$toolchainRoot-published.git"

function Invoke-FixtureGit {
    <#
    .SYNOPSIS
        git against a fixture repository, with no inherited git environment.
    .DESCRIPTION
        Removes every GIT_* variable for the length of the call, so the repository git acts on is
        the one -C names and nothing else. Run from a hook, GIT_DIR alone is enough to point all
        of this at the real tree.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    try {
        return & git -C $Root @Arguments 2>&1
    } finally {
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

try {
    New-Item -ItemType Directory -Path (Join-Path $toolchainRoot 'gradle') -Force | Out-Null
    $catalogFile = Join-Path $toolchainRoot 'gradle/libs.versions.toml'
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--quiet') | Out-Null

    # Where git says it will write, asked before anything is written. A fixture that has taken
    # hold of the real repository fails here instead of committing to it.
    $fixtureGitDir = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    $expectedGitDir = (Join-Path $toolchainRoot '.git')
    Assert-True ($fixtureGitDir -and
        ([IO.Path]::GetFullPath($fixtureGitDir).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath($expectedGitDir).TrimEnd('\', '/'))) `
        ("The fixture repository resolved to $fixtureGitDir, not $expectedGitDir. Refusing to " +
            'write: this is the shape that put three fixture commits on the real branch.')

    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.name', 'Contracts') | Out-Null

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "1.29.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitSha = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()

    # One file in the fixture, so one file in its first commit. A commit that carries hundreds is
    # a commit against somebody else's repository.
    $firstCommitFiles = @(Invoke-FixtureGit -Root $toolchainRoot `
        -Arguments @('show', '--name-only', '--format=', 'HEAD') | Where-Object { "$_".Trim() })
    Assert-True ($firstCommitFiles.Count -eq 1 -and "$($firstCommitFiles[0])".Trim() -eq 'gradle/libs.versions.toml') `
        ("The fixture's first commit touched $($firstCommitFiles.Count) files: " +
            (($firstCommitFiles | Select-Object -First 5) -join ', '))

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.13.0"', 'manager-floor = "1.30.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'move the pin', '--quiet') | Out-Null

    $workingToolchain = Read-CatalogToolchain -Text (Get-Content -LiteralPath $catalogFile -Raw) `
        -Source 'the working catalog'
    Assert-True ($workingToolchain.PatcherVersion -eq '1.13.0' -and $workingToolchain.ManagerFloor -eq '1.30.0') `
        "The working catalog was not read: $($workingToolchain.PatcherVersion), $($workingToolchain.ManagerFloor)"

    # The source push after the pin moved: the receipt's own commit still pinned 1.12.0, so that
    # is what it answers for, and the push this used to stop now goes through.
    $atRelease = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $releaseCommitSha `
        -WorkingToolchain $workingToolchain
    Assert-True ($atRelease.Toolchain.PatcherVersion -eq '1.12.0') `
        "The receipt was not held to the patcher its own commit pinned: $($atRelease.Toolchain.PatcherVersion)"
    Assert-True ($atRelease.Toolchain.ManagerFloor -eq '1.29.0') `
        "The receipt was not held to the Manager floor its own commit pinned: $($atRelease.Toolchain.ManagerFloor)"
    Assert-True ($atRelease.Note -like '*1.12.0*' -and $atRelease.Note -like '*1.13.0*') `
        "The difference between the two catalogs was not reported: $($atRelease.Note)"

    # The release push: the receipt's commit is the commit being released, so the catalog it is
    # held to is the working one and the strict comparison is unchanged. Without this case the
    # one above would pass just as well if the check had been turned off.
    $head = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atHead = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $head -WorkingToolchain $workingToolchain
    Assert-True ($atHead.Toolchain.PatcherVersion -eq '1.13.0' -and $atHead.Toolchain.ManagerFloor -eq '1.30.0') `
        "A receipt at the released commit was not held to that commit's catalog: $($atHead.Toolchain.PatcherVersion)"
    Assert-True ($null -eq $atHead.Note) "An unchanged catalog still reported a difference: $($atHead.Note)"

    # The floor the index description is held to: the published release's own, through its tag,
    # while the working catalog already pins the next one. An untagged version isn't guessed at.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('tag', 'v0.0.9', $releaseCommitSha) | Out-Null
    $indexFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9'
    Assert-True ($indexFloor.Floor -eq '1.29.0' -and $indexFloor.Source -eq 'tag v0.0.9') `
        "The index floor was not read at the release tag: $($indexFloor.Floor) from $($indexFloor.Source)"
    $untagged = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.10'
    Assert-True ($null -eq $untagged.Floor -and $untagged.Note -like "*v0.0.10 isn't in this clone*") `
        "An untagged release was given a floor: $($untagged.Floor), $($untagged.Note)"
    # A commit the caller read off the remote wins over the clone's tag, which still names the old
    # commit after a release is re-cut on GitHub.
    $recutFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9' -Commit $head
    Assert-True ($recutFloor.Floor -eq '1.30.0') "The clone's tag was read over the published commit: $($recutFloor.Floor)"
    # With no tag in the clone the remote is asked, since `gh release create` makes the tag on
    # GitHub only. A bare repository stands in for it: a lightweight tag, an annotated one read
    # through the commit it peels to, and one on a commit this clone doesn't have. The annotated
    # tag is made on the remote alone, so this clone has its commit and never saw the tag object:
    # only the peeled line leads to a commit here.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--bare', '--quiet', $toolchainRemote) | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('push', '--quiet', $toolchainRemote,
        "${releaseCommitSha}:refs/tags/v0.0.12", "${head}:refs/heads/main") | Out-Null
    Invoke-FixtureGit -Root $toolchainRemote -Arguments @('-c', 'user.name=Contracts', '-c', 'user.email=contracts@example.invalid',
        'tag', '-a', '-m', 'release', 'v0.0.13', $head) | Out-Null
    $onlyThere = "$(Invoke-FixtureGit -Root $toolchainRemote -Arguments @('-c', 'user.name=Contracts',
        '-c', 'user.email=contracts@example.invalid', 'commit-tree', "$head^{tree}", '-m', 'only there') |
        Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $toolchainRemote -Arguments @('tag', 'v0.0.14', $onlyThere) | Out-Null
    $remoteFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.12' -RemoteUrl $toolchainRemote
    Assert-True ($remoteFloor.Floor -eq '1.29.0' -and $remoteFloor.Source -eq "tag v0.0.12 on $toolchainRemote") `
        "A tag only the remote has was not read there: $($remoteFloor.Floor) from $($remoteFloor.Source), $($remoteFloor.Note)"
    $peeledFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.13' -RemoteUrl $toolchainRemote
    Assert-True ($peeledFloor.Floor -eq '1.30.0') `
        "An annotated tag only the remote has was not read at its commit: $($peeledFloor.Floor), $($peeledFloor.Note)"
    foreach ($unread in @(
            @{ Version = '0.0.14'; Remote = $toolchainRemote; Note = "*names commit $($onlyThere.Substring(0, 8)), which this clone doesn't have*" },
            @{ Version = '0.0.15'; Remote = $toolchainRemote; Note = "*v0.0.15 isn't in this clone or on $toolchainRemote*" },
            @{ Version = '0.0.15'; Remote = (Join-Path $toolchainRoot 'missing.git'); Note = "*couldn't be asked for it*" })) {
        $unreadFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version $unread.Version -RemoteUrl $unread.Remote
        Assert-True ($null -eq $unreadFloor.Floor -and $unreadFloor.Note -like $unread.Note) `
            "A floor the remote couldn't give was not reported as unchecked: $($unreadFloor.Floor), $($unreadFloor.Note)"
    }
    # The clone's tag can be stale: a release re-cut on GitHub moves the tag there, and git fetch
    # won't move it here. With a remote named, the published tag is read first. The clone's v0.0.9
    # still names the release commit, and the remote's names the next one.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('push', '--quiet', $toolchainRemote, "${head}:refs/tags/v0.0.9") | Out-Null
    $publishedFirst = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.9' -RemoteUrl $toolchainRemote
    Assert-True ($publishedFirst.Floor -eq '1.30.0' -and $publishedFirst.Source -eq "tag v0.0.9 on $toolchainRemote") `
        "The clone's own tag was read over the published one: $($publishedFirst.Floor) from $($publishedFirst.Source)"

    # A commit with no catalog in it, and a receipt naming no commit at all. Both fall back to
    # the working catalog rather than throwing, and the first says so.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rm', '--quiet', '--', 'gradle/libs.versions.toml') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'no catalog', '--quiet') | Out-Null
    $bare = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atBare = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $bare -WorkingToolchain $workingToolchain
    Assert-True ($atBare.Toolchain.PatcherVersion -eq '1.13.0') `
        'A commit with no catalog did not fall back to the working one.'
    Assert-True ($atBare.Note -like '*no version catalog*') `
        "The fallback was not reported: $($atBare.Note)"
    # The index floor doesn't fall back: the working catalog is the wrong answer for a release.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('tag', 'v0.0.11', $bare) | Out-Null
    $bareFloor = Resolve-IndexManagerFloor -Root $toolchainRoot -Version '0.0.11'
    Assert-True ($null -eq $bareFloor.Floor -and $bareFloor.Note -like '*no version catalog*') `
        "A tag with no catalog was given a floor: $($bareFloor.Floor), $($bareFloor.Note)"

    $noCommit = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit '' -WorkingToolchain $workingToolchain
    Assert-True ($noCommit.Toolchain.PatcherVersion -eq '1.13.0' -and $null -eq $noCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working catalog.'

    # The patch list, the same way: a patch renamed after the release is held to the name its own
    # commit carried, which is what the hold after 0.58.0 needed; the released commit itself is
    # held to the working list; no list at that commit, or no commit, falls back to the working one.
    $listFile = Join-Path $toolchainRoot 'patches-list.json'
    function Save-FixtureList([string[]]$Names) {
        $list = @{ patches = @($Names | ForEach-Object { @{ name = $_; compatiblePackages = @{ 'com.example' = @('1.0.0') } } }) }
        Set-Content -LiteralPath $listFile -Encoding UTF8 -Value ($list | ConvertTo-Json -Depth 5)
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "list $($Names -join ' ')", '--quiet') | Out-Null
        return "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $listReleased = Save-FixtureList @('Alpha', 'Beta')
    $listNow = Save-FixtureList @('Alpha', 'Gamma')
    $workingList = Get-Content -LiteralPath $listFile -Raw | ConvertFrom-Json
    $atListRelease = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listReleased -WorkingPatchList $workingList
    $namesAtRelease = @($atListRelease.PatchList.patches | ForEach-Object { [string]$_.name }) -join ','
    Assert-True ($namesAtRelease -eq 'Alpha,Beta' -and $atListRelease.Note -like '*2 patches*') `
        "The receipt was not held to the patch list its own commit carried: $namesAtRelease / $($atListRelease.Note)"
    $atListHead = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listNow -WorkingPatchList $workingList
    Assert-True ((@($atListHead.PatchList.patches | ForEach-Object { [string]$_.name }) -join ',') -eq 'Alpha,Gamma' -and
        $null -eq $atListHead.Note) "A receipt at the released commit was not held to the working patch list: $($atListHead.Note)"
    $atNoList = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $releaseCommitSha -WorkingPatchList $workingList
    Assert-True ($atNoList.PatchList -eq $workingList -and $atNoList.Note -like '*no patch list*') `
        "A commit with no patch list did not fall back to the working one: $($atNoList.Note)"
    $atNoCommit = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit '' -WorkingPatchList $workingList
    Assert-True ($atNoCommit.PatchList -eq $workingList -and $null -eq $atNoCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working patch list.'
    # And the release check hands the receipt that list, names and target both, rather than the
    # working one. A helper nothing calls would pass every case above. Anchored at the end, since
    # the unanchored pattern also matched PackageVersions[0], the newest build alone; the release
    # root section below runs the check on a receipt of its own as well.
    $factsSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'validate-release-facts.ps1') -Raw
    Assert-True ($factsSource -match '-ExpectedPatchNames @\(\$resolvedList\.PatchList\.patches' -and
        $factsSource -match '\$receiptTarget = Get-PatchTarget -PatchList \$resolvedList\.PatchList' -and
        $factsSource -match '-ExpectedPackageName \$receiptTarget\.PackageName' -and
        $factsSource -match '-ExpectedPackageVersions \$receiptTarget\.PackageVersions(?![\w.\[])') `
        ('validate-release-facts.ps1 no longer holds the receipt to the patch list its own commit ' +
            'carried, or to every build that list declares.')

    # The receipt schema, read the same way: out of scripts/release-receipt.ps1 at the receipt's
    # commit. A release cut before the SBOM is held to schema 1 and says so, one cut since to this
    # checkout's schema, one from a newer checkout is refused rather than misread, and a commit
    # with no receipt script, or none at all, gets this checkout's.
    $receiptScript = Join-Path $toolchainRoot 'scripts/release-receipt.ps1'
    New-Item -ItemType Directory -Path (Split-Path -Parent $receiptScript) -Force | Out-Null
    $currentSchema = Get-ReleaseReceiptSchemaVersion
    $schemaCommits = @{}
    foreach ($written in @(1, $currentSchema, ($currentSchema + 1))) {
        Set-Content -LiteralPath $receiptScript -Encoding UTF8 -Value @('function Get-ReleaseReceiptSchemaVersion {', '    <#',
            '    .SYNOPSIS', '        Bumped when the shape changes. A receipt at return 9 would be a surprise.', '    #>',
            "    return $written", '}')
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "schema $written", '--quiet') | Out-Null
        $schemaCommits[$written] = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $atOne = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[1]
    Assert-True ($atOne.Version -eq 1 -and $atOne.Note -like "*schema 1, which its own commit $($schemaCommits[1].Substring(0, 8)) wrote*") `
        "A receipt cut at schema 1 was not held to it: $($atOne.Version), $($atOne.Note)"
    $atCurrent = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[$currentSchema]
    Assert-True ($atCurrent.Version -eq $currentSchema -and $null -eq $atCurrent.Note) `
        "A receipt cut at this checkout's schema was not held to it quietly: $($atCurrent.Version), $($atCurrent.Note)"
    Assert-Throws { Resolve-ReceiptSchema -Root $toolchainRoot -Commit $schemaCommits[$currentSchema + 1] } `
        "*writes receipt schema $($currentSchema + 1), newer than the $currentSchema this checkout reads*" `
        'A receipt schema newer than this checkout reads was read as if it were known.'
    foreach ($none in @($releaseCommitSha, '')) {
        $atNone = Resolve-ReceiptSchema -Root $toolchainRoot -Commit $none
        Assert-True ($atNone.Version -eq $currentSchema -and $null -eq $atNone.Note) `
            "A commit with no receipt script was not held to this checkout's schema: $($atNone.Version), $($atNone.Note)"
    }
    Assert-True ([System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'release-receipt.ps1')) -match
            '(?s)function\s+Get-ReleaseReceiptSchemaVersion\b.*?#>\s*return\s+(\d+)\s*\}' -and [int]$Matches[1] -eq $currentSchema) `
        'Resolve-ReceiptSchema can no longer read the schema out of this checkout''s own release-receipt.ps1.'

    # A catalog that pins nothing usable still stops the run, rather than being read as blank.
    foreach ($broken in @(
        @{ Name = 'no patcher pin'; Lines = @('[versions]', 'manager-floor = "1.29.0"') },
        @{ Name = 'no Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"') },
        @{ Name = 'an unusable Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "latest"') })) {
        Assert-Throws { Read-CatalogToolchain -Text ($broken.Lines -join "`n") -Source 'the test catalog' } `
            '*the test catalog*' "A catalog with $($broken.Name) was read without complaint."
    }
} finally {
    Remove-Item -LiteralPath $toolchainRoot, $toolchainRemote -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release receipt schema, manifest reading and validation contracts passed'

# --- release-advisories.ps1 ------------------------------------------------------------------
#
# The gate that asks OSV about the libraries a release's SBOM lists and refuses a release carrying
# a high or critical advisory. OSV's answers are recorded here as it gave them on 2026-09-25,
# trimmed to the fields the gate reads, and a stand-in for Invoke-RestMethod answers from them by
# package URL, so no case needs the network. A package URL it has no answer for fails the way an
# OSV this machine can't reach does. gson 2.8.8 is the deliberately vulnerable library:
# GHSA-4jrv-ppp4-jm57 (CVE-2022-25647), which OSV and GitHub rate HIGH.

. (Join-Path $PSScriptRoot 'release-advisories.ps1')

$gsonAdvisory = '{"id":"GHSA-4jrv-ppp4-jm57","summary":"Deserialization of Untrusted Data in Gson","aliases":["CVE-2022-25647"],"modified":"2026-09-10T03:49:19.710819205Z","database_specific":{"severity":"HIGH"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H"}]}'
$log4jCritical = '{"id":"GHSA-jfh8-c2jp-5v3q","summary":"Remote code injection in Log4j","aliases":["CVE-2021-44228"],"modified":"2025-10-22T19:37:02.616807Z","database_specific":{"severity":"CRITICAL"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H"}]}'
$log4jModerate = '{"id":"GHSA-8489-44mv-ggj8","summary":"Improper Input Validation and Injection in Apache Log4j2","aliases":["CVE-2021-44832"],"modified":"2026-06-09T10:45:14.253296471Z","database_specific":{"severity":"MODERATE"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H"}]}'
# Rated only by a CVSS 4 vector once OSV's label is taken out, which this gate can't score.
$log4jVectorFour = '{"id":"GHSA-3pxv-7cmr-fjr4","summary":"Apache Log4j Core: Silent log event loss in XmlLayout due to unescaped XML 1.0 forbidden characters","aliases":["CVE-2026-34480"],"modified":"2026-09-10T03:50:42.492278990Z","severity":[{"type":"CVSS_V4","score":"CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:N/VI:N/VA:N/SC:N/SI:L/SA:N"}]}'
$guavaModerate = '{"id":"GHSA-7g45-4rm6-3mm3","summary":"Guava vulnerable to insecure use of temporary directory","aliases":["CVE-2023-2976"],"modified":"2026-09-10T03:49:53.859811124Z","database_specific":{"severity":"MODERATE"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N"}]}'
$guavaLow = '{"id":"GHSA-5mg8-w23w-74h3","summary":"Information Disclosure in Guava","aliases":["CVE-2020-8908"],"modified":"2026-09-10T03:49:26.651391253Z","database_specific":{"severity":"LOW"},"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N"}]}'
$gsonPurl = 'pkg:maven/com.google.code.gson/gson@2.8.8'
$cleanPurl = 'pkg:maven/com.google.code.gson/gson@2.14.0'
$osvRecorded = @{
    $gsonPurl = "{`"vulns`":[$gsonAdvisory]}"
    'pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1' = "{`"vulns`":[$log4jCritical,$log4jModerate]}"
    'pkg:maven/com.google.guava/guava@31.1-jre' = "{`"vulns`":[$guavaModerate,$guavaLow]}"
    $cleanPurl = '{}'
}
# What the stand-in serves, which a case replaces to try another shape, and what it was asked.
$osvAnswers = $osvRecorded
$osvAsked = New-Object System.Collections.Generic.List[string]
$osvStandIn = {
    function Invoke-RestMethod {
        param($Uri, $Method, $ContentType, $Body, $TimeoutSec)
        $query = $Body | ConvertFrom-Json
        $key = [string]$query.package.purl
        if ($query.page_token) { $key += " page $($query.page_token)" }
        $osvAsked.Add($key)
        if (-not $osvAnswers.ContainsKey($key)) {
            throw "Unable to connect to the remote server (a stand-in for api.osv.dev with no answer for $key)"
        }
        $answer = $osvAnswers[$key]
        if ($answer -is [string] -and $answer.StartsWith('{')) { return ($answer | ConvertFrom-Json) }
        return $answer
    }
}
$advisoryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-advisories-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $advisoryRoot -Force | Out-Null
try {
    $today = [datetime]'2026-09-25'
    # What Read-ReleaseSbom hands the gate, for the libraries named.
    function New-GateSbom([string[]]$Purls) {
        $libraries = @(foreach ($purl in @($Purls | Where-Object { $_ })) {
            $parts = [regex]::Match($purl, '^pkg:maven/([^/]+)/([^@]+)@(.+)$')
            [pscustomobject]@{ Ref = $purl; Type = 'library'; Group = $parts.Groups[1].Value; Name = $parts.Groups[2].Value
                Version = $parts.Groups[3].Value; Purl = $purl }
        })
        return [pscustomobject]@{ Path = (Join-Path $advisoryRoot 'patches-9.9.9.cdx.json'); Libraries = $libraries }
    }
    # The gate on an SBOM of these libraries with these exception lines, and everything it said,
    # warnings included.
    function Invoke-Gate([string[]]$Purls, [string[]]$Exceptions = @(), [switch]$Skip) {
        $list = Join-Path $advisoryRoot 'advisory-exceptions.txt'
        Set-Content -LiteralPath $list -Encoding ASCII -Value (@('# exceptions for this case') + @($Exceptions))
        . $osvStandIn
        $osvAsked.Clear()
        return (@(Invoke-ReleaseAdvisoryGate -Sbom (New-GateSbom $Purls) -ExceptionsPath $list -Today $today `
            -SkipAdvisoryCheck:$Skip 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n")
    }
    $later = $today.AddDays(30).ToString('yyyy-MM-dd')
    $why = 'Only the build reads JSON with it, never input from outside.'

    # CVSS 3 base scores, against the numbers NVD and the specification's calculator give. The guava
    # vectors are the two that plain rounding gets wrong (5.4 and 3.2), and the one scored 8.6
    # rounds down to 8.5 that way; roundup as 3.1 defines it is the difference.
    foreach ($known in @(
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H'; Score = 9.8 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H'; Score = 7.7 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H/E:H'; Score = 10.0 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:N/I:N/A:H'; Score = 8.6 },
            @{ Vector = 'CVSS:3.1/AV:N/AC:H/PR:H/UI:N/S:U/C:H/I:H/A:H'; Score = 6.6 },
            @{ Vector = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N'; Score = 5.5 },
            @{ Vector = 'CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N'; Score = 3.3 },
            @{ Vector = 'CVSS:3.0/AV:N/AC:L/PR:L/UI:R/S:C/C:L/I:L/A:N'; Score = 5.4 },
            @{ Vector = 'CVSS:3.1/AV:P/AC:H/PR:H/UI:R/S:U/C:N/I:N/A:N'; Score = 0.0 })) {
        $scored = Get-Cvss3BaseScore -Vector $known.Vector
        Assert-True ($null -ne $scored -and [Math]::Abs($scored - $known.Score) -lt 0.001) `
            "$($known.Vector) scored $scored, not $($known.Score)."
    }
    foreach ($unscored in @('CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N',
            'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H', 'CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H',
            'CVSS:3.1/AV:n/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H', 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:u/C:H/I:H/A:H')) {
        Assert-True ($null -eq (Get-Cvss3BaseScore -Vector $unscored)) "$unscored was given a CVSS 3 score."
    }

    # How serious each recorded advisory is: OSV's label and the vector's score, whichever is worse,
    # and unrated when neither can be read.
    foreach ($rated in @(
            @{ Name = 'the gson advisory'; Json = $gsonAdvisory; Level = 'HIGH'; Serious = $true; Why = 'OSV rates it HIGH' },
            @{ Name = 'the gson advisory with no vector'; Json = $gsonAdvisory.Replace(',"severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:L/I:H/A:H"}]', '')
                Level = 'HIGH'; Serious = $true; Why = 'OSV rates it HIGH' },
            @{ Name = 'the gson advisory with no label'; Json = $gsonAdvisory.Replace('"database_specific":{"severity":"HIGH"},', '')
                Level = 'HIGH'; Serious = $true; Why = 'its CVSS 3 vector scores 7.7' },
            @{ Name = 'the gson advisory labelled LOW'; Json = $gsonAdvisory.Replace('"severity":"HIGH"', '"severity":"LOW"')
                Level = 'HIGH'; Serious = $true; Why = 'its CVSS 3 vector scores 7.7' },
            @{ Name = 'the log4j advisory'; Json = $log4jCritical; Level = 'CRITICAL'; Serious = $true; Why = 'OSV rates it CRITICAL' },
            @{ Name = 'a CVSS 4 vector alone'; Json = $log4jVectorFour; Level = 'UNRATED'; Serious = $true; Why = '*no severity*' },
            @{ Name = 'the guava temporary directory advisory'; Json = $guavaModerate; Level = 'MODERATE'; Serious = $false; Why = 'OSV rates it MODERATE' },
            @{ Name = 'a MEDIUM label'; Json = $guavaModerate.Replace('"MODERATE"', '"MEDIUM"'); Level = 'MODERATE'; Serious = $false; Why = 'OSV rates it MODERATE' },
            @{ Name = 'the guava disclosure advisory'; Json = $guavaLow; Level = 'LOW'; Serious = $false; Why = 'OSV rates it LOW' })) {
        $severity = Get-AdvisorySeverity -Advisory ($rated.Json | ConvertFrom-Json)
        Assert-True ($severity.Level -eq $rated.Level -and $severity.Serious -eq $rated.Serious -and $severity.Why -like $rated.Why) `
            "$($rated.Name) was rated $($severity.Level), serious $($severity.Serious), because $($severity.Why)."
    }

    # The exception list. Each broken line stops the read and names itself.
    $exceptionList = Join-Path $advisoryRoot 'read.txt'
    Set-Content -LiteralPath $exceptionList -Encoding ASCII -Value @('# accepted', '',
        "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why",
        "CVE-2021-44228 org.apache.logging.log4j:log4j-core 2026-09-24 $why")
    $read = @(Read-AdvisoryExceptions -Path $exceptionList -Today $today)
    Assert-True ($read.Count -eq 2 -and $read[0].Advisory -eq 'GHSA-4jrv-ppp4-jm57' -and $read[0].Package -eq 'com.google.code.gson:gson' -and
        -not $read[0].Expired -and $read[1].Expired -and $read[0].Reason -eq $why -and $read[0].Line -eq 3) `
        "The exception list was misread: $(@($read | ForEach-Object { "$($_.Advisory) $($_.Package) $($_.Until) expired=$($_.Expired)" }) -join '; ')"
    Assert-True (@(Read-AdvisoryExceptions -Path $exceptionList -Today $today.AddDays(-60)).Count -eq 2) `
        'An exception 90 days out on the day it was read was refused.'
    foreach ($broken in @(
            @{ Name = 'no date'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $why"; Pattern = '*isn''t a yyyy-MM-dd date*' },
            @{ Name = 'no package'; Line = "GHSA-4jrv-ppp4-jm57 $later $why"; Pattern = '*is not "<advisory> <group>:<name>*' },
            @{ Name = 'a date that does not exist'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson 2026-02-30 $why"; Pattern = '*isn''t a yyyy-MM-dd date*' },
            @{ Name = 'a date 91 days out'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $($today.AddDays(91).ToString('yyyy-MM-dd')) $why"
                Pattern = '*more than 90 days out*' },
            @{ Name = 'a two-word reason'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later Not reachable."; Pattern = '*without saying why*' },
            @{ Name = 'the same advisory twice'; Line = "GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why"; Twice = $true; Pattern = '*a second time*' })) {
        $lines = @($broken.Line)
        if ($broken.Twice) { $lines += $broken.Line }
        Set-Content -LiteralPath $exceptionList -Encoding ASCII -Value $lines
        Assert-Throws { Read-AdvisoryExceptions -Path $exceptionList -Today $today } $broken.Pattern `
            "An exception list with $($broken.Name) was read without complaint."
    }
    Assert-Throws { Read-AdvisoryExceptions -Path (Join-Path $advisoryRoot 'absent.txt') -Today $today } '*list is missing*' `
        'A missing exception list was read as an empty one.'
    # The date in the list is a day on the maintainer's own calendar. Both functions defaulted to
    # the UTC date, so on the east coast an exception ran out at 19:00 on the last day it named
    # and refused a release it still covered. The clock can't be moved here, so the defaults are
    # read as written.
    foreach ($dated in 'Read-AdvisoryExceptions', 'Invoke-ReleaseAdvisoryGate') {
        $parameter = @((Get-Command $dated).ScriptBlock.Ast.Body.ParamBlock.Parameters |
            Where-Object { $_.Name.VariablePath.UserPath -eq 'Today' })
        $default = if ($parameter.Count -eq 1 -and $parameter[0].DefaultValue) { $parameter[0].DefaultValue.Extent.Text } else { '' }
        Assert-True ($default -eq '[datetime]::Today') "$dated takes today as $default, not the local calendar day."
    }
    $checkedIn = Join-Path $PSScriptRoot 'advisory-exceptions.txt'
    try {
        $null = @(Read-AdvisoryExceptions -Path $checkedIn)
    } catch {
        throw "The checked-in scripts/advisory-exceptions.txt does not read: $($_.Exception.Message)"
    }

    # The gate. A clean library is asked about once and passes.
    $said = Invoke-Gate @($cleanPurl)
    Assert-True ($said -like '*OSV has no advisory for the libraries patches-9.9.9.cdx.json lists: gson 2.14.0*' -and
        ($osvAsked -join ', ') -eq $cleanPurl) "The gate did not ask OSV about the clean library, or did not say so: $said"

    # The deliberately vulnerable library is refused, naming the advisory, its alias and the version.
    Assert-Throws { Invoke-Gate @($cleanPurl, $gsonPurl) } `
        '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
        'The gate let a release carry gson 2.8.8.'
    Assert-Throws { Invoke-Gate @('pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1') } '*GHSA-jfh8-c2jp-5v3q (CRITICAL*' `
        'The gate let a release carry log4j-core 2.14.1.'
    # Moderate and low go through, and say so.
    $said = Invoke-Gate @('pkg:maven/com.google.guava/guava@31.1-jre')
    Assert-True ($said -like '*below high, let through: GHSA-7g45-4rm6-3mm3 (MODERATE*' -and
        $said -like '*below high, let through: GHSA-5mg8-w23w-74h3 (LOW*' -and $said -like '*2 advisories, none refused*') `
        "The gate refused, or said nothing of, moderate and low advisories: $said"
    # An advisory rated only by a vector this gate can't score counts as serious.
    $osvAnswers = @{ 'pkg:maven/org.apache.logging.log4j/log4j-core@2.26.0' = "{`"vulns`":[$log4jVectorFour]}" }
    try {
        Assert-Throws { Invoke-Gate @('pkg:maven/org.apache.logging.log4j/log4j-core@2.26.0') } '*GHSA-3pxv-7cmr-fjr4 (UNRATED*' `
            'The gate let an advisory through that no severity it can read describes.'
    } finally {
        $osvAnswers = $osvRecorded
    }

    # Exceptions: by OSV's id or an alias, for the one package, until the date.
    foreach ($named in @('GHSA-4jrv-ppp4-jm57', 'CVE-2022-25647', 'ghsa-4jrv-ppp4-jm57')) {
        $said = Invoke-Gate @($gsonPurl) @("$named com.google.code.gson:gson $later $why")
        Assert-True ($said -like "*accepted: GHSA-4jrv-ppp4-jm57 (HIGH*accepted until $later`: $why*") `
            "An exception naming $named did not accept the gson advisory: $said"
    }
    Assert-Throws { Invoke-Gate @($gsonPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson-extras $later $why") } `
        '*high or critical*GHSA-4jrv-ppp4-jm57*no longer reports*GHSA-4jrv-ppp4-jm57 for com.google.code.gson:gson-extras*' `
        'An exception for another package accepted the gson advisory, or was not called stale.'
    Assert-Throws { Invoke-Gate @($gsonPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson 2026-09-24 $why") } `
        '*GHSA-4jrv-ppp4-jm57 (HIGH*its exception ran out on 2026-09-24*' 'An exception past its date still accepted the gson advisory.'
    Assert-Throws { Invoke-Gate @($cleanPurl) @("GHSA-4jrv-ppp4-jm57 com.google.code.gson:gson $later $why") } `
        '*no longer reports*GHSA-4jrv-ppp4-jm57 for com.google.code.gson:gson (line 2)*' 'An exception nothing matches any more went unnoticed.'

    # OSV has to answer, and answer with something the gate can read.
    Assert-Throws { Invoke-Gate @('pkg:maven/com.example/unknown@1.0') } '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' `
        'The gate read an OSV it could not reach as no advisories.'
    $unreadable = @(
        @{ Name = 'a page of HTML'; Answer = '<html>Service Unavailable</html>'; Pattern = '*isn''t a query result*' },
        @{ Name = 'an advisory with no id'; Answer = '{"vulns":[{"summary":"no id"}]}'; Pattern = '*has no id*' })
    foreach ($odd in $unreadable) {
        $osvAnswers = @{ $cleanPurl = $odd.Answer }
        try {
            Assert-Throws { Invoke-Gate @($cleanPurl) } $odd.Pattern "The gate took $($odd.Name) from OSV for an answer."
        } finally {
            $osvAnswers = $osvRecorded
        }
    }
    # The second page of an answer is read, and an advisory OSV withdrew is not one.
    $osvAnswers = @{ $gsonPurl = '{"next_page_token":"p2"}'; "$gsonPurl page p2" = "{`"vulns`":[$gsonAdvisory]}" }
    try {
        Assert-Throws { Invoke-Gate @($gsonPurl) } '*GHSA-4jrv-ppp4-jm57 (HIGH*' 'The gate stopped at the first page of an answer.'
        Assert-True (($osvAsked -join ', ') -eq "$gsonPurl, $gsonPurl page p2") "The gate did not ask for the second page: $($osvAsked -join ', ')"
        $osvAnswers = @{ $gsonPurl = "{`"vulns`":[$($gsonAdvisory.Replace('{"id"', '{"withdrawn":"2026-09-01T00:00:00Z","id"'))]}" }
        $said = Invoke-Gate @($gsonPurl)
        Assert-True ($said -like '*OSV has no advisory*') "A withdrawn advisory refused the release: $said"
    } finally {
        $osvAnswers = $osvRecorded
    }

    # Offline work: the check is skipped with a warning and OSV isn't asked, but the exception list
    # is still read. And an SBOM with no library has nothing to ask about.
    $said = Invoke-Gate @('pkg:maven/com.example/unknown@1.0') -Skip
    Assert-True ($said -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries patches-9.9.9.cdx.json lists*' -and
        $osvAsked.Count -eq 0) "The skipped check asked OSV, or did not say it was skipped: $said"
    Assert-Throws { Invoke-Gate @($cleanPurl) @('GHSA-4jrv-ppp4-jm57 soon') -Skip } '*is not "<advisory>*' `
        'A skipped check left a broken exception list unread.'
    $said = Invoke-Gate @()
    Assert-True ($said -like '*lists no library to ask OSV about*' -and $osvAsked.Count -eq 0) `
        "An SBOM with no library was not passed as one: $said"
} finally {
    Remove-Item -LiteralPath $advisoryRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] advisory gate contracts passed'

# --- validate-release-facts.ps1 -------------------------------------------------------------
#
# The gate the pre-push hook runs on every push that touches a published file, and the one a
# release cannot go out without. Its receipt half has been exercised above since the receipt
# existed; the facts half, which is what holds README.md and patches-bundle.json to the
# generated catalog, had never been shown to fail at all.
#
# Driven against a copy of this checkout rather than a hand-built tree: a fixture assembled by
# hand is a second opinion about what the release files look like, and the thing worth catching
# is a real file drifting from the real catalog. One fact is moved per case.

$factsScript = Join-Path $PSScriptRoot 'validate-release-facts.ps1'
$factsRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-facts-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $factsRoot -Force | Out-Null
    foreach ($relative in @('patches-list.json', 'patches-bundle.json', 'gradle.properties', 'README.md', 'CHANGELOG.md')) {
        Copy-Item -LiteralPath (Join-Path $Root $relative) -Destination (Join-Path $factsRoot $relative)
    }
    New-Item -ItemType Directory -Path (Join-Path $factsRoot 'gradle') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') `
        -Destination (Join-Path $factsRoot 'gradle/libs.versions.toml')
    $bugFormRelative = '.github/ISSUE_TEMPLATE/bug_report.yml'
    New-Item -ItemType Directory -Path (Join-Path $factsRoot '.github/ISSUE_TEMPLATE') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root $bugFormRelative) -Destination (Join-Path $factsRoot $bugFormRelative)

    function Invoke-Facts {
        param([switch]$WithUrls)
        $arguments = @{ Root = $factsRoot; SkipDescriptionTestCount = $true }
        if (-not $WithUrls) { $arguments['SkipUrlCheck'] = $true }
        # 6>, not *>. The check says what it found with Write-Host, which is the information
        # stream, and that is all this wants to hide. Redirecting every stream also swallows the
        # terminating error, so each case below was accepted in silence and proved nothing.
        & $factsScript @arguments 6> $null
    }

    function Set-FactsFile {
        param([string]$Name, [scriptblock]$Edit)
        $path = Join-Path $factsRoot $Name
        $text = Get-Content -LiteralPath $path -Raw
        Set-Content -LiteralPath $path -Value (& $Edit $text) -Encoding UTF8 -NoNewline
    }

    # A prepare window: the source version has moved past the index, which still describes the
    # last release, and the gate allows that lag for an index that did not change. The copied
    # tree is not a release tree then, and holding it to the lagging index failed the control
    # for a reason that had nothing to do with the check (2026-09-17, 0.40.0 being prepared over
    # the 0.39.0 index). The copied index is written up to the catalog it sits beside, its
    # version strings, its patch count and the Facebook build it names, so every case below
    # still moves exactly one fact and is judged on the strict path.
    # A release hold is the same lag with the version standing still: patches join the catalog
    # while the index keeps the published count (2026-09-23, 93 against 0.58.0's 91), so the count
    # is synced even when the version already matches.
    # The build moves the same way. Facebook ships weekly, the catalog takes the new build before
    # the release that publishes it, and the index keeps naming the build it was released on
    # until then. Syncing only the version and the count left the description on the old build,
    # so the control failed on every tree the gate accepted in that window (2026-09-25 review).
    function Sync-FixtureIndex {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $indexPath = Join-Path $factsRoot 'patches-bundle.json'
        $indexText = Get-Content -LiteralPath $indexPath -Raw
        $index = $indexText | ConvertFrom-Json
        $indexVersion = "$($index.version)"
        $fixtureCatalog = Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json
        $count = @($fixtureCatalog.patches).Count
        $synced = $indexText
        # Bounded by digits, and by a dot on the left, so a version never rewrites part of a build
        # number: 0.10.1 is inside 583.0.0.10.10.
        if ($indexVersion -ne $fixtureVersion) {
            $synced = $synced -replace ('(?<![\d.])' + [regex]::Escape($indexVersion) + '(?!\d)'), $fixtureVersion
        }
        # (?<!\d) and not \b, here and in the count cases below: the raw JSON writes a line break
        # as \n, so a count that opens a paragraph follows the letter n, where \b finds no edge,
        # and an edit that missed it left the description saying the old count.
        $synced = $synced -replace '(?<!\d)\d+ patches\b', "$count patches"
        # The build the description names first is the one the check reads as the published
        # target, so that is the one moved to the catalog's newest, everywhere it appears.
        $fixtureBuild = (Get-PatchTarget -PatchList $fixtureCatalog).PackageVersion
        $indexBuild = [regex]::Match([string]$index.description, 'Facebook\s+(\d+(?:\.\d+)+)')
        if ($indexBuild.Success -and $indexBuild.Groups[1].Value -ne $fixtureBuild) {
            $synced = $synced -replace ('(?<![\d.])' + [regex]::Escape($indexBuild.Groups[1].Value) + '(?!\d)'), $fixtureBuild
        }
        if ($synced -ceq $indexText) { return }
        Set-FactsFile 'patches-bundle.json' { param($text) $synced }
    }

    # The bug form names the published version, which the synced index above now names too.
    function Sync-FixtureBugForm {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $publishedHere = "$((Get-Content -LiteralPath (Join-Path $Root 'patches-bundle.json') -Raw | ConvertFrom-Json).version)"
        if ($publishedHere -eq $fixtureVersion) { return }
        Set-FactsFile $bugFormRelative {
            param($text) $text -replace ('Version ' + [regex]::Escape($publishedHere) + ' for Facebook'), "Version $fixtureVersion for Facebook"
        }
    }

    function Reset-FactsFile {
        param([string]$Name)
        Copy-Item -LiteralPath (Join-Path $Root $Name) -Destination (Join-Path $factsRoot $Name) -Force
        if ($Name -eq 'patches-bundle.json') { Sync-FixtureIndex }
        if ($Name -eq $bugFormRelative) { Sync-FixtureBugForm }
    }

    Sync-FixtureIndex
    Sync-FixtureBugForm

    # The control. Everything below is this same tree with one fact moved, so a failure there is
    # the moved fact talking and not the fixture being wrong.
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check refused an unmodified copy of this checkout.'

    $catalogVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
        -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''

    # The windows the sync above exists for, built the way CONTRIBUTING's "When Facebook updates"
    # builds them: the catalog and the README move to a newer Facebook build while the index
    # still describes the last release. Prepared, the version is bumped and the CHANGELOG has
    # its entry; held, the version stays. The gate's own lenient run accepts both, and the
    # control built from the same tree has to as well, or every script push in that window stops.
    $newestBuild = (Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw |
        ConvertFrom-Json)).PackageVersion
    $movedBuild = "$([int]($newestBuild -split '\.')[0] + 3).0.0.10.10"
    $current = [version]$catalogVersion
    $nextVersion = "$($current.Major).$($current.Minor).$($current.Build + 1)"
    foreach ($window in @('prepared', 'held')) {
        if ($window -eq 'prepared') {
            Set-FactsFile 'gradle.properties' {
                param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', "`${1}$nextVersion"
            }
            Set-FactsFile 'patches-list.json' {
                param($text) $text.Replace('"v' + $catalogVersion + '"', '"v' + $nextVersion + '"')
            }
            Set-FactsFile 'CHANGELOG.md' {
                param($text) ([regex]'(?m)^## ').Replace($text,
                    "## $nextVersion (2026-09-26)`n`n* **Facebook:** Moves the patches to Facebook $movedBuild.`n`n## ", 1)
            }
        }
        Set-FactsFile 'patches-list.json' { param($text) $text.Replace($newestBuild, $movedBuild) }
        Set-FactsFile 'README.md' { param($text) $text.Replace($newestBuild, $movedBuild) }
        # What the maintainer's tree holds: the published index and form as they were, the form's
        # build moved with the catalog.
        Copy-Item -LiteralPath (Join-Path $Root 'patches-bundle.json') -Destination (Join-Path $factsRoot 'patches-bundle.json') -Force
        Copy-Item -LiteralPath (Join-Path $Root $bugFormRelative) -Destination (Join-Path $factsRoot $bugFormRelative) -Force
        Set-FactsFile $bugFormRelative {
            param($text) $text -replace ('(placeholder:\s*Version \S+ for Facebook )' + [regex]::Escape($newestBuild)), "`${1}$movedBuild"
        }
        $global:LASTEXITCODE = 0
        & $factsScript -Root $factsRoot -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6> $null
        Assert-True ($LASTEXITCODE -eq 0) "The gate refused the $window tree this case is built on, so it proves nothing."
        Sync-FixtureIndex
        Sync-FixtureBugForm
        try {
            Invoke-Facts
        } catch {
            throw "The release facts check refused the $window tree the gate accepts: $($_.Exception.Message)"
        }
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            "The release facts check refused the $window tree the gate accepts."
        $syncedDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description
        Assert-True ($syncedDescription -match "Facebook $([regex]::Escape($movedBuild))(?!\d)" -and
            $syncedDescription -notmatch [regex]::Escape($newestBuild)) `
            "The $window index was not synced to the catalog's build: $syncedDescription"
        foreach ($name in @('gradle.properties', 'patches-list.json', 'README.md', 'CHANGELOG.md',
                'patches-bundle.json', $bugFormRelative)) {
            Reset-FactsFile $name
        }
    }

    # The sync's version edit is bounded on both sides, because a version can sit in the build
    # number the index describes. Inside one, as 0.10.1 is in 583.0.0.10.10, either bound stops the
    # edit; at the end of one, as in 583.0.0.10.1, only the dot before it does; at the start, as in
    # 0.10.15, only the digit after it. Unbounded, syncing that index to 0.10.2 rewrote the build, the
    # build edit then found nothing to move, and the control would have refused the tree over a
    # build nobody released.
    foreach ($describedBuild in @('583.0.0.10.10', '583.0.0.10.1', '0.10.15')) {
        Set-FactsFile 'patches-bundle.json' {
            param($text) ($text -replace ('(?<![\d.])' + [regex]::Escape($catalogVersion) + '(?!\d)'), '0.10.1').Replace(
                $newestBuild, $describedBuild)
        }
        Set-FactsFile 'gradle.properties' { param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', '${1}0.10.2' }
        try {
            Sync-FixtureIndex
            $syncedDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description
            Assert-True ($syncedDescription -match "\bv0\.10\.2\b" -and
                $syncedDescription -match "Facebook $([regex]::Escape($newestBuild))(?!\d)" -and
                $syncedDescription -notmatch 'Facebook (?:583|0\.10)\.') `
                "Syncing an index at 0.10.1 that describes Facebook $describedBuild to 0.10.2 did not move it to the catalog's build: $syncedDescription"
        } finally {
            Reset-FactsFile 'gradle.properties'
            Reset-FactsFile 'patches-bundle.json'
        }
    }

    # Manager decodes created_at as kotlinx.datetime.LocalDateTime, not Instant.
    # A trailing Z produces its generic "remote metadata file is unavailable" error,
    # even when both the JSON and bundle download answer HTTP 200.
    foreach ($invalidTimestamp in @(
            '"2026-09-20T21:23:31Z"',
            '"2026-09-20T21:23:31+00:00"',
            '"2026-02-30T21:23:31"',
            '"2026-09-20"',
            '""',
            'null',
            '1790000000')) {
        Set-FactsFile 'patches-bundle.json' {
            param($text)
            $text -replace '"created_at"\s*:\s*("[^"\r\n]*"|null|\d+)', ('"created_at": ' + $invalidTimestamp)
        }
        Assert-Throws { Invoke-Facts } '*created_at*' `
            "An index with a Manager-incompatible created_at was accepted: $invalidTimestamp"
        Reset-FactsFile 'patches-bundle.json'
    }
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '"created_at"\s*:\s*"[^"\r\n]*"\s*,', ''
    }
    Assert-Throws { Invoke-Facts } '*created_at*' 'An index without created_at was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # A published index naming a version the catalog does not build. This is the shape v0.28.0
    # shipped in: the index said one thing and the bundle behind it was another.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace [regex]::Escape('"' + $catalogVersion + '"'), '"0.0.1"'
    }
    Assert-Throws { Invoke-Facts } '*' 'A published index naming another version was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # The count the published description quotes, which is what somebody reads before they
    # install. A number nobody would notice by eye, which is why the catalog is the source of it.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '(?<!\d)\d+ patches\b', '3 patches'
    }
    Assert-Throws { Invoke-Facts } '*' 'An index counting patches the catalog does not have was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # The same number in the README, which is the other half of the same promise.
    Set-FactsFile 'README.md' {
        param($text) $text -replace '(?<!\d)\d+ patches\b', '3 patches'
    }
    Assert-Throws { Invoke-Facts } '*' 'A README counting patches the catalog does not have was accepted.'
    Reset-FactsFile 'README.md'

    # The Manager floor in the README is what stops somebody being told to use a Manager that
    # refuses the bundle, so it is held to the patcher the catalog pins.
    #
    # The floor is read out of the fixture rather than written here. It used to name 1.29.0, and
    # when the catalog moved to Manager 1.30.0 the replacement below stopped matching: the README
    # went in unchanged, the check passed as it should have, and this case failed with "No error
    # was raised" while the gate it covers was working perfectly. A version literal in a test
    # goes stale on the next bump, and does it silently until something reads the message.
    $fixtureFloor = ([regex]::Match(
        (Get-Content -LiteralPath (Join-Path $factsRoot 'gradle/libs.versions.toml') -Raw),
        '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')).Groups[1].Value
    Assert-True ($fixtureFloor -match '^\d+\.\d+\.\d+$') `
        "The fixture catalog does not pin a Manager floor, so this case would prove nothing: $fixtureFloor"
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('Morphe Manager ' + [regex]::Escape($fixtureFloor) + ' or newer'),
            'Morphe Manager 1.20.0 or newer'
    }
    Assert-Throws { Invoke-Facts } '*' 'A README naming a Manager older than the patcher needs was accepted.'
    Reset-FactsFile 'README.md'

    # The same floor in the install step, where a link sits between the name and the version, so
    # the pattern above never reached it and a stale step passed while the badge was right. And in
    # the badge's picture, which carries it URL-encoded.
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('(\[Morphe Manager\]\([^)\s]+\)\s+)' + [regex]::Escape($fixtureFloor) + ' or newer'),
            '${1}1.20.0 or newer'
    }
    Assert-Throws { Invoke-Facts } '*Morphe Manager 1.20.0 or newer*' `
        'A README whose install step names an older Manager was accepted.'
    Reset-FactsFile 'README.md'
    Set-FactsFile 'README.md' {
        param($text) $text.Replace("Morphe%20Manager%20$fixtureFloor%2B", 'Morphe%20Manager%201.20.0%2B')
    }
    Assert-Throws { Invoke-Facts } '*README badge Manager floor*' 'A README badge showing an older Manager was accepted.'
    Reset-FactsFile 'README.md'

    # And each copy has to be there. The alt text and install step cases above name a stale floor,
    # which the stale-floor check refuses before the presence checks run, so neither presence check
    # was ever reached. An alt text with no version, or a step reworded past the stale-floor
    # pattern ("or later" for "or newer"), leaves no stale floor to find, and each is refused by
    # its own check's name.
    Set-FactsFile 'README.md' {
        param($text) $text.Replace("alt=`"For Morphe Manager $fixtureFloor or newer`"", 'alt="For Morphe Manager"')
    }
    Assert-Throws { Invoke-Facts } '*README badge alt Manager floor*' 'A README badge whose alt text names no Manager was accepted.'
    Reset-FactsFile 'README.md'
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('(?m)^(\d+\.\s+Install \[Morphe Manager\]\([^)\s]+\))\s+' + [regex]::Escape($fixtureFloor) +
            '\s+or newer\.'), '${1}, version 1.31.0 or later.'
    }
    Assert-Throws { Invoke-Facts } '*README install step Manager floor*' `
        'A README install step reworded past the stale-floor check was accepted.'
    Reset-FactsFile 'README.md'

    # The heading that says this version shipped. Renaming it is what happened on 2026-09-14,
    # and the file is read by this check and by nothing else.
    Set-FactsFile 'CHANGELOG.md' {
        param($text) $text -replace ('(?m)^##\s+' + [regex]::Escape($catalogVersion) + '\b.*$'), '## Unreleased'
    }
    Assert-Throws { Invoke-Facts } '*' 'A CHANGELOG with no heading for the built version was accepted.'
    Reset-FactsFile 'CHANGELOG.md'

    # The bug form's placeholders, which sat three releases behind the target on Hushfeed before
    # anything read them. One for the version line, one for the manager line.
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace '(placeholder:\s*Version \S+ for Facebook )\S+', '${1}46.2.3'
    }
    Assert-Throws { Invoke-Facts } '*bug report form version placeholder*' `
        'A bug report form naming an old Facebook build was accepted.'
    Reset-FactsFile $bugFormRelative
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace '(placeholder:\s*Morphe Manager )\S+', '${1}1.20.0'
    }
    Assert-Throws { Invoke-Facts } '*bug report form Manager placeholder*' `
        'A bug report form naming a Manager below the floor was accepted.'
    Reset-FactsFile $bugFormRelative

    # The download address, one check at a time in the order the script makes them: the release
    # asset's path, HTTPS, then GitHub. Each is refused by name, because a pattern that accepts
    # any error let this case pass on the shape check while it said it tested a dead link.
    foreach ($address in @(
            @{ From = 'https://github\.com/SysAdminDoc/Hushfacebook/releases/download/[^"]+'; To = 'http://127.0.0.1:1/patches.mpp'
                Pattern = '*patches-bundle.json download URL does not match*'; Name = 'an index whose address is not the release asset' },
            @{ From = '"https://github\.com/'; To = '"http://github.com/'
                Pattern = '*must use HTTPS*'; Name = 'an index serving the bundle over plain HTTP' },
            @{ From = '"https://github\.com/'; To = '"https://example.com/'
                Pattern = '*must be on github.com*'; Name = 'an index serving the bundle from another host' })) {
        Set-FactsFile 'patches-bundle.json' { param($text) $text -replace $address.From, $address.To }
        Assert-Throws { Invoke-Facts } $address.Pattern "The release facts check accepted $($address.Name)."
        Reset-FactsFile 'patches-bundle.json'
    }

    # A dead link: the address has every right shape and names a release nobody published, which
    # is how the index once pointed at a tag that did not exist yet. GitHub answers 404 for it;
    # here a stand-in for the request does, so the case needs no network. The fetch itself is
    # held to a refused connection with the shared helpers below.
    & {
        function Invoke-WebRequest { throw (New-NotFoundAnswer) }
        Assert-Throws { Invoke-Facts -WithUrls } '*indexed bundle URL https://github.com/*answered HTTP 404*' `
            'An index naming a release nobody published was accepted.'
    }

    # The test counts the description quotes, which only the strict path reads: a release, or the
    # push that rewrites the index. The copied tree holds no test results, so each folder gets a
    # suite of exactly as many tests as the copied description names, and one fact moves per case.
    $factsDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw |
        ConvertFrom-Json).description
    $runtimeQuoted = [int]([regex]::Match($factsDescription, '\b(\d+) runtime tests passed\b').Groups[1].Value)
    $patchQuoted = [int]([regex]::Match($factsDescription, '\b(\d+) patch tests passed\b').Groups[1].Value)
    Assert-True ($runtimeQuoted -gt 0 -and $patchQuoted -gt 0) `
        "The copied description quotes no test counts, so these cases would prove nothing: $factsDescription"
    function Write-FactsResults {
        param([string]$Folder, [string]$Suite, [int]$Tests, [int]$Skipped = 0)
        $directory = Join-Path $factsRoot $Folder
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $cases = (1..$Tests | ForEach-Object {
            if ($_ -le $Skipped) { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"><skipped/></testcase>" }
            else { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"/>" }
        }) -join ''
        Set-Content -LiteralPath (Join-Path $directory "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
            "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"$Tests`" " +
            "skipped=`"$Skipped`" failures=`"0`" errors=`"0`">$cases</testsuite>")
    }
    function Invoke-StrictFacts { & $factsScript -Root $factsRoot -SkipUrlCheck 6> $null }
    $runtimeResults = 'extensions/facebook/build/test-results/testDebugUnitTest'
    $patchResults = 'patches/build/test-results/test'
    try {
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'The strict release check refused test results that match the description.'

        # A fixture test that skipped, which Gradle reports as a pass.
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted -Skipped 1
        Assert-Throws { Invoke-StrictFacts } '*skipped 1 test*' `
            'A release was checked against patch test results with a skipped fixture test.'

        # A count the run doesn't have, which is how "All 269 patch tests passed" was written.
        Write-FactsResults $patchResults 'PatchTest' ($patchQuoted + 1)
        Assert-Throws { Invoke-StrictFacts } '*patch test count*' `
            'A description quoting a patch test count the run does not have was accepted.'

        # A test class deleted since the last run. Its results stay until the tests run again,
        # no source left is newer than them, and every class left has results, so its tests
        # were counted as passing. The sources go in first so the results are the newer files.
        $runtimeSource = Join-Path $factsRoot 'extensions/facebook/src/test/java/fixture/RuntimeTest.java'
        $patchSource = Join-Path $factsRoot 'patches/src/test/kotlin/fixture/PatchTest.kt'
        foreach ($source in @($runtimeSource, $patchSource)) {
            New-Item -ItemType Directory -Path (Split-Path -Parent $source) -Force | Out-Null
            Set-Content -LiteralPath $source -Value '' -Encoding ASCII
        }
        function Add-OrphanResult([string]$Folder, [string]$Suite) {
            Set-Content -LiteralPath (Join-Path (Join-Path $factsRoot $Folder) "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
                "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"1`" " +
                "skipped=`"0`" failures=`"0`" errors=`"0`"><testcase name=`"t1`" classname=`"fixture.$Suite`"/></testsuite>")
        }
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Write-FactsResults $runtimeResults 'RuntimeTest' ($runtimeQuoted - 1)
        Add-OrphanResult $runtimeResults 'GoneTest'
        Assert-Throws { Invoke-StrictFacts } '*runtime test results include 1 test class*GoneTest*' `
            'The runtime results of a deleted test class were counted.'
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' ($patchQuoted - 1)
        Add-OrphanResult $patchResults 'GonePatchTest'
        Assert-Throws { Invoke-StrictFacts } '*patch test results include 1 test class*GonePatchTest*' `
            'The patch results of a deleted test class were counted.'
        # The control: the same sources with no orphan pass.
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchQuoted
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'The strict release check refused results that match their sources and the description.'
        foreach ($folder in @('extensions/facebook/src', 'patches/src')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force
        }

        Remove-Item -LiteralPath (Join-Path $factsRoot 'patches') -Recurse -Force
        Assert-Throws { Invoke-StrictFacts } '*No patch test results*' `
            'A release was checked with no patch test results at all.'

        # Results the check is told to leave unread, as the pre-push hook does in its worktree,
        # where they can belong to another commit: read, a skipped runtime test fails the lenient
        # check; unread, it doesn't. A check that quotes counts can't be told to skip them.
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted -Skipped 1
        Assert-Throws { Invoke-Facts } '*skipped=1*' 'A lenient check read past a skipped runtime test.'
        & $factsScript -Root $factsRoot -SkipDescriptionTestCount -SkipUrlCheck -SkipTestResults 6> $null
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'A check told to leave the test results unread read them anyway.'
        Assert-Throws { & $factsScript -Root $factsRoot -SkipUrlCheck -SkipTestResults 6> $null } `
            '*SkipDescriptionTestCount*' 'A check holding the description to its counts left the results unread.'
    } finally {
        foreach ($folder in @('patches', 'extensions')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # And the same tree, once every fact is put back, is accepted again. Without this the cases
    # above would also pass against a fixture that had become permanently broken.
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check refused the fixture after every change was put back.'
} finally {
    Remove-Item -LiteralPath $factsRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release facts contracts passed'

# --- pre-push.ps1 ----------------------------------------------------------------------------
#
# Which files make the hook run the release check. The receipt is what the check holds a release
# to and the allowlist is what it accepts manifest changes from, and a push that moved only one
# of them ran no release check at all. Driven against a stub root whose validate script records
# that it was called, so the case proves the routing and not the check.

$prePushScript = Join-Path $PSScriptRoot 'pre-push.ps1'
$hookRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-hook-" + [guid]::NewGuid().ToString('N'))
$savedSkip = $env:HUSHFACEBOOK_SKIP_PRE_PUSH
$savedHookGit = @{}
# These cases invoke another hook against a foreign repository. Git's own hook environment
# must not leak into that repository or its local bare transport, including GIT_EXEC_PATH.
foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
    $savedHookGit[$variable.Name] = $variable.Value
    Remove-Item -LiteralPath ('Env:\' + $variable.Name)
}
try {
    $env:HUSHFACEBOOK_SKIP_PRE_PUSH = $null
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'scripts') -Force | Out-Null
    # A repository from the start, empty until the cases below commit to it: every push is scanned
    # for machine names now, a run by hand included, and the scan reads the root through git.
    & git -C $hookRoot init --quiet
    $actualHookGitDir = (& git -C $hookRoot rev-parse --absolute-git-dir).Trim()
    Assert-True ([IO.Path]::GetFullPath($actualHookGitDir).TrimEnd('\', '/') -ieq
        [IO.Path]::GetFullPath((Join-Path $hookRoot '.git')).TrimEnd('\', '/')) `
        'The hook fixture resolved outside its temporary repository; refusing to write.'
    & git -C $hookRoot config user.name 'Hook Contract'
    & git -C $hookRoot config user.email 'hook@example.invalid'
    $factsMarker = Join-Path $hookRoot 'facts-ran.txt'
    $contractsMarker = Join-Path $hookRoot 'contracts-ran.txt'
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
        '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath, [switch]$ArtifactIsHosted)',
        ("Set-Content -LiteralPath '$factsMarker' -Value " +
            "`"lag=`$AllowPublishedIndexLag verify=`$VerifyPublishedAsset artifact=`$ArtifactPath hosted=`$ArtifactIsHosted`""),
        'exit 0')
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)',
        "Set-Content -LiteralPath '$contractsMarker' -Value 'ran'",
        'exit 0')

    function Invoke-Hook {
        param([string[]]$Paths)
        Remove-Item -LiteralPath $factsMarker, $contractsMarker -Force -ErrorAction SilentlyContinue
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $hookRoot -ChangedPaths $Paths 6> $null
        if ($LASTEXITCODE -ne 0) { throw "pre-push exited $LASTEXITCODE for $($Paths -join ', ')" }
    }

    # The control: a file no gate reads runs no gate, so a marker below is the routing talking.
    Invoke-Hook -Paths @('CONTRIBUTING.md')
    Assert-True (-not (Test-Path -LiteralPath $factsMarker)) `
        'The release check ran for a push that changed nothing it reads.'

    # HUSHFACEBOOK_SKIP_PRE_PUSH is the way through the hook's own messages offer, and it has to
    # work whatever the working tree holds. A copy of the hook beside a common.ps1 with conflict
    # markers in it, as mid-rebase, stops on the parse error without the switch and goes through
    # with it. The hook used to load common.ps1 first and stop either way.
    $brokenHook = Join-Path $hookRoot 'broken-hook'
    New-Item -ItemType Directory -Path (Join-Path $brokenHook 'scripts') -Force | Out-Null
    Copy-Item -LiteralPath $prePushScript -Destination (Join-Path $brokenHook 'scripts/pre-push.ps1')
    Set-Content -LiteralPath (Join-Path $brokenHook 'scripts/common.ps1') -Encoding ASCII -Value @(
        '<<<<<<< HEAD', 'function Find-MachineNames {', '=======', '>>>>>>> theirs')
    $brokenPrePush = Join-Path $brokenHook 'scripts/pre-push.ps1'
    try {
        Assert-Throws { & $brokenPrePush -Root $brokenHook -ChangedPaths @('README.md') 6> $null } '*common.ps1*' `
            'The hook ran with a common.ps1 that does not parse, so the case below would prove nothing.'
        $env:HUSHFACEBOOK_SKIP_PRE_PUSH = '1'
        $global:LASTEXITCODE = 0
        try {
            $skipped = @(& $brokenPrePush -Root $brokenHook -ChangedPaths @('README.md') 6>&1 | ForEach-Object { "$_" }) -join "`n"
        } catch {
            throw "HUSHFACEBOOK_SKIP_PRE_PUSH=1 did not get a push past a common.ps1 that does not parse: $($_.Exception.Message)"
        }
        Assert-True ($LASTEXITCODE -eq 0 -and $skipped -like '*skipped by HUSHFACEBOOK_SKIP_PRE_PUSH*') `
            "HUSHFACEBOOK_SKIP_PRE_PUSH=1 did not say it skipped the hook: $skipped"
    } finally {
        $env:HUSHFACEBOOK_SKIP_PRE_PUSH = $null
        Remove-Item -LiteralPath $brokenHook -Recurse -Force -ErrorAction SilentlyContinue
    }

    Invoke-Hook -Paths @('release-receipt-0.31.0.json')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the release receipt ran no release check.'
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=True*') `
        'A receipt-only push took the strict published-index path.'

    Invoke-Hook -Paths @('scripts/manifest-delta-allowlist.txt')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the manifest delta allowlist ran no release check.'
    Assert-True (Test-Path -LiteralPath $contractsMarker) `
        'A push that changed the manifest delta allowlist skipped the script contract tests.'

    # Any script change runs the contract tests, and a gate that finds them gone stops the push
    # instead of noting it and carrying on, which is how this suite was missing for Hushfacebook's
    # first commits.
    Invoke-Hook -Paths @('scripts/patch-report.ps1')
    Assert-True (Test-Path -LiteralPath $contractsMarker) `
        'A push that changed a script outside every named suite skipped the script contract tests.'
    $contractsStubPath = Join-Path $hookRoot 'scripts/test-script-contracts.ps1'
    $contractsStubText = Get-Content -LiteralPath $contractsStubPath -Raw
    Remove-Item -LiteralPath $contractsStubPath -Force
    try {
        Assert-Throws { Invoke-Hook -Paths @('scripts/patch-report.ps1') } '*test-script-contracts.ps1 is missing*' `
            'A script push went through with the contract tests missing.'
        Assert-Throws { Invoke-Hook -Paths @('scripts/test-script-contracts.ps1') } '*was deleted in this push*' `
            'A push that deleted the contract tests went through.'
    } finally {
        Set-Content -LiteralPath $contractsStubPath -Value $contractsStubText -Encoding UTF8 -NoNewline
    }

    # The five verifier suites run only when their own files move, and pre-push.ps1 decides which
    # files those are. A push of pre-push.ps1 runs this suite and no other, so an edit that put a
    # suite line behind a dead branch, or dropped a file from a suite's list, went out through the
    # gate it switched off. This suite is the one place that holds the routing, then: all six
    # suite lines read through the parser (script-wiring.ps1), each also tried behind a dead
    # branch so the check can't pass by passing everything, and every file a verifier suite guards
    # pushed through the hook against stub suites that record they ran. A file starts exactly the
    # suites whose lists hold it. The source ledger's suite also guards files outside scripts/,
    # which are pushed after.
    $prePushSource = [System.IO.File]::ReadAllText($prePushScript)
    $deadSuiteCopy = Join-Path $hookRoot 'pre-push-dead-suite.ps1'
    $verifierRoutes = [ordered]@{
        'scripts/test-injected-registers.ps1' = @('BadDexFixture.java', 'DexDiff.java', 'injected-mutation-contracts.txt',
            'injected-register-contracts.ps1', 'injected-register-removal-allowlist.txt', 'script-wiring.ps1',
            'test-injected-registers.ps1', 'verify-all-patches.ps1', 'verify-injected-registers.ps1')
        'scripts/test-resource-table-check.ps1' = @('ResourceTableCheck.java', 'test-resource-table-check.ps1',
            'verify-all-patches.ps1')
        'scripts/test-injected-register-device.ps1' = @('injected-register-device.ps1', 'script-wiring.ps1',
            'test-injected-register-device.ps1', 'verify-injected-registers.ps1')
        'scripts/test-fingerprint-candidates.ps1' = @('FingerprintCandidates.java', 'FingerprintFixture.java',
            'fingerprint-calibration.txt', 'fingerprint-candidates.ps1', 'fingerprint-signature.schema.json',
            'test-fingerprint-candidates.ps1')
        'scripts/test-facebook-sources.ps1' = @('audit-facebook-sources.ps1', 'facebook-sources.ps1', 'patch-target.ps1',
            'test-facebook-sources.ps1')
    }
    foreach ($suite in @('scripts/test-script-contracts.ps1') + @($verifierRoutes.Keys)) {
        Assert-True (Test-PushGateRunsSuite $prePushScript $suite) "The push gate does not run $suite."
        $suiteLine = { param($Node)
            $Node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
            $Node.Left.Extent.Text -eq '$suites' -and $Node.Extent.Text -like "*'$suite'*" }.GetNewClosure()
        [System.IO.File]::WriteAllText($deadSuiteCopy,
            (Edit-ScriptNode $prePushSource $suiteLine { param($Text) "if (`$false) { $Text }" }))
        Assert-True (-not (Test-PushGateRunsSuite $deadSuiteCopy $suite)) `
            "The wiring check read $suite as running from behind a dead branch."
    }
    $verifierMarker = { param([string]$Suite) Join-Path $hookRoot ([IO.Path]::GetFileNameWithoutExtension($Suite) + '-ran.txt') }
    foreach ($suite in $verifierRoutes.Keys) {
        Set-Content -LiteralPath (Join-Path $hookRoot $suite) -Encoding UTF8 -Value @(
            'param([string]$Root)', "Set-Content -LiteralPath '$(& $verifierMarker $suite)' -Value 'ran'", 'exit 0')
    }
    foreach ($file in @(@($verifierRoutes.Values | ForEach-Object { $_ }) + @('patch-report.ps1') | Select-Object -Unique)) {
        foreach ($suite in $verifierRoutes.Keys) { Remove-Item -LiteralPath (& $verifierMarker $suite) -Force -ErrorAction SilentlyContinue }
        Invoke-Hook -Paths @("scripts/$file")
        $ran = @($verifierRoutes.Keys | Where-Object { Test-Path -LiteralPath (& $verifierMarker $_) }) -join ', '
        $expected = @($verifierRoutes.Keys | Where-Object { $verifierRoutes[$_] -contains $file }) -join ', '
        Assert-True ($ran -eq $expected) "A push of scripts/$file ran [$ran], not [$expected]."
    }
    # The ledger's rules read NOTICE, provenance.json and the catalog, and hold docs/sources.md to
    # the ledger. A push of any of those, or of the ledger alone, runs its suite and no other
    # verifier; the catalog also runs the release facts and the contract tests, held below.
    foreach ($file in @('sources/facebook-sources.json', 'NOTICE', 'provenance.json', 'docs/sources.md', 'patches-list.json')) {
        foreach ($suite in $verifierRoutes.Keys) { Remove-Item -LiteralPath (& $verifierMarker $suite) -Force -ErrorAction SilentlyContinue }
        Invoke-Hook -Paths @($file)
        $ran = @($verifierRoutes.Keys | Where-Object { Test-Path -LiteralPath (& $verifierMarker $_) }) -join ', '
        Assert-True ($ran -eq 'scripts/test-facebook-sources.ps1') "A push of $file ran [$ran], not the source ledger's suite alone."
        if ($file -ne 'patches-list.json') {
            Assert-True (-not (Test-Path -LiteralPath $contractsMarker) -and -not (Test-Path -LiteralPath $factsMarker)) `
                "A push of $file ran the contract tests or the release facts, which read nothing it changes."
        }
    }

    # The catalog is held to Meta's two signers, the builds every patch declares and the internal
    # dependencies the release scripts expect, and only the contract tests read it for those. A
    # push of the catalog alone ran the release facts and never them.
    Invoke-Hook -Paths @('patches-list.json')
    Assert-True ((Test-Path -LiteralPath $factsMarker) -and (Test-Path -LiteralPath $contractsMarker)) `
        'A push that changed only the catalog did not run both the release check and the script contract tests.'

    # These tests end with the marketing asset check, which holds the artwork and the README's hero
    # and links. A push of only an icon ran no gate, and one of only the README ran the release
    # check alone.
    foreach ($artwork in 'assets/icons/icon-16.png', 'concepts/marketing/2026-09-25/selected/icon-master.png', 'README.md') {
        Invoke-Hook -Paths @($artwork)
        Assert-True (Test-Path -LiteralPath $contractsMarker) `
            "A push that changed only $artwork did not run the script contract tests, which hold the marketing assets."
    }

    Invoke-Hook -Paths @('CHANGELOG.md')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the CHANGELOG ran no release check.'
    Assert-True (-not (Test-Path -LiteralPath $contractsMarker)) `
        'A push that changed only the CHANGELOG ran the script contract tests; the release check is what holds that file.'

    Invoke-Hook -Paths @('.github/ISSUE_TEMPLATE/bug_report.yml')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the bug report form ran no release check.'

    Invoke-Hook -Paths @('patches-bundle.json')
    Assert-True (Test-Path -LiteralPath $factsMarker) 'An index change ran no release check.'
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=False*') `
        'An index change was allowed to lag behind the published release.'
    # With no bundle built here the hosted one is checked on its own, never skipped; the release
    # root section below runs that against the real check.
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like '*verify=True artifact= hosted=True*') `
        "An index push with no bundle built here did not have the hosted one checked: $(Get-Content -LiteralPath $factsMarker -Raw)"

    # The order that shipped a dexless bundle: buildAndroid, then any task that reruns
    # :patches:jar, which leaves the plain jar in build/libs under the bundle's own name. The
    # finished bundle sits in build/release, and the index push must be compared against that
    # one. A plain jar left beside it in build/libs is the state :patches:test produces.
    $releaseDirectory = Join-Path $hookRoot 'patches/build/release'
    $libsDirectory = Join-Path $hookRoot 'patches/build/libs'
    New-Item -ItemType Directory -Path $releaseDirectory, $libsDirectory -Force | Out-Null
    # Normalized, because the hook hands over the listing's own full name.
    $releaseCopy = [System.IO.Path]::GetFullPath((Join-Path $releaseDirectory 'patches-9.9.9.mpp'))
    Set-Content -LiteralPath $releaseCopy -Value 'bundle with classes.dex' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $libsDirectory 'patches-9.9.9.mpp') -Value 'plain jar' -Encoding ASCII
    Invoke-Hook -Paths @('patches-bundle.json')
    $routed = Get-Content -LiteralPath $factsMarker -Raw
    Assert-True ($routed -like '*verify=True*') `
        'An index push with a built release bundle did not compare it against the published asset.'
    Assert-True ($routed -like "*artifact=$releaseCopy hosted=False*") `
        "The index push compared something other than the release copy: $routed"
    Remove-Item -LiteralPath (Join-Path $hookRoot 'patches') -Recurse -Force

    # A new remote branch can contain several unpublished commits. The code change here is in
    # the first commit and the tip changes only documentation. Looking at HEAD^..HEAD silently
    # misses the code and skips every build gate.
    $newBranchSource = Join-Path $hookRoot 'extensions/facebook/src/main/java/FirstCommit.java'
    New-Item -ItemType Directory -Path (Split-Path -Parent $newBranchSource) -Force | Out-Null
    Set-Content -LiteralPath $newBranchSource -Encoding UTF8 -Value 'final class FirstCommit {}'
    & git -C $hookRoot add extensions/facebook/src/main/java/FirstCommit.java
    & git -C $hookRoot commit --quiet -m 'code first'
    $firstCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    Set-Content -LiteralPath (Join-Path $hookRoot 'CONTRIBUTING.md') -Encoding UTF8 -Value 'tip only'
    & git -C $hookRoot add CONTRIBUTING.md
    & git -C $hookRoot commit --quiet -m 'docs tip'
    $newBranchHead = (& git -C $hookRoot rev-parse HEAD).Trim()
    # A stale local remote-tracking ref that already points at the code commit must not subtract
    # that commit from a new branch push. The destination advertises this branch as new.
    & git -C $hookRoot update-ref refs/remotes/origin/stale $firstCommit
    $newBranchRefs = "refs/heads/new $newBranchHead refs/heads/new $('0' * 40)"
    $savedNewBranchPath = $env:PATH
    $savedNewBranchActor = $env:GITHUB_ACTOR
    $savedNewBranchToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = Split-Path -Parent (Get-Command git).Source
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs $newBranchRefs 6> $null } `
            '*GITHUB_ACTOR*' 'A new branch checked only its documentation tip and skipped earlier code.'
    } finally {
        $env:PATH = $savedNewBranchPath
        $env:GITHUB_ACTOR = $savedNewBranchActor
        $env:GITHUB_TOKEN = $savedNewBranchToken
    }

    # A release tag can name the exact tree already on a remote branch. It adds a pointer,
    # not a new index: treating it as a first branch push creates a publication deadlock.
    $tagRemote = Join-Path $hookRoot 'tag-remote.git'
    & git init --bare --quiet $tagRemote
    & git -C $hookRoot push --quiet $tagRemote "${newBranchHead}:refs/heads/main"
    & git -C $hookRoot tag -a release-same-tree -m 'release' $newBranchHead
    $tagObject = (& git -C $hookRoot rev-parse refs/tags/release-same-tree).Trim()
    $tagRefs = "refs/tags/release-same-tree $tagObject refs/tags/release-same-tree $('0' * 40)"
    # Git hooks put git-core first. Keeping only that directory breaks Windows Git's local
    # transport because its runtime DLLs live elsewhere. Hide gh, not Git's dependencies.
    $pathWithoutGh = (@($savedNewBranchPath -split [IO.Path]::PathSeparator | Where-Object {
        $directory = $_.Trim('"')
        $directory -and -not (@('gh', 'gh.exe', 'gh.cmd', 'gh.bat') | Where-Object {
            Test-Path -LiteralPath (Join-Path $directory $_) -PathType Leaf
        })
    }) -join [IO.Path]::PathSeparator)
    try {
        $env:PATH = $pathWithoutGh
        Assert-True (-not (Get-Command gh -ErrorAction SilentlyContinue)) 'The tag fixture still exposes gh.'
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $tagRefs 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'An annotated tag of the advertised remote tree reran file-change gates.'
        $lightTagRefs = "refs/tags/light $newBranchHead refs/tags/light $('0' * 40)"
        & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $lightTagRefs 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'A lightweight tag of the advertised remote tree reran file-change gates.'

        # A stale local tracking ref is not proof that the target is hosted. Only the live
        # remote advertisement above can qualify; unknown targets retain the full-tree gate.
        $unknownTagRefs = "refs/tags/unknown $firstCommit refs/tags/unknown $('0' * 40)"
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $unknownTagRefs 6> $null } `
            '*GITHUB_ACTOR*' 'A tag not matching an advertised remote branch skipped code checks.'
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs "$tagRefs`n$newBranchRefs" 6> $null } `
            '*GITHUB_ACTOR*' 'A known tag suppressed checks for a new branch in the same push.'
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl (Join-Path $hookRoot 'missing-remote.git') -PushedRefs $tagRefs 6> $null } `
            '*remote branches*' 'An unavailable remote was treated as proof that a tag target was already hosted.'
    } finally {
        $env:PATH = $savedNewBranchPath
        $env:GITHUB_ACTOR = $savedNewBranchActor
        $env:GITHUB_TOKEN = $savedNewBranchToken
    }

    # The scan every push gets. A doc naming the maintainer's notes folder or a phone's serial
    # routes to no gate at all, and a test under patches/ or extensions/ only to the Gradle
    # gates, which never read for them, so neither reached the contract tests' scan. The hook
    # reads the pushed commit, whatever the working tree holds, and .gitignore stays exempt.
    $notesFolder = '.' + 'cla' + 'ude'
    $phoneSerial = 'R5' + 'C' + 'ZZZZ9999'
    $phoneDoc = Join-Path $hookRoot 'docs/phone.md'
    New-Item -ItemType Directory -Path (Split-Path -Parent $phoneDoc) -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $hookRoot 'CONTRIBUTING.md') -Encoding UTF8 -Value "Fixtures live in ~/$notesFolder/fixtures."
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    Set-Content -LiteralPath (Join-Path $hookRoot '.gitignore') -Encoding UTF8 -Value "$notesFolder/"
    & git -C $hookRoot add CONTRIBUTING.md docs/phone.md .gitignore
    & git -C $hookRoot commit --quiet -m 'names the machine'
    $namingCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $namingCommit refs/heads/main $newBranchHead" 6> $null } `
        "*$namingCommit*CONTRIBUTING.md*docs/phone.md*" 'A push of docs naming the machine and a phone went through.'
    Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('docs/phone.md') 6> $null } `
        '*the working tree*CONTRIBUTING.md*docs/phone.md*' 'A run by hand passed a working tree whose docs name the machine.'
    # The control: the same files naming nothing, .gitignore still naming the folder, go through.
    Set-Content -LiteralPath (Join-Path $hookRoot 'CONTRIBUTING.md') -Encoding UTF8 -Value 'Fixtures live in HUSHFACEBOOK_FIXTURE_DIR.'
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value 'adb -s $env:HUSHFACEBOOK_DEVICE_SERIAL install app.apk'
    & git -C $hookRoot add CONTRIBUTING.md docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names nothing'
    $cleanCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    $global:LASTEXITCODE = 0
    & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $cleanCommit refs/heads/main $namingCommit" 6> $null
    Assert-True ($LASTEXITCODE -eq 0) 'A push whose files name nothing, .gitignore aside, was stopped.'

    # Both cases above push HEAD of a tree that matches it, so a scan of the working tree passed
    # them too. Here the pushed commit and the working tree disagree, both ways round, and the
    # refusal is read for the <commit>:<path>: prefix git grep gives only a hit it read out of a
    # commit: the naming commit is refused while the tree holds the clean text, and the clean
    # commit goes through while the tree names the machine.
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $namingCommit refs/heads/main $newBranchHead" 6> $null } `
        "*${namingCommit}:CONTRIBUTING.md:1:*${namingCommit}:docs/phone.md:1:*" `
        'A push of a commit naming the machine went through because the working tree does not.'
    Set-Content -LiteralPath (Join-Path $hookRoot 'CONTRIBUTING.md') -Encoding UTF8 -Value "Fixtures live in ~/$notesFolder/fixtures."
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    try {
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $cleanCommit refs/heads/main $namingCommit" 6> $null
        Assert-True ($LASTEXITCODE -eq 0) "pre-push exited $LASTEXITCODE."
    } catch {
        throw "A push of a clean commit was stopped by what the working tree names: $($_.Exception.Message)"
    } finally {
        & git -C $hookRoot checkout --quiet -- CONTRIBUTING.md docs/phone.md
    }

    # Every commit the push publishes, not only each ref's tip: a serial one commit adds and the
    # next removes still goes out in the first, and the refusal names that commit by the prefix git
    # grep gives a hit read out of it. Pushed as an update of a branch, and as a new branch to a
    # remote whose main already holds the clean commit, as its tracking ref here says too.
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    & git -C $hookRoot add docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names the phone again'
    $servedSerial = (& git -C $hookRoot rev-parse HEAD).Trim()
    & git -C $hookRoot rm --quiet docs/phone.md
    Set-Content -LiteralPath (Join-Path $hookRoot 'CONTRIBUTING.md') -Encoding UTF8 -Value 'Fixtures live in HUSHFACEBOOK_FIXTURE_DIR, not in docs.'
    & git -C $hookRoot add CONTRIBUTING.md
    & git -C $hookRoot commit --quiet -m 'drops the doc'
    $droppedSerial = (& git -C $hookRoot rev-parse HEAD).Trim()
    $scanRemote = Join-Path $hookRoot 'scan-remote.git'
    & git init --bare --quiet $scanRemote
    & git -C $hookRoot push --quiet $scanRemote "${cleanCommit}:refs/heads/main"
    & git -C $hookRoot update-ref refs/remotes/origin/main $cleanCommit
    # And more branches than one rev-list call takes, none of them ever fetched here, sorted so
    # main lands in the middle batch: what a new branch publishes has to hold for every batch.
    foreach ($side in @(@{ Prefix = 'a'; From = 1 }, @{ Prefix = 'zz'; From = 1000 })) {
        foreach ($n in 0..209) {
            [IO.File]::WriteAllText((Join-Path $scanRemote ("refs/heads/$($side.Prefix){0:d3}" -f $n)),
                ('{0:x40}' -f ($side.From + $n)) + "`n")
        }
    }
    # A new branch is checked from its whole tree, code included, so a scan that let it through
    # would reach the build gates. With no credentials and no gh they refuse by name instead.
    $savedScanPath = $env:PATH
    $savedScanActor = $env:GITHUB_ACTOR
    $savedScanToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = $pathWithoutGh
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        foreach ($refs in @("refs/heads/main $droppedSerial refs/heads/main $cleanCommit",
                "refs/heads/topic $droppedSerial refs/heads/topic $('0' * 40)")) {
            Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $scanRemote -PushedRefs $refs 6> $null } `
                "*commit $servedSerial name*${servedSerial}:docs/phone.md:1:*" `
                "A push went through with a phone serial in an earlier commit than its tip: $refs"
        }
    } finally {
        $env:PATH = $savedScanPath
        $env:GITHUB_ACTOR = $savedScanActor
        $env:GITHUB_TOKEN = $savedScanToken
    }

    # Commits that cancel each other out change no file, and each still goes out. The scan runs
    # before the changed paths decide there's nothing to check.
    New-Item -ItemType Directory -Path (Split-Path -Parent $phoneDoc) -Force | Out-Null
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    & git -C $hookRoot add docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names the phone once more'
    $namesAgain = (& git -C $hookRoot rev-parse HEAD).Trim()
    & git -C $hookRoot rm --quiet docs/phone.md
    & git -C $hookRoot commit --quiet -m 'takes it out again'
    $cancelledOut = (& git -C $hookRoot rev-parse HEAD).Trim()
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $cancelledOut refs/heads/main $droppedSerial" 6> $null } `
        "*commit $namesAgain name*${namesAgain}:docs/phone.md:1:*" `
        'A push whose commits cancel each other out went through with them unscanned.'

    # A remote-tracking ref can outlive its branch on the remote, deleted there and never pruned
    # here, and a commit only it held is still published by a new branch that reaches it. What the
    # remote holds is read off the remote. The whole tree of a new branch reaches the build gates,
    # so they refuse by name again if the scan ever lets it through. The remote's main moves on by
    # its ref file, since only what the remote advertises is read and git won't push to a remote
    # whose other branches name commits it doesn't have.
    [IO.File]::WriteAllText((Join-Path $scanRemote 'refs/heads/main'), "$cancelledOut`n")
    New-Item -ItemType Directory -Path (Split-Path -Parent $phoneDoc) -Force | Out-Null
    Set-Content -LiteralPath $phoneDoc -Encoding UTF8 -Value "adb -s $phoneSerial install app.apk"
    & git -C $hookRoot add docs/phone.md
    & git -C $hookRoot commit --quiet -m 'names the phone on a branch deleted since'
    $staleNamed = (& git -C $hookRoot rev-parse HEAD).Trim()
    & git -C $hookRoot update-ref refs/remotes/origin/old $staleNamed
    & git -C $hookRoot rm --quiet docs/phone.md
    & git -C $hookRoot commit --quiet -m 'takes it out on a new branch'
    $staleDropped = (& git -C $hookRoot rev-parse HEAD).Trim()
    try {
        $env:PATH = $pathWithoutGh
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $scanRemote `
                -PushedRefs "refs/heads/topic2 $staleDropped refs/heads/topic2 $('0' * 40)" 6> $null } `
            "*commit $staleNamed name*${staleNamed}:docs/phone.md:1:*" `
            'A new branch went through with a commit only a stale remote-tracking ref held.'
    } finally {
        $env:PATH = $savedScanPath
        $env:GITHUB_ACTOR = $savedScanActor
        $env:GITHUB_TOKEN = $savedScanToken
    }

    # The scan hands git grep its commits a batch at a time. Batches of two over five commits
    # reach the third batch, a short one, where the only commit naming the phone sits.
    $batched = @(Find-MachineNames -Root $hookRoot -BatchSize 2 `
        -Commit @($cleanCommit, $droppedSerial, $newBranchHead, $cancelledOut, $servedSerial))
    Assert-True (($batched -join "`n") -like "*${servedSerial}:docs/phone.md:1:*") `
        "The scan missed a commit past its first batch: $($batched -join '; ')"

    # A hit in a binary file: git grep prints the file's bytes, and a carriage return among them
    # splits the printed line into pieces that don't open with a commit. The refusal puts the hit
    # down to the one commit that carries it, and none of the pieces.
    $binaryDoc = Join-Path $hookRoot 'docs/logo.bin'
    New-Item -ItemType Directory -Path (Split-Path -Parent $binaryDoc) -Force | Out-Null
    [IO.File]::WriteAllBytes($binaryDoc, [byte[]](@(0x89, 0x50, 0x4E, 0x47, 0x00, 0x0D) +
        [Text.Encoding]::ASCII.GetBytes("x$([char]13)adb -s $phoneSerial") + @(0x0D, 0x00, 0x0A)))
    & git -C $hookRoot add docs/logo.bin
    & git -C $hookRoot commit --quiet -m 'a picture that names the phone'
    $binaryNamed = (& git -C $hookRoot rev-parse HEAD).Trim()
    try {
        & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $binaryNamed refs/heads/main $staleDropped" 6> $null
        $binaryRefusal = 'no refusal'
    } catch {
        $binaryRefusal = $_.Exception.Message
    }
    Assert-True ($binaryRefusal -like "Tracked files in commit $binaryNamed name the maintainer's machine or phone*" -and
        $binaryRefusal -like "*${binaryNamed}:docs/logo.bin:1:*") `
        "A hit in a binary file was not put down to its commit alone: $binaryRefusal"
    & git -C $hookRoot rm --quiet docs/logo.bin
    & git -C $hookRoot commit --quiet -m 'takes the picture out'
    $pictureGone = (& git -C $hookRoot rev-parse HEAD).Trim()

    # Text in UTF-16, which is what Windows PowerShell's > writes (little-endian, with a byte order
    # mark), or big-endian: git grep reads bytes, where every letter sits beside a NUL. The phone
    # and the notes folder are both found that way, in a pushed commit and in the working tree.
    # Mid-sentence each byte order's pattern also finds the other, the NULs of the letters around
    # lining up, so each file puts its name where only its own pattern can: first thing after the
    # little-endian byte order mark, or last thing in a big-endian file.
    $notesName = $notesFolder.Substring(1)
    $wideDocs = [ordered]@{
        'docs/notes-be.txt' = @([Text.Encoding]::BigEndianUnicode, "Fixtures live in ~/.$notesName")
        'docs/notes-le.txt' = @([Text.Encoding]::Unicode, "$notesName keeps the working notes.")
        'docs/phone-be.txt' = @([Text.Encoding]::BigEndianUnicode, "adb -s $phoneSerial")
        'docs/phone-le.txt' = @([Text.Encoding]::Unicode, "$phoneSerial is the test phone.")
    }
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'docs') -Force | Out-Null
    foreach ($doc in $wideDocs.Keys) {
        [IO.File]::WriteAllText((Join-Path $hookRoot $doc), $wideDocs[$doc][1], $wideDocs[$doc][0])
    }
    & git -C $hookRoot add -- @($wideDocs.Keys)
    & git -C $hookRoot commit --quiet -m 'wide text names the machine'
    $wideNamed = (& git -C $hookRoot rev-parse HEAD).Trim()
    Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs "refs/heads/main $wideNamed refs/heads/main $pictureGone" 6> $null } `
        ("*${wideNamed}:docs/notes-be.txt:1:*${wideNamed}:docs/notes-le.txt:1:*" +
            "${wideNamed}:docs/phone-be.txt:1:*${wideNamed}:docs/phone-le.txt:1:*") `
        'A push went through with UTF-16 text naming the machine and a phone.'
    try {
        & $prePushScript -Root $hookRoot -ChangedPaths @('docs/phone-le.txt') 6> $null
        $wideHandRun = 'no refusal'
    } catch {
        $wideHandRun = $_.Exception.Message
    }
    Assert-True ($wideHandRun -like '*the working tree*docs/notes-be.txt:1:*docs/notes-le.txt:1:*docs/phone-be.txt:1:*docs/phone-le.txt:1:*') `
        "A run by hand passed a working tree whose UTF-16 text names the machine: $wideHandRun"
    & git -C $hookRoot rm --quiet -- @($wideDocs.Keys)
    & git -C $hookRoot commit --quiet -m 'takes the wide text out'

    # The build branch, which runs the Gradle gates that hold the Bouncy Castle graphs to the
    # reviewed release. Starting a real build from a contract test would be absurd, so the case
    # reads the first thing that branch does instead: with no GitHub credentials and no gh on
    # the path, it refuses by name, and nothing else in the hook says that. A push that moved
    # only a pin used to take the release path and never reach this. Git stays on the path for
    # the scan every push makes; only gh goes.
    $savedPath = $env:PATH
    $savedActor = $env:GITHUB_ACTOR
    $savedToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = $pathWithoutGh
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        foreach ($pin in @('gradle/libs.versions.toml', 'gradle/verification-metadata.xml',
                'settings.gradle.kts', 'build.gradle.kts', 'patches/build.gradle.kts')) {
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($pin) 6> $null } `
                '*GITHUB_ACTOR*' "A push that changed $pin did not reach the build gates."
        }
        # And the control: a file the build branch has no interest in must not reach it.
        & $prePushScript -Root $hookRoot -ChangedPaths @('CONTRIBUTING.md') 6> $null
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'A file no gate reads was routed into the build gates.'
    } finally {
        $env:PATH = $savedPath
        $env:GITHUB_ACTOR = $savedActor
        $env:GITHUB_TOKEN = $savedToken
    }

    # The build wrapper. HUSHFACEBOOK_BUILD_WRAPPER names the script that runs Gradle on this
    # machine, and the hook hands it the repository and the tasks. A stub stands in for it and
    # records what it was given, so no build starts.
    $savedWrapper = $env:HUSHFACEBOOK_BUILD_WRAPPER
    $savedActor = $env:GITHUB_ACTOR
    $savedToken = $env:GITHUB_TOKEN
    try {
        $env:GITHUB_ACTOR = 'contract'
        $env:GITHUB_TOKEN = 'contract'
        $wrapperMarker = Join-Path $hookRoot 'wrapper-ran.txt'
        $wrapperStub = Join-Path $hookRoot 'build-wrapper.ps1'
        Set-Content -LiteralPath $wrapperStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            "Set-Content -LiteralPath '$wrapperMarker' -Value (`"dir=`$ProjectDir tasks=`" + (`$Tasks -join ','))",
            'exit 0')
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $wrapperStub
        & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/facebook/src/main/java/Any.java') 6> $null
        Assert-True (Test-Path -LiteralPath $wrapperMarker) `
            'The hook did not run the build through the wrapper HUSHFACEBOOK_BUILD_WRAPPER names.'
        $wrapped = Get-Content -LiteralPath $wrapperMarker -Raw
        Assert-True ($wrapped -like "dir=$hookRoot tasks=*:extensions:facebook:test*:patches:test*") `
            "The build wrapper was not handed the repository and the test tasks: $wrapped"

        # The Gradle file that writes the release bundle. The contract tests hold it to the
        # directory common.ps1 reads the bundle from, and a push that moved only it ran the build
        # and never them; the bundle path then went unchecked until the next script push.
        Remove-Item -LiteralPath $contractsMarker, $wrapperMarker -Force -ErrorAction SilentlyContinue
        & $prePushScript -Root $hookRoot -ChangedPaths @('patches/build.gradle.kts') 6> $null
        Assert-True ((Test-Path -LiteralPath $contractsMarker) -and (Test-Path -LiteralPath $wrapperMarker)) `
            'A push that changed only patches/build.gradle.kts did not run both the script contract tests and the build.'

        $env:HUSHFACEBOOK_BUILD_WRAPPER = Join-Path $hookRoot 'no-such-wrapper.ps1'
        Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('patches/build.gradle.kts') 6> $null } `
            '*HUSHFACEBOOK_BUILD_WRAPPER*' 'A build wrapper that is not there was ignored rather than reported.'

        # The gate builds what is pushed, not what happens to be in the working tree. A stub build
        # fails on any tree whose marker says broken, and records the tree it was handed.
        $gateRepo = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-gate-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path (Join-Path $gateRepo 'extensions') -Force | Out-Null
        & git -C $gateRepo init --quiet
        $actualGateGitDir = (& git -C $gateRepo rev-parse --absolute-git-dir).Trim()
        Assert-True ([IO.Path]::GetFullPath($actualGateGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $gateRepo '.git')).TrimEnd('\', '/')) `
            'The gate fixture resolved outside its temporary repository; refusing to write.'
        & git -C $gateRepo config user.name 'Gate Contract'
        & git -C $gateRepo config user.email 'gate@example.invalid'
        $gateMarker = Join-Path $hookRoot 'gate-ran.txt'
        $gateStub = Join-Path $hookRoot 'gate-wrapper.ps1'
        Set-Content -LiteralPath $gateStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            '$state = (Get-Content -LiteralPath (Join-Path $ProjectDir ''extensions/marker.txt'') -Raw).Trim()',
            "Set-Content -LiteralPath '$gateMarker' -Value (`"dir=`$ProjectDir marker=`$state gitdir=`$env:GIT_DIR`")",
            'if ($state -eq ''broken'') { exit 1 }',
            'exit 0')
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $gateStub
        $gateFile = Join-Path $gateRepo 'extensions/marker.txt'
        function Save-GateCommit([string]$State) {
            Set-Content -LiteralPath $gateFile -Value $State -Encoding ASCII
            & git -C $gateRepo add extensions/marker.txt
            & git -C $gateRepo commit --quiet -m $State
            return (& git -C $gateRepo rev-parse HEAD).Trim()
        }
        try {
            $good = Save-GateCommit 'good'
            Set-Content -LiteralPath $gateFile -Value 'broken' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $good refs/heads/main $('0' * 40)" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'An uncommitted edit in the working tree failed a clean commit.'
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($built -like '*marker=good*' -and $built -notlike "*dir=$gateRepo marker*") `
                "The gate built the working tree instead of the pushed commit: $built"

            $broken = Save-GateCommit 'broken'
            Set-Content -LiteralPath $gateFile -Value 'good' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $broken refs/heads/main $good" 6> $null } `
                '*did not pass*' 'An uncommitted fix in the working tree passed a broken commit.'
            Assert-True ((& git -C $gateRepo status --porcelain) -like '*extensions/marker.txt*') `
                'Building the pushed commit touched the working tree it was kept apart from.'

            # A clean tree still builds in place.
            $fixed = Save-GateCommit 'good'
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'A clean tree with a good commit did not pass.'
            Assert-True ((Get-Content -LiteralPath $gateMarker -Raw) -like "*dir=$gateRepo marker=good*") `
                'A clean tree was not built in place.'

            # A tree that changes while it is built in place: an edit landing mid-build was tested
            # along with the commit, so that build says nothing about the commit alone.
            $meddler = Join-Path $hookRoot 'gate-wrapper-meddles.ps1'
            $meddled = Join-Path $gateRepo 'README.md'
            Set-Content -LiteralPath $meddler -Encoding UTF8 -Value @(
                'param([string]$ProjectDir, [string[]]$Tasks)',
                'Set-Content -LiteralPath (Join-Path $ProjectDir ''README.md'') -Value ''edited mid-build'' -Encoding ASCII',
                'exit 0')
            $env:HUSHFACEBOOK_BUILD_WRAPPER = $meddler
            try {
                Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null } `
                    '*changed while the runtime test build ran in place*' `
                    'A working tree that changed during an in-place build passed on that build.'
            } finally {
                $env:HUSHFACEBOOK_BUILD_WRAPPER = $gateStub
                Remove-Item -LiteralPath $meddled -Force -ErrorAction SilentlyContinue
            }
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'A clean tree failed once the mid-build edit was gone.'

            # But only for HEAD. A clean tree whose HEAD is good says nothing about an older commit
            # pushed by name, or another branch, and those used to have HEAD built in their place.
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/other $broken refs/heads/other $good" 6> $null } `
                '*did not pass*' 'A clean tree passed a broken commit that was pushed but is not HEAD.'

            # Uncommitted files anywhere count, not only under the source folders: the tests read
            # README.md and patches-list.json from the root.
            $rootFile = Join-Path $gateRepo 'README.md'
            Set-Content -LiteralPath $rootFile -Value 'uncommitted' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $built -like '*marker=good*' -and $built -notlike "*dir=$gateRepo marker*") `
                "An uncommitted root file was built in place with the push: $built"
            Remove-Item -LiteralPath $rootFile -Force

            # Git hands a hook GIT_DIR and GIT_WORK_TREE. Neither may steer the worktree commands
            # into this working tree, nor reach the build.
            Set-Content -LiteralPath $gateFile -Value 'broken' -Encoding ASCII
            $headBefore = (& git -C $gateRepo symbolic-ref HEAD).Trim()
            try {
                $env:GIT_DIR = Join-Path $gateRepo '.git'
                $env:GIT_WORK_TREE = $gateRepo
                & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            } finally {
                Remove-Item -LiteralPath Env:\GIT_DIR, Env:\GIT_WORK_TREE -ErrorAction SilentlyContinue
            }
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $built.Trim() -like '*marker=good gitdir=') `
                "The build ran with git's hook variables set: $built"
            Assert-True ((Get-Content -LiteralPath $gateFile -Raw).Trim() -eq 'broken' -and
                (& git -C $gateRepo symbolic-ref HEAD).Trim() -eq $headBefore) `
                "With GIT_DIR set, building the pushed commit rewrote the working tree it was kept apart from."

            # One push at a time through the gate worktree. With the lock held here, a hook in
            # another process has to give up rather than check its commit out under a running build.
            $gateHasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $gateDigest = $gateHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(
                    [IO.Path]::GetFullPath($gateRepo).ToLowerInvariant()))
            } finally {
                $gateHasher.Dispose()
            }
            $gateKey = -join ($gateDigest[0..5] | ForEach-Object { $_.ToString('x2') })
            $shell = (Get-Process -Id $PID).Path
            $childRefs = "refs/heads/main $fixed refs/heads/main $broken"
            function Invoke-ChildPush {
                # Windows PowerShell stops on a native command's first line of standard error.
                $preference = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                try {
                    return (& $shell -NoProfile -File $prePushScript -Root $gateRepo -PushedRefs $childRefs `
                        -GateLockTimeoutSeconds 1 2>&1 | Out-String)
                } finally {
                    $ErrorActionPreference = $preference
                }
            }
            $held = New-Object System.Threading.Mutex($false, "Local\hushfacebook-pre-push-$gateKey")
            Assert-True ($held.WaitOne(0)) 'The contract could not take the gate lock itself.'
            try {
                $waited = Invoke-ChildPush
                Assert-True ($LASTEXITCODE -ne 0 -and $waited -like '*held the gate worktree*') `
                    "A second push used the gate worktree while another push held it: $waited"
            } finally {
                $held.ReleaseMutex()
                $held.Dispose()
            }
            # The control: the same child push, with the lock free, goes through.
            $free = Invoke-ChildPush
            Assert-True ($LASTEXITCODE -eq 0) "The child push failed with the gate lock free: $free"

            # The release facts half checks the files a push carries as well. A stub check, committed
            # the way the real one is, fails on a README that says broken and records where it ran
            # and whether it read test results. Its own commit is never in a pushed range, so no
            # push below touches scripts/. A README push asks for the contract tests too, since they
            # end with the marketing asset check, so a stub suite that passes is committed with it.
            $gateFacts = Join-Path $hookRoot 'gate-facts-ran.txt'
            & git -C $gateRepo checkout --quiet -- extensions/marker.txt
            New-Item -ItemType Directory -Path (Join-Path $gateRepo 'scripts') -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $gateRepo 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
                'param([string]$Root)', 'exit 0')
            & git -C $gateRepo add scripts/test-script-contracts.ps1
            Set-Content -LiteralPath (Join-Path $gateRepo 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
                'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
                '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath, [switch]$SkipTestResults)',
                '$state = (Get-Content -LiteralPath (Join-Path $Root ''README.md'') -Raw).Trim()',
                "Set-Content -LiteralPath '$gateFacts' -Value (`"root=`$Root readme=`$state results=`$(-not `$SkipTestResults)`")",
                'if ($state -eq ''broken'') { exit 1 }',
                'exit 0')
            $gateReadme = Join-Path $gateRepo 'README.md'
            function Save-GateReadme([string]$State) {
                Set-Content -LiteralPath $gateReadme -Value $State -Encoding ASCII
                & git -C $gateRepo add README.md
                & git -C $gateRepo commit --quiet -m "readme $State"
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            & git -C $gateRepo add scripts/validate-release-facts.ps1
            $factsBase = Save-GateReadme 'base'
            $factsGood = Save-GateReadme 'good'

            # An uncommitted README that would fail the check doesn't fail a push without it.
            Set-Content -LiteralPath $gateReadme -Value 'broken' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsGood refs/heads/main $factsBase" 6> $null
            $checked = Get-Content -LiteralPath $gateFacts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $checked -like '*readme=good results=False*' -and
                $checked -notlike "*root=$gateRepo *") `
                "The release facts were read from the working tree instead of the pushed commit: $checked"

            # And an uncommitted fix doesn't pass a push whose own README fails.
            $factsBroken = Save-GateReadme 'broken'
            Set-Content -LiteralPath $gateReadme -Value 'good' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsBroken refs/heads/main $factsGood" 6> $null } `
                '*release facts do not agree*' 'An uncommitted README fix passed a push whose README fails the release facts.'

            # An index push is checked against the bundle and results this checkout built, so from a
            # dirty tree it is refused by name rather than checked against the wrong files.
            Set-Content -LiteralPath (Join-Path $gateRepo 'patches-bundle.json') -Value '{}' -Encoding ASCII
            & git -C $gateRepo add patches-bundle.json
            & git -C $gateRepo commit --quiet -m 'index'
            $factsIndex = (& git -C $gateRepo rev-parse HEAD).Trim()
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsIndex refs/heads/main $factsBroken" 6> $null } `
                '*clean checkout of the commit it pushes*' 'An index push from a dirty tree was checked against files it does not carry.'

            # The control: a clean tree pushing HEAD is checked in place, results and all.
            & git -C $gateRepo checkout --quiet -- README.md
            $factsFixed = Save-GateReadme 'good'
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsFixed refs/heads/main $factsIndex" 6> $null
            $checked = Get-Content -LiteralPath $gateFacts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $checked -like "*root=$gateRepo readme=good results=True*") `
                "A clean tree pushing HEAD was not checked in place: $checked"

            # The script suites are the pushed commit's too, run against that commit: they copy the
            # root files into their fixtures, and ran from the working tree until 2026-09-21. A stub
            # suite records where it ran and the state it was committed with.
            $gateContracts = Join-Path $hookRoot 'gate-contracts-ran.txt'
            $contractsStub = Join-Path $gateRepo 'scripts/test-script-contracts.ps1'
            function Save-GateContracts([string]$State) {
                Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value @(
                    'param([string]$Root)',
                    "Set-Content -LiteralPath '$gateContracts' -Value (`"root=`$Root state=$State`")",
                    $(if ($State -eq 'broken') { 'exit 1' } else { 'exit 0' }))
                & git -C $gateRepo add scripts/test-script-contracts.ps1
                & git -C $gateRepo commit --quiet -m "contracts $State"
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            $contractsGood = Save-GateContracts 'good'
            Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value @('param([string]$Root)', 'exit 1')
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsGood refs/heads/main $factsFixed" 6> $null
            $ran = Get-Content -LiteralPath $gateContracts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $ran -like '*state=good*' -and $ran -notlike "*root=$gateRepo *") `
                "The script contract tests ran from the working tree instead of the pushed commit: $ran"
            & git -C $gateRepo checkout --quiet -- scripts/test-script-contracts.ps1
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsGood refs/heads/main $factsFixed" 6> $null
            $ran = Get-Content -LiteralPath $gateContracts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $ran -like "*root=$gateRepo state=good*") `
                "A clean tree pushing HEAD did not run its script contract tests in place: $ran"
            $contractsBroken = Save-GateContracts 'broken'
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsBroken refs/heads/main $contractsGood" 6> $null } `
                '*script contract tests did not pass*' 'A push whose own script contract tests fail was let through.'
        } finally {
            foreach ($line in @(& git -C $gateRepo worktree list --porcelain)) {
                if ($line -like 'worktree *') {
                    $listed = $line.Substring('worktree '.Length)
                    if ([IO.Path]::GetFullPath($listed).TrimEnd('\', '/') -ine [IO.Path]::GetFullPath($gateRepo).TrimEnd('\', '/')) {
                        & git -C $gateRepo worktree remove --force $listed
                    }
                }
            }
            Remove-Item -LiteralPath $gateRepo -Recurse -Force -ErrorAction SilentlyContinue
        }
    } finally {
        $env:HUSHFACEBOOK_BUILD_WRAPPER = $savedWrapper
        $env:GITHUB_ACTOR = $savedActor
        $env:GITHUB_TOKEN = $savedToken
    }
} finally {
    $env:HUSHFACEBOOK_SKIP_PRE_PUSH = $savedSkip
    foreach ($name in $savedHookGit.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $savedHookGit[$name] }
    Remove-Item -LiteralPath $hookRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] pre-push routing contracts passed'

# --- Test-ChangelogVersions ------------------------------------------------------------------
#
# A released version's heading is the only record a reader has that it shipped. On 2026-09-14 a
# post-release commit renamed "## 0.31.0" to "## Unreleased" and the file then said that release
# never happened; nothing read this file at all. Both directions are exercised here, and the
# real CHANGELOG is the control.

$headingShapes = @"
## 0.32.0
some text
## 0.31.0 (2026-09-14)
more text
## [0.1.5](https://example.invalid/compare/v0.1.4...v0.1.5) (2026-06-01)
older text
"@
$shapes = @(Get-ChangelogVersions -Text $headingShapes)
Assert-True (($shapes -join ',') -eq '0.32.0,0.31.0,0.1.5') `
    "The heading shapes this file uses were not all read: $($shapes -join ',')"
Assert-True (@(Get-ChangelogVersions -Text "## Unreleased`n## Notes").Count -eq 0) `
    'A heading that names no version was read as one.'

$previousChangelog = "## 0.31.0`nshipped`n## 0.30.2`nshipped"
$goodChangelog = "## 0.32.0`nnew`n" + $previousChangelog
$good = Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True $good.Valid "A CHANGELOG that kept every shipped version was refused: $($good.Reason)"

# The 2026-09-14 defect, exactly: the previous version's heading renamed to Unreleased.
$renamed = Test-ChangelogVersions -Current ("## 0.32.0`nnew`n## Unreleased`nshipped`n## 0.30.2`nshipped") `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True (-not $renamed.Valid) 'A released version renamed to Unreleased was accepted.'
Assert-True ($renamed.Reason -like '*0.31.0*tag v0.31.0*') `
    "The renamed heading was refused for the wrong reason: $($renamed.Reason)"

$dropped = Test-ChangelogVersions -Current "## 0.32.0`nnew`n## 0.31.0`nshipped" `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog
Assert-True (-not $dropped.Valid) 'A shipped version deleted from the CHANGELOG was accepted.'

$noHeading = Test-ChangelogVersions -Current $previousChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog
Assert-True (-not $noHeading.Valid) 'A CHANGELOG with no heading for the built version was accepted.'
Assert-True ($noHeading.Reason -like '*no heading for 0.32.0*') `
    "The missing heading was refused for the wrong reason: $($noHeading.Reason)"

Assert-True (-not (Test-ChangelogVersions -Current "# Changelog`nnothing here" -ExpectedVersion '0.32.0').Valid) `
    'A CHANGELOG naming no version at all was accepted.'

# With no earlier file to compare against, the version being built is still required and a
# CHANGELOG that has it is still accepted. A first release has no tag behind it.
Assert-True (Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0').Valid `
    'A checkout with no earlier tag was refused.'

# The control. The real file, held to the real version, must pass: every case above is this
# same shape with one heading moved.
$realVersion = Get-BundleVersion -Root $Root
$realChangelog = Get-Content -LiteralPath (Join-Path $Root 'CHANGELOG.md') -Raw
$realTag = "$(& git -C $Root describe --tags --abbrev=0 HEAD 2>$null | Select-Object -First 1)".Trim()
$realPrevious = if ($realTag) { (& git -C $Root show "${realTag}:CHANGELOG.md" 2>$null) -join "`n" } else { '' }
$realCheck = if ([string]::IsNullOrWhiteSpace($realPrevious)) {
    Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion
} else {
    Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion -Previous $realPrevious
}
Assert-True $realCheck.Valid "This repository's own CHANGELOG was refused: $($realCheck.Reason)"

Write-Host '[scripts] changelog history contracts passed'

# --- Test-ChangelogManagerEntry --------------------------------------------------------------
#
# Morphe Manager skips a heading with no date and flags an app only for bullets scoped to it.
# Every heading from 0.23.0 to 0.40.0 was bare and Manager's list stopped at 0.22.0, while the
# history check above passed them all. Each refusal below is the readable entry with one thing
# taken away, and the real CHANGELOG is the control.

$readable = @"
## Unreleased

* not held to anything yet

## 0.42.0 (2026-09-20)

A sentence about the release.

### Settings

* **Facebook:** one change.
* **Facebook:** another change.

### On the video

* **Facebook:** a third.

## 0.41.0

* an older entry, frozen as it shipped
"@
$entry = Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.42.0'
Assert-True $entry.Valid "A dated entry with scoped bullets was refused: $($entry.Reason)"
Assert-True ($entry.Date -eq '2026-09-20' -and $entry.Bullets -eq 3) `
    "The readable entry was misread: date $($entry.Date), $($entry.Bullets) bullets"
$crlf = Test-ChangelogManagerEntry -Current ($readable -replace "`r?`n", "`r`n") -ExpectedVersion '0.42.0'
Assert-True ($crlf.Valid -and $crlf.Bullets -eq 3) "CRLF line endings changed the reading: $($crlf.Reason)"

$undated = Test-ChangelogManagerEntry -Current ($readable -replace '## 0\.42\.0 \(2026-09-20\)', '## 0.42.0') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $undated.Valid) 'An undated heading, which Manager skips, was accepted.'
Assert-True ($undated.Reason -like '*no date*') "The undated heading was refused for the wrong reason: $($undated.Reason)"

$unscoped = Test-ChangelogManagerEntry -Current ($readable -replace '\* \*\*Facebook:\*\* another', '* another') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $unscoped.Valid) 'A bullet Manager does not scope to Facebook was accepted.'
Assert-True ($unscoped.Reason -like 'Line 12 *') "The unscoped bullet was refused for the wrong reason: $($unscoped.Reason)"

$wrapped = Test-ChangelogManagerEntry -Current ($readable -replace 'one change\.', "one`n  change.") `
    -ExpectedVersion '0.42.0'
Assert-True (-not $wrapped.Valid) 'A wrapped bullet, whose second line Manager drops, was accepted.'
Assert-True ($wrapped.Reason -like '*continues the bullet*') "The wrapped bullet was refused for the wrong reason: $($wrapped.Reason)"

$noBullets = Test-ChangelogManagerEntry -Current "## 0.42.0 (2026-09-20)`n`nOnly prose.`n" -ExpectedVersion '0.42.0'
Assert-True (-not $noBullets.Valid) 'An entry with no scoped bullet, which gets no update badge, was accepted.'

$otherScope = Test-ChangelogManagerEntry -Current ($readable -replace '\*\*Facebook:\*\* a third', '**Instagram:** a third') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $otherScope.Valid) 'A bullet scoped to another app was accepted.'

Assert-True (-not (Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.43.0').Valid) `
    'An entry for a version the CHANGELOG does not name was accepted.'

# The control. The real file, held to the real version, must pass.
$realEntry = Test-ChangelogManagerEntry -Current $realChangelog -ExpectedVersion $realVersion
Assert-True $realEntry.Valid "Morphe Manager could not read this repository's own $realVersion entry: $($realEntry.Reason)"

Write-Host '[scripts] changelog Manager contracts passed'

# --- Resolve-D8 ------------------------------------------------------------------------------
#
# The build-tools directory is chosen by version number. Sorted as text, 9.0.0 wins over 37.0.0
# and 100.0.0 loses to it; the installed set happens to be 34 through 37, which is why nobody
# had seen it pick wrong.

. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
$sdkRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-sdk-" + [guid]::NewGuid().ToString('N'))
try {
    $checkout = Join-Path $sdkRoot 'checkout'
    $sdk = Join-Path $sdkRoot 'sdk'
    foreach ($version in @('9.0.0', '37.0.0', '100.0.0', 'not-a-version')) {
        $tools = Join-Path (Join-Path $sdk 'build-tools') $version
        New-Item -ItemType Directory -Path $tools -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $tools 'd8.bat') -Value '@echo off' -Encoding ASCII
    }
    # 38.0.0 is there but carries no d8, so it must not be picked over 37.0.0.
    New-Item -ItemType Directory -Path (Join-Path (Join-Path $sdk 'build-tools') '38.0.0') -Force | Out-Null
    New-Item -ItemType Directory -Path $checkout -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $checkout 'local.properties') -Encoding ASCII `
        -Value ('sdk.dir=' + ($sdk -replace '\\', '\\'))

    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*100.0.0*d8.bat') "Resolve-D8 chose $chosen, not the newest build-tools by version."

    Remove-Item -LiteralPath (Join-Path (Join-Path $sdk 'build-tools') '100.0.0') -Recurse -Force
    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*37.0.0*d8.bat') "Resolve-D8 chose $chosen over 37.0.0; 9.0.0 sorts above it as text."

    $explicit = Join-Path (Join-Path (Join-Path $sdk 'build-tools') '9.0.0') 'd8.bat'
    Assert-True ((Resolve-D8 -Explicit $explicit -Root $checkout) -eq $explicit) `
        'An explicit d8 path was not honoured.'
    Assert-Throws { Resolve-D8 -Root (Join-Path $sdkRoot 'nowhere') } '*local.properties*' `
        'A checkout with no local.properties resolved a d8 from somewhere.'
} finally {
    Remove-Item -LiteralPath $sdkRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] d8 resolution contracts passed'

# --- common.ps1 ------------------------------------------------------------------------------
#
# The helpers four release scripts used to carry copies of. They had already drifted: the
# cleanup helper recursed unconditionally in one script and only on request in another, and two
# of the four version reads were missing -LiteralPath, which turns a repository path holding a
# bracket into a wildcard that matches nothing.

$commonRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-common-" + [guid]::NewGuid().ToString('N'))
$savedJar = $env:HUSHFACEBOOK_DESKTOP_JAR
$savedWork = $env:HUSHFACEBOOK_WORKDIR
try {
    New-Item -ItemType Directory -Path $commonRoot -Force | Out-Null
    $work = Join-Path $commonRoot 'work'
    New-Item -ItemType Directory -Path $work -Force | Out-Null

    # The path guard, which is what keeps a generated name from reaching outside the directory
    # the caller owns.
    $inside = Resolve-WithinRoot -Path (Join-Path $work 'run/output.apk') -Root $work
    Assert-True ($inside -like "$work*") 'A path inside the work directory was refused.'
    Assert-Throws { Resolve-WithinRoot -Path (Join-Path $commonRoot 'elsewhere.apk') -Root $work } `
        '*outside the work directory*' 'A path outside the work directory was accepted.'
    Assert-Throws { Resolve-WithinRoot -Path (Join-Path $work '..\escape.apk') -Root $work } `
        '*outside the work directory*' 'A path that climbs out with .. was accepted.'
    # The prefix trap: a sibling directory whose name starts with the work directory's name.
    Assert-Throws { Resolve-WithinRoot -Path ($work + '-other\file.apk') -Root $work } `
        '*outside the work directory*' 'A sibling sharing the name prefix was accepted as inside.'

    # Cleanup, recursive by default, and refusing anything outside the work directory.
    $tree = Join-Path $work 'run/deep'
    New-Item -ItemType Directory -Path $tree -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tree 'leaf.txt') -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path (Join-Path $work 'run') -Root $work
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $work 'run'))) `
        'A generated directory tree was left behind by the default cleanup.'

    $single = Join-Path $work 'one.txt'
    Set-Content -LiteralPath $single -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path $single -Root $work -NoRecurse
    Assert-True (-not (Test-Path -LiteralPath $single)) 'A single generated file was not removed.'

    $outside = Join-Path $commonRoot 'keep.txt'
    Set-Content -LiteralPath $outside -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path $outside -Root $work -WarningAction SilentlyContinue
    Assert-True (Test-Path -LiteralPath $outside) 'Cleanup deleted a path outside the work directory.'
    # A path that is simply not there is nothing to do, not a failure.
    Remove-GeneratedPath -Path (Join-Path $work 'never-existed') -Root $work

    # The version read. -LiteralPath is the difference that had already drifted, so the fixture
    # directory carries the bracket that makes a wildcard read find nothing.
    $bracketRoot = Join-Path $commonRoot 'repo [1]'
    New-Item -ItemType Directory -Path $bracketRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Encoding ASCII -Value @(
        'org.gradle.caching = true', 'version = 9.9.9', 'android.useAndroidX = true')
    Assert-True ((Get-BundleVersion -Root $bracketRoot) -eq '9.9.9') `
        'The version read failed on a path containing a bracket.'
    Assert-Throws { Get-BundleVersion -Root (Join-Path $commonRoot 'no-such-repo') } `
        '*no gradle.properties*' 'A missing gradle.properties was not reported.'
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Value 'name = x' -Encoding ASCII
    Assert-Throws { Get-BundleVersion -Root $bracketRoot } '*names no version*' `
        'A gradle.properties with no version was accepted.'
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Value 'version =' -Encoding ASCII
    Assert-Throws { Get-BundleVersion -Root $bracketRoot } '*empty version*' `
        'A gradle.properties with an empty version was accepted.'

    # The desktop CLI lookup, which was two functions with different search orders. The jar
    # ships under its version, so the newest by write time is taken: sorting names as text puts
    # 1.9.0 above 1.15.0.
    $env:HUSHFACEBOOK_DESKTOP_JAR = $null
    $env:HUSHFACEBOOK_WORKDIR = $null
    $tools = Join-Path $commonRoot 'repo/build/morphe-tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    $older = Join-Path $tools 'morphe-desktop-1.9.0-all.jar'
    $newer = Join-Path $tools 'morphe-desktop-1.15.0-all.jar'
    Set-Content -LiteralPath $older -Value 'old' -Encoding ASCII
    Set-Content -LiteralPath $newer -Value 'new' -Encoding ASCII
    (Get-Item -LiteralPath $older).LastWriteTime = (Get-Date).AddDays(-2)
    (Get-Item -LiteralPath $newer).LastWriteTime = (Get-Date)
    $repoRoot = Join-Path $commonRoot 'repo'
    Assert-True ((Resolve-DesktopCli -Root $repoRoot) -eq $newer) `
        'The desktop CLI lookup did not take the newest jar by write time.'

    Assert-True ($null -eq (Resolve-DesktopCli -Root (Join-Path $commonRoot 'empty'))) `
        'The lookup invented a jar where there is none.'
    Assert-Throws { Resolve-DesktopCli -Root (Join-Path $commonRoot 'empty') -Required } `
        '*No Morphe desktop CLI*' 'A required lookup with nothing to find did not say so.'
    Assert-Throws { Resolve-DesktopCli -Explicit (Join-Path $commonRoot 'absent.jar') -Root $repoRoot } `
        '*at the path given*' 'A named jar that is not there was quietly replaced by a search.'
    Assert-True ((Resolve-DesktopCli -Explicit $older -Root $repoRoot) -eq $older) `
        'An explicitly named jar was not honoured.'

    $env:HUSHFACEBOOK_DESKTOP_JAR = $newer
    Assert-True ((Resolve-DesktopCli -Root $repoRoot) -eq $newer) `
        'HUSHFACEBOOK_DESKTOP_JAR was not read.'

    # Split bundles. Facebook ships as an .apkm, and aapt2, apksigner and the resource check each
    # read one APK: the base, whose manifest and resource table describe the app. A split is
    # often the larger file, so the base is found by name first and by size only when no entry
    # carries its name, the way an .xapk names it after the package.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function New-TestBundleArchive {
        param([string]$Path, [System.Collections.Specialized.OrderedDictionary]$Entries)
        $archive = [System.IO.Compression.ZipFile]::Open($Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $Entries.Keys) {
                $writer = New-Object System.IO.StreamWriter($archive.CreateEntry($name).Open())
                try { $writer.Write($Entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $archive.Dispose() }
    }
    function Test-SamePath([string]$A, [string]$B) {
        return [System.IO.Path]::GetFullPath($A).Equals([System.IO.Path]::GetFullPath($B),
            [System.StringComparison]::OrdinalIgnoreCase)
    }
    $plainApk = Join-Path $commonRoot 'plain.apk'
    Set-Content -LiteralPath $plainApk -Value 'one apk' -Encoding ASCII
    Assert-True (Test-SamePath (Get-BaseApk -Apk $plainApk -Destination (Join-Path $commonRoot 'never.apk')) $plainApk) `
        'A plain APK was copied or replaced instead of being read as it is.'

    $apkm = Join-Path $commonRoot 'facebook.apkm'
    $apkmEntries = [ordered]@{
        'info.json' = '{"versioncode":"475019344"}'
        'base.apk' = 'base'
        'split_config.arm64_v8a.apk' = ('native code ' * 64)
    }
    New-TestBundleArchive -Path $apkm -Entries $apkmEntries
    $baseOut = Join-Path $commonRoot 'out/base.apk'
    Assert-True ((Test-SamePath (Get-BaseApk -Apk $apkm -Destination $baseOut) $baseOut) -and
        (Get-Content -LiteralPath $baseOut -Raw) -eq 'base') `
        'The base APK was not the one taken out of an .apkm with a larger split beside it.'

    # Relative paths, the way the recipe in the notes passes a fixture, from a session whose
    # process directory is somewhere else. PowerShell reads them against its own location and
    # .NET against the process's, so an unresolved path named a file in the wrong folder.
    $savedProcessDirectory = [Environment]::CurrentDirectory
    Push-Location -LiteralPath $commonRoot
    try {
        [Environment]::CurrentDirectory = [System.IO.Path]::GetTempPath()
        $relativeOut = Get-BaseApk -Apk 'facebook.apkm' -Destination 'out\relative-base.apk'
        Assert-True ((Test-SamePath $relativeOut (Join-Path $commonRoot 'out\relative-base.apk')) -and
            (Get-Content -LiteralPath (Join-Path $commonRoot 'out\relative-base.apk') -Raw) -eq 'base') `
            'A relative bundle path was read against the process directory instead of the current location.'
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }

    $xapk = Join-Path $commonRoot 'facebook.xapk'
    $xapkEntries = [ordered]@{
        'config.en.apk' = 'language'
        'com.example.app.apk' = ('app ' * 64)
    }
    New-TestBundleArchive -Path $xapk -Entries $xapkEntries
    $xapkOut = Join-Path $commonRoot 'out/xapk-base.apk'
    Assert-True ((Test-SamePath (Get-BaseApk -Apk $xapk -Destination $xapkOut) $xapkOut) -and
        (Get-Content -LiteralPath $xapkOut -Raw).StartsWith('app ')) `
        'A bundle with no base.apk did not fall back to its largest APK.'

    $noApk = Join-Path $commonRoot 'empty.apkm'
    New-TestBundleArchive -Path $noApk -Entries ([ordered]@{ 'info.json' = '{}' })
    Assert-Throws { Get-BaseApk -Apk $noApk -Destination (Join-Path $commonRoot 'out/none.apk') } `
        '*holds no APK*' 'A bundle with no APK in it handed back something to read.'

    # The fetch the release facts make of the indexed bundle and the add-source page. A real
    # request first: nothing listens on port 1, so it is refused before it leaves this machine.
    # Then the two answers a stand-in gives, a 404 the way both shells raise it and a 200.
    Assert-Throws {
        Assert-UrlReachable -Uri ([Uri]'http://127.0.0.1:1/patches.mpp') -Description 'test bundle URL' -FailureHint 'hint'
    } '*Could not reach the test bundle URL http://127.0.0.1:1/patches.mpp*' 'An address nothing answers passed the reachability check.'
    & {
        function Invoke-WebRequest { throw (New-NotFoundAnswer) }
        Assert-Throws {
            Assert-UrlReachable -Uri ([Uri]'https://github.com/example/none.mpp') -Description 'test bundle URL' `
                -FailureHint 'Publish it first.'
        } '*test bundle URL https://github.com/example/none.mpp answered HTTP 404. Publish it first.' `
            'An address answering 404 passed the reachability check.'
    }
    & {
        function Invoke-WebRequest { [pscustomobject]@{ StatusCode = 200 } }
        Assert-UrlReachable -Uri ([Uri]'https://github.com/example/patches.mpp') -Description 'test bundle URL' `
            -FailureHint 'hint' 6> $null
    }
} finally {
    $env:HUSHFACEBOOK_DESKTOP_JAR = $savedJar
    $env:HUSHFACEBOOK_WORKDIR = $savedWork
    Remove-Item -LiteralPath $commonRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] shared helper contracts passed'

# --- split bundle callers ----------------------------------------------------------------------
#
# The morphe CLI merges an .apkm's splits into one APK before it patches and leaves the merge
# beside its output. The patched manifest has to be compared with that merge, not with the base
# APK: the merge itself moves the manifest, and a receipt compared with the base would record the
# merge's changes as the patches' own, for somebody to allowlist. build-release-receipt.ps1 is run
# against exactly that in the release root section below; a pattern over its text passed with the
# merged APK ignored. Every caller that reads an APK out of a bundle goes through Get-BaseApk.
foreach ($name in @('build-release-receipt.ps1', 'verify-all-patches.ps1', 'verify-injected-registers.ps1')) {
    Assert-True ((Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw) -match 'Get-BaseApk -Apk') `
        "$name reads a bundle without taking its base APK out first."
}

Write-Host '[scripts] split bundle contracts passed'

# --- relative paths ------------------------------------------------------------------------------
#
# .NET reads a relative path against the process directory, which Set-Location doesn't move, while
# Test-Path and native commands use PowerShell's location. A path a script had just found could then
# open as a file somewhere else, or not at all: a relative -DesktopJar reached DexDiff as a jar that
# wasn't there. Every reader that hands a caller's path to .NET is asked here from a location the
# process directory isn't. The scripts that take such paths are run the same way in the release
# root below.

$relativeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-relative-" + [guid]::NewGuid().ToString('N'))
$savedProcessDirectory = [Environment]::CurrentDirectory
$savedDesktopJar = $env:HUSHFACEBOOK_DESKTOP_JAR
try {
    $here = Join-Path $relativeRoot 'here'
    $elsewhere = Join-Path $relativeRoot 'elsewhere'
    New-Item -ItemType Directory -Path (Join-Path $here 'tools'), $elsewhere -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $here 'tools\morphe-desktop.jar') -Value 'jar' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $here 'tools\d8.bat') -Value '@echo off' -Encoding ASCII
    New-TestBundleArchive -Path (Join-Path $here 'patches.mpp') -Entries ([ordered]@{
        'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.9`nTimestamp: 1000`nPatcher-Version: 1.14.1`n`n" })
    New-TestBundleArchive -Path (Join-Path $here 'app.apk') -Entries ([ordered]@{
        'AndroidManifest.xml' = 'manifest'; 'classes.dex' = "dex`n035" })
    New-TestBundleArchive -Path (Join-Path $here 'app.apkm') -Entries ([ordered]@{ 'base.apk' = 'base' })

    Push-Location -LiteralPath $here
    try {
        [Environment]::CurrentDirectory = $elsewhere
        $jar = Resolve-DesktopCli -Explicit 'tools\morphe-desktop.jar'
        Assert-True (Test-SamePath $jar (Join-Path $here 'tools\morphe-desktop.jar')) "A relative -DesktopJar came back as $jar."
        $env:HUSHFACEBOOK_DESKTOP_JAR = 'tools\morphe-desktop.jar'
        $jar = Resolve-DesktopCli
        Assert-True (Test-SamePath $jar (Join-Path $here 'tools\morphe-desktop.jar')) "A relative HUSHFACEBOOK_DESKTOP_JAR came back as $jar."
        $d8 = Resolve-D8 -Explicit 'tools\d8.bat' -Root $here
        Assert-True (Test-SamePath $d8 (Join-Path $here 'tools\d8.bat')) "A relative -D8 came back as $d8."
        $facts = Get-BundleManifestFacts -BundlePath 'patches.mpp'
        Assert-True ($facts.patcherVersion -eq '1.14.1') "A relative bundle was read as $($facts.patcherVersion)."
        Assert-True (Test-ApkFile -Path 'app.apk') 'A relative APK was not read as one.'
        $base = Get-BaseApk -Apk 'app.apkm' -Destination 'out\base.apk'
        Assert-True ((Test-SamePath $base (Join-Path $here 'out\base.apk')) -and (Test-Path -LiteralPath $base)) `
            "A relative bundle's base APK went to $base."
        Assert-True (@(Get-ChildItem -LiteralPath $elsewhere -Recurse -Force).Count -eq 0) `
            'A relative path was written in the process directory instead.'
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }
} finally {
    [Environment]::CurrentDirectory = $savedProcessDirectory
    $env:HUSHFACEBOOK_DESKTOP_JAR = $savedDesktopJar
    Remove-Item -LiteralPath $relativeRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] relative path contracts passed'

# --- a release root ------------------------------------------------------------------------------
#
# The release files of this checkout, copied into a fixture repository and committed, the way the
# tree looks at a release commit. The release check reads the commit a receipt names out of git,
# so a copied tree that is not a repository never reaches the receipt, and the facts fixture above
# holds none: every case there took the "no receipt" exit, and the receipt half was covered only
# by a pattern over the script's text, which also matched a mutation that held the receipt to the
# newest build alone. The receipt builder runs here too, on stand-ins for the tools it starts,
# since patterns over its text passed with the merged APK ignored and with forced runs decided
# against the newest build, and so does the device build, on the same stand-ins.

$releaseRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-release-" + [guid]::NewGuid().ToString('N'))
try {
    $releaseRepo = Join-Path $releaseRoot 'repo'
    # The source ledger and the two files its rules hold an adopted source to go in as well: a
    # release is held to the census, and .gitignore has to let the ledger be committed.
    $releaseFiles = @('patches-list.json', 'patches-bundle.json', 'gradle.properties', 'README.md', 'CHANGELOG.md',
        'gradle/libs.versions.toml', '.github/ISSUE_TEMPLATE/bug_report.yml', '.gitignore',
        'sources/facebook-sources.json', 'NOTICE', 'provenance.json')
    foreach ($relative in $releaseFiles) {
        $destination = Join-Path $releaseRepo $relative
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $Root $relative) -Destination $destination
    }
    # The ledger, dated today and listed on every index, so the census a release is held to passes
    # and each published-asset case below is refused for its own fact. The checked-in ledger's date
    # moves with every audit and its listings wait on submissions, and neither is what those cases
    # are about. The census cases further down move one fact at a time.
    $releaseLedgerPath = Join-Path $releaseRepo 'sources/facebook-sources.json'
    $releaseLedgerSource = [System.IO.File]::ReadAllText($releaseLedgerPath)
    function Save-ReleaseLedger([int]$AgeDays = 0, [switch]$Pending) {
        $document = $releaseLedgerSource | ConvertFrom-Json
        $checked = [datetime]::UtcNow.Date.AddDays(-$AgeDays).ToString('yyyy-MM-dd')
        $document.census.checkedAt = $checked
        foreach ($index in $document.indexes) {
            $index.hushfacebook = if ($Pending) { [pscustomobject]@{ status = 'not-listed'; checked = $checked } } else {
                [pscustomobject]@{ status = 'listed'; url = 'https://example.com/listing'; checked = $checked } }
        }
        foreach ($record in @(@($document.entries) + @($document.outOfScope))) { if ($record) { $record.lastChecked = $checked } }
        [System.IO.File]::WriteAllText($releaseLedgerPath, ($document | ConvertTo-Json -Depth 20),
            (New-Object System.Text.UTF8Encoding($false)))
    }
    Save-ReleaseLedger
    # The copied source can be one commit ahead of the published index while a release is being
    # prepared. Here the copied tree is the release, so the index is written up to the catalog
    # before any strict index-push case runs.
    $releaseVersionForIndex = Get-BundleVersion -Root $releaseRepo
    $releaseCatalogForIndex = Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw |
        ConvertFrom-Json
    $releaseTargetForIndex = Get-PatchTarget -PatchList $releaseCatalogForIndex
    $releasePatchCountForIndex = @($releaseCatalogForIndex.patches).Count
    $releaseFloor = (Read-CatalogToolchain -Source 'the copied catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $releaseRepo 'gradle/libs.versions.toml') -Raw)).ManagerFloor
    $releaseIndexPath = Join-Path $releaseRepo 'patches-bundle.json'
    $releaseIndexText = Get-Content -LiteralPath $releaseIndexPath -Raw
    $releaseIndex = $releaseIndexText | ConvertFrom-Json
    $copiedIndexVersion = [string]$releaseIndex.version
    if ($copiedIndexVersion -ne $releaseVersionForIndex) {
        $releaseIndexText = $releaseIndexText -replace
            ('(?<![\d.])' + [regex]::Escape($copiedIndexVersion) + '(?!\d)'), $releaseVersionForIndex
    }
    $releaseIndexText = $releaseIndexText -replace
        '(?<!\d)\d+ patches\b', "$releasePatchCountForIndex patches"
    $describedBuild = [regex]::Match([string]$releaseIndex.description, 'Facebook\s+(\d+(?:\.\d+)+)')
    if ($describedBuild.Success -and $describedBuild.Groups[1].Value -ne $releaseTargetForIndex.PackageVersion) {
        $releaseIndexText = $releaseIndexText -replace
            ('(?<![\d.])' + [regex]::Escape($describedBuild.Groups[1].Value) + '(?!\d)'),
            $releaseTargetForIndex.PackageVersion
    }
    $releaseIndexText = $releaseIndexText -replace
        '(Morphe Manager )\d+(?:\.\d+)+( or newer)', "`${1}$releaseFloor`${2}"
    Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText
    $releaseBugFormPath = Join-Path $releaseRepo '.github/ISSUE_TEMPLATE/bug_report.yml'
    $releaseBugFormText = Get-Content -LiteralPath $releaseBugFormPath -Raw
    $releaseBugFormText = $releaseBugFormText -replace
        '(placeholder:\s*Version )\S+( for Facebook)', "`${1}$releaseVersionForIndex`${2}"
    $releaseBugFormText = $releaseBugFormText -replace
        '(placeholder:\s*Version \S+ for Facebook )\d+(?:\.\d+)+',
        "`${1}$($releaseTargetForIndex.PackageVersion)"
    Set-Content -LiteralPath $releaseBugFormPath -Encoding UTF8 -NoNewline -Value $releaseBugFormText
    # Through Invoke-FixtureGit, with the git directory proved before anything is written, for
    # the reason the toolchain fixture above gives.
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('init', '--quiet') | Out-Null
    $releaseGitDir = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    Assert-True ($releaseGitDir -and ([IO.Path]::GetFullPath($releaseGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $releaseRepo '.git')).TrimEnd('\', '/'))) `
        "The release fixture resolved to $releaseGitDir, not its own repository. Refusing to write."
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', 'user.name', 'Contracts') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitted = @(Invoke-FixtureGit -Root $releaseRepo -Arguments @('show', '--name-only', '--format=', 'HEAD') |
        Where-Object { "$_".Trim() })
    Assert-True ($releaseCommitted.Count -eq $releaseFiles.Count) `
        ("The release fixture's commit touched $($releaseCommitted.Count) files, not the $($releaseFiles.Count) " +
            'copied: ' + (($releaseCommitted | Select-Object -First 5) -join ', '))
    $releaseCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    $releaseSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct') |
        Select-Object -First 1)".Trim()

    $releaseVersionHere = Get-BundleVersion -Root $releaseRepo
    $releaseCatalog = Get-Content -LiteralPath (Join-Path $releaseRepo 'patches-list.json') -Raw | ConvertFrom-Json
    $releaseTarget = Get-PatchTarget -PatchList $releaseCatalog
    $releaseNames = @($releaseCatalog.patches | ForEach-Object { [string]$_.name })
    $releaseToolchain = Read-CatalogToolchain -Source 'the release fixture catalog' `
        -Text (Get-Content -LiteralPath (Join-Path $releaseRepo 'gradle/libs.versions.toml') -Raw)
    $releaseReceipt = Join-Path $releaseRepo "release-receipt-$releaseVersionHere.json"
    # The tag the check reads the index's floor through, named for the version the index publishes.
    $indexVersionHere = [string]($releaseIndexText | ConvertFrom-Json).version
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$indexVersionHere", $releaseCommit) | Out-Null

    # A receipt for this commit with a run of each build given, every patch applied and no
    # manifest change, written where the release check looks for it. A schema 1 receipt names no
    # SBOM, as the ones cut before it existed don't.
    function Save-ReleaseReceipt([string[]]$Builds, [string]$Commit = $releaseCommit, [long]$Seconds = $releaseSeconds,
            [int]$Schema = (Get-ReleaseReceiptSchemaVersion)) {
        $targets = @(for ($i = 0; $i -lt $Builds.Count; $i++) {
            [ordered]@{
                source        = [ordered]@{ file = "facebook-$($Builds[$i])-arm64-v8a.apkm"
                    package = $releaseTarget.PackageName; versionName = $Builds[$i]; versionCode = "47500000$i"
                    sha256 = ([string]'ABCDEF'[$i % 6] * 64); forced = $false }
                patches       = @($releaseNames | ForEach-Object { [ordered]@{ name = $_; applied = $true; reason = $null } })
                manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                    exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
            }
        })
        $document = [ordered]@{
            schemaVersion = $Schema
            release   = [ordered]@{ version = $releaseVersionHere; tag = "v$releaseVersionHere"; commit = $Commit
                commitTimestamp = $Seconds; patchCount = $releaseNames.Count }
            bundle    = [ordered]@{ file = "patches-$releaseVersionHere.mpp"; sizeBytes = 10; sha256 = ('E' * 64)
                timestamp = $Seconds * 1000 }
            sbom      = [ordered]@{ file = "patches-$releaseVersionHere.cdx.json"; sha256 = ('D' * 64); components = 3 }
            toolchain = [ordered]@{ patcherVersion = $releaseToolchain.PatcherVersion; managerFloor = $releaseToolchain.ManagerFloor }
            extension = [ordered]@{ dexPayloads = @([ordered]@{ name = 'extensions/facebook.mpe'; sizeBytes = 10; sha256 = ('F' * 64) }) }
            targets   = $targets
        }
        if ($Schema -lt 2) { $document.Remove('sbom') }
        Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
    }
    # The run the hook makes for a push that carries a receipt: lenient, since the index may lag
    # the source, and with no network. What it says is kept, to tell a receipt it compared from
    # one it never found.
    function Invoke-ReleaseCheck {
        $global:LASTEXITCODE = 0
        $said = @(& $factsScript -Root $releaseRepo -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck 6>&1 |
            ForEach-Object { "$_" }) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "The release check exited $LASTEXITCODE on the release root: $said" }
        return $said
    }

    # A run of every declared build, which is what a release needs, compared rather than skipped.
    Save-ReleaseReceipt -Builds $releaseTarget.PackageVersions
    try {
        $said = Invoke-ReleaseCheck
    } catch {
        throw "The release check refused a receipt with a run of every declared build: $($_.Exception.Message)"
    }
    $proved = "the receipt proves $($releaseNames.Count) patches on $($releaseTarget.PackageVersions -join ', ') " +
        "from commit $($releaseCommit.Substring(0, 8))"
    Assert-True ($said -like "*$proved*") "The release check did not compare the receipt it was given: $said"
    Assert-True ($said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere pins*") `
        "The release check did not hold the index's Manager floor to the release tag: $said"

    # An index still naming the floor of the release before it, which is what reusing its text
    # does. Nothing read the description's floor until 2026-09-25.
    Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value (
        $releaseIndexText -replace '(Morphe Manager )\d+(?:\.\d+)+( or newer)', '${1}1.20.0${2}')
    try {
        Assert-Throws { Invoke-ReleaseCheck } '*Morphe Manager 1.20.0 or newer*' `
            'An index naming another Manager floor than its release tag pins was accepted.'
    } finally {
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText
    }

    # A run of the newest build alone, the receipt a release that skipped the older fixture would
    # write. Refused, naming the build it never ran.
    Save-ReleaseReceipt -Builds @($releaseTarget.PackageVersions[0])
    $unproved = @($releaseTarget.PackageVersions | Select-Object -Skip 1)
    Assert-True ($unproved.Count -gt 0) 'The catalog declares one build, so the case below would prove nothing.'
    Assert-Throws { Invoke-ReleaseCheck } ("*No target in the receipt is the declared $($releaseTarget.PackageName) " +
            "$($unproved -join ', ') patched without -f*") `
        'The release check accepted a receipt with no run of an older declared build.'

    # build-release-receipt.ps1 itself, on the same root. Stand-ins take the tools' places: a JDK
    # that answers -version and does what the desktop CLI leaves behind for each input (the result
    # report, the patched APK and the merged APK beside it), and an aapt2 that prints the manifest
    # lines written for each APK. One .apkm per declared build, and a bundle stamped with the
    # commit's time where buildAndroid leaves it, carrying the classes.dex the published asset check
    # at the end of this section looks for. The CLI's merge carries a component the base
    # APK's manifest lacks, so a delta taken against the base instead of the merge records it as
    # the patches' own and the empty allowlist refuses the receipt.
    $tools = Join-Path $releaseRoot 'tools'
    $fixtures = Join-Path $releaseRoot 'fixtures'
    New-Item -ItemType Directory -Path $tools, $fixtures -Force | Out-Null
    $stubJava = Join-Path $tools 'java.cmd'
    $stubAapt2 = Join-Path $tools 'aapt2.cmd'
    $stubJar = Join-Path $tools 'morphe-desktop.jar'
    $javaLog = Join-Path $tools 'java.log'
    [System.IO.File]::WriteAllText($stubJava, ((@(
        '@echo off',
        'setlocal EnableExtensions EnableDelayedExpansion',
        'if "%~1"=="-version" (',
        '    echo openjdk version "21.0.5" 2024-10-15',
        '    exit /b 0',
        ')',
        'set "OUT=" & set "RESULT=" & set "LAST=" & set "PREV=" & set "FORCED=0"',
        'rem Its own folder, read before shift moves %0 along with the arguments.',
        'set "HERE=%~dp0"',
        'shift',
        'shift',
        'rem patch-for-device.ps1 hands the CLI one argument file, a quoted value a line with',
        'rem backslashes doubled; the receipt builder passes the arguments themselves.',
        'set "FIRST=%~1"',
        'if not "!FIRST:~0,1!"=="@" goto next',
        'set "ARGUMENTS=!FIRST:~1!"',
        'for /f "usebackq delims=" %%L in ("%ARGUMENTS%") do (',
        '    set "V=%%~L"',
        '    set "V=!V:\\=\!"',
        '    call :take',
        ')',
        'goto run',
        ':next',
        'if "%~1"=="" goto run',
        'set "V=%~1"',
        'call :take',
        'shift',
        'goto next',
        ':take',
        'if "!PREV!"=="-o" set "OUT=!V!"',
        'if "!PREV!"=="-r" set "RESULT=!V!"',
        'if "!V!"=="-f" set "FORCED=1"',
        'set "PREV=!V!"',
        'set "LAST=!V!"',
        'exit /b 0',
        ':run',
        '>>"!HERE!java.log" echo patch !LAST! forced=!FORCED!',
        'rem A case that needs something to change while a fixture is patched leaves this behind.',
        'if exist "!HERE!during-patch.cmd" call "!HERE!during-patch.cmd"',
        'copy /y "!LAST!.result.json" "!RESULT!" >nul || exit /b 3',
        'copy /y "!HERE!patched.apk" "!OUT!" >nul || exit /b 4',
        'copy /y "!LAST!.patched.txt" "!OUT!.xmltree" >nul || exit /b 5',
        'for %%F in ("!OUT!") do set "OUTDIR=%%~dpF"',
        'for %%F in ("!LAST!") do set "STEM=%%~nF"',
        'copy /y "!LAST!.merged.txt" "!OUTDIR!!STEM!-merged.apk" >nul || exit /b 6',
        'exit /b 0') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    [System.IO.File]::WriteAllText($stubAapt2, ((@(
        '@echo off',
        'setlocal EnableExtensions DisableDelayedExpansion',
        'set "APK="',
        ':next',
        'if "%~1"=="" goto run',
        'set "APK=%~1"',
        'shift',
        'goto next',
        ':run',
        'if exist "%APK%.xmltree" (type "%APK%.xmltree") else (type "%APK%")',
        'exit /b %errorlevel%') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    Set-Content -LiteralPath $stubJar -Value 'not a jar' -Encoding ASCII
    New-TestBundleArchive -Path (Join-Path $tools 'patched.apk') -Entries ([ordered]@{
        'AndroidManifest.xml' = 'binary manifest'; 'classes.dex' = "dex`n035" })

    $androidName = 'http://schemas.android.com/apk/res/android:name(0x01010003)='
    $androidExported = '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
    function Get-FixtureManifest([string]$Build, [string]$Code, [switch]$WithSplit, [string]$Package = $releaseTarget.PackageName) {
        $lines = @(
            'N: android=http://schemas.android.com/apk/res/android (line=1)',
            '  E: manifest (line=1)',
            "    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=$Code",
            "    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)=`"$Build`" (Raw: `"$Build`")",
            "    A: package=`"$Package`" (Raw: `"$Package`")",
            '      E: uses-permission (line=10)',
            "        A: $androidName`"android.permission.INTERNET`" (Raw: `"android.permission.INTERNET`")",
            '      E: application (line=20)',
            '        E: activity (line=21)',
            "          A: $androidName`"com.facebook.katana.LoginActivity`" (Raw: `"com.facebook.katana.LoginActivity`")",
            $androidExported)
        if ($WithSplit) {
            $lines += @('        E: activity (line=40)',
                "          A: $androidName`"com.facebook.split.FeatureActivity`" (Raw: `"com.facebook.split.FeatureActivity`")",
                $androidExported)
        }
        return ($lines -join "`n") + "`n"
    }
    $dependencyNamesHere = @(Get-PatchDependencyNames -PatchList $releaseCatalog -RequestedNames $releaseNames)
    $fixturePaths = @{}
    $versionCode = 475119344
    # Beside the declared builds, a newer one the catalog doesn't declare, the kind a release run
    # patches under -f to see what still applies on it.
    $newerBuild = "$([int]($releaseTarget.PackageVersion -split '\.')[0] + 1).0.0.1.1"
    foreach ($build in @($newerBuild) + @($releaseTarget.PackageVersions)) {
        $apkm = Join-Path $fixtures "facebook-$build-arm64-v8a.apkm"
        New-TestBundleArchive -Path $apkm -Entries ([ordered]@{
            'info.json' = "{`"versioncode`":`"$versionCode`"}"
            'base.apk' = Get-FixtureManifest -Build $build -Code "$versionCode"
            'split_config.arm64_v8a.apk' = ('native code ' * 64) })
        Set-Content -LiteralPath "$apkm.merged.txt" -Encoding ASCII -NoNewline `
            -Value (Get-FixtureManifest -Build $build -Code "$versionCode" -WithSplit)
        Set-Content -LiteralPath "$apkm.patched.txt" -Encoding ASCII -NoNewline `
            -Value (Get-FixtureManifest -Build $build -Code "$versionCode" -WithSplit)
        # The report the CLI writes: every patch and the internal dependencies applied, one step,
        # and the input's own version, which is what the CLI reports.
        Set-Content -LiteralPath "$apkm.result.json" -Encoding ASCII -Value ([ordered]@{
            patchingSteps = @([ordered]@{ success = $true })
            appliedPatches = @(@($releaseNames) + @($dependencyNamesHere) | ForEach-Object { [ordered]@{ name = $_ } })
            failedPatches = @()
            packageName = $releaseTarget.PackageName
            packageVersion = $build } | ConvertTo-Json -Depth 6)
        $fixturePaths[$build] = $apkm
        $versionCode -= 100000
    }
    $releaseBundle = Get-ReleaseBundlePath -Root $releaseRepo
    New-Item -ItemType Directory -Path (Split-Path -Parent $releaseBundle) -Force | Out-Null
    New-TestBundleArchive -Path $releaseBundle -Entries ([ordered]@{
        'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($releaseSeconds * 1000)`n" +
            "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
        'classes.dex' = "dex`n035" + ('patches' * 8)
        'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
    # And the SBOM buildAndroid writes beside it, listing a library OSV has nothing against.
    $releaseSbom = [System.IO.Path]::ChangeExtension($releaseBundle, '.cdx.json')
    New-TestSbom -Path $releaseSbom -Bundle $releaseBundle

    # The builder reads git with a plain `git -C`, which a GIT_DIR inherited from a hook would
    # override, so every GIT_* variable is cleared for the length of a run. OSV is the stand-in
    # above, and what the builder says is kept in $builderSaid, warnings included.
    $builderSaid = ''
    function Invoke-ReceiptBuilder([string[]]$Fixtures, [string]$Bundle,
            [string]$WorkDir = (Join-Path $releaseRoot 'work'), [string]$DesktopJar = $stubJar, [switch]$SkipAdvisoryCheck) {
        Remove-Item -LiteralPath $javaLog -Force -ErrorAction SilentlyContinue
        $saved = @{}
        foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
            $saved[$variable.Name] = $variable.Value
            Remove-Item -LiteralPath ('Env:\' + $variable.Name)
        }
        try {
            . $osvStandIn
            $global:LASTEXITCODE = 0
            $arguments = @{ Root = $releaseRepo; Fixture = $Fixtures; WorkDir = $WorkDir; DesktopJar = $DesktopJar
                Java = $stubJava; Aapt2 = $stubAapt2 }
            if ($Bundle) { $arguments['Bundle'] = $Bundle }
            if ($SkipAdvisoryCheck) { $arguments['SkipAdvisoryCheck'] = $true }
            $script:builderSaid = @(& (Join-Path $PSScriptRoot 'build-release-receipt.ps1') @arguments 3>&1 6>&1 |
                ForEach-Object { "$_" }) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw "build-release-receipt.ps1 exited $LASTEXITCODE." }
        } finally {
            foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
        }
    }

    # A fixture for every declared build and the newer one: one target each, only the newer build
    # forced, every patch applied, and no manifest change, because the patched manifest is held to
    # the merge and not to the base.
    $builtBuilds = @($releaseTarget.PackageVersions) + @($newerBuild)
    $allFixtures = @($builtBuilds | ForEach-Object { $fixturePaths[$_] })
    try {
        Invoke-ReceiptBuilder -Fixtures $allFixtures
    } catch {
        throw "build-release-receipt.ps1 refused a run of every declared build: $($_.Exception.Message)"
    }
    $built = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
    $builtTargets = @($built.targets)
    $builtVersions = @($builtTargets | ForEach-Object { [string]$_.source.versionName })
    Assert-True (($builtVersions -join ',') -eq ($builtBuilds -join ',')) `
        "The receipt does not hold one run of each fixture: $($builtVersions -join ', ')"
    foreach ($builtTarget in $builtTargets) {
        $label = [string]$builtTarget.source.versionName
        $declared = $releaseTarget.PackageVersions -contains $label
        Assert-True ($builtTarget.source.forced -eq (-not $declared)) `
            "The receipt says $label was $(if ($builtTarget.source.forced) { 'forced' } else { 'not forced' })."
        Assert-True ($builtTarget.source.sha256 -eq (Get-Sha256Hex -Path $fixturePaths[$label])) `
            "The receipt does not hash the $label fixture it was given."
        Assert-True (@($builtTarget.patches | Where-Object { $_.applied }).Count -eq $releaseNames.Count) `
            "The receipt does not record every patch applied to $label."
        $changes = @(ConvertTo-ManifestDeltaEntries -Delta $builtTarget.manifestDelta)
        Assert-True ($changes.Count -eq 0) "The receipt records the merge's own manifest change for $label as the patches': $($changes -join ', ')"
    }
    $patchRuns = @(Get-Content -LiteralPath $javaLog)
    $expectedRuns = @($builtBuilds | ForEach-Object {
        "patch $($fixturePaths[$_]) forced=$(if ($releaseTarget.PackageVersions -contains $_) { 0 } else { 1 })" })
    Assert-True (($patchRuns -join "`n") -eq ($expectedRuns -join "`n")) `
        "The CLI was not run once per fixture, with -f for the undeclared build only: $($patchRuns -join '; ')"
    # The SBOM beside the bundle, recorded by name, hash and count, once OSV had been asked about it.
    Assert-True ($built.sbom.file -eq "patches-$releaseVersionHere.cdx.json" -and
        $built.sbom.sha256 -ceq (Get-Sha256Hex -Path $releaseSbom) -and [int]$built.sbom.components -eq 3) `
        "The receipt does not record the SBOM beside the bundle: $($built.sbom | ConvertTo-Json -Compress)"
    Assert-True ($builderSaid -like "*OSV has no advisory for the libraries patches-$releaseVersionHere.cdx.json lists: gson 2.14.0*") `
        "The receipt builder did not put the SBOM's libraries to OSV: $builderSaid"
    # And the receipt the builder writes is one the release check accepts.
    $said = Invoke-ReleaseCheck
    $builtProved = "the receipt proves $($releaseNames.Count) patches on $($builtVersions -join ', ') " +
        "from commit $($releaseCommit.Substring(0, 8))"
    Assert-True ($said -like "*$builtProved*") "The release check did not accept the receipt the builder wrote: $said"

    # The newest build alone, or beside the undeclared one, is not enough for a receipt: the older
    # declared build has no run. The builder says so before it patches anything. It used to patch
    # every fixture first, which for Facebook unpacks gigabytes, and then refuse its own receipt.
    foreach ($partial in @(@($releaseTarget.PackageVersion), @($releaseTarget.PackageVersion, $newerBuild))) {
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($partial | ForEach-Object { $fixturePaths[$_] }) } `
            "*No fixture is the declared $($releaseTarget.PackageName) $($unproved -join ', ')*Nothing was patched*" `
            "build-release-receipt.ps1 went ahead with fixtures of $($partial -join ', ') only."
        if (Test-Path -LiteralPath $javaLog) {
            throw ("build-release-receipt.ps1 patched before it found a declared build missing: " +
                (@(Get-Content -LiteralPath $javaLog) -join '; '))
        }
    }

    # The SBOM and what OSV says about it come before anything is patched. The deliberately
    # vulnerable fixture is this bundle with an SBOM listing gson 2.8.8, and no receipt comes of it.
    # Offline, -SkipAdvisoryCheck gets a receipt with a warning, and the index push below asks OSV
    # again. An OSV out of reach stops the run, and so does an SBOM of another build or none at all.
    $cleanSbomBytes = [System.IO.File]::ReadAllBytes($releaseSbom)
    $cleanReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    try {
        New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -Libraries @($gsonPurl)
        Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
            '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
            'build-release-receipt.ps1 wrote a receipt for a bundle carrying gson 2.8.8.'
        Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 patched before it asked OSV about the SBOM.'
        Invoke-ReceiptBuilder -Fixtures $allFixtures -SkipAdvisoryCheck
        Assert-True ($builderSaid -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries*' -and
            (Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json).sbom.sha256 -ceq (Get-Sha256Hex -Path $releaseSbom)) `
            "An offline receipt run did not say the advisory check was skipped, or did not record the SBOM: $builderSaid"
        foreach ($refused in @(
                @{ Name = 'an OSV out of reach'; Sbom = { New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -Libraries @('pkg:maven/com.example/unknown@1.0') }
                    Pattern = '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' },
                @{ Name = 'an SBOM of another build'; Pattern = '*The SBOM does not describe the bundle*written for another build*'
                    Sbom = { New-TestSbom -Path $releaseSbom -Bundle $releaseBundle -Mutate { param($d) $d.metadata.component.hashes[0].content = ('0' * 64) } } },
                @{ Name = 'no SBOM'; Sbom = { Remove-Item -LiteralPath $releaseSbom }; Pattern = "*No SBOM for the bundle: $releaseSbom*" })) {
            & $refused.Sbom
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } $refused.Pattern "build-release-receipt.ps1 went ahead with $($refused.Name)."
            Assert-True (-not (Test-Path -LiteralPath $javaLog)) "build-release-receipt.ps1 patched with $($refused.Name)."
        }
        # The receipt names the SBOM still beside the bundle when it's written: another buildAndroid
        # during the patch runs, which take long enough for one, replaces it.
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        $replacement = Join-Path $releaseRoot 'replacement.cdx.json'
        New-TestSbom -Path $replacement -Bundle $releaseBundle
        $duringPatch = Join-Path $tools 'during-patch.cmd'
        [System.IO.File]::WriteAllText($duringPatch, "@copy /y `"$replacement`" `"$releaseSbom`" >nul`r`n", [System.Text.Encoding]::ASCII)
        try {
            Assert-Throws { Invoke-ReceiptBuilder -Fixtures $allFixtures } `
                "*does not pass validation: The receipt says patches-$releaseVersionHere.cdx.json hashes to*" `
                'build-release-receipt.ps1 wrote a receipt naming an SBOM that was replaced while it patched.'
        } finally {
            Remove-Item -LiteralPath $duringPatch -Force -ErrorAction SilentlyContinue
        }
    } finally {
        [System.IO.File]::WriteAllBytes($releaseSbom, $cleanSbomBytes)
        [System.IO.File]::WriteAllBytes($releaseReceipt, $cleanReceiptBytes)
    }

    # patch-for-device.ps1 on the same root and stand-ins. It held every run's report to the
    # newest declared build, so an older declared build (README: 577 works too) was patched and
    # then refused with "unexpected package or version". Each declared build is held to its own
    # version now, the default fixture is still the newest build, and an undeclared build is
    # refused before the CLI starts. No -Serial, so nothing goes near adb.
    $deviceOut = Join-Path $releaseRoot 'device'
    function Invoke-DeviceBuild([string]$Apk, [string]$OutDir = $deviceOut, [string]$DesktopJar = $stubJar) {
        Remove-Item -LiteralPath $javaLog -Force -ErrorAction SilentlyContinue
        $arguments = @{ Root = $releaseRepo; DesktopJar = $DesktopJar; Java = $stubJava; Aapt2 = $stubAapt2; OutDir = $OutDir }
        if ($Apk) { $arguments['Apk'] = $Apk }
        & (Join-Path $PSScriptRoot 'patch-for-device.ps1') @arguments 6> $null
    }
    $deviceApk = Join-Path $deviceOut "hushfacebook-$releaseVersionHere-signed.apk"
    foreach ($build in $releaseTarget.PackageVersions) {
        try {
            Invoke-DeviceBuild -Apk $fixturePaths[$build]
        } catch {
            throw "patch-for-device.ps1 refused the declared build ${build}: $($_.Exception.Message)"
        }
        $deviceRuns = @(Get-Content -LiteralPath $javaLog)
        Assert-True ($deviceRuns.Count -eq 1 -and $deviceRuns[0] -eq "patch $($fixturePaths[$build]) forced=0" -and
            (Test-Path -LiteralPath $deviceApk -PathType Leaf)) `
            "patch-for-device.ps1 did not build $build once, without -f: $($deviceRuns -join '; ')"
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $deviceOut 'stock-base.apk'))) `
            "patch-for-device.ps1 left the base APK it read for $build behind."
    }
    $savedFixtureDir = $env:HUSHFACEBOOK_FIXTURE_DIR
    try {
        $env:HUSHFACEBOOK_FIXTURE_DIR = $fixtures
        Invoke-DeviceBuild
        Assert-True ((@(Get-Content -LiteralPath $javaLog) -join '; ') -eq
            "patch $($fixturePaths[$releaseTarget.PackageVersion]) forced=0") `
            "With no -Apk, patch-for-device.ps1 did not take the newest declared build from the fixture folder."
    } finally {
        $env:HUSHFACEBOOK_FIXTURE_DIR = $savedFixtureDir
    }
    Assert-Throws { Invoke-DeviceBuild -Apk $fixturePaths[$newerBuild] } "*$newerBuild, which the bundle does not declare*" `
        'patch-for-device.ps1 took a build the catalog does not declare.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI on an undeclared build.'

    # Another Meta app at a build the catalog declares. Neither script may hand it to the CLI: each
    # refuses it by name before anything is patched. The builder gets it beside the newest declared
    # build, so every declared build has a fixture and only the package check stands in the way.
    $otherPackage = 'com.facebook.lite'
    $otherApkm = Join-Path $fixtures "facebook-lite-$($unproved[0])-arm64-v8a.apkm"
    New-TestBundleArchive -Path $otherApkm -Entries ([ordered]@{
        'info.json' = "{`"versioncode`":`"$versionCode`"}"
        'base.apk' = Get-FixtureManifest -Build $unproved[0] -Code "$versionCode" -Package $otherPackage })
    $otherRefusal = "*$(Split-Path -Leaf $otherApkm) is $otherPackage, not the catalog's target $($releaseTarget.PackageName)*"
    Assert-Throws { Invoke-ReceiptBuilder -Fixtures @($fixturePaths[$releaseTarget.PackageVersion], $otherApkm) } $otherRefusal `
        'build-release-receipt.ps1 took a fixture of another package.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'build-release-receipt.ps1 started the CLI on another package.'
    Assert-Throws { Invoke-DeviceBuild -Apk $otherApkm } $otherRefusal 'patch-for-device.ps1 took another package.'
    Assert-True (-not (Test-Path -LiteralPath $javaLog)) 'patch-for-device.ps1 started the CLI on another package.'

    # The builder, the release check and the device build given relative paths, from a location
    # the process directory isn't. Each found its file with Test-Path, in PowerShell's location,
    # and then opened or wrote it with .NET, which reads the process directory: the builder died
    # on a relative -Bundle with "Could not find file", and the release check on -ArtifactPath.
    $elsewhere = Join-Path $releaseRoot 'elsewhere'
    New-Item -ItemType Directory -Path $elsewhere -Force | Out-Null
    $inRoot = { param([string]$Path) $Path.Substring($releaseRoot.TrimEnd('\').Length + 1) }
    $savedProcessDirectory = [Environment]::CurrentDirectory
    Push-Location -LiteralPath $releaseRoot
    try {
        [Environment]::CurrentDirectory = $elsewhere
        try {
            Invoke-ReceiptBuilder -Fixtures @($allFixtures | ForEach-Object { & $inRoot $_ }) `
                -Bundle (& $inRoot $releaseBundle) -WorkDir 'work-relative' -DesktopJar (& $inRoot $stubJar)
        } catch {
            throw "build-release-receipt.ps1 refused relative paths: $($_.Exception.Message)"
        }
        $global:LASTEXITCODE = 0
        & $factsScript -Root $releaseRepo -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipUrlCheck `
            -ArtifactPath (& $inRoot $releaseBundle) 6> $null
        Assert-True ($LASTEXITCODE -eq 0) "The release check exited $LASTEXITCODE on a relative -ArtifactPath."
        try {
            Invoke-DeviceBuild -Apk (& $inRoot $fixturePaths[$releaseTarget.PackageVersion]) -OutDir 'device-relative' `
                -DesktopJar (& $inRoot $stubJar)
        } catch {
            throw "patch-for-device.ps1 refused relative paths: $($_.Exception.Message)"
        }
        Assert-True (Test-Path -LiteralPath (Join-Path $releaseRoot "device-relative\hushfacebook-$releaseVersionHere-signed.apk")) `
            'patch-for-device.ps1 given a relative -OutDir left no APK there.'
        Assert-True (@(Get-ChildItem -LiteralPath $elsewhere -Recurse -Force).Count -eq 0) `
            ("A relative path was written in the process directory instead: " +
                ((Get-ChildItem -LiteralPath $elsewhere -Recurse -Force | Select-Object -First 3 | ForEach-Object { $_.Name }) -join ', '))
    } finally {
        [Environment]::CurrentDirectory = $savedProcessDirectory
        Pop-Location
    }

    # The index push. `gh release create` makes the release tag on GitHub only, so the clone that
    # pushes the new description doesn't have it, and the floor check printed that it hadn't run
    # and let the push through. The hook's published asset run reads the tag's commit off the
    # remote and holds the bundle to it, so the floor is read at that commit as well; a run with no
    # bundle asks the repository the index names. A bare repository answers for GitHub through the
    # fixture's url.insteadOf, and stand-ins serve the download, its checksum list, the repository
    # description and the CLI's patch listing, so nothing leaves the machine.
    $indexUrl = [Uri]($releaseIndexText | ConvertFrom-Json).download_url
    $indexRepository = 'https://github.com/' + ((@($indexUrl.AbsolutePath.Trim('/') -split '/') | Select-Object -First 2) -join '/') + '.git'
    $publishedRepo = (Join-Path $releaseRoot 'published.git').Replace('\', '/')
    Invoke-FixtureGit -Root $releaseRoot -Arguments @('init', '--bare', '--quiet', $publishedRepo) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', "url.$publishedRepo.insteadOf", $indexRepository) | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', $publishedRepo, "refs/tags/v$indexVersionHere") | Out-Null
    Set-Content -LiteralPath (Join-Path $tools 'patch-names.txt') -Encoding ASCII -Value @($releaseNames | ForEach-Object { "Name: $_" })
    $listJava = Join-Path $tools 'list-java.cmd'
    [System.IO.File]::WriteAllText($listJava, ((@(
        '@echo off',
        'setlocal EnableExtensions',
        'rem The release check runs -jar <cli> list-patches ... --out=<file>, and cmd splits that at the',
        'rem equals sign unless the path has a space in it.',
        'set "HERE=%~dp0"',
        'set "LISTING="',
        'if "%~1"=="-version" (',
        '    echo openjdk version "21.0.5" 2024-10-15',
        '    exit /b 0',
        ')',
        ':next',
        'if "%~1"=="" exit /b 2',
        'set "ARG=%~1"',
        'if "%ARG%"=="--out" set "LISTING=%~2"',
        'if "%ARG:~0,6%"=="--out=" set "LISTING=%ARG:~6%"',
        'if defined LISTING goto list',
        'shift',
        'goto next',
        ':list',
        'copy /y "%HERE%patch-names.txt" "%LISTING%" >nul || exit /b 3',
        'exit /b 0') -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
    # What GitHub answers: the served bundle and SBOM, a checksum list naming both (or $servedSums,
    # when a case wants a wrong one), and the repository description. Dot-sourced into each runner,
    # so the check it starts finds them first. The SBOM served is a copy of the one beside the bundle,
    # which the cases below take away along with the bundle.
    $servedBundle = $releaseBundle
    $cleanServedSbom = Join-Path $releaseRoot "served\patches-$releaseVersionHere.cdx.json"
    New-Item -ItemType Directory -Path (Split-Path -Parent $cleanServedSbom) -Force | Out-Null
    Copy-Item -LiteralPath $releaseSbom -Destination $cleanServedSbom
    $servedSbom = $cleanServedSbom
    $servedSums = $null
    $publishedStandIns = {
        function Invoke-WebRequest {
            param($Uri, $Method, $OutFile, $MaximumRedirection, $TimeoutSec, [switch]$PassThru, [switch]$UseBasicParsing)
            if ($OutFile) {
                $served = if ("$Uri" -like '*.cdx.json') { $servedSbom } else { $servedBundle }
                Copy-Item -LiteralPath $served -Destination $OutFile -Force
            }
            $sums = if ($servedSums) { $servedSums } else {
                "$((Get-FileHash -LiteralPath $servedBundle -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.mpp`n" +
                    "$((Get-FileHash -LiteralPath $servedSbom -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.cdx.json`n"
            }
            [pscustomobject]@{ StatusCode = 200; Content = [Text.Encoding]::UTF8.GetBytes($sums) }
        }
        function gh {
            $global:LASTEXITCODE = 0
            "Hushfacebook v${indexVersionHere}: $($releaseNames.Count) patches for Facebook $($releaseTarget.PackageVersion)."
        }
    }
    function Invoke-IndexPushCheck([hashtable]$Arguments) {
        $saved = @{}
        foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
            $saved[$variable.Name] = $variable.Value
            Remove-Item -LiteralPath ('Env:\' + $variable.Name)
        }
        # The asset check asks git for the published tag from where it runs, not through -Root.
        Push-Location -LiteralPath $releaseRepo
        try {
            . $publishedStandIns
            . $osvStandIn
            $global:LASTEXITCODE = 0
            $said = @(& $factsScript -Root $releaseRepo @Arguments 3>&1 6>&1 | ForEach-Object { "$_" }) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw "The release check exited $LASTEXITCODE on the index push: $said" }
            return $said
        } finally {
            Pop-Location
            foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
        }
    }
    $publishedRun = @{ VerifyPublishedAsset = $true; ArtifactPath = $releaseBundle; SkipDescriptionTestCount = $true
        SkipUrlCheck = $true; DesktopJar = $stubJar; Java = $listJava }
    try {
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '-d', "v$indexVersionHere") | Out-Null
        $said = Invoke-IndexPushCheck $publishedRun
        Assert-True ($said -like "*published bundle is pinned to v$indexVersionHere ($releaseCommit)*" -and
            $said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere pins*") `
            "The index push was not held to the Manager floor its published tag pins: $said"
        # The SBOM the receipt names, as the release hosts it, and OSV asked about it again.
        Assert-True ($said -like ("*the hosted patches-$indexVersionHere.cdx.json is the SBOM the receipt names and " +
                "SHA256SUMS.txt lists, and it describes patches-$indexVersionHere.mpp, payloads and all*") -and
            $said -like "*OSV has no advisory for the libraries patches-$indexVersionHere.cdx.json lists: gson 2.14.0*") `
            "The index push did not hold the hosted SBOM to the receipt, or did not ask OSV about it: $said"
        Assert-True ($said -like '*the Facebook-family source census is 0 day(s) old*every index lists Hushfacebook or has its submission*') `
            "The index push was not held to the Facebook-family source census: $said"

        # The census, one fact at a time. Fourteen days old is still a release; fifteen isn't. An
        # index that doesn't list Hushfacebook yet is named in what the release says, not a refusal:
        # a submission is a public request on someone else's project. The lenient check every other
        # push runs reads none of it, so a README fix never waits on an audit.
        try {
            Save-ReleaseLedger -AgeDays 14
            $said = Invoke-IndexPushCheck $publishedRun
            Assert-True ($said -like '*the Facebook-family source census is 14 day(s) old*') `
                "A release on a census 14 days old did not say so: $said"
            Save-ReleaseLedger -AgeDays 15
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*source census*the census is 15 days old*audit-facebook-sources.ps1*' `
                'A release went out on a census 15 days old.'
            try {
                Invoke-ReleaseCheck | Out-Null
            } catch {
                throw "The lenient check an ordinary push runs refused a census 15 days old: $($_.Exception.Message)"
            }
            Save-ReleaseLedger -Pending
            $said = Invoke-IndexPushCheck $publishedRun
            Assert-True ($said -like '*Hushfacebook is not listed on *yet and has no submission recorded there*') `
                "A release with an index that doesn't list Hushfacebook didn't name it: $said"
        } finally {
            Save-ReleaseLedger
        }

        # Each way the hosted SBOM can fail it. A receipt that names the SBOM served has its hash
        # and count written in, so that what refuses the push is the case's own difference. The
        # deliberately vulnerable fixture goes out here too: the receipt for it was built offline,
        # and this is the check that asks OSV again.
        $cleanReceiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
        function Use-ServedSbom([string[]]$Libraries, [switch]$KeepReceipt) {
            $script:servedSbom = Join-Path $releaseRoot "served\case\patches-$releaseVersionHere.cdx.json"
            New-Item -ItemType Directory -Path (Split-Path -Parent $script:servedSbom) -Force | Out-Null
            New-TestSbom -Path $script:servedSbom -Bundle $releaseBundle -Libraries $Libraries
            if (-not $KeepReceipt) {
                $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
                $document.sbom.sha256 = Get-Sha256Hex -Path $script:servedSbom
                $document.sbom.components = @($Libraries).Count + 2
                Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
            }
        }
        try {
            $servedSums = "$((Get-FileHash -LiteralPath $releaseBundle -Algorithm SHA256).Hash.ToLowerInvariant())  patches-$indexVersionHere.mpp`n"
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*SHA256SUMS.txt has no entry for patches-$indexVersionHere.cdx.json*" `
                'An index push went through with an SBOM SHA256SUMS does not list.'
            $servedSums += "$('1' * 64)  patches-$indexVersionHere.cdx.json`n"
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*SHA256SUMS.txt lists $('1' * 64) for patches-$indexVersionHere.cdx.json, but the hosted SBOM is*" `
                'An index push went through with an SBOM SHA256SUMS lists under another hash.'
            $servedSums = $null
            # Another SBOM of the same bundle: its serial number alone makes it another file.
            Use-ServedSbom @($cleanPurl) -KeepReceipt
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*The receipt says patches-$indexVersionHere.cdx.json hashes to*" `
                'An index push went through with a hosted SBOM the receipt does not name.'
            Use-ServedSbom @($gsonPurl)
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*high or critical*GHSA-4jrv-ppp4-jm57 (HIGH, CVE-2022-25647) in com.google.code.gson:gson 2.8.8*' `
                'An index push went through with a bundle carrying gson 2.8.8.'
            # The offline way through is explicit, and it says so.
            $offlineRun = $publishedRun.Clone()
            $offlineRun['SkipAdvisoryCheck'] = $true
            $said = Invoke-IndexPushCheck $offlineRun
            Assert-True ($said -like '*-SkipAdvisoryCheck: OSV was not asked about the libraries*') `
                "An index push with the advisory check skipped did not say so: $said"
            Use-ServedSbom @('pkg:maven/com.example/unknown@1.0')
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } '*could not be asked about pkg:maven/com.example/unknown@1.0*fails closed*' `
                'An index push went through without OSV answering.'
            $document = Get-Content -LiteralPath $releaseReceipt -Raw | ConvertFrom-Json
            $document.sbom.file = 'patches-9.9.8.cdx.json'
            Set-Content -LiteralPath $releaseReceipt -Encoding UTF8 -Value ($document | ConvertTo-Json -Depth 12)
            Assert-Throws { Invoke-IndexPushCheck $publishedRun } "*receipt names the SBOM patches-9.9.8.cdx.json; the one for $releaseVersionHere is patches-$releaseVersionHere.cdx.json*" `
                'An index push fetched an SBOM the receipt names under another version.'
        } finally {
            $servedSbom = $cleanServedSbom
            $servedSums = $null
            $osvAnswers = $osvRecorded
            [System.IO.File]::WriteAllBytes($releaseReceipt, $cleanReceiptBytes)
        }
        $said = Invoke-IndexPushCheck @{ SkipDescriptionTestCount = $true }
        Assert-True ($said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere on $indexRepository pins*") `
            "A run with no bundle did not read the floor at the tag the index's repository has: $said"
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value (
            $releaseIndexText -replace '(Morphe Manager )\d+(?:\.\d+)+( or newer)', '${1}1.20.0${2}')
        Assert-Throws { Invoke-IndexPushCheck @{ SkipDescriptionTestCount = $true } } '*Morphe Manager 1.20.0 or newer*' `
            'An index push from a clone without the release tag named a Manager floor the tag does not pin, and went through.'
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText

        # The index push through the hook, which decides what the hosted asset is held to. A bundle
        # built here is compared with it byte for byte when there's one for the version the index
        # publishes: the only bundle, whatever its name, or among several the one named for that
        # version. With none of those the check downloads the asset and holds it to everything else
        # a local build gets. The hook runs the check the pushed tree carries, strict, so the scripts
        # are copied beside the release files and the test results the description quotes are
        # written. The CLI, its JDK and GitHub are stand-ins, and the temporary folder is the
        # fixture's own, so a download left behind shows.
        $hookTemp = Join-Path $releaseRoot 'hook-temp'
        $releaseBuilds = Split-Path -Parent $releaseBundle
        $parkedBundle = Join-Path $releaseRoot "parked\patches-$releaseVersionHere.mpp"
        $parkedSbom = [System.IO.Path]::ChangeExtension($parkedBundle, '.cdx.json')
        $otherBuilds = @('patches-9.9.8.mpp', 'patches-9.9.9.mpp' | ForEach-Object { Join-Path $releaseBuilds $_ })
        $releaseDescription = [string]($releaseIndexText | ConvertFrom-Json).description
        function Invoke-IndexPushHook {
            $saved = @{}
            foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' -or $_.Name -in @('TMP', 'TEMP',
                    'HUSHFACEBOOK_DESKTOP_JAR', 'HUSHFACEBOOK_JAVA', 'HUSHFACEBOOK_SKIP_PRE_PUSH') })) {
                $saved[$variable.Name] = $variable.Value
                Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
            }
            $env:TMP = $hookTemp
            $env:TEMP = $hookTemp
            $env:HUSHFACEBOOK_DESKTOP_JAR = $stubJar
            $env:HUSHFACEBOOK_JAVA = $listJava
            try {
                . $publishedStandIns
                . $osvStandIn
                $global:LASTEXITCODE = 0
                $said = @(& $prePushScript -Root $releaseRepo -ChangedPaths @('patches-bundle.json') 3>&1 6>&1 |
                    ForEach-Object { "$_" }) -join "`n"
                if ($LASTEXITCODE -ne 0) { throw "The index push exited $LASTEXITCODE`: $said" }
                return $said
            } finally {
                foreach ($name in @('TMP', 'TEMP', 'HUSHFACEBOOK_DESKTOP_JAR', 'HUSHFACEBOOK_JAVA')) {
                    Remove-Item -LiteralPath ('Env:\' + $name) -ErrorAction SilentlyContinue
                }
                foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
            }
        }
        # Pass or fail, the downloads are gone afterwards and build/release holds what it held.
        function Assert-LeftAlone([string]$Case, [string[]]$Builds) {
            $left = @(Get-ChildItem -LiteralPath $hookTemp -File -Recurse | Where-Object { $_.Name -like '*.mpp' -or $_.Name -like '*.cdx.json' } |
                ForEach-Object { $_.Name })
            Assert-True ($left.Count -eq 0) "The $Case left its download behind: $($left -join ', ')"
            $now = @(Get-ChildItem -LiteralPath $releaseBuilds -File | ForEach-Object { $_.Name } | Sort-Object) -join ', '
            Assert-True ($now -eq (@($Builds | Sort-Object) -join ', ')) "The $Case changed patches/build/release: $now"
        }
        Copy-Item -LiteralPath $PSScriptRoot -Destination (Join-Path $releaseRepo 'scripts') -Recurse
        foreach ($results in @(
                @{ Folder = 'extensions/facebook/build/test-results/testDebugUnitTest'; Suite = 'RuntimeTest'; Quote = '\b(\d+) runtime tests passed\b' },
                @{ Folder = 'patches/build/test-results/test'; Suite = 'PatchTest'; Quote = '\b(\d+) patch tests passed\b' })) {
            $quoted = [regex]::Match($releaseDescription, $results.Quote).Groups[1].Value
            Assert-True ($quoted -match '^[1-9]\d*$') "The copied description quotes no $($results.Suite) count: $releaseDescription"
            $directory = Join-Path $releaseRepo $results.Folder
            New-Item -ItemType Directory -Path $directory -Force | Out-Null
            $cases = (1..[int]$quoted | ForEach-Object { "<testcase name=`"t$_`" classname=`"fixture.$($results.Suite)`"/>" }) -join ''
            Set-Content -LiteralPath (Join-Path $directory "TEST-fixture.$($results.Suite).xml") -Encoding UTF8 -Value (
                "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$($results.Suite)`" tests=`"$quoted`" " +
                "skipped=`"0`" failures=`"0`" errors=`"0`">$cases</testsuite>")
        }
        New-Item -ItemType Directory -Path $hookTemp, (Split-Path -Parent $parkedBundle) -Force | Out-Null
        try {
            # No bundle built here, nor the SBOM beside it. The hosted one passes every check a local
            # build gets, and the check says the byte-for-byte comparison had nothing to compare with.
            Move-Item -LiteralPath $releaseBundle -Destination $parkedBundle
            Move-Item -LiteralPath $releaseSbom -Destination $parkedSbom
            $servedBundle = $parkedBundle
            $said = Invoke-IndexPushHook
            foreach ($line in @('no local bundle here, so the hosted asset is downloaded and checked on its own',
                    "no bundle built here is compared with the hosted patches-$indexVersionHere.mpp byte for byte",
                    "published bundle is pinned to v$indexVersionHere ($releaseCommit)",
                    'the published bundle carries classes.dex',
                    "the published bundle carries $($releaseNames.Count) patches, as described",
                    "the bundle stamps patcher $($releaseToolchain.PatcherVersion), as the catalog pins",
                    "the receipt proves $($releaseNames.Count) patches on",
                    "the hosted patches-$indexVersionHere.cdx.json is the SBOM the receipt names",
                    "OSV has no advisory for the libraries patches-$indexVersionHere.cdx.json lists")) {
                Assert-True ($said -like "*$line*") "An index push with no bundle built here left the hosted one unchecked ($line): $said"
            }
            Assert-LeftAlone 'hosted check' @()
            # And a hosted file SHA256SUMS doesn't list, or one without the classes.dex Manager
            # loads, is refused, with nothing left behind.
            $servedSums = "$('0' * 64)  patches-$indexVersionHere.mpp`n"
            Assert-Throws { Invoke-IndexPushHook } "*SHA256SUMS.txt lists $('0' * 64) for patches-$indexVersionHere.mpp*" `
                'An index push went through with a hosted bundle SHA256SUMS does not list.'
            Assert-LeftAlone 'refused hosted check' @()
            $servedSums = $null
            $servedBundle = Join-Path $releaseRoot "dexless\patches-$releaseVersionHere.mpp"
            New-Item -ItemType Directory -Path (Split-Path -Parent $servedBundle) -Force | Out-Null
            New-TestBundleArchive -Path $servedBundle -Entries ([ordered]@{
                'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($releaseSeconds * 1000)`n" +
                    "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
                'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
            Assert-Throws { Invoke-IndexPushHook } '*The published bundle has no classes.dex*' `
                'An index push went through with a hosted bundle Manager would load no patches from.'
            Assert-LeftAlone 'refused hosted check' @()

            # Several bundles. The one named for the index version is compared byte for byte, and
            # the other is left alone; with neither named for it, the hosted one is checked on its own.
            Move-Item -LiteralPath $parkedBundle -Destination $releaseBundle
            Move-Item -LiteralPath $parkedSbom -Destination $releaseSbom
            $servedBundle = $releaseBundle
            New-TestBundleArchive -Path $otherBuilds[0] -Entries ([ordered]@{ 'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.8`n`n" })
            $said = Invoke-IndexPushHook
            Assert-True ($said -like "*found 2 bundles, so the hosted asset is compared with patches-$indexVersionHere.mpp, built here*" -and
                $said -like "*the hosted patches-$indexVersionHere.mpp matches the bundle built here byte for byte*" -and
                $said -like "*the receipt proves $($releaseNames.Count) patches on*") `
                "An index push with several bundles did not compare the hosted one with the bundle for its version: $said"
            Assert-LeftAlone 'comparison with the bundle built here' @("patches-$releaseVersionHere.mpp", "patches-$releaseVersionHere.cdx.json",
                'patches-9.9.8.mpp')
            Move-Item -LiteralPath $releaseBundle -Destination $parkedBundle
            Move-Item -LiteralPath $releaseSbom -Destination $parkedSbom
            $servedBundle = $parkedBundle
            New-TestBundleArchive -Path $otherBuilds[1] -Entries ([ordered]@{ 'META-INF/MANIFEST.MF' = "Manifest-Version: 1.0`nVersion: 9.9.9`n`n" })
            $said = Invoke-IndexPushHook
            Assert-True ($said -like "*found 2 bundles and none is patches-$indexVersionHere.mpp, so the hosted asset is downloaded and checked on its own*" -and
                $said -like "*no bundle built here is compared with the hosted patches-$indexVersionHere.mpp byte for byte*" -and
                $said -like "*the receipt proves $($releaseNames.Count) patches on*") `
                "An index push with several bundles, none for its version, left the hosted one unchecked: $said"
            Assert-LeftAlone 'hosted check' @('patches-9.9.8.mpp', 'patches-9.9.9.mpp')
        } finally {
            $servedBundle = $releaseBundle
            $servedSums = $null
            Remove-Item -LiteralPath $otherBuilds -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path -LiteralPath $releaseBundle) -and (Test-Path -LiteralPath $parkedBundle)) {
                Move-Item -LiteralPath $parkedBundle -Destination $releaseBundle
            }
            if (-not (Test-Path -LiteralPath $releaseSbom) -and (Test-Path -LiteralPath $parkedSbom)) {
                Move-Item -LiteralPath $parkedSbom -Destination $releaseSbom
            }
            foreach ($folder in @('scripts', 'extensions', 'patches/build/test-results')) {
                Remove-Item -LiteralPath (Join-Path $releaseRepo $folder) -Recurse -Force -ErrorAction SilentlyContinue
            }
        }

        # A release re-cut on GitHub onto another commit, here one with no catalog, while the clone
        # keeps its tag on the first: the floor is read where the published tag points, and a
        # published asset run refuses a floor it can't read rather than noting it.
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('rm', '--cached', '--quiet', '--', 'gradle/libs.versions.toml') | Out-Null
        $recutTree = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('write-tree') | Select-Object -First 1)".Trim()
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
        $recut = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit-tree', $recutTree, '-p', $releaseCommit,
            '-m', 're-cut') | Select-Object -First 1)".Trim()
        $recutSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct', $recut) |
            Select-Object -First 1)".Trim()
        $servedBundle = Join-Path $releaseRoot "recut\patches-$releaseVersionHere.mpp"
        New-Item -ItemType Directory -Path (Split-Path -Parent $servedBundle) -Force | Out-Null
        New-TestBundleArchive -Path $servedBundle -Entries ([ordered]@{
            'META-INF/MANIFEST.MF' = ("Manifest-Version: 1.0`nVersion: $releaseVersionHere`nTimestamp: $($recutSeconds * 1000)`n" +
                "Patcher-Version: $($releaseToolchain.PatcherVersion)`n`n")
            'classes.dex' = "dex`n035" + ('patches' * 8)
            'extensions/facebook.mpe' = "dex`n035" + ('payload' * 8) })
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('push', '--quiet', '--force', $publishedRepo,
            "${recut}:refs/tags/v$indexVersionHere") | Out-Null
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$indexVersionHere", $releaseCommit) | Out-Null
        $recutRun = $publishedRun.Clone()
        $recutRun['ArtifactPath'] = $servedBundle
        Assert-Throws { Invoke-IndexPushCheck $recutRun } `
            "*published release's Manager floor couldn't be read*tag v$indexVersionHere has no version catalog*" `
            'An index push was checked against the clone''s tag, or went through with its release floor unread.'
    } finally {
        Set-Content -LiteralPath $releaseIndexPath -Encoding UTF8 -NoNewline -Value $releaseIndexText
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '--force', "v$indexVersionHere", $releaseCommit) | Out-Null
        Invoke-FixtureGit -Root $releaseRepo -Arguments @('config', '--unset', "url.$publishedRepo.insteadOf") | Out-Null
    }

    # A release cut before the receipt named an SBOM, which is where the checkout that cut 0.1.1
    # stands until the next release: its commit's receipt script writes schema 1, and its receipt
    # names no SBOM. An ordinary push holds that receipt to schema 1 and says so, and a schema 2
    # receipt for the same commit is refused. The commit sits beside the branch, made with
    # commit-tree, so nothing after this sees it.
    $schemaOneScript = Join-Path $releaseRoot 'schema-one-release-receipt.ps1'
    Set-Content -LiteralPath $schemaOneScript -Encoding ASCII -Value @('function Get-ReleaseReceiptSchemaVersion {', '    <#',
        '    .SYNOPSIS', '        Bumped when the shape changes.', '    #>', '    return 1', '}')
    $schemaOneBlob = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('hash-object', '-w', $schemaOneScript) | Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('update-index', '--add', '--cacheinfo',
        "100644,$schemaOneBlob,scripts/release-receipt.ps1") | Out-Null
    $schemaOneTree = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('write-tree') | Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('reset', '--quiet') | Out-Null
    $schemaOneCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit-tree', $schemaOneTree, '-p', $releaseCommit,
        '-m', 'cut before the SBOM') | Select-Object -First 1)".Trim()
    $schemaOneSeconds = [long]"$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('log', '-1', '--format=%ct', $schemaOneCommit) |
        Select-Object -First 1)".Trim()
    $receiptBytes = [System.IO.File]::ReadAllBytes($releaseReceipt)
    try {
        Save-ReleaseReceipt -Builds $releaseTarget.PackageVersions -Commit $schemaOneCommit -Seconds $schemaOneSeconds -Schema 1
        $said = Invoke-ReleaseCheck
        Assert-True ($said -like "*the receipt is held to schema 1, which its own commit $($schemaOneCommit.Substring(0, 8)) wrote*" -and
            $said -like "*the receipt proves $($releaseNames.Count) patches on*from commit $($schemaOneCommit.Substring(0, 8))*") `
            "A receipt cut before the SBOM was not read as its own commit wrote it: $said"
        Save-ReleaseReceipt -Builds $releaseTarget.PackageVersions -Commit $schemaOneCommit -Seconds $schemaOneSeconds
        Assert-Throws { Invoke-ReleaseCheck } '*schema version 2; its release is read at version 1*' `
            'A schema 2 receipt was accepted for a commit whose builder wrote schema 1.'
    } finally {
        [System.IO.File]::WriteAllBytes($releaseReceipt, $receiptBytes)
    }

    # A lag window: the source has moved on to the next version and pins the next Manager floor,
    # the clone already has that version's tag, and the index still describes the release before
    # it. The description is held to the floor the index version's tag pins, not the source
    # version's; the two tags pin different floors here, so reading the wrong one refuses the tree.
    # Then the same window in a clone without the index version's tag, which says the floor
    # wasn't checked.
    function Edit-ReleaseFile([string]$Name, [scriptblock]$Edit) {
        $path = Join-Path $releaseRepo $Name
        $text = [System.IO.File]::ReadAllText($path)
        $edited = & $Edit $text
        if ($edited -ceq $text) { throw "The lag window left $Name as it was, so the case below would prove nothing." }
        [System.IO.File]::WriteAllText($path, $edited, (New-Object System.Text.UTF8Encoding($false)))
    }
    $releasedAs = [version]$releaseVersionHere
    $laterVersion = "$($releasedAs.Major).$($releasedAs.Minor).$($releasedAs.Build + 1)"
    $releasedFloor = [version]$releaseFloor
    $laterFloor = "$($releasedFloor.Major).$($releasedFloor.Minor + 1).0"
    Edit-ReleaseFile 'gradle.properties' { param($text) $text -replace '(?m)^(\s*version\s*=\s*)\S+', "`${1}$laterVersion" }
    Edit-ReleaseFile 'patches-list.json' { param($text) $text.Replace('"v' + $releaseVersionHere + '"', '"v' + $laterVersion + '"') }
    Edit-ReleaseFile 'CHANGELOG.md' {
        param($text) ([regex]'(?m)^## ').Replace($text,
            "## $laterVersion (2026-09-26)`n`n* **Facebook:** Needs Morphe Manager $laterFloor or newer.`n`n## ", 1)
    }
    Edit-ReleaseFile 'gradle/libs.versions.toml' {
        param($text) $text -replace '(?m)^(\s*manager-floor\s*=\s*")[^"]+', "`${1}$laterFloor"
    }
    Edit-ReleaseFile 'README.md' { param($text) $text.Replace($releaseFloor, $laterFloor) }
    Edit-ReleaseFile '.github/ISSUE_TEMPLATE/bug_report.yml' {
        param($text) $text.Replace("Morphe Manager $releaseFloor", "Morphe Manager $laterFloor")
    }
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('add', '--', 'gradle.properties', 'patches-list.json', 'CHANGELOG.md',
        'gradle/libs.versions.toml', 'README.md', '.github/ISSUE_TEMPLATE/bug_report.yml') | Out-Null
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('commit', '-m', 'next release', '--quiet') | Out-Null
    $laterCommit = "$(Invoke-FixtureGit -Root $releaseRepo -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', "v$laterVersion", $laterCommit) | Out-Null
    $said = Invoke-ReleaseCheck
    Assert-True ($said -like "*source $laterVersion is being prepared while the working index remains on $indexVersionHere*" -and
        $said -like "*the index asks for Morphe Manager $releaseFloor or newer, as tag v$indexVersionHere pins*") `
        "In a lag window the index was not held to the floor its own version's tag pins: $said"
    Invoke-FixtureGit -Root $releaseRepo -Arguments @('tag', '-d', "v$indexVersionHere") | Out-Null
    $said = Invoke-ReleaseCheck
    Assert-True ($said -like "*tag v$indexVersionHere isn't in this clone, so the Manager floor the index names wasn't checked*") `
        "A lag window without the index version's tag did not say the floor went unchecked: $said"
} finally {
    Remove-Item -LiteralPath $releaseRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release root contracts passed'

# --- release bundle path ---------------------------------------------------------------------
#
# Every release step reads the bundle from patches/build/release. The plugin's buildAndroid
# finishes the bundle inside the jar task's own output, so a task run after it that reruns
# :patches:jar put the plain jar back under the same name in build/libs: v0.43.0 shipped with no
# classes.dex that way. buildAndroid copies the finished bundle to build/release, where nothing
# else writes, and the Gradle file and this helper have to agree on that directory.

$bundlePathRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfacebook-bundle-path-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $bundlePathRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $bundlePathRoot 'gradle.properties') -Value 'version = 9.9.9' -Encoding ASCII
    $expectedRelease = Join-Path $bundlePathRoot 'patches/build/release/patches-9.9.9.mpp'
    Assert-True ((Get-ReleaseBundlePath -Root $bundlePathRoot) -eq $expectedRelease) `
        'The release bundle path did not follow gradle.properties into patches/build/release.'
    Assert-True ((Get-ReleaseBundlePath -Root $bundlePathRoot -Version '1.2.3') -eq
        (Join-Path $bundlePathRoot 'patches/build/release/patches-1.2.3.mpp')) `
        'An explicit version was not used for the release bundle path.'
} finally {
    Remove-Item -LiteralPath $bundlePathRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$gradleFile = Get-Content -LiteralPath (Join-Path $Root 'patches/build.gradle.kts') -Raw
Assert-True ($gradleFile -match 'buildDirectory\.dir\("release"\)' -and
    $gradleFile -match 'buildDirectory\.file\("release/\$releaseBundleName"\)' -and
    $gradleFile -match 'buildDirectory\.file\("release/bundle\.sha256"\)') `
    'patches/build.gradle.kts no longer writes and verifies the bundle in build/release, where the scripts read it.'
# The receipt builder looks for the SBOM beside the bundle, under the bundle's name with .cdx.json
# for its extension, and the release check fetches it from the release under that name.
Assert-True ($gradleFile -match 'val releaseBundleName = "patches-\$\{project\.version\}\.mpp"' -and
    $gradleFile -match 'val releaseSbomName = "patches-\$\{project\.version\}\.cdx\.json"' -and
    $gradleFile -match 'output\.set\(layout\.buildDirectory\.file\("release/\$releaseSbomName"\)\)' -and
    $gradleFile -match 'finalizedBy\(releaseSbom\)') `
    'patches/build.gradle.kts no longer writes the SBOM beside the bundle in build/release after each buildAndroid.'

# Code only: a comment may say where the bundle used to be read from.
$libsReaders = New-Object System.Collections.Generic.List[string]
foreach ($script in @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File)) {
    if ($script.Name -eq 'test-script-contracts.ps1') { continue }
    $inBlockComment = $false
    $number = 0
    foreach ($line in @(Get-Content -LiteralPath $script.FullName)) {
        $number++
        $trimmed = $line.Trim()
        if ($inBlockComment) {
            if ($trimmed -like '*#>*') { $inBlockComment = $false }
            continue
        }
        if ($trimmed.StartsWith('<#')) {
            if ($trimmed -notlike '*#>*') { $inBlockComment = $true }
            continue
        }
        if ($trimmed.StartsWith('#')) { continue }
        if ($trimmed -match 'build[\\/]+libs') { $libsReaders.Add("$($script.Name):$number") }
    }
}
Assert-True ($libsReaders.Count -eq 0) `
    ("These script lines read patches/build/libs, which :patches:jar rewrites with the plain jar: " +
        ($libsReaders -join ', '))

Write-Host '[scripts] release bundle path contracts passed'

# --- tracked files name no machine -----------------------------------------------------------
#
# No tracked file names the working-notes folder .gitignore keeps out, the backup folders on the
# maintainer's machine that share its name, or a phone's adb serial. Four fixture tests fell back
# to one of those folders, which skipped quietly on every other machine and published this one's
# layout, and five scripts carried the test phone's serial. .gitignore is the one exception: it
# has to name what it keeps out. Find-MachineNames in common.ps1 holds both patterns, built from
# parts so neither file can match itself, and the pre-push hook runs the same scan on every
# commit a push publishes, whatever the push touches; the routing section above plants hits for it.

$machineNames = @(Find-MachineNames -Root $Root)
Assert-True ($machineNames.Count -eq 0) `
    ("Tracked files name the maintainer's machine or phone: " + ($machineNames -join '; '))
# A search that does not run must not read as a clean tree.
Assert-Throws { Find-MachineNames -Root (Join-Path ([System.IO.Path]::GetTempPath()) ('hushfacebook-absent-' + [guid]::NewGuid().ToString('N'))) } `
    '*could not search*' 'A machine-name scan that could not run read as a clean tree.'

Write-Host '[scripts] tracked-file machine name contracts passed'

$global:LASTEXITCODE = 0
Write-Host '[scripts] report, target, Java and guarded replacement contracts passed'
$marketingAssets = Join-Path $Root 'scripts/test-marketing-assets.ps1'
if (-not (Test-Path -LiteralPath $marketingAssets -PathType Leaf)) {
    throw 'scripts/test-marketing-assets.ps1 is missing. Marketing changes need a repeatable asset check.'
}
& $marketingAssets -Root $Root
if ($LASTEXITCODE -ne 0) {
    throw 'The marketing asset checks did not pass.'
}
