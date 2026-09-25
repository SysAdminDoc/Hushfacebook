<#
.SYNOPSIS
    Checks a push before it leaves the machine.

.DESCRIPTION
    This project builds nothing on GitHub, so a push is the last point at which anything can
    be checked. Its only reported failure so far was a source index that advertised a bundle
    the release did not carry, which the release check catches when it is actually run.

    Called by .git/hooks/pre-push with the remote name and URL, reading the pushed refs from
    standard input the way git supplies them. Run scripts/install-hooks.ps1 once to wire it up.

    Only what changed is checked: runtime tests when extension or patch sources move, and the
    release facts when a published file moves. The one check every push gets is a scan of every
    commit it publishes for tracked files that name the maintainer's machine or a phone. Set
    HUSHFACEBOOK_SKIP_PRE_PUSH=1 to push anyway.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$RemoteName,
    [Parameter(Position = 1)][string]$RemoteUrl,
    [string]$Root,
    [string[]]$ChangedPaths,
    [string]$PushedRefs,
    # How long a push waits for another push from this checkout to finish with the gate worktree.
    [int]$GateLockTimeoutSeconds = 3600
)

$ErrorActionPreference = 'Stop'

# Not a parameter default. Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File, and any [CmdletBinding()] or [Parameter(...)]
# attribute makes a script advanced, so the default threw and the hook failed before it checked
# anything. The hook prefers pwsh, which does not have this, and falls back to Windows PowerShell
# wherever pwsh is off the PATH: a git hook runs with git's environment, so that is the ordinary
# case rather than the rare one.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
# Read before anything that can fail to load. The switch is the way through the hook's own messages
# offer, and common.ps1 is the working tree's copy: mid-rebase it can hold conflict markers, or a
# half-made edit, and loading it first stopped the push with a parse error while the switch was set.
if ($env:HUSHFACEBOOK_SKIP_PRE_PUSH -eq '1') {
    Write-Host '[pre-push] skipped by HUSHFACEBOOK_SKIP_PRE_PUSH'
    exit 0
}
. (Join-Path $PSScriptRoot 'common.ps1')

# A hook runs with git's own environment. User environment variables set after the shell
# launched, or set in the user scope only, may be absent. Import the four this script and
# its suites need from the registry so a gate worktree can find the desktop CLI, the
# fixture folder, the build governor and the device serial.
foreach ($envName in @('HUSHFACEBOOK_DESKTOP_JAR', 'HUSHFACEBOOK_FIXTURE_DIR',
        'HUSHFACEBOOK_BUILD_WRAPPER', 'HUSHFACEBOOK_DEVICE_SERIAL')) {
    if (-not (Test-Path "Env:\$envName")) {
        $regValue = [Environment]::GetEnvironmentVariable($envName, [EnvironmentVariableTarget]::User)
        if ($regValue) { Set-Item -LiteralPath "Env:\$envName" -Value $regValue }
    }
}

$zeroObject = '0' * 40
# The tip of each pushed ref, peeled, filled in by Get-PushedPaths. The build gate builds each of
# these, and never whatever else the working tree holds.
$script:pushedCommits = New-Object System.Collections.Generic.List[string]
# Every commit the push publishes, also filled in by Get-PushedPaths, for the machine-name scan.
$script:publishedCommits = New-Object System.Collections.Generic.List[string]
# What the remote advertises, read once by Get-RemoteHeld.
$script:remoteHeld = $null

function Write-Step {
    param([string]$Message)
    Write-Host "[pre-push] $Message"
}

