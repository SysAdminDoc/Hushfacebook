/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
 * The reels rule beyond the category: a ShowcaseFeedUnit whose story type is a row of reels, the
 * debug line's showcase type, and the pre-EOF injector's hook, with what each counts and reports.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedReelsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the rule. */
    enum Category { ENGAGEMENT, SHOWCASE, FB_SHORTS, FB_SHORTS_FALLBACK, END_OF_FEED_REELS, END_OF_FEED_CONTENT }

    /** Stands in for the showcase story type enum Redex renamed: its constant names are the 580 enum's. */
    enum StoryType {
        UNSET_OR_UNRECOGNIZED_ENUM_VALUE, SHOWCASE_EVENTS, SHOWCASE_FB_HASHTAG_DEEP_DIVE, SHOWCASE_FB_SEARCH_REELS,
        SHOWCASE_FB_SHORTS_CFU, SHOWCASE_FB_SHORTS_DYNAMIC_MIDCARD, SHOWCASE_FB_SHORTS_MIDCARD,
        SHOWCASE_FB_SHORTS_PROMO, SHOWCASE_FRIEND_DIGEST, SHOWCASE_GROUP_DIGEST, SHOWCASE_LOCAL_NEWS,
        SHOWCASE_MARKETPLACE, SHOWCASE_SHORT_VIDEO, SHOWCASE_TODAY_IN, SHOWCASE_UCP_INTEREST, SHOWCASE_UNKNOWN
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_FEED_REELS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
        FeedFilter.PRE_EOF_LOGGED.set(0);
        LogBufferManager.clearLogBuffer();
    }

    private static TypedFeedUnit showcase() {
        return new TypedFeedUnit(ShowcaseType.UNIT_TYPE);
    }

    /** The guard with only the reels patch in, and the showcase stub answering [type]. */
    private static boolean reels(Category category, Object feedUnit, StoryType type) {
        return FeedFilter.hideEdge(category, feedUnit, false, false, story -> null, false, story -> null, true,
                unit -> type);
    }

    private static String reelsLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.REELS_ROUTE + ": ")) return line;
        }
        return null;
    }

    private static String feedLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.FEED_ROUTE + ": ")) return line;
        }
        return null;
    }

    /** Every reels story type is a constant of the enum both builds carry. */
    @Test
    public void theReelsStoryTypesAreTheEnumsOwnNames() {
        assertEquals(6, ShowcaseType.REELS_TYPES.length);
        for (String type : ShowcaseType.REELS_TYPES) StoryType.valueOf(type);
    }

    /**
     * A plain SHOWCASE edge whose story type is one of the six rows of reels goes, with the type in
     * its reason. The same edge of another story type stays, and so does the hashtag deep dive,
     * which nobody has seen on a signed-in feed yet.
     */
    @Test
    public void aShowcaseRowOfReelsGoesByItsStoryType() {
        for (String name : ShowcaseType.REELS_TYPES) {
            assertTrue(name, reels(Category.SHOWCASE, showcase(), StoryType.valueOf(name)));
        }
        for (StoryType kept : new StoryType[]{StoryType.SHOWCASE_MARKETPLACE, StoryType.SHOWCASE_EVENTS,
                StoryType.SHOWCASE_FRIEND_DIGEST, StoryType.SHOWCASE_GROUP_DIGEST, StoryType.SHOWCASE_LOCAL_NEWS,
                StoryType.SHOWCASE_TODAY_IN, StoryType.SHOWCASE_UCP_INTEREST, StoryType.SHOWCASE_UNKNOWN,
                StoryType.UNSET_OR_UNRECOGNIZED_ENUM_VALUE, StoryType.SHOWCASE_FB_HASHTAG_DEEP_DIVE}) {
            assertFalse(kept.name(), reels(Category.SHOWCASE, showcase(), kept));
        }
        assertFalse("no story type", reels(Category.SHOWCASE, showcase(), null));
        assertTrue(feedLine(), feedLine().contains("Removed: ShowcaseFeedUnit:SHOWCASE_FB_SEARCH_REELS 1, "));
    }

    /**
     * The rows the category already names go by the category, for any unit, without the showcase
     * type being read. The posts around them stay: a friend's post, and the end of the feed you
     * follow.
     */
    @Test
    public void theReelsCategoriesGoAndThePostsAroundThemStay() {
        AtomicInteger asked = new AtomicInteger();
        StoryFlag.Accessor counting = unit -> {
            asked.incrementAndGet();
            return StoryType.SHOWCASE_SHORT_VIDEO;
        };
        for (Category category : new Category[]{Category.FB_SHORTS, Category.FB_SHORTS_FALLBACK,
                Category.END_OF_FEED_REELS}) {
            assertTrue(category.name(), FeedFilter.hideEdge(category, showcase(), false, false, story -> null, false,
                    story -> null, true, counting));
        }
        assertEquals("a category rule read the showcase type", 0, asked.get());
        assertFalse(reels(Category.ENGAGEMENT, new GraphQLStory(), StoryType.SHOWCASE_SHORT_VIDEO));
        assertFalse(reels(Category.ENGAGEMENT, new TypedFeedUnit("Story"), StoryType.SHOWCASE_SHORT_VIDEO));
        assertFalse(reels(Category.END_OF_FEED_CONTENT, new TypedFeedUnit("Story"), StoryType.SHOWCASE_SHORT_VIDEO));
    }

    /**
     * The mutation controls: the FB_SHORTS showcase row and the SHOWCASE row of reels both stay with
     * the switch off, without the patch, while paused and before the settings are ready.
     */
    @Test
    public void theRowsStayWithoutTheSwitchThePatchOrTheSettings() {
        assertTrue(reels(Category.FB_SHORTS, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));
        assertTrue(reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));

        assertFalse("without the patch", FeedFilter.hideEdge(Category.FB_SHORTS, showcase(), false, false,
                story -> null, false, story -> null, false, unit -> StoryType.SHOWCASE_SHORT_VIDEO));
        assertFalse("without the patch", FeedFilter.hideEdge(Category.SHOWCASE, showcase(), false, false,
                story -> null, false, story -> null, false, unit -> StoryType.SHOWCASE_SHORT_VIDEO));

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, reels(Category.FB_SHORTS, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));
            assertFalse("paused by " + why, reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));
        }
        PauseForTests.resume();

        boolean[] hidden = {true, true};
        SettingsContextRule.withoutContext(() -> {
            hidden[0] = reels(Category.FB_SHORTS, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
            hidden[1] = reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
        });
        assertFalse("before the settings are ready", hidden[0]);
        assertFalse("before the settings are ready", hidden[1]);

        Settings.HIDE_FEED_REELS.save(false);
        assertFalse("switched off", reels(Category.FB_SHORTS, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));
        assertFalse("switched off", reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));
    }

    /**
     * The patch fills the stub for the one class that answers the showcase type name, so a unit of
     * any other type never reaches it, whatever its category.
     */
    @Test
    public void aUnitOfAnotherTypeNeverReachesTheStub() {
        AtomicInteger asked = new AtomicInteger();
        StoryFlag.Accessor counting = unit -> {
            asked.incrementAndGet();
            return StoryType.SHOWCASE_SHORT_VIDEO;
        };
        for (Object unit : new Object[]{TypedFeedUnit.storiesTray(), new TypedFeedUnit("DiscoverFeedUnit"),
                new GraphQLStory(), new Object(), null, new TypedFeedUnit.Unreadable()}) {
            assertFalse(String.valueOf(unit), FeedFilter.hideEdge(Category.SHOWCASE, unit, false, false,
                    story -> null, false, story -> null, true, counting));
        }
        assertEquals(0, asked.get());
        assertEquals("no showcase unit was read", null, reelsLine());
    }

    /**
     * A stub that throws keeps the row and names itself in Hook status once; the stub as the
     * extension ships it, never filled, keeps the row and names the accessor as missing.
     */
    @Test
    public void aStubThatThrowsOrWasNeverFilledKeepsTheRow() {
        for (int i = 0; i < 2; i++) {
            assertFalse(FeedFilter.hideEdge(Category.SHOWCASE, showcase(), false, false, story -> null, false,
                    story -> null, true, unit -> {
                        throw new ClassCastException("another class");
                    }));
        }
        String status = String.join("\n", HookStatus.report());
        assertTrue(status, status.contains(FamilyNames.FEED_REELS + ": invoked 2, 0 found, 1 missing. "
                + "First missing: a working 'showcase story type accessor' hook (it threw java.lang.ClassCastException)"));

        HookStatus.clear();
        assertFalse(FeedFilter.hideEdge(Category.SHOWCASE, showcase(), false, false, story -> null, false,
                story -> null, true));
        status = String.join("\n", HookStatus.report());
        assertTrue(status, status.contains(FamilyNames.FEED_REELS + ": invoked 1, 0 found, 1 missing. "
                + "First missing: method ShowcaseFeedUnit#the showcase_story_type accessor"));

        HookStatus.clear();
        assertTrue(reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO));
        status = String.join("\n", HookStatus.report());
        assertTrue(status, status.contains(FamilyNames.FEED_REELS + ": invoked 1, 1 found, 0 missing"));
    }

    /**
     * Each showcase unit the rule reads counts on the reels route under its story type, and each
     * one it hides as a removal with the same reason the feed route gives. A row the category
     * already hid isn't read, so it counts on the feed route only.
     */
    @Test
    public void theReelsRouteCountsWhatTheRuleRead() {
        reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
        reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
        reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_MARKETPLACE);
        reels(Category.FB_SHORTS, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
        reels(Category.ENGAGEMENT, new GraphQLStory(), StoryType.SHOWCASE_SHORT_VIDEO);

        assertEquals(FeedFilter.REELS_ROUTE + ": 3 lists, 3 items, 2 removed. "
                + "Last reason: ShowcaseFeedUnit:SHOWCASE_SHORT_VIDEO. Removed: ShowcaseFeedUnit:SHOWCASE_SHORT_VIDEO 2. "
                + "Kinds: SHOWCASE_SHORT_VIDEO 2, SHOWCASE_MARKETPLACE 1", reelsLine());
        assertEquals(FeedFilter.FEED_ROUTE + ": 5 lists, 5 items, 3 removed. Last reason: FB_SHORTS. "
                + "Removed: ShowcaseFeedUnit:SHOWCASE_SHORT_VIDEO 2, FB_SHORTS 1. "
                + "Kinds: SHOWCASE 3, ENGAGEMENT 1, FB_SHORTS 1", feedLine());

        FeedFilterCounters.clear();
        Settings.HIDE_FEED_REELS.save(false);
        reels(Category.SHOWCASE, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
        assertEquals("the rule read a unit with its switch off", null, reelsLine());
    }

    private static List<String> feedEdgeLines(String log) {
        List<String> lines = new ArrayList<>();
        for (String line : log.split("\n")) {
            if (line.contains("Feed edge: ")) lines.add(line.substring(line.indexOf("Feed edge: ")));
        }
        return lines;
    }

    /**
     * With debug logging on, each edge's line says what showcase type it carries while the patch is
     * in, and none for a unit that isn't a showcase. Without the patch the line says nothing of it,
     * and the stub isn't asked.
     */
    @Test
    public void theDebugLineNamesTheShowcaseTypeOnlyWithThePatch() {
        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        Settings.HIDE_FEED_REELS.save(false);
        reels(Category.FB_SHORTS, showcase(), StoryType.SHOWCASE_SHORT_VIDEO);
        reels(Category.ENGAGEMENT, new TypedFeedUnit("Story"), StoryType.SHOWCASE_SHORT_VIDEO);
        AtomicInteger asked = new AtomicInteger();
        FeedFilter.hideEdge(Category.FB_SHORTS, showcase(), false, false, story -> null, false, story -> null,
                false, unit -> {
                    asked.incrementAndGet();
                    return StoryType.SHOWCASE_SHORT_VIDEO;
                });

        List<String> lines = feedEdgeLines(LogBufferManager.buildExportText());
        assertEquals(lines.toString(), 3, lines.size());
        assertEquals("Feed edge: FB_SHORTS ShowcaseFeedUnit ifr=none showcase=SHOWCASE_SHORT_VIDEO", lines.get(0).trim());
        assertEquals("Feed edge: ENGAGEMENT Story ifr=none showcase=none", lines.get(1).trim());
        assertEquals("Feed edge: FB_SHORTS ShowcaseFeedUnit ifr=none", lines.get(2).trim());
        assertEquals("the line asked the stub without the patch", 0, asked.get());
    }

    /** The pre-EOF injector returns at once while the switch is on, and every call counts. */
    @Test
    public void thePreEofRowIsSkippedWhileTheSwitchIsOn() {
        assertTrue(FeedFilter.hidePreEofReels());
        assertTrue(FeedFilter.hidePreEofReels());
        assertEquals(FeedFilter.REELS_ROUTE + ": 2 lists, 2 items, 2 removed. Last reason: pre-EOF unit. "
                + "Removed: pre-EOF unit 2. Kinds: pre-EOF unit 2", reelsLine());
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains(FamilyNames.FEED_REELS + ": invoked 2, 0 found, 0 missing"));
    }

    /**
     * The mutation controls: switched off, paused and before the settings are ready, Facebook
     * builds its row, and the call still counts.
     */
    @Test
    public void thePreEofRowIsBuiltWithoutTheSwitchOrTheSettings() {
        Settings.HIDE_FEED_REELS.save(false);
        assertFalse("switched off", FeedFilter.hidePreEofReels());
        Settings.HIDE_FEED_REELS.resetToDefault();

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, FeedFilter.hidePreEofReels());
        }
        PauseForTests.resume();

        boolean[] hidden = {true};
        SettingsContextRule.withoutContext(() -> hidden[0] = FeedFilter.hidePreEofReels());
        assertFalse("before the settings are ready", hidden[0]);
        assertEquals(FeedFilter.REELS_ROUTE + ": 5 lists, 5 items, 0 removed. Kinds: pre-EOF unit 5", reelsLine());

        assertTrue("the hook didn't come back", FeedFilter.hidePreEofReels());
    }

    private static int occurrences(String text, String of) {
        int count = 0;
        for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + 1)) count++;
        return count;
    }

    /**
     * With debug logging on, the first skip and the first keep each say so once, which tells a
     * report that Facebook asks for the row on this account. Off, nothing is logged or remembered.
     */
    @Test
    public void thePreEofDebugLineComesOncePerDecision() {
        FeedFilter.hidePreEofReels();
        assertEquals("a line was remembered with debug logging off", 0, FeedFilter.PRE_EOF_LOGGED.get());

        BaseSettings.DEBUG.save(true);
        LogBufferManager.clearLogBuffer();
        for (int i = 0; i < 3; i++) FeedFilter.hidePreEofReels();
        Settings.HIDE_FEED_REELS.save(false);
        FeedFilter.hidePreEofReels();
        FeedFilter.hidePreEofReels();

        String log = LogBufferManager.buildExportText();
        assertEquals(log, 1, occurrences(log, "Reels in feed: skipped the pre-EOF Reels unit"));
        assertEquals(log, 1, occurrences(log, "Reels in feed: kept the pre-EOF Reels unit"));
    }
}
