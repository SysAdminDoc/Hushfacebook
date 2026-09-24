/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

/** The rules the shared feed guard asks about each edge. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeedFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the rule. */
    enum Category { ORGANIC, SPONSORED, PROMOTION, INJECTED_STORY }

    @After
    public void restoreSwitches() {
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        Settings.HIDE_PROMOTED_POSTS.resetToDefault();
        Settings.HIDE_SUGGESTED_POSTS.resetToDefault();
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
}
