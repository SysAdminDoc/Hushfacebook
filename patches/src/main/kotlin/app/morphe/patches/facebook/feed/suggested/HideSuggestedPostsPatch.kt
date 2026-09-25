/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesuggested/HideSuggestedPostsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the instance-of chain moved into the extension's
 * feed filter, behind the shared feed hook. The patch still refuses a build that carries none of
 * the unit classes, so a rename fails at patch time instead of filtering nothing.
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.settings.settingsPatch

/**
 * Units Facebook injects into the feed that are not paid ads. All keep their real names through
 * Redex. The extension's `FeedFilter.SUGGESTED_UNITS` holds the same list, and a test holds the
 * two to each other.
 */
internal val SUGGESTED_FEED_UNITS = listOf(
    // "Pages you may like" and its variants.
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayLikeFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPaginatedPagesYouMayLikeFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLCreativePagesYouMayLikeFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPYMLWithLargeImageFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayFollowFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPagesYouMayAdvertiseFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLPymgfFeedUnit;",
    // Facebook's own in-feed upsell nags.
    "Lcom/facebook/graphql/model/GraphQLQuickPromotionFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLQuickPromotionNativeTemplateFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLEndOfFeedUpsellCustomNTFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLExploreFeedUpsellNTUnit;",
    "Lcom/facebook/graphql/model/GraphQLGreetingCardPromotionFeedUnit;",
    // In-feed prompts and experiment slots.
    "Lcom/facebook/graphql/model/GraphQLStoryGallerySurveyFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLBusinessPageReviewFeedUnit;",
    "Lcom/facebook/graphql/model/GraphQLHoldoutAdFeedUnit;",
)

@Suppress("unused")
val hideSuggestedPostsPatch = bytecodePatch(
    name = "Hide suggested and promoted posts",
    description = "Removes posts that Facebook adds to the feed, such as \"Pages you may like\" and its own " +
        "upsells. In-feed surveys go too.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        if (SUGGESTED_FEED_UNITS.none { classDefByOrNull(it) != null }) {
            throw PatchException(
                "None of the suggested feed unit classes is in this APK; the model package was renamed or moved",
            )
        }
        enableStatus("suggestedPosts")
    }
}
