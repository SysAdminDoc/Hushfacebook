/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.ads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;

/** The two page filters that take server-inlined ads out of Reels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReelsAdFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

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

    private static final String AD = AdBase.class.getName();

    @After
    public void restoreSwitch() {
        Settings.HIDE_SPONSORED_REELS.resetToDefault();
        FeedFilterCounters.clear();
    }

    @Test
    public void aPageLosesItsAdsAndKeepsItsReels() {
        Reel first = new Reel();
        Reel second = new Reel();
        Collection<?> kept = ReelsAdFilter.withoutAds(Arrays.asList(first, new VideoAd(), second), AD);

        assertEquals(Arrays.asList(first, second), new ArrayList<>(kept));
    }

    /** The mutation control: a page with no ad comes back as the very same object. */
    @Test
    public void aPageWithoutAdsIsHandedBackUntouched() {
        List<Object> page = Collections.unmodifiableList(Arrays.asList(new Reel(), new Reel()));

        assertSame(page, ReelsAdFilter.withoutAds(page, AD));
    }

    @Test
    public void aSectionLosesItsAdsAndAnEmptiedSectionGoes() {
        Reel reel = new Reel();
        Section mixed = new Section(new ArrayList<>(Arrays.asList(reel, new VideoAd())));
        Section adsOnly = new Section(Collections.unmodifiableList(Collections.singletonList(new VideoAd())));

        List<?> kept = ReelsAdFilter.withoutAdSections(Arrays.asList(mixed, adsOnly), AD);

        assertEquals(1, kept.size());
        assertSame(mixed, kept.get(0));
        assertEquals(Collections.singletonList(reel), mixed.items);
        assertTrue("an immutable list is replaced rather than edited", adsOnly.items.isEmpty());
    }

    @Test
    public void theSwitchLetsThePageThroughAsSent() {
        Settings.HIDE_SPONSORED_REELS.save(false);
        List<Object> page = Arrays.asList(new Reel(), new VideoAd());

        assertSame(page, ReelsAdFilter.withoutAds(page, AD));
    }

    /**
     * Both levels report what they were handed and what they took out, switched on or off, so a
     * diagnostic report says whether the hook ran without debug logging having been on.
     */
    @Test
    public void bothLevelsCountWhatTheySawAndWhatTheyDropped() {
        FeedFilterCounters.clear();
        ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), new VideoAd(), new Reel()), AD);
        ReelsAdFilter.withoutAdSections(Collections.singletonList(
                new Section(new ArrayList<>(Arrays.asList(new Reel(), new VideoAd())))), AD);
        Settings.HIDE_SPONSORED_REELS.save(false);
        ReelsAdFilter.withoutAds(Arrays.asList(new Reel(), new VideoAd()), AD);

        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(ReelsAdFilter.PAGES_ROUTE + ": 2 lists, 5 items, 1 removed"));
        assertTrue(report, report.contains(ReelsAdFilter.SECTIONS_ROUTE + ": 1 lists, 1 items, 1 removed"));
    }
}