function Get-PushedPaths {
    <#
        Git writes "<local ref> <local sha> <remote ref> <remote sha>" per ref on stdin. A remote
        sha of all zeroes means the branch is new there. A new branch is checked conservatively
        from its complete resulting tree, so a stale local tracking ref cannot hide a code path.

        Read from the console rather than $input: a script started with -File binds stdin to its
        parameters, so piping into it fails to bind and leaves $input empty, which made the hook
        report that a push changed nothing.
    #>
    param([string]$Text)

    $paths = New-Object System.Collections.Generic.HashSet[string]
    foreach ($line in ($Text -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -lt 4) { continue }
        $localSha = $parts[1]
        $remoteSha = $parts[3]
        if ($localSha -eq $zeroObject) { continue }

        if ($remoteSha -eq $zeroObject) {
            # A new tag of an already hosted branch adds no files. In particular it does not
            # rewrite the source index, which must keep naming the previous working bundle
            # until the new release asset exists. Trust a live advertisement, not tracking refs.
            if ($parts[2] -like 'refs/tags/*' -and $RemoteUrl) {
                $target = Invoke-GitQuietly @('rev-parse', '--verify', "$localSha^{commit}")
                if ($LASTEXITCODE -ne 0) { throw "Could not resolve tag target $localSha." }
                $advertised = @(Invoke-GitQuietly @('ls-remote', '--heads', $RemoteUrl))
                if ($LASTEXITCODE -ne 0) { throw 'Could not read remote branches to verify the tag target.' }
                $targetPattern = '^' + [regex]::Escape(([string]$target).Trim()) + '\s+refs/heads/'
                if (@($advertised | Where-Object { $_ -match $targetPattern }).Count -gt 0) {
                    Write-Step 'new tag names an advertised remote branch commit; no file changes'
                    continue
                }
            }
            # This intentionally runs the relevant gate for any matching path in the tree, even
            # when the branch changed only documentation. A first push is rare, and a complete
            # tree cannot be made incomplete by a deleted or force-updated remote-tracking ref.
            $range = "$localSha complete branch tree"
            $names = Invoke-GitQuietly @('ls-tree', '-r', '--name-only', $localSha)
        } else {
            $range = "$remoteSha..$localSha"
            $names = Invoke-GitQuietly @('diff', '--name-only', $remoteSha, $localSha)
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Could not read what $range changes. Fetch the remote and try again."
        }
        foreach ($name in @($names)) {
            if (-not [string]::IsNullOrWhiteSpace($name)) { [void]$paths.Add($name.Trim()) }
        }
        $commit = Invoke-GitQuietly @('rev-parse', '--verify', "$localSha^{commit}")
        if ($LASTEXITCODE -eq 0 -and $commit -and -not $script:pushedCommits.Contains(([string]$commit).Trim())) {
            $script:pushedCommits.Add(([string]$commit).Trim())
        }
        # Every commit the ref publishes, not only its tip: a file one commit adds and the next
        # deletes still goes out in the first. A new branch publishes what the remote doesn't
        # hold, as the remote itself says: a remote-tracking ref can outlive its branch there,
        # deleted and never pruned here, and a commit only it held went out unscanned. The
        # remote's commits go on the command line two hundred at a time, a commit is published
        # when no batch reaches it, and the ones this clone never fetched are skipped. Not on
        # standard input: Windows PowerShell puts a byte order mark in front of the first line it
        # pipes to a program, so that line named no commit and nothing it held was left out.
        $published = if ($remoteSha -eq $zeroObject) {
            $held = @(Get-RemoteHeld)
            $kept = $null
            for ($i = 0; $i -eq 0 -or $i -lt $held.Count; $i += 200) {
                $batch = @($held | Select-Object -Skip $i -First 200)
                $reached = @(Invoke-GitQuietly (@('rev-list', '--ignore-missing', $localSha, '--not') + $batch))
                if ($LASTEXITCODE -ne 0) { break }
                if ($null -eq $kept) {
                    $kept = $reached
                } else {
                    $inBatch = New-Object 'System.Collections.Generic.HashSet[string]' (, [string[]]$reached)
                    $kept = @($kept | Where-Object { $inBatch.Contains($_) })
                }
            }
            $kept
        } else {
            Invoke-GitQuietly @('rev-list', "$remoteSha..$localSha")
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Could not list the commits a push of $($parts[0]) publishes. Fetch the remote and try again."
        }
        foreach ($sha in @($published)) {
            $sha = ([string]$sha).Trim()
            if ($sha -and -not $script:publishedCommits.Contains($sha)) { $script:publishedCommits.Add($sha) }
        }
    }
    return $paths
}

function Invoke-GitQuietly {
    <#
        git in this repository with its standard error dropped, returning standard output and
        leaving $LASTEXITCODE for the caller to read. Windows PowerShell 5.1 turns a native
        command's standard error into a terminating error under Stop even when it is redirected,
        so a warning, or the "fatal:" a missing object prints, stopped the hook with a
        NativeCommandError before the caller could say what went wrong.
    #>
    param([string[]]$Arguments)
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & git @Arguments 2>$null
    } finally {
        $ErrorActionPreference = $preference
    }
}

function Get-RemoteHeld {
    <#
        The commits the remote advertises, its branches' and its tags', read once per push. What
        a new branch publishes is what none of them reaches. A run by hand that names no remote
        gets none, so every commit the branch reaches is scanned. A remote that can't be read
        stops the push, rather than letting a guess decide what goes out unscanned.
    #>
    if ($null -ne $script:remoteHeld) { return $script:remoteHeld }
    $held = @()
    if ($RemoteUrl) {
        $advertised = @(Invoke-GitQuietly @('ls-remote', '--heads', '--tags', $RemoteUrl))
        if ($LASTEXITCODE -ne 0) {
            throw ("Could not read what $RemoteUrl holds, so the commits a new branch publishes can't " +
                'be told from the ones already there. Check the connection and push again.')
        }
        $held = @($advertised | ForEach-Object { if ("$_" -match '^([0-9a-f]{40})\s') { $Matches[1] } } |
            Select-Object -Unique)
    }
    $script:remoteHeld = $held
    return $held
}

