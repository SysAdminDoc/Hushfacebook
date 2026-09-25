<h1 align="center">Hushfacebook</h1>

<p align="center">
  <a href="https://github.com/SysAdminDoc/Hushfacebook/releases"><img src="https://img.shields.io/badge/version-0.1.1-0866FF" alt="Version 0.1.1"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/platform-Android%2011%2B-3DDC84" alt="Platform Android 11+">
  <img src="https://img.shields.io/badge/Facebook-580.0.0.51.74-0866FF" alt="Facebook 580.0.0.51.74">
  <img src="https://img.shields.io/badge/for-Morphe%20Manager%201.31.0%2B-8A2BE2" alt="For Morphe Manager 1.31.0 or newer">
</p>

Patches for the Facebook app on Android, for use with Morphe. They take sponsored posts, sponsored stories and Reels ads out of Facebook, stop its background ad downloads and ad tracking, add story and reel downloads, open links in your own browser, and fix the screens a re-signed build breaks.

Hushfacebook brings the Facebook patches from the Morphe patch sources that have them into one place, so they can be kept working as Facebook updates. Most of them come from [Andrew Liang's patches](https://github.com/andrewliang25/morphe-patches), rewritten here with fixes, and the feed filter also drops promoted posts the way [FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches) does. The build, the settings screen and the checks behind every release come from [Hushfeed](https://github.com/SysAdminDoc/hushfeed). See [Where the patches come from](#where-the-patches-come-from).

This project has no connection to Meta or to the Morphe project. Neither endorses it, and neither wrote it.

## Install

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) 1.31.0 or newer.
2. Add Hushfacebook as a patch source: https://morphe.software/add-source?github=SysAdminDoc%2FHushfacebook
3. Get Facebook 580.0.0.51.74 for arm64-v8a from [APKMirror](https://www.apkmirror.com/apk/facebook-2/facebook/). Take the Android 11+ bundle (.apkm). Facebook 577.0.0.50.72 works too.
4. In Morphe Manager, pick that file, keep the default patch selection or change it, and patch.

There are 13 patches for `com.facebook.katana`. The arm64-v8a builds are the ones they're checked against. Meta builds the armeabi-v7a and Android 9 variants of each release separately, and those lack code some of the patches need.

Facebook releases a new version about once a week, and each one renames most of its code. Every patch here finds what it changes by names Facebook keeps (its GraphQL model classes, log strings, enum names, manifest components) rather than by the names that change, which is why most of them carry over from one build to the next. When one doesn't, patching stops with a message naming what it couldn't find, instead of producing an app that quietly does nothing. Please report it.

## Keep your signing key

Morphe Manager signs the patched Facebook with a key it makes on your phone. Android installs an update over your patched Facebook only when the update carries that same key, so the key is what lets you update without losing Facebook's data.

- **Back it up right after your first patch.** In Morphe Manager, open Settings → System → Import & export → Signing key and tap Export. The Export button only appears once you've patched something. Keep the `Morphe.keystore` file somewhere private, because anyone who has it can sign an APK your phone will accept as an update. Morphe's settings backup doesn't include the key.
- **On a new phone, import it before you patch anything.** It's the same dialog. Reinstalling Morphe Manager or clearing its storage makes a new key, and without your exported copy nothing you patched earlier can be updated in place.
- **A different key means starting over.** Android refuses an update signed with another key. Unless you use Root Mount, the only way forward is to uninstall the patched Facebook, and that deletes its data. You'll have to sign in again, and anything kept only in the app is gone. A Root Mount install sits over Meta's own Facebook, so a key change doesn't touch its data.

Morphe's own guide is [Backup and keystore](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md).

## Android developer verification

Google is starting to require that Android apps come from registered developers. From September 30, 2026 the check runs in Brazil, Indonesia, Singapore and Thailand, and only on installs from seven app stores: Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore and GetApps ([Google's overview](https://developer.android.com/developer-verification)). Google's [FAQ](https://developer.android.com/developer-verification/guides/faq) says apps that are sideloaded aren't covered yet, and an install from Morphe Manager is a sideload. A rollout to every install source is planned for 2027.

Once it does reach sideloads, a patched Facebook won't count as registered. It keeps Meta's package name but carries your key, and for a package name someone else already holds, Google's answer is to use a different name or to file a request that goes through extra review, with no promised outcome. Two ways in stay open:

- **The advanced flow.** For people who accept the risk, Google added a setting to allow apps from unverified developers, under Settings → System → Developer options → Allow apps from unverified developers ([Google's help page](https://support.google.com/android/answer/17588095)). Turning it on takes a one-time 24-hour wait, and each install afterwards still shows a warning with an Install anyway button. Google's pages describe the steps a little differently, so follow what your phone shows. Updates to an unregistered app need this setting on too.
- **ADB from a computer.** Google says apps installed with `adb install` don't need verification and the 24-hour wait doesn't apply to them. In Morphe Manager, turn on Keep patched APKs under Settings → System, export the patched copy, and install it with `adb install -r <file>.apk`. The same-key rule above still applies.

Neither path has been tried here on a certified phone in one of those four countries yet. If you try one, please open an issue saying what happened.

## Patches

| Patch | What it does |
|---|---|
| `AMOLED black theme` | Makes Facebook's dark mode black instead of dark grey. Turn on dark mode in Facebook first. |
| `Block ad telemetry` | Stops Facebook watching for screenshots of ads and reporting which apps you install for ad attribution. |
| `Block background ad prefetch` | Stops Facebook downloading ads and its ad model in the background, which saves data, battery and storage. |
| `Disable Audience Network` | Stops Facebook serving ads to other apps. Those apps then show their own ads or none, and rewarded ads can fail. |
| `Download any reel` | Adds a Download button beside every reel. Videos save at the best quality the player streams. |
| `Download any story` | Adds Save to the menu of any story, including stories with music. Videos save at the best quality the player streams. |
| `Hide sponsored posts` | Removes sponsored and promoted posts from the news feed, with no gap left behind. |
| `Hide sponsored reels` | Removes ads from Reels and Watch, including product banners over a reel and ads inside a video. |
| `Hide sponsored stories` | Removes ad cards from the story viewer, so swiping through stories only shows stories people posted. |
| `Hide suggested and promoted posts` | Removes posts that Facebook adds to the feed, such as "Pages you may like", upsells and surveys. |
| `Hushfacebook settings` | Adds Hushfacebook settings to Facebook's launcher icon. Long-press the icon to turn features on or off, pause Hushfacebook, export diagnostics and read the licenses. |
| `Open links in external browser` | Opens web links in your default browser instead of Facebook's in-app browser. Facebook pages still open in the app. |
| `Restore screens on re-signed builds` | Makes profiles and some Settings pages open again on a re-signed build. A Root Mount install doesn't need this patch. |

`Download any reel` and `AMOLED black theme` are off by default. Everything else is on.

## Settings

Long-press Facebook's icon on your home screen and tap **Hushfacebook**. The screen lists the features this build carries:

- Switches for the feed, story and Reels filters, for opening links in your browser and for story saves. They take effect straight away, with no restart and no new patching.
- **Pause Hushfacebook**. From the next start, every switch acts as if it were off and Facebook's own code runs in its place. Your settings stay as they are. Pause can't undo what was set when you patched, and the screen lists what stays in.
- **Debug logging** and **Export diagnostic report**, for bug reports. The report leaves out links, account and post ids, session cookies and names. It also says which patches a switch runs and which stay in while paused.
- **Licenses**, the notices of every project this is built on.

Hushfacebook pauses itself when Facebook crashes within a minute of starting three times in a row, and the screen says so. If you can't reach the screen at all, an empty file named `hushfacebook-safe-mode` in Facebook's folder under `Android/data` pauses it too. Safe mode is the same pause. If Facebook still keeps closing, the cause is Facebook itself or a patch that stays in while paused, so patch again without the one you suspect.

### What Pause turns off

| Patch | While paused |
|---|---|
| Hide sponsored posts | Off. Sponsored and promoted posts come back. |
| Hide suggested and promoted posts | Off. |
| Hide sponsored stories | Off. |
| Hide sponsored reels | Partly. Ads inside a page of reels come back, but banners over a reel and mid-roll ads stay blocked. |
| Open links in external browser | Off. Links open in Facebook's own browser. |
| Download any story | Partly. Save stays in every story's menu, and it runs Facebook's own save. |
| Download any reel | Stays. The Download button keeps working. |
| Block ad telemetry, Block background ad prefetch, Disable Audience Network, AMOLED black theme, Restore screens on re-signed builds | Stay. They were set when you patched, and changing one means patching again. |

## Known limitations

- **Other Meta apps.** A patched Facebook is signed with your key, not Meta's. Meta's apps share permissions that Android only lets one signer own, so with a re-signed Facebook installed, the official Messenger, Facebook Lite, Instagram or Threads may refuse to install (`INSTALL_FAILED_DUPLICATE_PERMISSION`), and signing in to one of them through Facebook may fail. Installing through Morphe Manager's Root Mount keeps Meta's signature and avoids both.
- **Links from other apps.** Android may stop sending facebook.com links to a re-signed Facebook, because the app's link verification is tied to Meta's signature.
- **Two Facebook builds.** Patches are checked on Facebook 580.0.0.51.74 and 577.0.0.50.72. Another build will often work, and Morphe Manager can patch it if you allow other versions, but it hasn't been checked.
- **Downloads.** Stories encoded only as VP9 save at 360p, because Android can't join VP9 video with AAC sound in an MP4.

## Privacy

Hushfacebook doesn't collect anything and has no server. The only time the patched app goes online on Hushfacebook's behalf is to download a story or reel you asked to save, from the same Facebook address the player streams it from. A save only follows HTTPS addresses on Meta's media servers (fbcdn.net, fbsbx.com and cdninstagram.com), redirects included, and the file lands in Facebook's cache first. Only once it's whole, under 512 MB and actually a photo or video does it go to your gallery. Links in the code point only at github.com and gitlab.com, the sources named in the notices, and www.gnu.org for the licence.

## Where the patches come from

| Source | What came from it |
|---|---|
| [andrewliang25/morphe-patches](https://github.com/andrewliang25/morphe-patches) at `5db2e57` | Every Facebook patch: the feed, story and Reels ad filters, the prefetch, telemetry and Audience Network blocks, the external browser, the re-signed build fix, the AMOLED theme and both downloads. Rewritten rather than copied commit by commit, with fixes listed in the [changelog](CHANGELOG.md). |
| [SapitoSucio/FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches) | The idea of dropping promoted posts beside sponsored ones. Andrew Liang credits it for some ideas and implementations too. |
| [SysAdminDoc/hushfeed](https://github.com/SysAdminDoc/hushfeed) at `1f1f81a` | The Gradle build, the shared extension library with its settings, pause and diagnostics, the bytecode helpers, and the checks that apply every patch to real Facebook builds before a release. |
| [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches) | The patcher, the patch template and the code both of the above grew from. |

Every source file says where it came from in its header, and [provenance.json](provenance.json) maps each file to its source, commit and licence. [docs/sources.md](docs/sources.md) covers the other Facebook and Messenger patch sources, what each one does, and what this bundle took from it.

## Building from source

You need JDK 17 or newer, the Android SDK, and a GitHub token with `read:packages`, because the Morphe patcher comes from GitHub Packages.

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<a token with read:packages>
./gradlew :patches:generatePatchesList
./gradlew :patches:buildAndroid
```

The bundle lands in `patches/build/release/patches-<version>.mpp`, beside its SHA-256. Run `generatePatchesList` before `buildAndroid`, or the bundle loses its Android payload.

Tests: `./gradlew :patches:test :extensions:facebook:testDebugUnitTest`. Set `HUSHFACEBOOK_FIXTURE_DIR` to a folder holding the Facebook bundles to run the tests that read real builds. Without it they skip and say so.

To apply every patch to a real build and check the result, run `scripts/verify-all-patches.ps1 -Apk <facebook .apkm> -DesktopJar <morphe-desktop jar> -WorkDir <scratch folder>`. It holds the patched resource table to Meta's, and the code the patches inject to the shapes Android's verifier rejects: branches into the middle of an instruction, calls with the wrong registers, values read at the wrong width, broken try ranges, and a second feed hook. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

## License

[GPL-3.0](LICENSE), with the Morphe section 7 notices carried in [NOTICE](NOTICE). Facebook, Messenger and Meta are trademarks of Meta Platforms, Inc.
