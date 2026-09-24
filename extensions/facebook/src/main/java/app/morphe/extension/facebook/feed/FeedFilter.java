/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The rules follow the feed guards of https://github.com/andrewliang25/morphe-patches (GPL-3.0,
 * Andrew Liang: the SPONSORED category and the suggested unit list) and of
 * https://github.com/SapitoSucio/FroggoMorphePatches (GPL-3.0: the PROMOTION category).
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;

/**
 * What the news feed guard asks about each edge before Facebook adds it to the feed.
 *
 * <p>The guard runs at the start of {@code FeedUnitCollectionManager.addNewEdgeToCollection},
 * the one funnel every news feed edge passes through. Answering true there makes the method
 * return false, which Facebook already handles: it logs "Edge not added to FUC" and carries on,
 * so a hidden post leaves no gap and logs no impression.
 *
 * <p>Every feed filter shares this one guard. Two patches each prepending their own guard to the
 * same method is how FroggoMorphePatches ended up with branch targets that pointed into the wrong
 * code, so the patches here only switch their rule on and the rules live in Java.
 */
@SuppressWarnings("unused")
public final class FeedFilter {
    private static final String SPONSORED = "SPONSORED";
    private static final String PROMOTION = "PROMOTION";

    /**
     * Units Facebook injects into the feed that are not posts from anyone you follow. Every one
     * keeps its real name through Meta's obfuscator, so the check needs no obfuscated identifier.
     *
     * <p>Left out on purpose: {@code GraphQLFriendsLocationsFeedUnit}, a real feature, and People
     * You May Know, whose container unit has no kept name, so dropping the item types it holds
     * would not remove the row.
     */
    private static final String[] SUGGESTED_UNITS = {
            // "Pages you may like" and its variants.
            "com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit",
            "com.facebook.graphql.model.GraphQLPaginatedPagesYouMayLikeFeedUnit",
            "com.facebook.graphql.model.GraphQLCreativePagesYouMayLikeFeedUnit",
            "com.facebook.graphql.model.GraphQLPYMLWithLargeImageFeedUnit",
            "com.facebook.graphql.model.GraphQLPagesYouMayFollowFeedUnit",
            "com.facebook.graphql.model.GraphQLPagesYouMayAdvertiseFeedUnit",
            "com.facebook.graphql.model.GraphQLPymgfFeedUnit",
            // Facebook's own in-feed upsell nags.
            "com.facebook.graphql.model.GraphQLQuickPromotionFeedUnit",
            "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit",
            "com.facebook.graphql.model.GraphQLEndOfFeedUpsellCustomNTFeedUnit",
            "com.facebook.graphql.model.GraphQLExploreFeedUpsellNTUnit",
            "com.facebook.graphql.model.GraphQLGreetingCardPromotionFeedUnit",
            // In-feed prompts and experiment slots.
            "com.facebook.graphql.model.GraphQLStoryGallerySurveyFeedUnit",
            "com.facebook.graphql.model.GraphQLBusinessPageReviewFeedUnit",
            "com.facebook.graphql.model.GraphQLHoldoutAdFeedUnit",
    };

    /** The unit classes this build carries, looked up once. A class Facebook dropped is skipped. */
    private static volatile Class<?>[] suggestedClasses;

    private FeedFilter() {
    }

    /**
     * Injection point, at the start of the feed funnel. Whether the edge with this story category
     * and this feed unit stays out of the news feed. Never throws: false leaves the edge to
     * Facebook.
     *
     * @param category the edge's {@code GraphQLFeedStoryCategory} constant.
     * @param feedUnit the edge's feed unit, inflated if the tree had not built it yet.
     */
    public static boolean hideEdge(Object category, Object feedUnit) {
        try {
            if (SettingsStatus.sponsoredPosts() && hiddenCategory(category)) return true;
            return SettingsStatus.suggestedPosts()
                    && Settings.HIDE_SUGGESTED_POSTS.get()
                    && isSuggested(feedUnit);
        } catch (Throwable failure) {
            Logger.printException(() -> "Feed filter: could not judge an edge", failure);
            return false;
        }
    }

    /**
     * Whether a story category is one the switches hide. The category arrives as the enum
     * constant itself: the obfuscator renames the enum's fields on every release, but
     * {@link Enum#name()} answers the literal the constant was built with.
     */
    static boolean hiddenCategory(Object category) {
        if (!(category instanceof Enum)) return false;
        String name = ((Enum<?>) category).name();
        if (SPONSORED.equals(name)) return Settings.HIDE_SPONSORED_POSTS.get();
        if (PROMOTION.equals(name)) return Settings.HIDE_PROMOTED_POSTS.get();
        return false;
    }

    /** Whether the feed unit is one of the injected suggestion or upsell units. */
    static boolean isSuggested(Object feedUnit) {
        if (feedUnit == null) return false;
        for (Class<?> type : suggestedClasses()) {
            if (type.isInstance(feedUnit)) return true;
        }
        return false;
    }

    private static Class<?>[] suggestedClasses() {
        Class<?>[] found = suggestedClasses;
        if (found != null) return found;
        java.util.List<Class<?>> classes = new java.util.ArrayList<>(SUGGESTED_UNITS.length);
        ClassLoader loader = FeedFilter.class.getClassLoader();
        for (String name : SUGGESTED_UNITS) {
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (Throwable missing) {
                // This build has no such unit. Nothing to hide for it.
            }
        }
        found = classes.toArray(new Class<?>[0]);
        suggestedClasses = found;
        if (found.length == 0) {
            Logger.printInfo(() -> "Feed filter: none of the suggested unit classes is in this build");
        }
        return found;
    }

    /** Injection point. Whether the story viewer's ad bucket sources contribute nothing. */
    public static boolean hideSponsoredStories() {
        try {
            return Settings.HIDE_SPONSORED_STORIES.get();
        } catch (Throwable failure) {
            Logger.printException(() -> "Story filter: could not read its switch", failure);
            return false;
        }
    }
}
