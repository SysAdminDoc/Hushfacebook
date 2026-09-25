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

import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * The GenAI rule in the shared feed guard: it hides a post only on a definite true, keeps every
 * other post with a reason in the report, counts on a route of its own, and reads nothing of a post
 * while its switch is off or Hushfacebook is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class GenAiRuleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    enum Category { ORGANIC, SPONSORED }

    /** Stands in for the accessor the patch fills in, and counts how often the guard asked it. */
    private static final class Answer implements StoryFlag.Accessor {
        private final Object info;
        int calls;

        Answer(Object info) {
            this.info = info;
        }

        @Override
        public Object model(Object story) {
            calls++;
            if (info instanceof RuntimeException) throw (RuntimeException) info;
            return info;
        }
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_AI_DETECTED_POSTS.resetToDefault();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        StoryFlag.cachedMembers = null;
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static boolean guard(Object category, Object unit, StoryFlag.Accessor accessor) {
        return FeedFilter.hideEdge(category, unit, true, true, story -> null, true, accessor);
    }

    private static String counters() {
        return String.join("\n", FeedFilterCounters.report());
    }

    /** The line the GenAI route wrote, or null when the rule never ran. */
    private static String aiLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.AI_ROUTE + ": ")) return line;
        }
        return null;
    }

    /**
     * The keys are the ones read from Facebook 573, 577 and 580: the flag's String.hashCode() and
     * the MD5 tag of the info's GraphQL type. The patch's own test pins the same numbers.
     */
    @Test
    public void theKeysAreTheOnesFacebookUses() {
        assertEquals(0x723ea5fe, GenAiLabel.DETECTED_FLAG_KEY);
        assertEquals(0x70da9d19, GenAiLabel.DETECTED_INFO_TYPE_TAG);
        // The self-disclosure model's tag, a different type, must not collide with it.
        assertEquals(0x9213d34e, StoryFlag.typeTag("XFBAIGeneratedSelfDisclosureInfo"));
    }

    @Test
    public void theSwitchStartsOff() {
        assertFalse("the switch has to start off until a signed-in feed has been recorded",
                Settings.HIDE_AI_DETECTED_POSTS.defaultValue);
        assertFalse(Settings.HIDE_AI_DETECTED_POSTS.get());
    }

    /** The one case that hides: a story whose detected-AI flag is true, with the switch on. */
    @Test
    public void aStoryFacebookDetectedAsAiIsHidden() {
        Settings.HIDE_AI_DETECTED_POSTS.save(true);
        BaseModelWithTree info = FeedGuardForTests.detectedInfo(true);

        assertTrue(guard(Category.ORGANIC, new GraphQLStory(), new Answer(info)));
        assertEquals("the flag was read once", 1, info.reads);
        assertTrue(counters(), counters().contains(FeedFilter.FEED_ROUTE + ": 1 lists, 1 items, 1 removed. "
                + "Last reason: was_detected_as_ai_generated"));
        assertEquals(FeedFilter.AI_ROUTE + ": 1 lists, 1 items, 1 removed. Last reason: flag true. Removed: flag true 1. "
                        + "Kinds: flag true 1",
                aiLine());
    }

    /**
     * The mutation control for the one above: the same story with the flag false, and every way
     * the metadata can be missing or unclear, keeps the post and records why.
     */
    @Test
    public void falseNullMissingOrAmbiguousMetadataKeepsThePostAndSaysWhy() {
        Settings.HIDE_AI_DETECTED_POSTS.save(true);
        GraphQLStory story = new GraphQLStory();

        assertFalse("false", guard(Category.ORGANIC, story, new Answer(FeedGuardForTests.detectedInfo(false))));
        assertFalse("a model that doesn't hold the flag reads false",
                guard(Category.ORGANIC, story, new Answer(new BaseModelWithTree(GenAiLabel.DETECTED_INFO_TYPE_TAG))));
        assertFalse("null", guard(Category.ORGANIC, story, new Answer(null)));
        assertFalse("not a story", guard(Category.ORGANIC, new Object(), new Answer(null)));
        assertFalse("no feed unit", guard(Category.ORGANIC, null, new Answer(null)));
        assertFalse("the accessor was never filled in", guard(Category.ORGANIC, story, GenAiLabel.PATCHED));
        BaseModelWithTree selfDisclosed = new BaseModelWithTree(StoryFlag.typeTag("XFBAIGeneratedSelfDisclosureInfo"))
                .with(GenAiLabel.DETECTED_FLAG, true);
        assertFalse("info of another GraphQL type, even with a true flag at the same key",
                guard(Category.ORGANIC, story, new Answer(selfDisclosed)));
        assertEquals("a model of another type isn't read", 0, selfDisclosed.reads);
        assertFalse("info that isn't a tree model", guard(Category.ORGANIC, story, new Answer(new Object())));
        assertFalse("an accessor that throws", guard(Category.ORGANIC, story, new Answer(new IllegalStateException())));

        assertEquals(FeedFilter.AI_ROUTE + ": 9 lists, 9 items, 0 removed. Kinds: flag false 2, "
                        + "accessor not patched 1, ambiguous: info not a tree model 1, ambiguous: info of another type 1, "
                        + "no GenAI info 1, no feed unit 1, not a story 1, read failed 1",
                aiLine());
        assertTrue(counters(), counters().contains(FeedFilter.FEED_ROUTE + ": 9 lists, 9 items, 0 removed"));

        List<String> misses = HookStatus.missing(FamilyNames.AI_DETECTED_POSTS);
        assertEquals(java.util.Arrays.asList(
                "method com.facebook.graphql.model.GraphQLStory#the ai_generated_detected_info accessor",
                "a working 'GenAI info accessor' hook (it threw java.lang.IllegalStateException)"), misses);
    }

    /** Off, the rule doesn't ask for the post's GenAI info at all, so Facebook's path is all that runs. */
    @Test
    public void switchedOffTheRuleReadsNothing() {
        BaseModelWithTree info = FeedGuardForTests.detectedInfo(true);
        Answer answer = new Answer(info);

        assertFalse(guard(Category.ORGANIC, new GraphQLStory(), answer));
        assertEquals("the accessor was asked with the switch off", 0, answer.calls);
        assertEquals(0, info.reads);
        assertNull("the rule counted a post with its switch off", aiLine());
    }

    @Test
    public void pausedTheRuleReadsNothing() {
        Settings.HIDE_AI_DETECTED_POSTS.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            BaseModelWithTree info = FeedGuardForTests.detectedInfo(true);
            Answer answer = new Answer(info);
            assertFalse(why + " hid a post", guard(Category.ORGANIC, new GraphQLStory(), answer));
            assertEquals(why + " asked the accessor", 0, answer.calls);
        }
        assertNull(aiLine());

        PauseForTests.resume();
        assertTrue("the rule didn't come back after the pause",
                guard(Category.ORGANIC, new GraphQLStory(), new Answer(FeedGuardForTests.detectedInfo(true))));
    }

    /** Without the patch the rule doesn't run whatever the switch says, and the public guard is that. */
    @Test
    public void anUnpatchedBuildHidesNothing() {
        Settings.HIDE_AI_DETECTED_POSTS.save(true);
        Answer answer = new Answer(FeedGuardForTests.detectedInfo(true));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new GraphQLStory(), true, true, story -> null, false, answer));
        assertEquals(0, answer.calls);
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new GraphQLStory()));
        assertNull(aiLine());
    }

    /** A post another rule already hid isn't read again, and doesn't count on the GenAI route. */
    @Test
    public void anEarlierRuleWinsAndTheRuleOnlyCountsWhatItRead() {
        Settings.HIDE_AI_DETECTED_POSTS.save(true);
        Answer answer = new Answer(FeedGuardForTests.detectedInfo(true));
        assertTrue(guard(Category.SPONSORED, new GraphQLStory(), answer));
        assertEquals(0, answer.calls);
        assertTrue(guard(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), answer));
        assertNull(aiLine());
        assertTrue(counters(), counters().contains("Last reason: GraphQLPagesYouMayLikeFeedUnit"));
    }

    /**
     * A build missing a member the reader needs keeps every post, and the Hook status row names
     * what's missing. The control, with every member there, reports three found.
     */
    @Test
    public void theReportSaysWhetherTheReaderFoundWhatItNeeds() throws Exception {
        Settings.HIDE_AI_DETECTED_POSTS.save(true);
        guard(Category.ORGANIC, new GraphQLStory(), new Answer(null));
        List<String> found = HookStatus.report();
        assertTrue(String.join("\n", found),
                found.contains(FamilyNames.AI_DETECTED_POSTS + ": invoked 1, 3 found, 0 missing"));

        HookStatus.clear();
        FeedFilterCounters.clear();
        StoryFlag.Members complete = StoryFlag.Members.lookUp(StoryFlag.class.getClassLoader());
        StoryFlag.cachedMembers = new StoryFlag.Members(complete.story, complete.treeModel, null, complete.typeTag);
        assertFalse(guard(Category.ORGANIC, new GraphQLStory(), new Answer(FeedGuardForTests.detectedInfo(true))));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.AI_DETECTED_POSTS + ": invoked 1, 2 found, 1 missing. "
                + "First missing: method com.facebook.graphql.modelutil.BaseModelWithTree#getCachedBoolean(int)"));
        assertEquals(FeedFilter.AI_ROUTE + ": 1 lists, 1 items, 0 removed. Kinds: reader missing 1", aiLine());
    }
}
