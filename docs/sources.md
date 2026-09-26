# Where the Facebook patches come from

Hushfacebook started on 2026-09-24 as an attempt to put every working Facebook patch for Morphe in one place. Before writing anything, we went through every public patch source we could find that touches Facebook (`com.facebook.katana`), Messenger (`com.facebook.orca`) or Facebook Lite (`com.facebook.lite`). This page is what we found and what we took from each.

The short version: two sources are actively patching current Facebook builds, a handful of forks and copies sit around them, and Messenger has a bigger patch ecosystem than Facebook itself. Morphe's official bundle ships nothing for any of the three apps.

This page is the readable version. The one the scripts hold us to is [sources/facebook-sources.json](../sources/facebook-sources.json), which records every source with its branches and the commits we last read them at, its licence and a hash of the licence text, the Facebook builds it declares, its features, and what we're allowed to take from it.

## How a source gets in

Each source in the ledger gets one of four answers:

- **adopted** means Hushfacebook ships code from it. Only one source is adopted today.
- **candidate** means its licence lets us port code, and we might, once the checks below pass.
- **behavior-only** means we never port its code. We can read what it does and find the same thing in Facebook's own code, and that's all.
- **rejected** means it's licensed but there's nothing there to take.

A source with no licence, or one whose licence can't be combined with GPL-3.0, is behavior-only. So is a source whose Facebook code came from one of those, whatever its own licence says. Before code from a source can ship, the ledger needs the commit it came from, a compatible licence with its URL and hash, the source's name in [NOTICE](../NOTICE), a rule in [provenance.json](../provenance.json) naming that repository and commit, and a release receipt showing the patches applied to both Facebook builds the bundle declares. `scripts/test-facebook-sources.ps1` refuses a ledger that breaks any of that, and so does every local release.

