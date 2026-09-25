/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
 * The Stories tray hook at the start of both of the feed's tray adapter methods: true skips the
 * adapter while the switch is on, and the report counts each adapter the feed asked for, under its
 * own kind, and each one skipped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoriesTrayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_STORIES_TRAY.resetToDefault();
        FeedFilterCounters.clear();
        HookStatus.clear();
    }

    private static String line() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(FeedFilter.TRAY_ROUTE + ": ")) return line;
        }
        return null;
    }

    /** Picking the patch is the choice to hide the tray, so its switch starts on. */
    @Test
    public void theSwitchStartsOnOnceThePatchIsPicked() {
        assertTrue(Settings.HIDE_STORIES_TRAY.defaultValue);
    }

    @Test
    public void bothAdaptersAreSkippedWhileTheSwitchIsOn() {
        for (int i = 0; i < 3; i++) assertTrue(FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY));
        assertTrue(FeedFilter.hideStoriesTray(FeedFilter.UNIFIED_TRAY));

        assertEquals(FeedFilter.TRAY_ROUTE + ": 4 lists, 4 items, 4 removed. Last reason: unified adapter skipped. "
                + "Removed: legacy adapter skipped 3, unified adapter skipped 1. Kinds: legacy 3, unified 1", line());
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains(FamilyNames.STORIES_TRAY + ": invoked 4, 0 found, 0 missing"));
    }

    /** The mutation control: off, both adapters are built and only the asking is counted. */
    @Test
    public void switchedOffBothAdaptersAreBuilt() {
        Settings.HIDE_STORIES_TRAY.save(false);
        assertFalse(FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY));
        assertFalse(FeedFilter.hideStoriesTray(FeedFilter.UNIFIED_TRAY));
        assertEquals(FeedFilter.TRAY_ROUTE + ": 2 lists, 2 items, 0 removed. Kinds: legacy 1, unified 1", line());
    }

    private static int occurrences(String text, String of) {
        int count = 0;
        for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + 1)) count++;
        return count;
    }

    /**
     * With debug logging on, the first time each adapter is skipped or kept says so once, which
     * tells a report which tray this phone builds. Off, nothing is logged and nothing remembered,
     * so turning logging on later still gets the lines.
     */
    @Test
    public void theDebugLineNamesEachAdapterAndWhatHappenedOnce() {
        FeedFilter.TRAY_LOGGED.set(0);
        LogBufferManager.clearLogBuffer();
        try {
            FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY);
            assertEquals("a line was remembered with debug logging off", 0, FeedFilter.TRAY_LOGGED.get());

            BaseSettings.DEBUG.save(true);
            for (int i = 0; i < 3; i++) FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY);
            FeedFilter.hideStoriesTray(FeedFilter.UNIFIED_TRAY);
            Settings.HIDE_STORIES_TRAY.save(false);
            FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY);
            FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY);

            String log = LogBufferManager.buildExportText();
            assertEquals(log, 1, occurrences(log, "Stories tray: skipped legacy adapter"));
            assertEquals(log, 1, occurrences(log, "Stories tray: skipped unified adapter"));
            assertEquals(log, 1, occurrences(log, "Stories tray: kept legacy adapter"));
            assertEquals(log, 0, occurrences(log, "Stories tray: kept unified adapter"));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            FeedFilter.TRAY_LOGGED.set(0);
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test
    public void pausedBothAdaptersAreBuilt() {
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertFalse(why + " skipped the legacy tray", FeedFilter.hideStoriesTray(FeedFilter.LEGACY_TRAY));
            assertFalse(why + " skipped the unified tray", FeedFilter.hideStoriesTray(FeedFilter.UNIFIED_TRAY));
        }
        PauseForTests.resume();
        assertTrue("the tray hook didn't come back after the pause", FeedFilter.hideStoriesTray(FeedFilter.UNIFIED_TRAY));
    }
}
