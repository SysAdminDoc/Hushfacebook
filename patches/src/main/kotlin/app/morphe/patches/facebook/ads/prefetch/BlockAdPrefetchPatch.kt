/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/blockadprefetch/BlockAdPrefetchPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.prefetch

import app.morphe.patches.facebook.shared.neuterVoidMethods
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.misc.settings.settingsPatch

/** Background ad fetchers. Each keeps its real `com.facebook` name; each void method enqueues. */
private val AD_PREFETCH_SCHEDULERS = listOf(
    // News feed ads channel — the periodic WorkManager prefetch and its emerging-surface twin.
    "Lcom/facebook/feed/push/adschannelbackgroundprefetch/FeedAdsChannelBackgroundPrefetchInitializerAppJob;",
    "Lcom/facebook/feed/push/adschannelemergingsurfaceprefetch/FeedAdsChannelEmergingSurfacePrefetchInitializerAppJob;",
    // Reels and Watch.
    "Lcom/facebook/video/videohome/prefetching/ads/background/ReelsAdsBackgroundPrefetchAppJob;",
    // Stories viewer ads.
    "Lcom/facebook/stories/features/ads/prefetch/StoryViewerAdsPrefetchAppInitializationController;",
    "Lcom/facebook/stories/features/ads/prefetch/StoryViewerAdsPrefetchController;",
    // Revalidation of the cached ad payloads.
    "Lcom/facebook/addelivery/deliveryvalidation/cachedadsvalidator/NewsFeedAdCacheSyncInitializerAppJob;",
    // The on-device ad-ranking ML model: downloads the model and keeps it warm.
    "Lcom/facebook/feed/ads/mlranker/MlRankerAppJob;",
)

@Suppress("unused")
val blockAdPrefetchPatch = bytecodePatch(
    name = "Block background ad prefetch",
    description = "Stops Facebook downloading ads and its ad model in the background. That " +
        "saves data and battery. The ads don't take up storage either.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    dependsOn(facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    // Block the schedulers, not the workers: two of the three workers no longer override doWork,
    // and androidx.work.Worker's own is renamed and returns an obfuscated Result with no safe
    // value to synthesise.
    //
    // WorkManager persists its schedule, so a device that already ran an unpatched build keeps
    // work enqueued before patching until its app data is cleared.
    execute {
        val neutered = AD_PREFETCH_SCHEDULERS.sumOf { neuterVoidMethods(it) }

        check(neutered > 0) {
            "No ad prefetch schedulers found; the com.facebook.feed.push / videohome.prefetching " +
                "packages were renamed or removed"
        }

        enableStatus("adPrefetch")
    }
}