function Invoke-WithoutGitEnvironment {
    <#
        Runs $Action with every GIT_* variable removed, and puts them back after. Git exports
        GIT_DIR and its relatives to a hook, and git -C does not override them, so a worktree
        command run with them set would act on this repository's own working tree rather than the
        one it names. A build started with them set would read this checkout's git state too.
    #>
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name)
    }
    try {
        & $Action
    } finally {
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

function Invoke-HookGit {
    <#
        git without the hook's own GIT_* environment, returning standard output and throwing on
        failure. Windows PowerShell 5.1 turns a native command's standard error into a terminating
        error under Stop, so that preference is relaxed here.
    #>
    param([string[]]$Arguments)
    Invoke-WithoutGitEnvironment {
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $errors = New-Object System.Collections.Generic.List[string]
            $output = @(& git @Arguments 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { $errors.Add($_.ToString()) } else { $_ }
            })
            if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed: $($errors -join ' ')" }
            return $output
        } finally {
            $ErrorActionPreference = $preference
        }
    }
}

function Get-GateKey {
    # Names this checkout's gate worktree and lock, so two checkouts never share either.
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes([IO.Path]::GetFullPath($Root).ToLowerInvariant()))
    } finally {
        $hasher.Dispose()
    }
    return -join ($digest[0..5] | ForEach-Object { $_.ToString('x2') })
}

function Enter-GateLock {
    <#
        One push at a time through this checkout's gate worktree. Two pushes from one checkout,
        two agents sharing a tree for instance, would otherwise share it, and the second one's
        checkout could land while the first one's build was still reading the tree, so the first
        verdict would describe the second commit. Returns the held mutex; release it when done.
    #>
    $mutex = New-Object System.Threading.Mutex($false, "Local\hushfacebook-pre-push-$(Get-GateKey)")
    $owned = $false
    try {
        $owned = $mutex.WaitOne([TimeSpan]::FromSeconds($GateLockTimeoutSeconds))
    } catch [System.Threading.AbandonedMutexException] {
        # The last holder ended without letting go. The worktree is reset before every build.
        $owned = $true
    }
    if (-not $owned) {
        $mutex.Dispose()
        throw ("Another push from this checkout held the gate worktree for $GateLockTimeoutSeconds " +
            'seconds. Let it finish and push again.')
    }
    return $mutex
}

function Get-GateWorktree {
    <#
        A clean worktree of $Commit in the temp directory, reused between pushes so its build
        folder stays warm. Hold Enter-GateLock while using it.
    #>
    param([Parameter(Mandatory = $true)][string]$Commit)
    $tree = Join-Path ([IO.Path]::GetTempPath()) "hushfacebook-pre-push-$(Get-GateKey)"
    if (Test-Path -LiteralPath (Join-Path $tree '.git')) {
        Invoke-HookGit @('-C', $tree, 'checkout', '--detach', '--force', '--quiet', $Commit) | Out-Null
        Invoke-HookGit @('-C', $tree, 'clean', '-fdxq', '-e', 'build', '-e', '.gradle', '-e', 'local.properties') | Out-Null
    } else {
        if (Test-Path -LiteralPath $tree) { Remove-Item -LiteralPath $tree -Recurse -Force }
        Invoke-HookGit @('-C', $Root, 'worktree', 'prune') | Out-Null
        Invoke-HookGit @('-C', $Root, 'worktree', 'add', '--detach', '--quiet', $tree, $Commit) | Out-Null
    }
    $properties = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        Copy-Item -LiteralPath $properties -Destination (Join-Path $tree 'local.properties') -Force
    }
    $at = ([string](Invoke-HookGit @('-C', $tree, 'rev-parse', 'HEAD') | Select-Object -Last 1)).Trim()
    if ($at -ne $Commit) { throw "The gate worktree $tree is at $at, not $Commit." }
    $left = @(Invoke-HookGit @('-C', $tree, 'status', '--porcelain'))
    if ($left.Count -gt 0) { throw "The gate worktree $tree is not clean: $($left -join '; ')" }
    return $tree
}

