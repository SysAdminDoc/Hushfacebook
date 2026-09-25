![Hushfacebook. Keep the people. Cut the noise.](assets/readme-hero.png)

<p align="center">
  <a href="https://github.com/SysAdminDoc/Hushfacebook/releases"><img src="https://img.shields.io/badge/version-0.1.2-0866FF" alt="Version 0.1.2"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
  <img src="https://img.shields.io/badge/platform-Android%2011%2B-3DDC84" alt="Platform Android 11+">
  <img src="https://img.shields.io/badge/Facebook-580.0.0.51.74-0866FF" alt="Facebook 580.0.0.51.74">
  <img src="https://img.shields.io/badge/for-Morphe%20Manager%201.32.0%2B-8A2BE2" alt="For Morphe Manager 1.32.0 or newer">
</p>

# Hushfacebook

Hushfacebook is a Morphe patch bundle for Android that takes the clutter out of Facebook and puts useful controls back in your hands.

[Add to Morphe](https://morphe.software/add-source?github=SysAdminDoc%2FHushfacebook) | [Download a release](https://github.com/SysAdminDoc/Hushfacebook/releases/latest) | [Browse the patches](#patches) | [Support development](https://ko-fi.com/X8K126YVER)

## Why use it

- **A quieter feed.** Sponsored and suggested posts disappear, along with ads in Stories, Reels, and Watch.
- **Less tracking.** Selected ad telemetry and background ad downloads stop. Common trackers also come off links you open or share.
- **Media you can keep.** Save stories and reels at the best quality Facebook streams, with progress and cancellation controls.
- **Controls that recover.** Every runtime feature has a switch. Pause mode, automatic safe mode, settings backups, and privacy-filtered diagnostics help when Facebook changes.

The project brings the Facebook patches from Morphe sources into one maintained bundle. Most started with [Andrew Liang's patches](https://github.com/andrewliang25/morphe-patches) and were rewritten here with fixes. The feed filter also removes promoted posts using the approach from [FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches). Its build, settings screen, and release checks share a foundation with the sister project [Hushfeed](https://github.com/SysAdminDoc/hushfeed). See [Where the patches come from](#where-the-patches-come-from) for the full provenance.

This project has no connection to Meta or to the Morphe project. Neither endorses it, and neither wrote it.

## Install

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) 1.32.0 or newer.
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

Google is starting to require that Android apps come from registered developers. From September 30, 2026 the check runs in Brazil, Indonesia, Singapore and Thailand, and only on installs from seven app stores: Google Play, HONOR App Market, OPPO App Market, Galaxy Store, Palm Store, V-Appstore and GetApps ([Google's overview](https://developer.android.com/developer-verification)). Google's [FAQ](https://developer.android.com/developer-verification/guides/faq) says apps that are sideloaded aren't covered yet, and an install from Morphe Manager is a sideload. In 2027 Google plans to take the check global, to all apps on certified Android devices. Installs over ADB stay exempt.

Once it does reach sideloads, a patched Facebook won't count as registered. It keeps Meta's package name but carries your key, and for a package name someone else already holds, Google's answer is to use a different name or to file a request that goes through extra review, with no promised outcome. Two ways in stay open:

- **The advanced flow.** For people who accept the risk, Google added a setting to allow apps from unverified developers, under Settings → System → Developer options → Allow apps from unverified developers ([Google's help page](https://support.google.com/android/answer/17588095)). Turning it on takes a one-time 24-hour wait, and each install afterwards still shows a warning with an Install anyway button. When the wait is over, your phone asks whether to keep the setting on for seven days or indefinitely. Pick indefinitely, because once it lapses, updates to the patched Facebook fail. Google's pages describe the steps a little differently, so follow what your phone shows.
- **ADB from a computer.** Google says apps installed with `adb install` don't need verification and the 24-hour wait doesn't apply to them. In Morphe Manager, turn on Keep patched APKs under Settings → System, export the patched copy, and install it with `adb install -r <file>.apk`. The same-key rule above still applies.

Neither path can be tried against a real block until the check reaches sideloads, so neither has been tested here. If you get to try one, please open an issue saying what happened.

## Patches

| Patch | What it does |
|---|---|
| `AMOLED black theme` | Makes Facebook's dark mode black instead of dark grey. Turn on dark mode in Facebook first. |
| `Block ad telemetry` | Stops Facebook watching for screenshots of ads and reporting which apps you install for ad attribution. |
| `Block background ad prefetch` | Stops Facebook downloading ads and its ad model in the background. That saves data and battery. The ads don't take up storage either. |
| `Disable Audience Network` | Stops Facebook serving ads to other apps. Those apps then show their own ads or none, and rewarded ads can fail. |
| `Download any reel` | Adds a Download button beside every reel. Videos save at the best quality the player streams. |
| `Download any story` | Adds Save to the menu of any story, including stories with music. Videos save at the best quality the player streams. |
| `Hide sponsored posts` | Removes sponsored and promoted posts from the news feed, with no gap left behind. |
| `Hide sponsored reels` | Removes ads from Reels and Watch, including product banners over a reel and ads inside a video. |
| `Hide sponsored stories` | Removes ad cards from the story viewer, so swiping through stories only shows stories people posted. |
| `Hide suggested and promoted posts` | Removes posts that Facebook adds to the feed, such as "Pages you may like" and its own upsells. In-feed surveys go too. |
| `Hushfacebook settings` | Adds Hushfacebook settings to Facebook's launcher icon. Long-press the icon to turn features on or off, pause Hushfacebook, save your switches to a file or load them, and export diagnostics. The licenses are there too. |
| `Open links in external browser` | Opens web links in your default browser instead of Facebook's in-app browser. Facebook pages still open in the app. |
| `Restore screens on re-signed builds` | Makes profiles and some Settings pages open again on a re-signed build. A Root Mount install doesn't need this patch. |

`Download any reel` and `AMOLED black theme` are off by default. Everything else is on.

While a story or reel saves, a notification shows how far it's got, with a Cancel button. Facebook has to be allowed to post notifications for it. You can also turn off its "Hushfacebook saves" channel in Facebook's notification settings, and a save then runs with just a message when it starts and one when it ends. A cancelled save leaves nothing behind. One that Android stops half way leaves nothing in the gallery either, and the next save clears what it left in Facebook's cache.

## Settings

Long-press Facebook's icon on your home screen and tap **Hushfacebook**. The screen lists the features this build carries:

- A switch for each filter. Opening links in your browser and the two download features have switches too. They take effect straight away, with no restart and no new patching, though a reel already on screen keeps the buttons it was built with. While Hushfacebook is paused, a change waits until it's back on.
- **Pause Hushfacebook**. From the next start, every one of those switches acts as if it were off and Facebook's own code runs in its place. Debug logging keeps working, and your settings stay as they are. Pause can't undo what was set when you patched, and the screen lists what stays in.
- **Debug logging** and **Export diagnostic report**, for bug reports. The report leaves out links, account and post ids, session cookies and names. It names your Facebook build and says, for every patch but the settings entry itself, whether a switch runs it. For each of those whose hooks have run, it gives how often they ran and the first thing they couldn't find. Failed saves and links no browser opened are in it too.
- **Licenses**, the notices of every project this is built on.

Morphe Manager can export your patch choices and your signing key, but not the switches on this screen. **Export settings** saves them to a JSON file wherever you pick, and **Import settings** reads one back, on this phone or a new one. Before anything changes, the screen says how many switches the file would change and how many entries in it this version doesn't know, which it skips. A damaged file or one from a newer Hushfacebook changes nothing. Pause and debug logging stay out of the file, and so does anything about you or your phone.

Everything Hushfacebook shows, from the settings screen to the save notification, follows your phone's language in English, German, Spanish, Indonesian, Brazilian Portuguese and Turkish, and falls back to English in any other. Facebook's own language doesn't change. The diagnostic report stays in English, so whoever reads it can, and so do the error toasts Debug logging shows. With TalkBack on, section titles are headings you can jump between, and each switch says it's a switch and whether it's on. Every row's text wraps in full, even at the largest text size.

Hushfacebook pauses itself when Facebook crashes within a minute of starting three times in a row, and the screen says so. If you can't reach the screen at all, an empty file named `hushfacebook-safe-mode` in `Android/data/com.facebook.katana/files` pauses it too. It has to be in that `files` folder, not the one above it. Safe mode is the same pause. It changes what the switches answer, but every patch's code stays in place, so if Facebook keeps closing in safe mode, the cause can be Facebook itself or any patch, whichever row of the table below it's in. To find it, patch again without the patch you suspect, or with fewer patches.

### What Pause turns off

| Patch | While paused |
|---|---|
| Hide sponsored posts | Off. Sponsored and promoted posts come back. |
| Hide suggested and promoted posts | Off. |
| Hide sponsored stories | Off. |
| Hide sponsored reels | Partly. Ads inside a page of reels come back. Banners over a reel and mid-roll ads stay blocked, and so do ads the app adds on its own. |
| Open links in external browser | Off. Links open in Facebook's own browser. |
| Download any story | Off. Only your own stories have Save, and it's Facebook's own. |
| Download any reel | Off. Reels show only Facebook's own buttons. |
| Block ad telemetry, Block background ad prefetch, Disable Audience Network, AMOLED black theme, Restore screens on re-signed builds | Stay. They were set when you patched, and changing one means patching again. |

## Known limitations

- **Other Meta apps.** A patched Facebook is signed with your key, not Meta's. Meta's apps share permissions that Android only lets one signer own, so with a re-signed Facebook installed, the official Messenger, Facebook Lite, Instagram or Threads may refuse to install (`INSTALL_FAILED_DUPLICATE_PERMISSION`), and signing in to one of them through Facebook may fail. Installing through Morphe Manager's Root Mount keeps Meta's signature and avoids both.
- **Links from other apps.** Android may stop sending facebook.com links to a re-signed Facebook, because the app's link verification is tied to Meta's signature.
- **Two Facebook builds.** Patches are checked on Facebook 580.0.0.51.74 and 577.0.0.50.72. Another build will often work, and Morphe Manager can patch it if you allow other versions, but it hasn't been checked.
- **Downloads.** Stories encoded only as VP9 save at 360p, because Android can't join VP9 video with AAC sound in an MP4. Stories Facebook sends only as AV1 save at the lower single-file quality before Android 14, or on a phone that can't decode AV1. And a story that was already open when you turned Save any story on saves at 360p until you open it again.

## Privacy

Hushfacebook doesn't collect anything and has no server. The only time the patched app goes online on Hushfacebook's behalf is to download a story or reel you asked to save, from the same Facebook address the player streams it from. A save only follows HTTPS addresses on Meta's media servers (fbcdn.net, fbsbx.com and cdninstagram.com), redirects included, and the file lands in Facebook's cache first. It goes to your gallery only once it's whole and under 512 MB, and only if it's really a photo or video.

## Where the patches come from

| Source | What came from it |
|---|---|
| [andrewliang25/morphe-patches](https://github.com/andrewliang25/morphe-patches) at `5db2e57` | Every Facebook patch here, from the ad filters to both downloads. They were rewritten rather than copied commit by commit, and the fixes are listed in the [changelog](CHANGELOG.md). |
| [SapitoSucio/FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches) | The idea of dropping promoted posts beside sponsored ones. Andrew Liang credits it for some ideas and implementations too. |
| [SysAdminDoc/hushfeed](https://github.com/SysAdminDoc/hushfeed) at `1f1f81a` | The Gradle build, the shared extension library with its settings screen and diagnostics, the pause, the bytecode helpers, and the checks that apply every patch to real Facebook builds before a release. The settings export and import came later, from `bcc57ee`. |
| [Morphe](https://github.com/MorpheApp) and [ReVanced](https://gitlab.com/ReVanced/revanced-patches) | The patcher and the patch template. Both of the above grew from their code. |

Every source file says where it came from in its header, and [provenance.json](provenance.json) maps each file to the project and commit it came from, with its licence. [docs/sources.md](docs/sources.md) covers the other Facebook and Messenger patch sources: what each one does and what this bundle took from it.

## Building from source

You need JDK 17 or newer and the Android SDK. The Morphe patcher comes from GitHub Packages, so you also need a GitHub token with `read:packages`.

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<a token with read:packages>
./gradlew :patches:generatePatchesList
./gradlew :patches:buildAndroid
```

The bundle lands in `patches/build/release/patches-<version>.mpp`, beside its SHA-256. Run `generatePatchesList` before `buildAndroid`, or the bundle loses its Android payload.

Tests: `./gradlew :patches:test :extensions:facebook:testDebugUnitTest`. Set `HUSHFACEBOOK_FIXTURE_DIR` to a folder holding the Facebook bundles to run the tests that read real builds. Without it they skip and say so.

To apply every patch to a real build and check the result, run `scripts/verify-all-patches.ps1 -Apk <facebook .apkm> -DesktopJar <morphe-desktop jar> -WorkDir <scratch folder>`. It holds the patched resource table to Meta's. It also checks the code the patches inject for the shapes Android's verifier rejects, such as branches into the middle of an instruction, calls with the wrong registers, values read at the wrong width and broken try ranges. And it requires exactly one feed guard, in `addNewEdgeToCollection`, because a guard anywhere else filters nothing. That last rule is this project's, not the verifier's. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

## License

[GPL-3.0](LICENSE), with the Morphe section 7 notices carried in [NOTICE](NOTICE). Facebook, Messenger and Meta are trademarks of Meta Platforms, Inc.
