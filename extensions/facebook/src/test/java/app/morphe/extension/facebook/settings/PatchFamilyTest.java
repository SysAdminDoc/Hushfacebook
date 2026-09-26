/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The one list the settings screen and the diagnostic report read to say what Pause turns off
 * and what it can't reach. It has to cover every switch, every patch and every status flag, or
 * one of them goes unmentioned.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PatchFamilyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        PauseForTests.resume();
        Settings.HIDE_PROMOTED_POSTS.resetToDefault();
        Settings.HIDE_SPONSORED_POSTS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void everySwitchBelongsToExactlyOneFamily() {
        Map<BooleanSetting, PatchFamily> owners = new HashMap<>();
        for (PatchFamily family : PatchFamily.values()) {
            for (BooleanSetting setting : family.switches) {
                PatchFamily earlier = owners.put(setting, family);
                assertNull(setting.key + " belongs to " + earlier + " and to " + family, earlier);
            }
        }
        assertEquals(new HashSet<>(PausedHooksTest.settingsSwitches()), owners.keySet());
    }

    @Test
    public void everyStatusFlagBelongsToExactlyOneFamily() {
        Set<String> flags = new TreeSet<>();
        for (Method method : SettingsStatus.class.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && method.getReturnType() == boolean.class && method.getParameterCount() == 0) {
                flags.add(method.getName());
            }
        }
        Set<String> named = new TreeSet<>();
        for (PatchFamily family : PatchFamily.values()) {
            assertTrue("two families share " + family.statusMethod, named.add(family.statusMethod));
        }
        assertEquals(flags, named);
    }

    /** The names are Morphe Manager's, so a report and the patch list say the same thing. */
    @Test
    public void everyPatchButTheSettingsEntryIsAFamily() throws Exception {
        JSONArray patches = new JSONObject(new String(Files.readAllBytes(patchesList().toPath()),
                StandardCharsets.UTF_8)).getJSONArray("patches");
        Set<String> listed = new TreeSet<>();
        for (int i = 0; i < patches.length(); i++) listed.add(patches.getJSONObject(i).getString("name"));
        assertTrue("the settings entry left the patch list", listed.remove("Hushfacebook settings"));

        Set<String> families = new TreeSet<>();
        for (PatchFamily family : PatchFamily.values()) families.add(family.patchName);
        assertEquals(listed, families);
    }

    @Test
    public void aPatchWithNoSwitchSaysWhatOfItStaysIn() {
        for (PatchFamily family : PatchFamily.values()) {
            if (family.switches.isEmpty()) {
                assertNotNull(family.patchName + " has no switch, so Pause can't reach it", family.staysWhilePaused);
            }
        }
    }

    @Test
    public void theStaysRowNamesWhatPauseCantReach() {
        assertNull("a build of switches alone has nothing that stays in",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.SPONSORED_POSTS, PatchFamily.EXTERNAL_BROWSER)));
        assertEquals("The background ad prefetch block. It was set when you patched, so Pause can't turn it off. "
                        + "To rule it out, patch again without the patch it comes from.",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.AD_PREFETCH)));
        // Every download asks its switch before it goes in, so a pause takes them out whole.
        assertNull(PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.REEL_DOWNLOAD, PatchFamily.STORY_DOWNLOAD,
                PatchFamily.VIDEO_DOWNLOAD)));
        // Alone, a family's text is followed by "It was set", so a text naming several parts still
        // has to be one thing. 4a7bba9 made the Reels one plural and this sentence stopped reading.
        assertEquals("The part of the Reels ad block patched into the app. It was set when you patched, so "
                        + "Pause can't turn it off. To rule it out, patch again without the patch it comes from.",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.SPONSORED_REELS)));
        assertEquals("The part of the Reels ad block patched into the app and the ad telemetry block. They "
                        + "were set when you patched, so Pause can't turn them off. To rule one out, patch again "
                        + "without the patch it comes from.",
                PatchFamily.staysWhilePausedSummary(EnumSet.of(PatchFamily.SPONSORED_REELS, PatchFamily.AD_TELEMETRY,
                        PatchFamily.SPONSORED_POSTS, PatchFamily.STORY_DOWNLOAD)));

        String everything = PatchFamily.staysWhilePausedSummary(EnumSet.allOf(PatchFamily.class));
        for (PatchFamily family : PatchFamily.values()) {
            if (family.staysWhilePaused == null) continue;
            assertTrue(family.patchName + " is missing from: " + everything,
                    everything.toLowerCase().contains(family.staysWhilePaused.toLowerCase()));
        }
    }

    @Test
    public void theReportSaysWhatASwitchRunsAndWhatStaysIn() {
        Set<PatchFamily> build = EnumSet.of(PatchFamily.SPONSORED_POSTS, PatchFamily.SPONSORED_REELS,
                PatchFamily.AD_PREFETCH);
        Settings.HIDE_PROMOTED_POSTS.save(false);

        List<String> running = PatchFamily.reportLines(build, false);
        assertEquals(Arrays.asList(
                "Hide sponsored posts: on (hushfacebook_hide_sponsored_posts=on, hushfacebook_hide_promoted_posts=off)",
                "Hide sponsored reels: on (hushfacebook_hide_sponsored_reels=on); stays in while paused: "
                        + "the part of the Reels ad block patched into the app",
                "Block background ad prefetch: no switch, stays in while paused: the background ad prefetch block",
                "not in this build: Hide suggested and promoted posts, Hide Stories tray, Hide Reels in the feed, "
                        + "Block background-return feed refresh, Hide AI-detected posts, "
                        + "Hide sponsored stories, Open links in "
                        + "external browser, Sanitize sharing links, Download any story, Download any reel, "
                        + "Download any video, Block ad telemetry, Disable Audience Network, AMOLED black theme, Material You theme, "
                        + "Restore screens on re-signed builds"),
                running);
        // The reel button has a switch now, so the report says what it's set to.
        assertEquals("Download any reel: on (hushfacebook_download_reels=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.REEL_DOWNLOAD), false).get(0));
        // So has the video menu's item, and a pause takes it out whole.
        assertEquals("Download any video: on (hushfacebook_download_videos=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.VIDEO_DOWNLOAD), false).get(0));
        assertEquals("Download any video: disabled while paused (saved hushfacebook_download_videos=on)",
                PatchFamily.reportLines(EnumSet.of(PatchFamily.VIDEO_DOWNLOAD), true).get(0));

        List<String> paused = PatchFamily.reportLines(build, true);
        assertEquals("Hide sponsored posts: disabled while paused (saved "
                + "hushfacebook_hide_sponsored_posts=on, hushfacebook_hide_promoted_posts=off)", paused.get(0));
        assertEquals("Hide sponsored reels: disabled while paused (saved "
                + "hushfacebook_hide_sponsored_reels=on); stays in while paused: the part of the Reels ad "
                + "block patched into the app",
                paused.get(1));
        assertEquals("a patch with no switch reads the same paused", running.get(2), paused.get(2));
        assertEquals(running.get(3), paused.get(3));

        // Both of a family's switches off is the family off; one of them on keeps it on above.
        Settings.HIDE_SPONSORED_POSTS.save(false);
        assertEquals("Hide sponsored posts: disabled by its switch (hushfacebook_hide_sponsored_posts=off, "
                + "hushfacebook_hide_promoted_posts=off)", PatchFamily.reportLines(build, false).get(0));
    }

    /**
     * A paused export marks the Hook status lines of the families a switch runs, and leaves the
     * ones with no switch alone: they keep working, and a mark would tell a reader otherwise.
     */
    @Test
    public void aPausedExportMarksOnlyTheFamiliesASwitchRuns() {
        HookStatus.clear();
        PatchFamily.registerDiagnostics();
        HookStatus.invoked(FamilyNames.SPONSORED_POSTS);
        HookStatus.invoked(FamilyNames.AMOLED_THEME);

        List<String> lines = HookStatus.report(" (paused)");
        assertTrue(String.join("\n", lines),
                lines.contains("Hide sponsored posts: invoked 1, 0 found, 0 missing (paused)"));
        assertTrue(String.join("\n", lines), lines.contains("AMOLED black theme: invoked 1, 0 found, 0 missing"));
    }

    /** The section goes through the redactor like every other one, and has to come out whole. */
    @Test
    public void theExportCarriesTheSectionWhole() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        LogBufferManager.registerReportSection(PatchFamily.REPORT);
        // A paused process always makes a report, so nothing else has to go wrong first.
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("\n[PATCHES]\n"));
        assertTrue(report, report.contains("what was set when patching stays in"));
        for (String line : PatchFamily.reportLines(EnumSet.allOf(PatchFamily.class), true)) {
            assertTrue("the export changed or lost \"" + line + "\":\n" + report, report.contains("\n" + line + "\n"));
        }
    }

    /** patches-list.json at the repository root, found from wherever Gradle runs the test. */
    private static File patchesList() {
        for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, "patches-list.json");
            if (candidate.isFile()) return candidate;
        }
        throw new AssertionError("no patches-list.json above " + new File("").getAbsolutePath());
    }
}