Push-Location $Root
try {
    if ($PSBoundParameters.ContainsKey('ChangedPaths')) {
        $paths = New-Object System.Collections.Generic.HashSet[string]
        foreach ($name in @($ChangedPaths)) { [void]$paths.Add($name) }
    } else {
        $refs = ''
        if ($PSBoundParameters.ContainsKey('PushedRefs')) {
            $refs = $PushedRefs
        } elseif (-not [Console]::IsInputRedirected) {
            throw 'No pushed refs on standard input. Run this from the pre-push hook, or pass -ChangedPaths.'
        } else {
            $refs = [Console]::In.ReadToEnd()
        }
        $paths = Get-PushedPaths -Text $refs
    }

    # Every push, whatever it moves: no tracked file may name the maintainer's working-notes folder
    # or a phone's serial. The contract tests hold the working tree to that, but they run for
    # script changes, and the files that once fell back to that folder were tests under patches/
    # and extensions/, which route to the Gradle gates alone. Every commit the push publishes is
    # read straight out of the object store, not only each ref's tip, since a serial one commit
    # adds and the next removes still goes out in the first. That needs no worktree and takes a
    # moment. It runs before the changed paths decide anything: commits that cancel each other out
    # change no file and still go out. A push that publishes no commit, a deletion or a tag of a
    # commit the remote has, has nothing to scan. A run by hand reads the tracked files of this
    # working tree.
    if ($PSBoundParameters.ContainsKey('ChangedPaths')) {
        $scanned = @()
        $scope = 'the working tree'
    } else {
        $scanned = @(@($script:pushedCommits) + @($script:publishedCommits) | Select-Object -Unique)
        $scope = "the $($scanned.Count) commit(s) this push publishes"
    }
    $named = @()
    if ($PSBoundParameters.ContainsKey('ChangedPaths') -or $scanned.Count -gt 0) {
        $named = @(Find-MachineNames -Root $Root -Commit $scanned)
    }
    if ($named.Count -gt 0) {
        # Each hit is a line git grep printed, <commit>:<path>:<line>:<text> when read out of a
        # commit. A hit in a binary file prints that file's bytes, and a carriage return among them
        # splits the line into pieces that don't open with a commit, so the commits are taken only
        # from lines that do, and control characters are shown as ?.
        $hits = @($named | Where-Object { ([string]$_) -match '^[0-9a-f]{40}:' })
        if ($hits.Count -eq 0) { $hits = $named }
        $shown = @($hits | Select-Object -First 5 | ForEach-Object { ([string]$_) -replace '\p{Cc}', '?' }) -join '; '
        if ($scanned.Count -eq 0) {
            throw ("Tracked files in the working tree name the maintainer's machine or phone, and a push " +
                "would publish them: $shown")
        }
        $carriers = @($hits | ForEach-Object { ([string]$_).Split(':')[0] } | Select-Object -Unique)
        throw ("Tracked files in commit $($carriers -join ', ') name the maintainer's machine or phone, " +
            'and this push would publish them. A commit on top leaves them in the history it publishes, ' +
            "so rewrite the commit that added them: $shown")
    }
    if ($PSBoundParameters.ContainsKey('ChangedPaths') -or $scanned.Count -gt 0) {
        Write-Step "no tracked file in $scope names a machine or phone"
    } else {
        Write-Step 'the push publishes no commit, so there is nothing to scan'
    }

    if ($paths.Count -eq 0) {
        Write-Step 'nothing to check'
        exit 0
    }

    $touchesCode = @($paths | Where-Object {
        $_ -like 'extensions/*' -or $_ -like 'patches/*' -or
        # The pins and the reviewed checksums. Two Gradle tasks hold the Bouncy Castle graphs to
        # the reviewed release, and they only run on the way to a test task; a push that moved
        # the pin alone ran the release facts check, which knows nothing about them.
        $_ -eq 'gradle/libs.versions.toml' -or $_ -eq 'gradle/verification-metadata.xml' -or
        $_ -eq 'settings.gradle.kts' -or $_ -eq 'build.gradle.kts'
    }).Count -gt 0
    $touchesScripts = @($paths | Where-Object { $_ -like 'scripts/*' }).Count -gt 0
    # The contract tests read two files outside scripts/ that nothing else checks: the catalog,
    # held to the builds, signers and dependencies the release scripts expect, and the Gradle file
    # that writes the release bundle where common.ps1 reads it. A push that moved only one of them
    # never ran the tests, and the break surfaced on the next unrelated script push instead.
    # They also end with the marketing asset check, which holds the artwork's sizes and alpha and
    # the README's hero and links. A push of only artwork or only the README ran no check of them.
    $touchesContracts = $touchesScripts -or @($paths | Where-Object {
        $_ -eq 'patches-list.json' -or $_ -eq 'patches/build.gradle.kts' -or
        $_ -like 'assets/*' -or $_ -like 'concepts/marketing/*' -or $_ -eq 'README.md'
    }).Count -gt 0
    $injectedRegisterVerifierPaths = @(
        'scripts/BadDexFixture.java',
        'scripts/DexDiff.java',
        'scripts/injected-mutation-contracts.txt',
        'scripts/injected-register-contracts.ps1',
        'scripts/injected-register-removal-allowlist.txt',
        'scripts/script-wiring.ps1',
        'scripts/test-injected-registers.ps1',
        'scripts/verify-all-patches.ps1',
        'scripts/verify-injected-registers.ps1'
    )
    $touchesInjectedRegisterVerifier = @($paths | Where-Object {
        $_ -in $injectedRegisterVerifierPaths
    }).Count -gt 0
    $resourceTableCheckPaths = @(
        'scripts/ResourceTableCheck.java',
        'scripts/test-resource-table-check.ps1',
        'scripts/verify-all-patches.ps1'
    )
    $touchesResourceTableCheck = @($paths | Where-Object {
        $_ -in $resourceTableCheckPaths
    }).Count -gt 0
    $injectedRegisterDevicePaths = @(
        'scripts/injected-register-device.ps1',
        'scripts/script-wiring.ps1',
        'scripts/test-injected-register-device.ps1',
        'scripts/verify-injected-registers.ps1'
    )
    $touchesInjectedRegisterDevice = @($paths | Where-Object {
        $_ -in $injectedRegisterDevicePaths
    }).Count -gt 0
    $fingerprintCandidatePaths = @(
        'scripts/FingerprintCandidates.java',
        'scripts/FingerprintFixture.java',
        'scripts/fingerprint-calibration.txt',
        'scripts/fingerprint-candidates.ps1',
        'scripts/fingerprint-signature.schema.json',
        'scripts/test-fingerprint-candidates.ps1'
    )
    $touchesFingerprintCandidates = @($paths | Where-Object {
        $_ -in $fingerprintCandidatePaths
    }).Count -gt 0
    # The source ledger's rules read NOTICE, provenance.json and the catalog's declared builds, and
    # hold docs/sources.md to the ledger, so a push of any of them runs the ledger's suite too.
    $facebookSourcePaths = @(
        'NOTICE',
        'docs/sources.md',
        'patches-list.json',
        'provenance.json',
        'scripts/audit-facebook-sources.ps1',
        'scripts/facebook-sources.ps1',
        'scripts/patch-target.ps1',
        'scripts/test-facebook-sources.ps1',
        'sources/facebook-sources.json'
    )
    $touchesFacebookSources = @($paths | Where-Object {
        $_ -in $facebookSourcePaths
    }).Count -gt 0
    $touchesRelease = @($paths | Where-Object {
        $_ -eq 'patches-bundle.json' -or $_ -eq 'patches-list.json' -or
        $_ -eq 'gradle.properties' -or $_ -eq 'README.md' -or
        # The pins the release check holds the bundle and the README to. A push that moved
        # only one of these ran no gate at all.
        $_ -eq 'gradle/libs.versions.toml' -or $_ -eq 'settings.gradle.kts' -or
        $_ -eq 'gradle/verification-metadata.xml' -or
        $_ -eq 'gradle/wrapper/gradle-wrapper.properties' -or
        # The receipt is the file the release check holds a release to, and the allowlist is
        # what decides which manifest changes it accepts. A push that moved only one of those
        # ran the script contract tests at most, and never the check that reads them.
        $_ -like 'release-receipt-*.json' -or
        $_ -eq 'scripts/manifest-delta-allowlist.txt' -or
        # A released version's heading is the only record a reader has that it shipped, and one
        # was renamed away by a post-release commit that no gate read.
        $_ -eq 'CHANGELOG.md' -or
        # Its version placeholders are held to the target and the published index.
        $_ -eq '.github/ISSUE_TEMPLATE/bug_report.yml'
    }).Count -gt 0

    # Every gate below checks the tip of each pushed ref, and checks it in place only when it is HEAD
    # and nothing in the working tree differs from it. Everything else goes to a clean worktree of the
    # commit. Uncommitted work that isn't in the push can fail it (another agent's did, twice on
    # 2026-09-21) or pass it, and a push of anything but HEAD would otherwise have HEAD checked in
    # its place. The whole tree counts: the tests read README.md, patches-list.json, the artwork
    # and NOTICE as well as the sources, the release facts are those same files, and the script
    # contract tests copy them into their fixtures.
    if ($PSBoundParameters.ContainsKey('ChangedPaths')) {
        # A run by hand names its paths itself and checks this working tree as it stands.
        $head = $null
        $dirty = @()
        $gateCommits = @($null)
    } elseif ($touchesCode -or $touchesRelease -or $touchesScripts -or $touchesContracts -or $touchesFacebookSources) {
        $head = ([string](Invoke-HookGit @('-C', $Root, 'rev-parse', 'HEAD') | Select-Object -Last 1)).Trim()
        $dirty = @(Invoke-HookGit @('-C', $Root, 'status', '--porcelain', '--untracked-files=all'))
        $gateCommits = @($script:pushedCommits)
        if ($gateCommits.Count -eq 0) { $gateCommits = @($head) }
        if ($dirty.Count -gt 0) {
            $shown = @($dirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
            Write-Step ("uncommitted changes in the working tree ($shown), so each pushed ref's tip is " +
                'checked in a clean worktree instead of this working tree')
        }
    }
    # A commit is checked in place when it is HEAD of a clean tree, or when a run by hand names none.
    function Test-InPlace([string]$Commit) { return -not $Commit -or ($dirty.Count -eq 0 -and $Commit -eq $head) }
    # An in-place check reads this working tree, and another agent can change it during a build
    # that takes minutes. The result then covers the commit plus that edit, so the tree is read
    # again after each in-place step and the push stops if it moved. A run by hand checks the
    # tree as it stands and has nothing to compare.
    function Assert-TreeUnchanged([string]$Step) {
        if (-not $head) { return }
        $nowHead = ([string](Invoke-HookGit @('-C', $Root, 'rev-parse', 'HEAD') | Select-Object -Last 1)).Trim()
        $nowDirty = @(Invoke-HookGit @('-C', $Root, 'status', '--porcelain', '--untracked-files=all'))
        if ($nowHead -eq $head -and $nowDirty.Count -eq 0) { return }
        $moved = if ($nowHead -ne $head) { "HEAD moved from $head to $nowHead" } else {
            @($nowDirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
        }
        throw ("The working tree changed while $Step ran in place ($moved), so the result covers " +
            'more than the pushed commit. Push again: a tree with uncommitted changes is checked ' +
            'in a clean worktree of the commit instead.')
    }

    # Script, notice, failure message. The contract tests run for every script change and for the
    # other files above; the two injected-register suites and the resource table check's run only
    # when their own files moved, and the source ledger's when the ledger or a file its rules read
    # did. Each one is the pushed commit's copy, run against that commit.
    $suites = @()
    if ($touchesContracts) {
        $contractsNotice = if ($touchesScripts) { 'scripts changed, running their contract tests' } else {
            'the catalog, the release bundle''s Gradle file, the README or the artwork changed, running the script contract tests'
        }
        $suites += , @('scripts/test-script-contracts.ps1', $contractsNotice,
            'The script contract tests did not pass.')
    }
    if ($touchesInjectedRegisterVerifier) {
        $suites += , @('scripts/test-injected-registers.ps1', 'injected-register verifier changed, running its fixture tests',
            'The injected-register verifier fixture tests did not pass.')
    }
    if ($touchesResourceTableCheck) {
        $suites += , @('scripts/test-resource-table-check.ps1', 'resource table check changed, running its fixture tests',
            'The resource table check fixture tests did not pass.')
    }
    if ($touchesInjectedRegisterDevice) {
        $suites += , @('scripts/test-injected-register-device.ps1', 'injected-register device helper changed, running its cleanup fixtures',
            'The injected-register device cleanup fixtures did not pass.')
    }
    if ($touchesFingerprintCandidates) {
        $suites += , @('scripts/test-fingerprint-candidates.ps1', 'fingerprint ranking changed, running its calibration',
            'The fingerprint ranking calibration did not pass.')
    }
    if ($touchesFacebookSources) {
        $suites += , @('scripts/test-facebook-sources.ps1', 'the Facebook-family source ledger or what it reads changed, running its rules',
            'The Facebook-family source ledger does not keep its rules.')
    }
    if ($suites.Count -gt 0) {
        $scriptsLock = $null
        try {
            foreach ($scriptsCommit in $gateCommits) {
                if (Test-InPlace $scriptsCommit) {
                    $scriptsRoot = $Root
                } else {
                    if (-not $scriptsLock) { $scriptsLock = Enter-GateLock }
                    $scriptsRoot = Get-GateWorktree -Commit $scriptsCommit
                }
                try {
                foreach ($suite in $suites) {
                    $suiteScript = Join-Path $scriptsRoot $suite[0]
                    if (-not (Test-Path -LiteralPath $suiteScript -PathType Leaf)) {
                        if ($paths.Contains($suite[0])) {
                            throw "$($suite[0]) was deleted in this push. The gate scripts must not lose their tests."
                        }
                        # Every suite listed here is one the gate expects. Skipping a missing one
                        # with a note is how three of them were absent from the first commit on.
                        $inCommit = if ($scriptsCommit) { $scriptsCommit } else { 'the working tree' }
                        throw "$($suite[0]) is missing from $inCommit. The gate expects it, so the push stops."
                    }
                    $where = if ($scriptsRoot -eq $Root) { '' } else { " for $scriptsCommit in $scriptsRoot" }
                    Write-Step ($suite[1] + $where)
                    $global:LASTEXITCODE = 0
                    Invoke-WithoutGitEnvironment { & $suiteScript -Root $scriptsRoot }
                    if ($LASTEXITCODE -ne 0) { throw $suite[2] }
                }
                } finally {
                    if ($scriptsRoot -eq $Root) { Assert-TreeUnchanged 'the script tests' }
                }
            }
        } finally {
            if ($scriptsLock) {
                $scriptsLock.ReleaseMutex()
                $scriptsLock.Dispose()
            }
        }
    }

    if ($touchesCode) {
        Write-Step 'extension or patch sources changed, running the runtime tests and the API level check'

        # The Morphe settings plugin resolves from GitHub Packages, which needs a reader token.
        # A hook runs with git's environment, not the shell's, so these are usually absent and
        # the build fails while applying the plugin, long before a test runs.
        if (-not $env:GITHUB_ACTOR -or -not $env:GITHUB_TOKEN) {
            if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
                throw ('Set GITHUB_ACTOR and GITHUB_TOKEN, or install the gh CLI: the patches ' +
                    'plugin resolves from GitHub Packages and cannot be applied without them.')
            }
            $login = (& gh api user --jq .login 2>$null)
            $token = (& gh auth token 2>$null)
            if ([string]::IsNullOrWhiteSpace($login) -or [string]::IsNullOrWhiteSpace($token)) {
                throw 'gh is not signed in, so the patches plugin cannot be resolved. Run gh auth login.'
            }
            $env:GITHUB_ACTOR = $login
            $env:GITHUB_TOKEN = $token
        }

        # The lint runs alongside the tests because the tests cannot see this class of defect at
        # all: they run on a desktop JVM, where every java.util method exists whatever the
        # payload's floor says. Only the API level check reads minSdk, and it reads the SDK_INT
        # guards with it, so a call that is properly guarded stays quiet.
        # The patch module has tests of its own, on the register helpers and the anchors, and
        # nothing before a push ran them: they only ran on the way to generatePatchesList.
        $tasks = @(
            ':extensions:facebook:test',
            ':patches:test',
            ':extensions:shared:library:lint',
            ':extensions:facebook:lint'
        )
        # HUSHFACEBOOK_BUILD_WRAPPER names a PowerShell script that runs Gradle on this machine,
        # called as <wrapper> -ProjectDir <repository> -Tasks <task>...: a machine that shares its
        # CPU and memory between several builds points it at a governor. Unset, the Gradle
        # wrapper in the repository runs the tasks directly.
        $wrapper = $env:HUSHFACEBOOK_BUILD_WRAPPER
        if ($wrapper -and -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
            throw "HUSHFACEBOOK_BUILD_WRAPPER names $wrapper, which is not there."
        }

        $gateLock = $null
        try {
            foreach ($gateCommit in $gateCommits) {
                if (Test-InPlace $gateCommit) {
                    $gateRoot = $Root
                } else {
                    if (-not $gateLock) { $gateLock = Enter-GateLock }
                    $gateRoot = Get-GateWorktree -Commit $gateCommit
                    Write-Step "building $gateCommit in $gateRoot"
                }
                try {
                $global:LASTEXITCODE = 0
                Invoke-WithoutGitEnvironment {
                    if ($wrapper) {
                        & $wrapper -ProjectDir $gateRoot -Tasks $tasks
                    } else {
                        & (Join-Path $gateRoot 'gradlew.bat') -p $gateRoot @tasks
                    }
                }
                if ($LASTEXITCODE -ne 0) {
                    throw ('The runtime test build did not pass. Read the output above: it says whether a ' +
                        'test failed, an API level above the payload floor was reached, or the build could ' +
                        'not start. Push anyway with HUSHFACEBOOK_SKIP_PRE_PUSH=1.')
                }
                } finally {
                    if ($gateRoot -eq $Root) { Assert-TreeUnchanged 'the runtime test build' }
                }
            }
        } finally {
            if ($gateLock) {
                $gateLock.ReleaseMutex()
                $gateLock.Dispose()
            }
        }
    }

    if ($touchesRelease) {
        Write-Step 'a published file changed, checking the release facts'
        # The description's test count belongs to the release it describes. Holding this tree to
        # it only means something while the description is being rewritten, which is when
        # patches-bundle.json is one of the files that moved.
        $describesThisTree = @($paths | Where-Object { $_ -eq 'patches-bundle.json' }).Count -gt 0
        $factsFailed = 'The release facts do not agree. Fix them or push with HUSHFACEBOOK_SKIP_PRE_PUSH=1.'
        if ($describesThisTree) {
            # The index push holds the bundle and the test results this checkout built to the new
            # description, and those exist only here, so it has to be a clean checkout of the
            # commit it pushes: anything else would check other files than the ones going out.
            $elsewhere = @($gateCommits | Where-Object { -not (Test-InPlace $_) })
            if ($elsewhere.Count -gt 0) {
                throw ('An index push checks the bundle and test results this checkout built, so it ' +
                    'has to come from a clean checkout of the commit it pushes. Commit or stash the ' +
                    'rest, check out ' + ($elsewhere -join ', ') + ' and push again.')
            }
            $validate = Join-Path $Root 'scripts/validate-release-facts.ps1'
            try {
            $global:LASTEXITCODE = 0
            # The release copy buildAndroid leaves in patches/build/release, which no other task
            # writes. patches/build/libs was read here until 2026-09-21: the patch tests this hook
            # runs rerun :patches:jar, which put the plain jar back over the bundle under the same
            # name, and the sources and javadoc jars share the .mpp extension there as well.
            $artifacts = @(Get-ChildItem -LiteralPath (Join-Path $Root 'patches/build/release') `
                -Filter '*.mpp' -File -ErrorAction SilentlyContinue)
            # The bundle this checkout built, so the indexed URL, its hash and the hosted checksum
            # entry can all be compared against something real: the only one there, whatever its
            # name, or among several the one named for the version the index publishes. With none
            # of those the hosted asset is downloaded and held to every other check a local build
            # gets. This push is the one that hands a release to Manager users, and it used to go
            # out with the hosted bundle unread whenever build/release didn't hold exactly one.
            $indexPath = Join-Path $Root 'patches-bundle.json'
            $indexVersion = $null
            if (Test-Path -LiteralPath $indexPath -PathType Leaf) {
                try {
                    $indexVersion = [string](Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json).version
                } catch {
                    throw "patches-bundle.json is not JSON the release check can read: $($_.Exception.Message)"
                }
            }
            $forIndex = @($artifacts | Where-Object { $_.Name -eq "patches-$indexVersion.mpp" })
            $builtHere = if ($artifacts.Count -eq 1) { $artifacts[0] } elseif ($forIndex.Count -eq 1) { $forIndex[0] }
            if ($builtHere) {
                $among = if ($artifacts.Count -gt 1) { "found $($artifacts.Count) bundles, so " } else { '' }
                Write-Step "${among}the hosted asset is compared with $($builtHere.Name), built here"
                & $validate -Root $Root -VerifyPublishedAsset -ArtifactPath $builtHere.FullName
            } else {
                $found = if ($artifacts.Count -gt 1) {
                    "found $($artifacts.Count) bundles and none is patches-$indexVersion.mpp"
                } else {
                    'no local bundle here'
                }
                Write-Step "$found, so the hosted asset is downloaded and checked on its own"
                & $validate -Root $Root -VerifyPublishedAsset -ArtifactIsHosted
            }
            if ($LASTEXITCODE -ne 0) { throw $factsFailed }
            } finally {
                Assert-TreeUnchanged 'the release facts check'
            }
        } else {
            # Every other push checks the files it carries, against the published index it leaves
            # alone. The indexed URL is still fetched; only the byte-for-byte hash comparison needs
            # a local bundle, and at any time but an index push build/release holds a bundle built
            # from whatever the tree was then. A release source commit reaches GitHub before its tag
            # and bundle exist, so the unchanged index may keep naming the previous release.
            $factsLock = $null
            try {
                foreach ($factsCommit in $gateCommits) {
                    $arguments = @{ SkipDescriptionTestCount = $true; AllowPublishedIndexLag = $true }
                    if (Test-InPlace $factsCommit) {
                        $factsRoot = $Root
                    } else {
                        if (-not $factsLock) { $factsLock = Enter-GateLock }
                        $factsRoot = Get-GateWorktree -Commit $factsCommit
                        Write-Step "checking the release facts of $factsCommit in $factsRoot"
                    }
                    # The pushed commit's own check, which reads its own helpers. In the worktree its
                    # build folders can hold another commit's test results, so they are left unread
                    # there, by any check that knows how.
                    $validate = Join-Path $factsRoot 'scripts/validate-release-facts.ps1'
                    if ($factsRoot -ne $Root -and (Get-Command $validate).Parameters.ContainsKey('SkipTestResults')) {
                        $arguments['SkipTestResults'] = $true
                    }
                    try {
                    $global:LASTEXITCODE = 0
                    & $validate -Root $factsRoot @arguments
                    if ($LASTEXITCODE -ne 0) { throw $factsFailed }
                    } finally {
                        if ($factsRoot -eq $Root) { Assert-TreeUnchanged 'the release facts check' }
                    }
                }
            } finally {
                if ($factsLock) {
                    $factsLock.ReleaseMutex()
                    $factsLock.Dispose()
                }
            }
        }
    }

    if (-not $touchesScripts -and -not $touchesCode -and -not $touchesRelease -and -not $touchesContracts -and
            -not $touchesFacebookSources) {
        Write-Step 'no code or published file changed'
    }
    Write-Step 'ok'
    exit 0
} finally {
    Pop-Location
}
