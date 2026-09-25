/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.modelutil.BaseModelWithTree;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The "Suggested for you" rule in the shared feed guard: it hides a story only when Facebook's own
 * recommendation flag reads a definite true, keeps every other post with a reason in the report,
 * counts on a route of its own, and reads nothing of a post while its switch is off or
 * Hushfacebook is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RecommendationRuleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC, SPONSORED, ENGAGEMENT }

    /** Stands in for the accessor the patch fills in, and counts how often the guard asked it. */
    private static final class Answer implements StoryFlag.Accessor {
        private final Object context;
        int calls;

        Answer(Object context) {
            this.context = context;
        }

        @Override
        public Object model(Object story) {
            calls++;
            if (context instanceof RuntimeException) throw (RuntimeException) context;
            return context;
        }
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_SUGGESTED_FOR_YOU.resetToDefault();
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.resetToDefault();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        StoryFlag.cachedMembers = null;
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    /** The guard with the suggested patch in and the given recommendation accessor. */
    private static boolean guard(Object category, Object unit, StoryFlag.Accessor accessor) {
        return FeedFilter.hideEdge(category, unit, true, true, accessor, false, GenAiLabel.PATCHED);
    }

    private static String counters() {
        return String.join("\n", FeedFilterCounters.report());
    }

    /** The line the recommendation route wrote, or null when the rule never read a post. */
    private static String line() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.RECOMMENDATION_ROUTE + ": ")) return line;
        }
        return null;
    }

    /**
     * The keys are the ones Facebook 573, 577 and 580 load: the flag's String.hashCode() and the MD5
     * tag of StoryRecommendationContext. The patch's own test pins the same numbers.
     */
    @Test
    public void theKeysAreTheOnesFacebookUses() {
        assertEquals(0x6ca2c94b, RecommendationLabel.FLAG.flagKey);
        assertEquals(0xcc3b6841, RecommendationLabel.FLAG.modelTypeTag);
        assertEquals("the field GraphQLStory holds the context under", 0xd4bc5329,
                RecommendationLabel.CONTEXT_FIELD.hashCode());
        assertEquals(FamilyNames.SUGGESTED_POSTS, RecommendationLabel.FLAG.family);
    }

    /** The switch it runs under, on by default like the rest of Hide suggested and promoted posts. */
    @Test
    public void itRunsUnderTheSuggestedForYouSwitch() {
        assertTrue(Settings.HIDE_SUGGESTED_FOR_YOU.defaultValue);
        assertTrue(Settings.HIDE_SUGGESTED_FOR_YOU.get());
    }

    /** The one case that hides: a story whose recommendation flag is true, with the switch on. */
    @Test
    public void aStoryFacebookRecommendsIsHidden() {
        BaseModelWithTree context = FeedGuardForTests.recommendationContext(true);

        assertTrue(guard(Category.ENGAGEMENT, new GraphQLStory(), new Answer(context)));
        assertEquals("the flag was read once", 1, context.reads);
        assertTrue(counters(), counters().contains(FeedFilter.FEED_ROUTE + ": 1 lists, 1 items, 1 removed. "
                + "Last reason: is_in_feed_recommendation_story"));
        assertEquals(FeedFilter.RECOMMENDATION_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: flag true. "
                + "Removed: flag true 1. Kinds: flag true 1", line());
    }

    /**
     * The mutation control for the one above: a story from someone followed reads false, and every
     * way the metadata can be missing or unclear keeps the post and records why.
     */
    @Test
    public void falseNullMissingOrAmbiguousMetadataKeepsThePostAndSaysWhy() {
        GraphQLStory story = new GraphQLStory();

        assertFalse("false", guard(Category.ENGAGEMENT, story, new Answer(FeedGuardForTests.recommendationContext(false))));
        assertFalse("a context that doesn't hold the flag reads false",
                guard(Category.ENGAGEMENT, story, new Answer(new BaseModelWithTree(RecommendationLabel.FLAG.modelTypeTag))));
        assertFalse("null", guard(Category.ENGAGEMENT, story, new Answer(null)));
        assertFalse("not a story", guard(Category.ENGAGEMENT, new Object(), new Answer(null)));
        assertFalse("no feed unit", guard(Category.ENGAGEMENT, null, new Answer(null)));
        assertFalse("the accessor was never filled in", guard(Category.ENGAGEMENT, story, RecommendationLabel.PATCHED));
        BaseModelWithTree otherType = new BaseModelWithTree(GenAiLabel.DETECTED_INFO_TYPE_TAG)
                .with(RecommendationLabel.RECOMMENDED_FLAG, true);
        assertFalse("a model of another GraphQL type, even with a true flag at the same key",
                guard(Category.ENGAGEMENT, story, new Answer(otherType)));
        assertEquals("a model of another type isn't read", 0, otherType.reads);
        assertFalse("a context that isn't a tree model", guard(Category.ENGAGEMENT, story, new Answer(new Object())));
        assertFalse("an accessor that throws", guard(Category.ENGAGEMENT, story, new Answer(new IllegalStateException())));

        assertEquals(FeedFilter.RECOMMENDATION_ROUTE + ": 9 lists, 9 items, 0 removed. Kinds: flag false 2, "
                        + "accessor not patched 1, ambiguous: info not a tree model 1, ambiguous: info of another type 1, "
                        + "no feed unit 1, no recommendation context 1, not a story 1, read failed 1",
                line());
        assertTrue(counters(), counters().contains(FeedFilter.FEED_ROUTE + ": 9 lists, 9 items, 0 removed"));

        List<String> misses = HookStatus.missing(FamilyNames.SUGGESTED_POSTS);
        assertEquals(Arrays.asList(
                "method com.facebook.graphql.model.GraphQLStory#the recommendation_context accessor",
                "a working 'recommendation context accessor' hook (it threw java.lang.IllegalStateException)"), misses);
    }

    /** Off, the rule doesn't ask for the story's recommendation context at all. */
    @Test
    public void switchedOffTheRuleReadsNothing() {
        Settings.HIDE_SUGGESTED_FOR_YOU.save(false);
        BaseModelWithTree context = FeedGuardForTests.recommendationContext(true);
        Answer answer = new Answer(context);

        assertFalse(guard(Category.ENGAGEMENT, new GraphQLStory(), answer));
        assertEquals("the accessor was asked with the switch off", 0, answer.calls);
        assertEquals(0, context.reads);
        assertNull("the rule counted a post with its switch off", line());
    }

    @Test
    public void pausedTheRuleReadsNothing() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            Answer answer = new Answer(FeedGuardForTests.recommendationContext(true));
            assertFalse(why + " hid a post", guard(Category.ENGAGEMENT, new GraphQLStory(), answer));
            assertEquals(why + " asked the accessor", 0, answer.calls);
        }
        assertNull(line());

        PauseForTests.resume();
        assertTrue("the rule didn't come back after the pause",
                guard(Category.ENGAGEMENT, new GraphQLStory(), new Answer(FeedGuardForTests.recommendationContext(true))));
    }

    /** Without the suggested patch the rule doesn't run, and the public guard is that. */
    @Test
    public void anUnpatchedBuildHidesNothing() {
        Answer answer = new Answer(FeedGuardForTests.recommendationContext(true));
        assertFalse(FeedFilter.hideEdge(Category.ENGAGEMENT, new GraphQLStory(), true, false, answer, false,
                GenAiLabel.PATCHED));
        assertEquals(0, answer.calls);
        assertFalse(FeedFilter.hideEdge(Category.ENGAGEMENT, new GraphQLStory()));
        assertNull(line());
    }

    /**
     * A post an earlier rule hid isn't read again, and doesn't count on the recommendation route.
     * A suggested unit isn't a story, so the rule counts it as one and leaves it to its own rule.
     */
    @Test
    public void anEarlierRuleWinsAndTheRuleOnlyCountsWhatItRead() {
        Answer answer = new Answer(FeedGuardForTests.recommendationContext(true));
        assertTrue(guard(Category.SPONSORED, new GraphQLStory(), answer));
        assertTrue(guard(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), answer));
        assertEquals(0, answer.calls);
        assertNull(line());

        Settings.HIDE_SUGGESTED_POSTS.save(false);
        assertFalse(guard(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), answer));
        assertEquals(FeedFilter.RECOMMENDATION_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: not a story 1", line());
    }

    /**
     * With debug logging on, each edge's line says what the recommendation flag reads, to pair a
     * report with the posts on screen: true, false, or none when there's nothing to read. It names
     * no post and no account. With the suggested patch out, the flag isn't read at all.
     */
    @Test
    public void theDebugLineSaysWhatTheFlagReads() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        try {
            // Off, only the debug line reads the flag.
            Settings.HIDE_SUGGESTED_FOR_YOU.save(false);
            guard(Category.ENGAGEMENT, new GraphQLStory(), new Answer(FeedGuardForTests.recommendationContext(true)));
            guard(Category.ORGANIC, new GraphQLStory(), new Answer(FeedGuardForTests.recommendationContext(false)));
            guard(Category.ORGANIC, new GraphQLStory(), new Answer(null));
            Answer unpatched = new Answer(FeedGuardForTests.recommendationContext(true));
            FeedFilter.hideEdge(Category.SPONSORED, new GraphQLStory(), false, false, unpatched, false, GenAiLabel.PATCHED);

            String log = LogBufferManager.buildExportText();
            assertTrue(log, log.contains("Feed edge: ENGAGEMENT null ifr=true"));
            assertTrue(log, log.contains("Feed edge: ORGANIC null ifr=false"));
            assertTrue(log, log.contains("Feed edge: ORGANIC null ifr=none"));
            assertTrue(log, log.contains("Feed edge: SPONSORED null ifr=none"));
            assertEquals("the flag was read with the suggested patch out", 0, unpatched.calls);
            assertNull("the debug line counted as a read of the rule", line());
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
        }
    }

    /** The members the flag is read through go into the suggested family's Hook status row. */
    @Test
    public void theReportSaysWhetherTheReaderFoundWhatItNeeds() {
        guard(Category.ENGAGEMENT, new GraphQLStory(), new Answer(null));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.SUGGESTED_POSTS + ": invoked 1, 4 found, 0 missing"));

        HookStatus.clear();
        FeedFilterCounters.clear();
        StoryFlag.Members complete = StoryFlag.Members.lookUp(StoryFlag.class.getClassLoader());
        StoryFlag.cachedMembers = new StoryFlag.Members(complete.story, complete.treeModel, complete.cachedBoolean, null);
        assertFalse(guard(Category.ENGAGEMENT, new GraphQLStory(), new Answer(FeedGuardForTests.recommendationContext(true))));
        report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.SUGGESTED_POSTS + ": invoked 1, 3 found, 1 missing. "
                + "First missing: field com.facebook.graphservice.tree.TreeJNI#mTypeTag"));
        assertEquals(FeedFilter.RECOMMENDATION_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: reader missing 1", line());
    }
}
