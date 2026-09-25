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

import java.lang.reflect.Field;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** The rules the shared feed guard asks about each edge. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the rule. */
    enum Category { ORGANIC, SPONSORED, PROMOTION, INJECTED_STORY, ENGAGEMENT, FB_SHORTS, FB_SHORTS_FALLBACK, END_OF_FEED_REELS, FB_STORIES }

    @After
    public void restoreSwitches() {
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        Settings.HIDE_PROMOTED_POSTS.resetToDefault();
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
        Settings.HIDE_SUGGESTED_FOR_YOU.resetToDefault();
        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.resetToDefault();
        Settings.HIDE_STORIES_TRAY.resetToDefault();
        Settings.HIDE_FEED_REELS.resetToDefault();
        FeedFilterCounters.clear();
    }

    /** The guard with only the reels patch in: no story flag rule reads anything. */
    private static boolean reelsOnly(Category category, Object feedUnit, boolean reelsPatched) {
        return FeedFilter.hideEdge(category, feedUnit, false, false, story -> null, false, story -> null, reelsPatched);
    }

    /**
     * A row of reels goes under each of the three categories Facebook files one under, with the
     * patch in and the switch on (its default). A post, a story row and the reels rows of a build
     * without the patch all stay, and so do the rows once the switch is off.
     */
    @Test
    public void reelsRowsLeaveTheFeedWithTheirPatchAndSwitch() {
        assertTrue("the switch starts on", Settings.HIDE_FEED_REELS.get());
        for (Category reels : new Category[]{Category.FB_SHORTS, Category.FB_SHORTS_FALLBACK, Category.END_OF_FEED_REELS}) {
            assertTrue(reels.name(), reelsOnly(reels, new Object(), true));
            assertFalse("without the patch: " + reels, reelsOnly(reels, new Object(), false));
        }
        assertFalse("a post stays", reelsOnly(Category.ENGAGEMENT, new Object(), true));
        assertFalse("a stories row stays", reelsOnly(Category.FB_STORIES, new Object(), true));
        assertFalse("a category that is not an enum is not guessed at", FeedFilter.hideEdge(
                "FB_SHORTS", new Object(), false, false, story -> null, false, story -> null, true));

        Settings.HIDE_FEED_REELS.save(false);
        assertFalse("the switch off keeps the rows", reelsOnly(Category.FB_SHORTS, new Object(), true));
    }

    /** A hidden row counts under its category, so a report says how many of each went. */
    @Test
    public void aHiddenReelsRowCountsUnderItsCategory() {
        reelsOnly(Category.FB_SHORTS, new Object(), true);
        reelsOnly(Category.FB_SHORTS, new Object(), true);
        reelsOnly(Category.END_OF_FEED_REELS, new Object(), true);
        reelsOnly(Category.ENGAGEMENT, new Object(), true);
        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(FeedFilter.FEED_ROUTE + ": 4 lists, 4 items, 3 removed"));
        assertTrue(report, report.contains("Removed: FB_SHORTS 2, END_OF_FEED_REELS 1"));
    }

    @Test
    public void sponsoredAndPromotedEdgesAreHiddenByDefault() {
        assertTrue(FeedFilter.hiddenCategory(Category.SPONSORED));
        assertTrue(FeedFilter.hiddenCategory(Category.PROMOTION));
    }

    /** The mutation control: an ordinary post, or a category the rule does not know, stays. */
    @Test
    public void organicAndUnknownEdgesStay() {
        assertFalse(FeedFilter.hiddenCategory(Category.ORGANIC));
        assertFalse(FeedFilter.hiddenCategory(Category.INJECTED_STORY));
        assertFalse(FeedFilter.hiddenCategory(null));
        assertFalse("a category that is not an enum is not guessed at", FeedFilter.hiddenCategory("SPONSORED"));
    }

    @Test
    public void eachSwitchTurnsItsCategoryBackOn() {
        Settings.HIDE_PROMOTED_POSTS.save(false);
        assertTrue(FeedFilter.hiddenCategory(Category.SPONSORED));
        assertFalse(FeedFilter.hiddenCategory(Category.PROMOTION));

        Settings.HIDE_SPONSORED_POSTS.save(false);
        assertFalse(FeedFilter.hiddenCategory(Category.SPONSORED));
    }

    @Test
    public void aSuggestedUnitIsRecognisedByItsKeptClass() {
        assertTrue(FeedFilter.isSuggested(new GraphQLPagesYouMayLikeFeedUnit()));
        assertFalse(FeedFilter.isSuggested(new Object()));
        assertFalse(FeedFilter.isSuggested(null));
    }

    /**
     * No patch flipped the status flags in a test JVM, so the shared guard must leave every edge
     * alone: a feed filter only acts for the patches that were selected.
     */
    @Test
    public void anUnpatchedBuildHidesNothing() {
        assertFalse(FeedFilter.hideEdge(Category.SPONSORED, new GraphQLPagesYouMayLikeFeedUnit()));
    }

    /** With both patches in, the guard hides by category first and by unit type second. */
    @Test
    public void thePatchedGuardHidesByCategoryAndByUnit() {
        assertTrue(FeedFilter.hideEdge(Category.SPONSORED, new Object(), true, false));
        assertFalse("the suggested rule belongs to its own patch",
                FeedFilter.hideEdge(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), true, false));
        assertTrue(FeedFilter.hideEdge(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), false, true));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new Object(), true, true));
    }

    /**
     * Every edge is counted before a rule runs, and a hidden one records why, so a diagnostic
     * report shows the guard ran even on a build where no patch switched a rule on.
     */
    @Test
    public void everyEdgeIsCountedAndEveryHiddenOneSaysWhy() {
        FeedFilterCounters.clear();
        FeedFilter.hideEdge(Category.ORGANIC, new Object(), true, true);
        FeedFilter.hideEdge(Category.SPONSORED, new Object(), true, true);
        FeedFilter.hideEdge(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), true, true);
        FeedFilter.hideEdge(Category.SPONSORED, null);

        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(FeedFilter.FEED_ROUTE + ": 4 lists, 4 items, 2 removed"));
        assertTrue(report, report.contains("Last reason: GraphQLPagesYouMayLikeFeedUnit"));
        assertTrue(report, report.contains("Kinds: ORGANIC 2, SPONSORED 2"));
    }

    /**
     * A build that carries none of the suggested unit classes hides nothing under that switch, and
     * the report says so: the model package moved. The control, with the class there, reports it
     * found. A clear starts a new Hook status row, and the classes are reported into it again.
     */
    @Test
    public void theReportSaysWhetherTheSuggestedUnitsAreInThisBuild() throws Exception {
        Field cache = FeedFilter.class.getDeclaredField("suggestedClasses");
        cache.setAccessible(true);
        LogBufferManager.clearLogBuffer();
        try {
            cache.set(null, new Class<?>[0]);
            assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new Object(), true, true));
            String report = LogBufferManager.buildExportText();
            // The three found are the members the recommendation flag is read through.
            assertTrue(report, report.contains("Hide suggested and promoted posts: invoked 1, 3 found, 1 missing. "
                    + "First missing: class com.facebook.graphql.model#any suggested feed unit"));
            assertTrue(report, report.contains("Hide sponsored posts: invoked 1, 0 found, 0 missing"));

            cache.set(null, null);
            LogBufferManager.clearLogBuffer();
            assertTrue(FeedFilter.hideEdge(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), true, true));
            assertTrue(String.join("\n", HookStatus.report()),
                    HookStatus.report().contains("Hide suggested and promoted posts: invoked 1, 4 found, 0 missing"));
        } finally {
            cache.set(null, null);
            LogBufferManager.clearLogBuffer();
        }
    }

    /**
     * On a signed-in feed, posts from accounts nobody there followed arrived as ENGAGEMENT stories,
     * like posts from friends, and no INJECTED_STORY edge ever came. The "Suggested for you" switch
     * reads Facebook's own recommendation flag now (RecommendationRuleTest), and the category on its
     * own hides nothing.
     */
    @Test
    public void theStoryCategoryAloneNoLongerMarksASuggestedPost() {
        assertFalse(FeedFilter.hideEdge(Category.INJECTED_STORY, new Object(), false, true));
        assertFalse(FeedFilter.hideEdge(Category.INJECTED_STORY, new GraphQLStory(), false, true,
                story -> FeedGuardForTests.recommendationContext(false), false, GenAiLabel.PATCHED));
    }

    /**
     * "People you may know" has no kept class, so it's found by the GraphQL type its
     * getTypeName() answers. The type of the row's own edges, and a unit with no type, stay.
     */
    @Test
    public void peopleYouMayKnowGoesByItsTypeName() {
        assertTrue(FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow(), false, true));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC,
                new TypedFeedUnit("PaginatedPeopleYouMayKnowFeedUnitUsersEdge"), false, true));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new TypedFeedUnit("Story"), false, true));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow(), true, false));
        Settings.HIDE_PEOPLE_YOU_MAY_KNOW.save(false);
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow(), false, true));
    }

    /**
     * The Stories tray is never an edge on a real feed: the feed's adapter list adds it as an
     * adapter of its own (StoriesTrayTest). A unit answering its type name, should one ever come
     * through, is left to Facebook like any other unit no rule claims, with the tray's switch on.
     */
    @Test
    public void aStoriesTrayEdgeIsNotHiddenByTheGuard() {
        assertTrue(Settings.HIDE_STORIES_TRAY.get());
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.storiesTray(), true, true));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.storiesTray()));
        assertTrue(String.join("\n", FeedFilterCounters.report()),
                String.join("\n", FeedFilterCounters.report()).contains(FeedFilter.FEED_ROUTE + ": 2 lists, 2 items, 0 removed"));
    }

    /** A type name that can't be read, or isn't text, keeps the unit: the rules fail open. */
    @Test
    public void aUnitWhoseTypeNameCantBeReadStays() {
        assertNull(FeedFilter.typeName(new TypedFeedUnit.Unreadable()));
        assertNull(FeedFilter.typeName(new Object()));
        assertNull(FeedFilter.typeName(null));
        assertEquals("StoriesTrayFeedUnit", FeedFilter.typeName(TypedFeedUnit.storiesTray()));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, new TypedFeedUnit.Unreadable(), true, true));
    }

    /**
     * A model whose native tree Facebook already released answers getTypeName() from native code
     * that no try block catches, so the guard asks isValidGraphServicesJNIModel() first and keeps
     * the unit without asking its name. The control, with the tree still there, reads the name and
     * hides the row.
     */
    @Test
    public void aReleasedTreeIsNeverAskedItsTypeName() {
        BaseModelWithTree released = new BaseModelWithTree("PaginatedPeopleYouMayKnowFeedUnit") { }.released();
        assertNull(FeedFilter.typeName(released));
        assertFalse(FeedFilter.hideEdge(Category.ORGANIC, released, true, true));
        assertFalse("the guard asked a released tree its type name", released.readAfterRelease);

        BaseModelWithTree live = new BaseModelWithTree("PaginatedPeopleYouMayKnowFeedUnit") { };
        assertEquals("PaginatedPeopleYouMayKnowFeedUnit", FeedFilter.typeName(live));
        assertTrue(FeedFilter.hideEdge(Category.ORGANIC, live, true, true));
    }

    /**
     * Every rule that hides counts under its own reason, so a report says how many posts each one
     * took out: the total and whichever rule fired last couldn't. Rules run several times each, in
     * a mixed order, and a kept post adds nothing to any of them.
     */
    @Test
    public void eachRuleCountsWhatItHid() {
        FeedFilterCounters.clear();
        StoryFlag.Accessor recommended = story -> FeedGuardForTests.recommendationContext(true);
        for (int i = 0; i < 3; i++) {
            FeedFilter.hideEdge(Category.SPONSORED, new Object(), true, true);
            FeedFilter.hideEdge(Category.ORGANIC, new GraphQLStory(), true, true, recommended, false,
                    GenAiLabel.PATCHED);
        }
        FeedFilter.hideEdge(Category.PROMOTION, new Object(), true, true);
        FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow(), true, true);
        FeedFilter.hideEdge(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit(), true, true);
        FeedFilter.hideEdge(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow(), true, true);
        FeedFilter.hideEdge(Category.ORGANIC, new Object(), true, true);

        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(FeedFilter.FEED_ROUTE + ": 11 lists, 11 items, 10 removed. "
                + "Last reason: PaginatedPeopleYouMayKnowFeedUnit. Removed: SPONSORED 3, "
                + "is_in_feed_recommendation_story 3, PaginatedPeopleYouMayKnowFeedUnit 2, "
                + "GraphQLPagesYouMayLikeFeedUnit 1, PROMOTION 1. Kinds: ORGANIC 7, SPONSORED 3, PROMOTION 1"));
    }
}