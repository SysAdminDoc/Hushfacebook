/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.facebook.graphql.model.GraphQLPagesYouMayLikeFeedUnit;
import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.facebook.ads.ReelsAdFilter;
import app.morphe.extension.facebook.download.MediaDownload;
import app.morphe.extension.facebook.download.PlayerSourcesForTests;
import app.morphe.extension.facebook.download.ReelDownload;
import app.morphe.extension.facebook.download.VideoMenuItemForTests;
import app.morphe.extension.facebook.feed.FeedFilter;
import app.morphe.extension.facebook.feed.ReturnRefresh;
import app.morphe.extension.facebook.feed.FeedGuardForTests;
import app.morphe.extension.facebook.feed.TypedFeedUnit;
import app.morphe.extension.facebook.misc.ExternalBrowser;
import app.morphe.extension.facebook.misc.LinkCleaner;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * What Pause and safe mode promise: every hook a switch runs takes Facebook's own path, and every
 * saved value stays as it is.
 *
 * <p>Each probe is one hook with its switch on. It must change Facebook's behaviour while
 * Hushfacebook runs, which is the control, and leave it alone while paused. A family that gains a
 * switch without a probe here fails the first test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PausedHooksTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for GraphQLFeedStoryCategory: only the constant names matter to the guard. */
    enum Category { ORGANIC, SPONSORED, PROMOTION, FB_SHORTS, SHOWCASE }

    /** Stands in for the showcase story type enum: only the constant names matter to the rule. */
    enum ShowcaseStoryType { SHOWCASE_SHORT_VIDEO }

    /** Stands in for the obfuscated ad item base class; the patch passes its binary name. */
    public static class AdBase {
    }

    public static final class VideoAd extends AdBase {
    }

    public static final class Reel {
    }

    /** A section wrapper: the screen reads the list it holds. */
    public static final class Section {
        List<Object> items;

        Section(List<Object> items) {
            this.items = items;
        }
    }

    /**
     * A story card. Hushfacebook's save asks it for its media before anything else, so a card
     * nobody asked was left to Facebook's own save.
     */
    public static final class StoryCard {
        boolean asked;

        public Object getMedia() {
            asked = true;
            return null;
        }
    }

    /** One hook with its switch on: true when it changed what Facebook would have done. */
    interface Probe {
        boolean changedFacebook();
    }

    private static final String AD = AdBase.class.getName();

    @After
    public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : settingsSwitches()) setting.resetToDefault();
        FeedFilterCounters.clear();
    }

    private static Map<PatchFamily, List<Probe>> probes() {
        Map<PatchFamily, List<Probe>> probes = new EnumMap<>(PatchFamily.class);
        probes.put(PatchFamily.SPONSORED_POSTS, Arrays.asList(
                () -> FeedGuardForTests.hides(Category.SPONSORED, new Object()),
                () -> FeedGuardForTests.hides(Category.PROMOTION, new Object())));
        probes.put(PatchFamily.SUGGESTED_POSTS, Arrays.asList(
                () -> FeedGuardForTests.hides(Category.ORGANIC, new GraphQLPagesYouMayLikeFeedUnit()),
                // A story Facebook's own recommendation flag marks as suggested for you.
                () -> FeedGuardForTests.hidesRecommended(Category.ORGANIC, new GraphQLStory(),
                        FeedGuardForTests.recommendationContext(true)),
                () -> FeedGuardForTests.hides(Category.ORGANIC, TypedFeedUnit.peopleYouMayKnow())));
        // Each of the feed's two Stories tray adapters returns nothing.
        probes.put(PatchFamily.STORIES_TRAY, Arrays.asList(
                () -> FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY),
                () -> FeedFilter.hideStoriesTray(FeedFilter.UNIFIED_TRAY)));
        // A row of reels between posts, by its category and by its showcase story type, and the
        // Reels row the pre-EOF injector builds without passing the edge guard.
        probes.put(PatchFamily.FEED_REELS, Arrays.asList(
                () -> FeedGuardForTests.hidesReels(Category.FB_SHORTS, new Object()),
                () -> FeedGuardForTests.hidesShowcaseReels(Category.SHOWCASE, ShowcaseStoryType.SHOWCASE_SHORT_VIDEO),
                FeedFilter::hidePreEofReels));
        probes.put(PatchFamily.RETURN_REFRESH, Collections.singletonList(() -> {
            ReturnRefresh.uiHidden();
            return ReturnRefresh.skip();
        }));
        // A story Facebook's own detection marked as made with AI.
        probes.put(PatchFamily.AI_DETECTED_POSTS, Collections.singletonList(
                () -> FeedGuardForTests.hides(Category.ORGANIC, new GraphQLStory(), FeedGuardForTests.detectedInfo(true))));
        probes.put(PatchFamily.SPONSORED_STORIES, Collections.singletonList(FeedFilter::hideSponsoredStories));
        probes.put(PatchFamily.SPONSORED_REELS, Arrays.asList(
                () -> {
                    VideoAd ad = new VideoAd();
                    return !ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), ad), AD).contains(ad);
                },
                () -> {
                    VideoAd ad = new VideoAd();
                    Section section = new Section(new ArrayList<>(Arrays.asList(new Reel(), ad)));
                    ReelsAdFilter.withoutAdSections(Collections.singletonList(section), AD);
                    return !section.items.contains(ad);
                }));
        probes.put(PatchFamily.EXTERNAL_BROWSER, Collections.singletonList(() -> {
            Activity browser = Robolectric.buildActivity(Activity.class,
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))).create().get();
            boolean left = ExternalBrowser.redirect(browser, browser.getIntent());
            boolean started = shadowOf(browser).getNextStartedActivity() != null;
            assertEquals("the answer and what the browser did disagree", left, started);
            return left;
        }));
        probes.put(PatchFamily.STORY_DOWNLOAD, Arrays.asList(
                () -> {
                    StoryCard card = new StoryCard();
                    MediaDownload.saveStory(RuntimeEnvironment.getApplication(), card);
                    return card.asked;
                },
                // The menu offers Save on someone else's story.
                () -> MediaDownload.offersSave(false),
                // The recorder runs in every player Facebook builds, not only in stories.
                PlayerSourcesForTests::recordsAPlayer));
        // Every reel's sidebar gets the Download button.
        probes.put(PatchFamily.REEL_DOWNLOAD, Collections.singletonList(ReelDownload::showsButton));
        // A video post's menu gets Download to phone, and the video recorder keeps a player.
        probes.put(PatchFamily.VIDEO_DOWNLOAD, Arrays.asList(
                VideoMenuItemForTests::addsAnItem,
                PlayerSourcesForTests::recordsAVideoPlayer));
        // A shared link loses what the app added to it.
        probes.put(PatchFamily.SANITIZE_SHARING_LINKS, Collections.singletonList(() -> {
            String shared = "https://www.facebook.com/share/p/1AbCdEf/?mibextid=WC7FNe";
            return !shared.equals(LinkCleaner.sanitizeShared(shared));
        }));
        return probes;
    }

    /** Every switch the settings screen can show, read off the class so a new one can't hide. */
    static List<BooleanSetting> settingsSwitches() {
        List<BooleanSetting> switches = new ArrayList<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != BooleanSetting.class) continue;
            try {
                switches.add((BooleanSetting) field.get(null));
            } catch (IllegalAccessException unreadable) {
                throw new AssertionError(unreadable);
            }
        }
        return switches;
    }

    private static Set<PatchFamily> switched() {
        Set<PatchFamily> switched = EnumSet.noneOf(PatchFamily.class);
        for (PatchFamily family : PatchFamily.values()) {
            if (!family.switches.isEmpty()) switched.add(family);
        }
        return switched;
    }

    /** Adds a line to [wrong] for every probe that didn't answer [changes]. */
    private static void everyProbe(Map<PatchFamily, List<Probe>> probes, boolean changes, String when,
                                   List<String> wrong) {
        for (Map.Entry<PatchFamily, List<Probe>> entry : probes.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (entry.getValue().get(i).changedFacebook() != changes) {
                    wrong.add(entry.getKey().patchName + ", probe " + i + ", " + when
                            + (changes ? ": left Facebook alone" : ": still changed Facebook"));
                }
            }
        }
    }

    @Test
    public void everyHookASwitchRunsTakesFacebooksOwnPathWhilePaused() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(true);
        Map<PatchFamily, List<Probe>> probes = probes();
        assertEquals("every family with a switch needs a probe here", switched(), probes.keySet());

        // Every hook is asked every time, so one run names every hook that broke the promise.
        List<String> wrong = new ArrayList<>();
        everyProbe(probes, true, "running", wrong);

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            everyProbe(probes, false, "paused by " + why, wrong);
        }

        PauseForTests.resume();
        everyProbe(probes, true, "running again", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    /**
     * Facebook can call a hook before its application's onCreate hands Hushfacebook the context,
     * from a thread it starts early, and again while setContext is still deciding whether this
     * start runs paused. Until both are done, every hook takes Facebook's own path whatever is
     * saved (see Utils.settingsReady). This JVM's Setting class loaded with a context, so a hook
     * that reads its switch anyway answers on here and is named. Whether a hook reads a switch
     * before its guard, which is what crashes a start, is ColdStartHooksTest's to see.
     */
    @Test
    public void untilTheSettingsAreReadyEveryHookTakesFacebooksOwnPath() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(true);
        Map<PatchFamily, List<Probe>> probes = probes();
        assertEquals("every family with a switch needs a probe here", switched(), probes.keySet());

        List<String> wrong = new ArrayList<>();
        SettingsContextRule.withoutContext(() -> everyProbe(probes, false, "before the context is set", wrong));
        // Safe mode on, as after three crashed starts: the context is set and the pause undecided.
        BaseSettings.SAFE_MODE.save(true);
        try {
            SettingsContextRule.beforeThePauseIsDecided(
                    () -> everyProbe(probes, false, "before the pause is decided", wrong));
        } finally {
            BaseSettings.SAFE_MODE.resetToDefault();
        }
        everyProbe(probes, true, "once they're ready", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    @Test
    public void pausedEverySwitchAnswersOffAndKeepsWhatWasSaved() {
        List<BooleanSetting> switches = settingsSwitches();
        assertFalse("found no switches to check", switches.isEmpty());
        for (BooleanSetting setting : switches) setting.save(true);

        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        for (BooleanSetting setting : switches) {
            assertFalse(setting.key + " answered on while paused", setting.get());
            assertTrue(setting.key + " lost what was saved", setting.savedValue());
        }

        PauseForTests.resume();
        for (BooleanSetting setting : switches) {
            assertTrue(setting.key + " stayed off after the pause ended", setting.get());
        }
    }

    /**
     * The Pause row and the paused card say Debug logging keeps working, which is how a paused
     * start gets logged for a report.
     */
    @Test
    public void debugLoggingKeepsWorkingWhilePaused() {
        BaseSettings.DEBUG.save(true);
        try {
            for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
                if (why == HushfacebookPause.Reason.NONE) continue;
                PauseForTests.pause(why);
                assertTrue("Debug logging answered off while paused by " + why, BaseSettings.DEBUG.get());
            }
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }
}
