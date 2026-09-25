/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;

/**
 * Whether a feed unit is a post Facebook recommends rather than one from someone you follow:
 * {@code recommendation_context.is_in_feed_recommendation_story} on a GraphQLStory.
 *
 * <p>It is the flag Facebook's own "hide suggested posts" filter reads, which drops every edge
 * carrying it for a day after someone asks. The Follow and Join buttons in a post's header read it
 * too. The story category can't tell these posts apart: on a signed-in feed they arrive as
 * ENGAGEMENT stories, like posts from friends. A friend's share of a page's post keeps the friend's
 * own story, which isn't recommended, so it stays.
 *
 * <p>Only a definite true hides anything (see {@link StoryFlag}).
 */
public final class RecommendationLabel {
    /** The GraphQL names. See the patch's Recommendations.kt for how the keys were read. */
    static final String CONTEXT_FIELD = "recommendation_context";
    static final String CONTEXT_TYPE = "StoryRecommendationContext";
    static final String RECOMMENDED_FLAG = "is_in_feed_recommendation_story";

    static final StoryFlag FLAG = new StoryFlag(FamilyNames.SUGGESTED_POSTS, "recommendation",
            "recommendation context", CONTEXT_FIELD, CONTEXT_TYPE, RECOMMENDED_FLAG);

    static final StoryFlag.Accessor PATCHED = RecommendationLabel::recommendationContext;

    private RecommendationLabel() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code recommendation_context} model, or
     * null when it has none. The patch replaces this body with a call to GraphQLStory's accessor,
     * whose name changes every build. Only a GraphQLStory may be passed.
     */
    public static Object recommendationContext(Object story) {
        return StoryFlag.NOT_PATCHED;
    }
}
