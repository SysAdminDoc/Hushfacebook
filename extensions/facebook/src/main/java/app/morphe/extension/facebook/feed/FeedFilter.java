/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The rules follow the feed guards of https://github.com/andrewliang25/morphe-patches (GPL-3.0,
 * Andrew Liang: the SPONSORED category and the suggested unit list) and of
 * https://github.com/SapitoSucio/FroggoMorphePatches (GPL-3.0: the PROMOTION category, and the
 * AI filter the GenAI rule follows).
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.facebook.settings.SettingsStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;

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
     * The GraphQL type the "People you may know" row answers {@code getTypeName()} with. Its class
     * is renamed on every release, but the type name is a literal in the method, in 577 and 580.
     */
    static final String PEOPLE_YOU_MAY_KNOW_TYPE = "PaginatedPeopleYouMayKnowFeedUnit";

    /** The type name of the row of stories at the top of the feed, found the same way. */
    static final String STORIES_TRAY_TYPE = "StoriesTrayFeedUnit";

    /** The diagnostic counter routes. Each news feed edge counts as a list of one post. */
    static final String FEED_ROUTE = "News feed posts";
    static final String STORY_ROUTE = "Story ad sources";
    /**
     * The edges the GenAI rule read, counted only while its switch is on. Its kinds say what each
     * read found, so a report shows why the posts it kept were kept.
     */
    static final String AI_ROUTE = "GenAI flag";
    /**
     * The stories the "Suggested for you" rule read, counted only while its switch is on, with what
     * each read of Facebook's recommendation flag found as the kind.
     */
    static final String RECOMMENDATION_ROUTE = "Recommendation flag";

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
        return hideEdge(category, feedUnit, SettingsStatus.sponsoredPosts(), SettingsStatus.suggestedPosts(),
                RecommendationLabel.PATCHED, SettingsStatus.storiesTray(), SettingsStatus.aiDetectedPosts(),
                GenAiLabel.PATCHED);
    }

    /** The guard with the sponsored and suggested patch-time flags passed in, and neither the tray nor GenAI. */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, RecommendationLabel.PATCHED, false,
                false, GenAiLabel.PATCHED);
    }

    /** The guard with the Stories tray's flag too, and no GenAI rule. */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            boolean storiesTrayPatched) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, RecommendationLabel.PATCHED,
                storiesTrayPatched, false, GenAiLabel.PATCHED);
    }

    /** The guard with the GenAI rule's flag and accessor, and no Stories tray. */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            boolean aiPatched, StoryFlag.Accessor aiAccessor) {
        return hideEdge(category, feedUnit, sponsoredPatched, suggestedPatched, RecommendationLabel.PATCHED, false,
                aiPatched, aiAccessor);
    }

    /**
     * The guard, with the four patch-time flags passed in so a test can stand in for the patches,
     * and the recommendation and GenAI accessors passed in so a test can stand in for the stubs the
     * patches fill in.
     *
     * <p>Every edge is counted before any rule runs, and every hidden one records why, so a
     * diagnostic report shows the guard is alive and what it took out without anyone having to
     * turn debug logging on first. The category name is an enum constant and the reason is a
     * kept class name or a GraphQL field name; none of them is content.
     *
     * <p>The two rules built on a story flag, "Suggested for you" and GenAI, read a story only while
     * their switch is on, so with the switch off or Hushfacebook paused they read nothing of the post
     * and Facebook's own path is all that runs.
     */
    static boolean hideEdge(Object category, Object feedUnit, boolean sponsoredPatched, boolean suggestedPatched,
            StoryFlag.Accessor recommendationAccessor, boolean storiesTrayPatched, boolean aiPatched,
            StoryFlag.Accessor aiAccessor) {
        try {
            if (sponsoredPatched) HookStatus.invoked(FamilyNames.SPONSORED_POSTS);
            if (suggestedPatched) {
                HookStatus.invoked(FamilyNames.SUGGESTED_POSTS);
                reportSuggestedClasses();
                RecommendationLabel.FLAG.report();
            }
            if (storiesTrayPatched) HookStatus.invoked(FamilyNames.STORIES_TRAY);
            if (aiPatched) {
                HookStatus.invoked(FamilyNames.AI_DETECTED_POSTS);
                GenAiLabel.FLAG.report();
            }
            FeedFilterCounters.sawList(FEED_ROUTE, 1);
            String categoryName = category instanceof Enum ? ((Enum<?>) category).name() : null;
            FeedFilterCounters.sawKind(FEED_ROUTE, categoryName);
            // What kind of post this is, for a report of what reaches the feed: an enum constant and
            // a GraphQL type name, never the post itself.
            Logger.printDebug(() -> "Feed edge: " + categoryName + " " + typeName(feedUnit));
            // An edge a prefetch adds before the settings are ready stays: no switch can be read yet.
            if (!Utils.settingsReady()) return false;

            String reason = null;
            if (sponsoredPatched && hiddenCategory(category)) {
                reason = categoryName;
            }
            if (reason == null && suggestedPatched) {
                if (Settings.HIDE_SUGGESTED_POSTS.get()) reason = suggestedUnitName(feedUnit);
                if (reason == null && Settings.HIDE_SUGGESTED_FOR_YOU.get()) {
                    reason = flagReason(RecommendationLabel.FLAG, RECOMMENDATION_ROUTE, feedUnit, recommendationAccessor);
                }
                if (reason == null && Settings.HIDE_PEOPLE_YOU_MAY_KNOW.get()
                        && PEOPLE_YOU_MAY_KNOW_TYPE.equals(typeName(feedUnit))) {
                    reason = PEOPLE_YOU_MAY_KNOW_TYPE;
                }
            }
            if (reason == null && storiesTrayPatched && Settings.HIDE_STORIES_TRAY.get()
                    && STORIES_TRAY_TYPE.equals(typeName(feedUnit))) {
                reason = STORIES_TRAY_TYPE;
            }
            if (reason == null && aiPatched && Settings.HIDE_AI_DETECTED_POSTS.get()) {
                reason = flagReason(GenAiLabel.FLAG, AI_ROUTE, feedUnit, aiAccessor);
            }
            if (reason == null) return false;

            FeedFilterCounters.removed(FEED_ROUTE, 1, reason);
            final String hidden = reason;
            Logger.printDebug(() -> "Feed filter: hid a " + hidden + " post");
            return true;
        } catch (Throwable failure) {
            if (sponsoredPatched) HookStatus.threw(FamilyNames.SPONSORED_POSTS, "feed guard", failure);
            if (suggestedPatched) HookStatus.threw(FamilyNames.SUGGESTED_POSTS, "feed guard", failure);
            if (storiesTrayPatched) HookStatus.threw(FamilyNames.STORIES_TRAY, "feed guard", failure);
            if (aiPatched) HookStatus.threw(FamilyNames.AI_DETECTED_POSTS, "feed guard", failure);
            Logger.printException(() -> "Feed filter: could not judge an edge", failure);
            return false;
        }
    }

    /**
     * A rule built on a story flag: the flag's name when it reads a definite true for this unit,
     * otherwise null. Every unit it reads is counted on the rule's own route under what the read
     * found, so a kept post always has a reason in the report.
     */
    private static String flagReason(StoryFlag flag, String route, Object feedUnit, StoryFlag.Accessor accessor) {
        StoryFlag.Outcome outcome = flag.read(feedUnit, accessor);
        String why = flag.reason(outcome);
        FeedFilterCounters.sawList(route, 1);
        FeedFilterCounters.sawKind(route, why);
        if (!outcome.hides) return null;
        FeedFilterCounters.removed(route, 1, why);
        return flag.flag;
    }

    /** Which Hook status row the suggested unit classes were last reported into. */
    private static volatile long reportedGeneration = -1;

    /**
     * The suggested unit classes this build carries, reported once per Hook status row: again
     * after a diagnostic clear, which empties the row this was written into. One class missing is
     * Facebook dropping a unit; all of them missing is a moved model package, and the rule then
     * hides nothing at all.
     */
    private static void reportSuggestedClasses() {
        long generation = HookStatus.generation();
        if (reportedGeneration == generation) return;
        reportedGeneration = generation;
        Class<?>[] found = suggestedClasses();
        for (Class<?> type : found) HookStatus.bound(FamilyNames.SUGGESTED_POSTS, type.getSimpleName());
        if (found.length == 0) {
            HookStatus.missingMember(FamilyNames.SUGGESTED_POSTS, "class", "com.facebook.graphql.model",
                    "any suggested feed unit");
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
        return suggestedUnitName(feedUnit) != null;
    }

    /** The kept name of the suggestion or upsell unit this feed unit is, or null for any other. */
    static String suggestedUnitName(Object feedUnit) {
        if (feedUnit == null) return null;
        for (Class<?> type : suggestedClasses()) {
            if (type.isInstance(feedUnit)) return type.getSimpleName();
        }
        return null;
    }

    /** Each feed unit class's public {@code getTypeName()}, or empty when it has none. */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Optional<java.lang.reflect.Method>>
            TYPE_NAME_METHODS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The GraphQL type name a feed unit answers, or null when it has no {@code getTypeName()} or
     * the call fails. Facebook's generated models all keep that method's name, whatever their own
     * class is renamed to, and it answers a literal. A unit this can't read is kept: the rules
     * built on it fail open.
     */
    static String typeName(Object feedUnit) {
        if (feedUnit == null) return null;
        try {
            java.util.Optional<java.lang.reflect.Method> method = TYPE_NAME_METHODS.computeIfAbsent(
                    feedUnit.getClass(), type -> {
                        try {
                            java.lang.reflect.Method found = type.getMethod("getTypeName");
                            return found.getReturnType() == String.class
                                    ? java.util.Optional.of(found) : java.util.Optional.empty();
                        } catch (NoSuchMethodException none) {
                            return java.util.Optional.empty();
                        }
                    });
            if (!method.isPresent()) return null;
            Object name = method.get().invoke(feedUnit);
            return name instanceof String ? (String) name : null;
        } catch (Throwable failure) {
            return null;
        }
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
            HookStatus.invoked(FamilyNames.SPONSORED_STORIES);
            FeedFilterCounters.sawList(STORY_ROUTE, 1);
            boolean hide = Utils.settingsReady() && Settings.HIDE_SPONSORED_STORIES.get();
            if (hide) FeedFilterCounters.removed(STORY_ROUTE, 1, "ad buckets skipped");
            return hide;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SPONSORED_STORIES, "story ad sources", failure);
            Logger.printException(() -> "Story filter: could not read its switch", failure);
            return false;
        }
    }
}
