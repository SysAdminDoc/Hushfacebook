/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredposts/HideSponsoredPostsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the guard moved into the shared feed hook, and
 * PROMOTION is dropped beside SPONSORED as https://github.com/SapitoSucio/FroggoMorphePatches
 * (GPL-3.0) does.
 */
package app.morphe.patches.facebook.ads.sponsoredposts

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.settings.settingsPatch

@Suppress("unused")
val hideSponsoredPostsPatch = bytecodePatch(
    name = "Hide sponsored posts",
    description = "Removes sponsored and promoted posts from the news feed, with no gap left behind.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Rejecting at the collection boundary means nothing downstream sees the ad: no placeholder,
    // no impression. The edge already carries the server's story category, so this costs one
    // cached-enum lookup rather than a walk of the story tree for sponsored_data.
    execute {
        enableStatus("sponsoredPosts")
    }
}
