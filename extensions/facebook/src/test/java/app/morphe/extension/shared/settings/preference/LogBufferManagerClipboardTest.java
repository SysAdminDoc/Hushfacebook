/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Copy quick report has to fit a GitHub issue. It used to keep the report's last characters, so
 * after a long Debug logging session the header, the paused line and [PATCHES] were what got cut.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class LogBufferManagerClipboardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final LogBufferManager.ReportSection PATCHES = section("PATCHES",
            "Hide sponsored posts: disabled while paused (saved hushfacebook_hide_sponsored_posts=on)");

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        LogBufferManager.registerReportSection(PATCHES);
    }

    @After
    public void tearDown() {
        PauseForTests.resume();
        LogBufferManager.clearReportSectionsForTests();
        LogBufferManager.clearLogBuffer();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    private static LogBufferManager.ReportSection section(String title, String line) {
        return new LogBufferManager.ReportSection() {
            @Override
            public String title() {
                return title;
            }

            @Override
            public List<String> lines() {
                return Collections.singletonList(line);
            }
        };
    }

    private static String run(char c, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) text.append(c);
        return text.toString();
    }

    /** Each build stamps the time to the millisecond, so two builds compare with it taken out. */
    private static String timeless(String report) {
        return report.replaceFirst("generated_utc: \\S+", "generated_utc: T");
    }

    private static void events(int count) {
        for (int i = 0; i < count; i++) {
            LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "Probe", "INFO", "event " + i + " " + run('x', 40));
        }
    }

    @Test
    public void aLongReportKeepsEverythingAheadOfTheEventsAndLosesTheOldestEvents() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        events(200);
        String whole = timeless(LogBufferManager.buildExportText());
        assertTrue("the whole report fits, so nothing here is tested", whole.length() > 3_000);

        String raw = LogBufferManager.clipboardText(3_000);
        assertTrue(raw.length() + " characters", raw.length() <= 3_000);
        String copy = timeless(raw);
        String head = whole.substring(0, whole.indexOf("\n\n[SELECTED EVENTS]"));
        assertTrue("the copy lost part of what comes before the events:\n" + copy, copy.startsWith(head));
        assertTrue(copy, copy.contains("hushfacebook: paused (switch)"));
        assertTrue(copy, copy.contains("[PATCHES]\nHide sponsored posts: disabled while paused"));

        Matcher note = Pattern.compile("clipboard_note: (\\d+) older events left out").matcher(copy);
        assertTrue(copy, note.find());
        int dropped = Integer.parseInt(note.group(1));
        assertTrue(copy, dropped > 0 && dropped < 200);
        for (int i = 0; i < 200; i++) {
            assertEquals("event " + i + " of 200, with " + dropped + " left out", i >= dropped,
                    copy.contains("| event " + i + " "));
        }
    }

    @Test
    public void aReportThatFitsIsCopiedWhole() {
        events(3);
        String whole = LogBufferManager.buildExportText();
        assertEquals(timeless(whole), timeless(LogBufferManager.clipboardText(whole.length())));
        assertFalse(whole.contains("clipboard_note"));
    }

    @Test
    public void aHeadTooLongForTheCopyIsCutAtItsEndNotItsStart() {
        LogBufferManager.registerReportSection(section("LONG", run('y', 5_000)));
        events(3);
        String copy = LogBufferManager.clipboardText(1_000);
        assertTrue(copy.length() + " characters", copy.length() <= 1_000);
        assertTrue(copy, copy.startsWith("MORPHE DIAGNOSTIC REPORT\n"));
        assertTrue(copy, copy.endsWith(
                "\nclipboard_note: cut at 1000 characters; use Save full report for everything\n"));
    }
}
