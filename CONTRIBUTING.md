# Contributing

Bug reports, fixes for a new Facebook build, new patches and pull requests are all welcome.

Not sure it's a bug, or just have a question? Start in [Discussions](https://github.com/SysAdminDoc/Hushfacebook/discussions). If it turns out to be a bug, it moves to an issue from there.

If you open an issue, include:

- the Facebook version and variant you patched (APKMirror names the variant, for example arm64-v8a, Android 11+)
- the Morphe Manager version and the Hushfacebook version
- the patches you selected
- what you expected and what happened, with steps to get there
- a diagnostic report or screenshots if it's visual or a crash. Remove private messages and account details from screenshots first.

GitHub doesn't let the person who opened an issue reopen it once a maintainer closes it, so a closing comment always says how to get it reopened: comment there and we'll reopen it.

## When Facebook updates

Facebook ships a new version about once a week and renames most of its code each time. A patch that finds what it needs by a kept name, a log string or a method's shape usually carries over, and a patch that doesn't fails at patch time with a message saying what it couldn't find. Fixing it for the new build goes like this:

1. Get the new build's arm64-v8a bundle from APKMirror and put it in your fixture folder.
2. Run `scripts/verify-all-patches.ps1 -Apk <new .apkm> -Force -DesktopJar <jar> -WorkDir <scratch>`. `-Force` lets the CLI patch a version the bundle doesn't declare yet, and the result names every patch that failed.
3. Find where the failing patch's anchor went, change the patch to find it on both the new build and the ones already declared, and never write down a name the obfuscator gave one build: `ObfuscatedIdentityTest` fails on one.
4. Add the build to `AppCompatibilities.kt` with its arm64 version code, regenerate the patch list, and run the checks again on every retained build.

For a patch change, say which Facebook build you tested against and what you checked.

## Building and checking

Read the README's build section first. Gradle needs `GITHUB_ACTOR` and `GITHUB_TOKEN` (a token with `read:packages`) to fetch the Morphe patcher. Run `:patches:generatePatchesList` before `:patches:buildAndroid`; a test run afterwards replaces the jar in `patches/build/libs`, which is why the release bundle is copied to `patches/build/release`.

The checks that matter before a release:

- `:patches:test` and `:extensions:facebook:testDebugUnitTest`, with `HUSHFACEBOOK_FIXTURE_DIR` set so the tests that read real Facebook builds run instead of skipping.
- `scripts/verify-all-patches.ps1` on every retained fixture. It applies all patches in one run, checks the CLI's own report and holds the rebuilt resource table to Facebook's.
- `scripts/build-release-receipt.ps1`, which writes the release receipt from those runs, and `scripts/validate-release-facts.ps1`, which holds the README, `patches-bundle.json`, the CHANGELOG and the bug form to the generated patch list.

`scripts/install-hooks.ps1` installs a pre-push hook that runs the tests when a push changes `extensions/` or `patches/`, and the release check when it changes a published file. Set `HUSHFACEBOOK_SKIP_PRE_PUSH=1` to push without it.

A release goes out in two commits. The first carries the new version with `patches-bundle.json` still naming the previous release. The bundle is built from that exact commit and published with its checksum, and the second commit points `patches-bundle.json` at it. Morphe Manager reads only `patches-bundle.json`, so a release isn't out until that second commit is pushed.

## Settings for your machine

Nothing in the repository points at a folder or a phone on anybody's machine. These variables do that instead, and none of them has a default:

- `HUSHFACEBOOK_FIXTURE_DIR` is the folder holding the Facebook bundles the fixture tests and scripts read. They're hundreds of megabytes each, so they aren't in the repository.
- `HUSHFACEBOOK_DESKTOP_JAR` is the Morphe desktop CLI jar. `HUSHFACEBOOK_WORKDIR` or a jar under `build/morphe-tools` works too.
- `HUSHFACEBOOK_BUILD_WRAPPER` names a PowerShell script the pre-push hook runs Gradle through, called as `<wrapper> -ProjectDir <repository> -Tasks <task>...`. Unset, the hook runs `gradlew.bat` itself.
- `HUSHFACEBOOK_DEVICE_SERIAL` is the adb serial of a test phone for `scripts/patch-for-device.ps1`. Keep your own phone out of it: a re-signed Facebook can't install over the Play Store copy without uninstalling it, which signs you out.

## Source notices

Keep every existing copyright, license, author credit and source-origin notice when you modify or move a file, and don't remove a notice unless the code it covers is gone from the file. Andrew Liang's and FroggoMorphePatches' code is GPL-3.0, and in this ecosystem a missing notice has already ended in DMCA takedowns more than once.

New source written for this project may use:

```text
/*
 * Copyright <year> Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
```

Code taken from another project keeps its notices and gets a `Forked from:` line with the file's URL at the commit it came from. Record it in `provenance.json` too. A rule naming a single file wins over the folder rule around it, which is how a file written here can sit among ported code. `ProvenanceTest` fails when a shipped file matches no rule or two, or when a rule names an upstream that NOTICE doesn't. It also holds every header to its rule. The header has to link a repository of that rule's chain, and each `Forked from` source has to be one of them. A file under a rule for code written here can't say it came from anywhere, however it words that, and every rule has to state its licence.
