# Where the Facebook patches come from

Hushfacebook started on 2026-09-24 as an attempt to put every working Facebook patch for Morphe in one place. Before writing anything, we went through every public patch source we could find that touches Facebook (`com.facebook.katana`), Messenger (`com.facebook.orca`) or Facebook Lite (`com.facebook.lite`). This page is what we found and what we took from each.

The short version: two sources are actively patching current Facebook builds, a handful of forks and copies sit around them, and Messenger has a bigger patch ecosystem than Facebook itself. Morphe's official bundle ships nothing for any of the three apps.

## The two Facebook sources that matter

**[andrewliang25/morphe-patches](https://github.com/andrewliang25/morphe-patches)** (GPL-3.0) targets Facebook 577.0.0.50.72. It's the careful one. Facebook renames almost every class on every weekly release, and Andrew's patches never write one of those names down. They find what they need through names Facebook keeps (GraphQL models, log strings, enum names, method shapes) and fail loudly at patch time when an anchor moves, instead of shipping a patch that quietly does nothing. Most of Hushfacebook's patches are ported from here, with fixes. The file headers and [provenance.json](../provenance.json) say which ones and from which commit.

**[SapitoSucio/FroggoMorphePatches](https://github.com/SapitoSucio/FroggoMorphePatches)** (GPL-3.0) targets Facebook 573.0.0.37.74 and has the most features, several of them still on its dev branch: feed auto-refresh blocking, story auto-advance, an AI-post filter, Material You colors and image downloads. It also hardcodes dozens of obfuscated class names that only exist in 573, so none of its code carries over to newer builds. We took ideas and anchors from it, not code. The feed filter here is built differently because of one lesson from Froggo's tracker (issues 3, 19 and 22): stacking several guards with labels at the same instruction of the feed method produced VerifyErrors. Hushfacebook hooks that method once and runs every feed rule from a single Java filter.

Three forks of Froggo add their own work for 573: [ArunTS96/FroggoMorphePatches](https://github.com/ArunTS96/FroggoMorphePatches) (suggested posts, a Following-feed home), [anemia004/xD-MorphePatches](https://github.com/anemia004/xD-MorphePatches) (a reworked downloader) and [nambara4-debug/Nambara-Facebook-Patches](https://github.com/nambara4-debug/Nambara-Facebook-Patches) (download variants).

## Older and smaller Facebook sources

- [ReVanced](https://gitlab.com/ReVanced/revanced-patches) (GPL-3.0) has two Facebook patches, still pinned to 490.0.0.63.82, and the four Messenger patches most of the Messenger work descends from.
- [RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced) (GPL-3.0) carries the ReVanced pair for Facebook plus eight Messenger patches.
- [meridianfresco/morphe-meta-patches](https://github.com/meridianfresco/morphe-meta-patches) (GPL-3.0) has two Facebook patches for 556.
- [ShuhaibNC/morphe-patches](https://github.com/ShuhaibNC/morphe-patches) (GPL-3.0) has the only Facebook Lite patch we found anywhere, which sends links to your own browser.

About a dozen other repositories turned out to be byte-for-byte copies of the ReVanced or De-Vanced patches, build scripts that consume the bundles above, or empty templates. None of them has Facebook code of its own.

## Messenger

Messenger isn't in this bundle yet, but it's the obvious next target, and the work already exists under GPL-3.0: [rushiranpise/morphe-patches](https://github.com/rushiranpise/morphe-patches) has the largest set (thirteen patches, including screen capture in encrypted chats and turning off media transcoding), De-Vanced has eight, and an unmerged branch of [brskt/revanced-patches](https://gitlab.com/brskt/revanced-patches) removes Meta AI from the inbox, notifications and search. That branch also found that Messenger 564 moved `onCreate` into an obfuscated superclass, which silently switches off every patch that hooks the application class by name.

## Outside Morphe

The widest map of Facebook's ad and clutter code is in LSPosed modules, not patch sources.

- [Loukious/FacebookAppAdsRemover](https://github.com/Loukious/FacebookAppAdsRemover) covers feed, story, Reels, Marketplace and game ads, plus feed filters, a keyword filter and link unwrapping. It has no license, so we only read its README for ideas and anchors, never its code.
- [gnadgnaoh/NexAlloy-XES](https://github.com/gnadgnaoh/NexAlloy-XES) (GPL-3.0) ports those hooks and adds its own for in-stream video ad breaks, profile timeline ads and search ads. Its code can be ported with credit.

## What we left out

- An age-verification bypass that one tracker asked for. It gets around a safety control.
- Meta Plus and Meta AI premium unlocks. Those get around a paywall.
- Exporting and importing login sessions. That handles people's credentials.
- Prebuilt patched APKs. Hushfacebook publishes patches only, never Meta's app.

## What's next

The gaps people ask about most, roughly in order: blocking the feed's auto-refresh, hiding posts from accounts you don't follow, turning off story auto-advance, hiding Reels entry points, the Stories tray and People You May Know, Marketplace, search and profile ads, and stripping tracking parameters from shared links. Every one of them exists in at least one of the sources above, which is where a new patch here starts.

If you know of a Facebook patch source we missed, open an issue with a link.
