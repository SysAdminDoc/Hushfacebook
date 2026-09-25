/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.ads.ReelsAdFilter;
import app.morphe.extension.facebook.download.MediaDownload;
import app.morphe.extension.facebook.download.PlayerSourcesForTests;
import app.morphe.extension.facebook.download.ReelDownload;
import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.feed.FeedGuardForTests;
import app.morphe.extension.facebook.misc.ExternalBrowser;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * A start in which one of Facebook's early threads wins the race: every hook called before the
 * application hands Hushfacebook its context, and setContext after them.
 *
 * <p>A hook that reads a switch before its guard fails Setting's static initialiser, which needs
 * the context, and then the read setContext makes to decide the pause throws NoClassDefFoundError
 * out of the start. PausedHooksTest can't see that: its sandbox loaded the settings classes with a
 * context first, so a read there just answers. This is the only class at sdk 33, so it runs in a
 * sandbox of its own where nothing has loaded them yet, and it declares no SettingsContextRule,
 * which would load them. Give another class sdk 33 and this stops proving anything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ColdStartHooksTest {

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    enum Category { ORGANIC, SPONSORED }

    /** Stands in for the obfuscated ad item base class; the patch passes its binary name. */
    public static class AdBase {
    }

    public static final class VideoAd extends AdBase {
    }

    public static final class Reel {
    }

    public static final class Section {
        List<Object> items;

        Section(List<Object> items) {
            this.items = items;
        }
    }

    public static final class StoryCard {
        public Object getMedia() {
            return null;
        }
    }

    @Test
    public void everyHookBeforeTheContextLeavesTheSettingsUsable() {
        Context app = RuntimeEnvironment.getApplication();
        String ad = AdBase.class.getName();
        assertFalse("something set the context before this test", Utils.settingsReady());

        // Every hook a switch runs, with the arguments PausedHooksTest's probes use, and the
        // public feed guard as the patch calls it. Each has to take Facebook's path.
        assertFalse(FeedGuardForTests.hides(Category.SPONSORED, new Object()));
        assertFalse(FeedGuardForTests.hides(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit()));
        assertFalse(FeedFilter.hideEdge(Category.SPONSORED, new Object()));
        assertFalse(FeedFilter.hideSponsoredStories());
        VideoAd reelAd = new VideoAd();
        assertTrue(ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), reelAd), ad).contains(reelAd));
        VideoAd sectionAd = new VideoAd();
        Section section = new Section(new ArrayList<>(Arrays.asList(new Reel(), sectionAd)));
        ReelsAdFilter.withoutAdSections(Collections.singletonList(section), ad);
        assertTrue(section.items.contains(sectionAd));
        Activity browser = Robolectric.buildActivity(Activity.class,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))).create().get();
        assertFalse(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertFalse(MediaDownload.saveStory(app, new StoryCard()));
        assertFalse(MediaDownload.offersSave(false));
        assertTrue("Facebook's own yes has to stand", MediaDownload.offersSave(true));
        assertFalse(ReelDownload.showsButton());
        assertFalse(PlayerSourcesForTests.recordsAPlayer());

        // A hook that touched the settings above left them unusable, and this is where a real
        // start would crash. While setContext decides the pause the context is already set, so a
        // hook firing then still has to wait: a paused start must not run its patched path.
        boolean[] readyWhileDeciding = { true };
        boolean[] adKeptWhileDeciding = { false };
        PauseForTests.whileDeciding(() -> {
            readyWhileDeciding[0] = Utils.settingsReady();
            VideoAd early = new VideoAd();
            adKeptWhileDeciding[0] = ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), early), ad).contains(early);
        });
        try {
            Utils.setContext(app);
        } catch (Throwable poisoned) {
            throw new AssertionError("a hook read a setting before the context was set, and setContext then threw",
                    poisoned);
        } finally {
            PauseForTests.whileDeciding(null);
        }
        assertFalse("hooks could read settings while the pause was still being decided", readyWhileDeciding[0]);
        assertTrue("a hook ran its patched path while the pause was still being decided", adKeptWhileDeciding[0]);
        assertTrue(Utils.settingsReady());
        assertTrue("the settings don't answer after a cold start", Settings.HIDE_SPONSORED_REELS.get());
    }
}
