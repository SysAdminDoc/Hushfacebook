/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesuggested/HideSuggestedPostsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the instance-of chain moved into the extension's
 * feed filter, behind the shared feed hook. The patch still refuses a build that carries none of
 * the unit classes, so a rename fails at patch time instead of filtering nothing. It also finds the
 * story's recommendation flag, held to Facebook's own filter, and the People you may know type
 * name, which two more switches read.
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.HIDE_RECOMMENDATIONS_VALIDATOR
import app.morphe.patches.facebook.feed.RECOMMENDATION_CONTEXT_FIELD
import app.morphe.patches.facebook.feed.RECOMMENDATION_CONTEXT_TYPE
import app.morphe.patches.facebook.feed.RECOMMENDED_FLAG
import app.morphe.patches.facebook.feed.edgePredicateCalls
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.feed.readsRecommendedFlag
import app.morphe.patches.facebook.feed.recommendationContextAccessors
import app.morphe.patches.facebook.feed.requireFeedTypeName
import app.morphe.patches.facebook.feed.requireStoryFlagReaders
import app.morphe.patches.facebook.feed.resolveStatic
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
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

/**
 * The type name the "People you may know" row answers. The extension's
 * `FeedFilter.PEOPLE_YOU_MAY_KNOW_TYPE` matches it.
 */
internal const val PEOPLE_YOU_MAY_KNOW_TYPE = "PaginatedPeopleYouMayKnowFeedUnit"

/** The extension class that reads the recommendation flag, and its accessor this patch fills in. */
internal const val RECOMMENDATION_LABEL = "$EXTENSION_PACKAGE/feed/RecommendationLabel;"
internal const val RECOMMENDATION_CONTEXT_STUB = "recommendationContext"

@Suppress("unused")
val hideSuggestedPostsPatch = bytecodePatch(
    name = "Hide suggested and promoted posts",
    description = "Removes what Facebook adds to the feed besides ads: \"Suggested for you\" posts, \"People " +
        "you may know\", \"Pages you may like\" and its own upsells. In-feed surveys go too. Each kind has " +
        "its own switch.",
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
        requireFeedTypeName(PEOPLE_YOU_MAY_KNOW_TYPE)

        // A "Suggested for you" post is an ordinary story on the wire (ENGAGEMENT, like a friend's),
        // so the rule reads the story's recommendation flag through GraphQLStory's accessor.
        val accessors = recommendationContextAccessors(classDefBy(GRAPHQL_STORY))
        val accessor = accessors.singleOrNull() ?: throw PatchException(
            "GraphQLStory has ${accessors.size} accessors of $RECOMMENDATION_CONTEXT_FIELD as " +
                "$RECOMMENDATION_CONTEXT_TYPE, expected one: ${accessors.joinToString { it.name }}",
        )

        // Facebook's own "hide suggested posts" filter reads the flag through that accessor. If it
        // stops doing that, the flag may have moved or changed meaning, and the rule stops here.
        val validators = classDefByStrings(HIDE_RECOMMENDATIONS_VALIDATOR, StringComparisonType.EQUALS)
            .flatMap { methodsHolding(it, HIDE_RECOMMENDATIONS_VALIDATOR) }
        if (validators.isEmpty()) {
            throw PatchException("No method holds \"$HIDE_RECOMMENDATIONS_VALIDATOR\" any more")
        }
        val predicates = validators.flatMap(::edgePredicateCalls).mapNotNull { call ->
            classDefByOrNull(call.definingClass)?.let { resolveStatic(it, call) }
        }
        if (predicates.none { readsRecommendedFlag(it, accessor) }) {
            throw PatchException(
                "Facebook's own recommendation filter no longer reads $RECOMMENDED_FLAG through " +
                    "GraphQLStory.${accessor.name}()",
            )
        }

        // The extension reads the flag and the model's type tag through these, by reflection.
        requireStoryFlagReaders()
        fillStoryModelStub(RECOMMENDATION_LABEL, RECOMMENDATION_CONTEXT_STUB, accessor)
        enableStatus("suggestedPosts")
    }
}