`scripts/audit-facebook-sources.ps1` keeps the ledger current. It reads the official Morphe bundle, the Morphe community directory, Awesome Morphe, the Morphe Patch Tracker, Jman's bundle index and the Morphe Archive, searches GitHub (and GitLab, given a token) for code naming any of the three packages, and then walks every source's branches, forks and licence. A repository whose files are byte for byte files the ledger already holds counts as a copy of that source, not as a new one. Anything else that moved (a new source, a licence that disappeared, a branch whose Facebook code changed, a fork we haven't seen) fails the audit with a report and leaves the ledger alone. When nothing moved, it stamps the day's date. A release won't go out on a census more than 14 days old.

The audit never downloads anyone's code. It reads what the forges' APIs say about a repository and hashes licence text in memory. The only files it writes are its report and the ledger's dates.

## The two Facebook sources that matter

**[andrewliang25/morphe-patches](https://github.com/andrewliang25/morphe-patches)** (GPL-3.0) targets Facebook 577.0.0.50.72. It's the careful one. Facebook renames almost every class on every weekly release, and Andrew's patches never write one of those names down. They find what they need through names Facebook keeps (GraphQL models, log strings, enum names, method shapes) and fail loudly at patch time when an anchor moves, instead of shipping a patch that quietly does nothing. Most of Hushfacebook's patches are ported from here, with fixes, from commit 5db2e57e. The file headers and [provenance.json](../provenance.json) say which ones. It's the only adopted source. The bundle also patches LINE, so the audit only watches its Facebook, shared and extension code.

**[SapitoSucio/FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches)** (GPL-3.0) targets Facebook 573.0.0.37.74 and has the most features, several of them only on its dev branch: feed auto-refresh blocking, story auto-advance, an AI-post filter, Material You colors and image downloads. It also hardcodes dozens of obfuscated class names that only exist in 573, so none of its code carries over to newer builds as it is. We took ideas and anchors from it, not code. The feed filter here is built differently because of one lesson from Froggo's tracker (issues 3, 19 and 22): stacking several guards with labels at the same instruction of the feed method produced VerifyErrors. Hushfacebook hooks that method once and runs every feed rule from a single Java filter.

Three more GPL-3.0 repositories carry Froggo's work for 573. [ArunTS96/FroggoMorphePatches](https://github.com/ArunTS96/FroggoMorphePatches) adds suggested-post filtering and a Following-feed home, spread over several branches. [mben25/morphe-patches](https://github.com/mben25/morphe-patches) carries the clearest current copy of the 11 Facebook patches inside a bundle for many other apps, a Material You resource map, and Froggo's 573 AI filter. Its downloader saves any video by taking over the Save button of Facebook's full-screen player. [anemia004/xD-MorphePatches](https://github.com/anemia004/xD-MorphePatches) is archived with a reworked downloader. [nambara4-debug/Nambara-Facebook-Patches](https://github.com/nambara4-debug/Nambara-Facebook-Patches) holds two ZIP files of download variants and no licence, so it's behavior-only.

## Older and smaller Facebook sources

- [ReVanced](https://gitlab.com/ReVanced/revanced-patches) (GPL-3.0) has two Facebook patches, still pinned to 490.0.0.63.82, and the four Messenger patches most of the Messenger work descends from. Its [GitHub home](https://github.com/ReVanced/revanced-patches) has been blocked by a DMCA notice since March, so GitLab holds it for now.
- [RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced) (GPL-3.0) carries the ReVanced pair for Facebook plus eight Messenger patches.
- [meridianfresco/morphe-meta-patches](https://github.com/meridianfresco/morphe-meta-patches) (GPL-3.0) is a Morphe port of the ReVanced pair.
- [chirag127/morphe-patches](https://github.com/chirag127/morphe-patches) has one Facebook patch, labelled STUB, and [Astronaut10/facebook-morphe-patches](https://github.com/Astronaut10/facebook-morphe-patches) is still the unmodified Morphe template. Both are rejected.

More than twenty other repositories are copies of ReVanced's Facebook and Messenger files, most of them byte for byte and some from older releases. The ledger records them as copies of ReVanced rather than as sources, and so does the audit when it finds another one. Sixteen more are recorded as out of scope: two WebView wrappers around facebook.com, and repositories that only name a Facebook package somewhere, like a launcher list, a localization table, a captured logcat or a build config.

## Messenger

Messenger isn't in this bundle yet, but it's the obvious next target, and the work already exists under GPL-3.0. [rushiranpise/morphe-patches](https://github.com/rushiranpise/morphe-patches) has the largest set (thirteen patches, including screen capture in encrypted chats and turning off media transcoding), De-Vanced has eight, and ReVanced has the four they all started from. The dev branch of [brskt/revanced-patches](https://gitlab.com/brskt/revanced-patches) removes Meta AI from the inbox, notifications and search. Its main branch is ReVanced's, commit for commit. That branch also found that Messenger 564 moved `onCreate` into an obfuscated superclass, which silently switches off every patch that hooks the application class by name.

Two Xposed modules are MIT-licensed and worth reading for a Messenger sibling: [C10udburst/MessengerEx](https://github.com/C10udburst/MessengerEx) and its update [N01-r0/messenger-cleaner-lsposed](https://github.com/N01-r0/messenger-cleaner-lsposed), which also checks a Messenger build's structure before trusting it. [hyowonbernabe/Messenger-Z](https://github.com/hyowonbernabe/Messenger-Z), [Mino260806/MessengerPro](https://github.com/Mino260806/MessengerPro), [pedguedes090/Messenger-Pro](https://github.com/pedguedes090/Messenger-Pro), [NeonOrbit/ChatHeadEnabler](https://github.com/NeonOrbit/ChatHeadEnabler) and [TheRoughy/MessengerPeep](https://github.com/TheRoughy/MessengerPeep) have no licence, so they're behavior-only. Messenger-Z's notes on letting Messenger install beside Facebook are the useful part.

## Facebook Lite

The only Facebook Lite patch we found is on the dev branch of [ShuhaibNC/morphe-patches](https://github.com/ShuhaibNC/morphe-patches): it sends links to your own browser, on any version. [Ashish-Bansal/OneTapVideoDownload](https://github.com/Ashish-Bansal/OneTapVideoDownload) has GPL-3.0 download hooks for Facebook and Lite from 2018, too old to anchor anything today. [BERTO-bid/FBLitePro](https://github.com/BERTO-bid/FBLitePro) hands out a rebuilt Lite APK that extracts cookies, has no licence, and is behavior-only.

## Outside Morphe

The widest map of Facebook's ad and clutter code is in LSPosed modules, not patch sources, and none of it can be ported.

- [Loukious/FacebookAppAdsRemover](https://github.com/Loukious/FacebookAppAdsRemover) covers feed, story, Reels, Marketplace and game ads, plus feed filters and link unwrapping. It has no licence, so we only read its README for ideas and anchors, never its code. [gamer765/FacebookAppAdsRemover-Patched](https://github.com/gamer765/FacebookAppAdsRemover-Patched) writes down Facebook 577's Reels and commercial-break paths, and has no licence either.
- [gnadgnaoh/NexAlloy-XES](https://github.com/gnadgnaoh/NexAlloy-XES) is GPL-3.0 at the repository level and adds hooks for in-stream ad breaks, profile timeline ads, search ads and link cleaning. Its Facebook master patch says it ports every FacebookAppAdsRemover hook, though, and that code has no licence. So its Facebook code is behavior-only like the original, until Loukious licenses it.
- [ByRafaelSystem/FBadsRemoverPro](https://github.com/ByRafaelSystem/FBadsRemoverPro) describes an ad remover and contains only a README and a licence.
- [rushiranpise/MetaPlusXposed](https://github.com/rushiranpise/MetaPlusXposed) unlocks the paid Meta Plus subscription, which we leave out. [FahadBinHussain/glitchdraft](https://github.com/FahadBinHussain/glitchdraft) syncs chat drafts through a server, and [ssyss501/XposedBase](https://github.com/ssyss501/XposedBase) is a starter project with one Facebook hook.

## Where Hushfacebook is listed

The [Morphe Patch Tracker](https://drnx64.github.io/morphe-track-patches/#/bundle/sysadmindoc) and the [Morphe Archive](https://github.com/rushiforai/morphe-archive/tree/main/examplepatches/SysAdminDoc/Hushfacebook) list it. [Awesome Morphe](https://github.com/nvbangg/awesome-morphe) and [Jman's ReVanced Patch Bundles](https://github.com/Jman-Github/ReVanced-Patch-Bundles) didn't list it when we checked on 2026-09-25, and neither did the Morphe community directory. The ledger records each of these with the date it was checked, and a release names the ones that still don't list Hushfacebook.

## What we left out

- An age-verification bypass that one tracker asked for. It gets around a safety control.
- Meta Plus and Meta AI premium unlocks. Those get around a paywall.
- Exporting and importing login sessions. That handles people's credentials.
- Prebuilt patched APKs. Hushfacebook publishes patches only, never Meta's app.

## What's next

The gaps people ask about most, roughly in order: blocking the feed's auto-refresh, turning off story auto-advance, hiding Reels entry points, and Marketplace, search and profile ads. Every one of them exists in at least one of the sources above. Where that source is adopted or a candidate, a new patch can start from its code once the ledger's checks pass. Where it's behavior-only, the patch starts from Facebook's own code instead. Stripping tracking parameters from shared links was on this list until Sanitize sharing links, and so were the Stories tray and People You May Know until Hide Stories tray and the new switches of Hide suggested and promoted posts. All three were written here from Facebook's own code. The "Suggested for you" switch reads Facebook's recommendation flag to hide posts from accounts you don't follow or groups you haven't joined. Hiding posts Facebook detected as AI, which Froggo, Arun and mben do for 573, is Hide AI-detected posts here. Froggo's 573 member names don't exist in 577 or 580, so it finds the same flag again through the schema keys Facebook's own AI label reads. Saving ordinary feed and Watch videos, asked for in [De-Vanced #148](https://github.com/RookieEnough/De-Vanced/issues/148), came off the list too. Download any video adds its own item to a post's menu and leaves Facebook's Save and Download rows as they are, where the 573 forks took over Facebook's Save.

If you know of a Facebook patch source we missed, open an issue with a link.
