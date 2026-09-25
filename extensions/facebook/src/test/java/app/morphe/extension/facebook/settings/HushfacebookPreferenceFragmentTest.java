/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The settings screen as it draws inside Facebook: a black page whose rows must be readable.
 *
 * <p>On a phone on 2026-09-24 every row title was near-black on black, because the rows took
 * Facebook's light activity theme, and the two diagnostics rows had no text at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class HushfacebookPreferenceFragmentTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Wording that tells the reader a paused Facebook is an unpatched one, which it isn't. */
    private static final Pattern UNPATCHED = Pattern.compile("(?i)unpatched|n't patched|not patched|as if it weren");

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        PauseForTests.resume();
        Settings.SAVE_FOLDER.resetToDefault();
    }

    @Test
    public void everyRowHasATitleAndLightText() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            assertReadable(rowsOf(controller));
        }
    }

    /**
     * A test JVM has no patched status flags, so the test above sees only the rows every build
     * has. This one draws the screen with every patch in.
     */
    @Test
    public void withEveryPatchInEveryRowIsReadableAndNoneCallsAPausedFacebookUnpatched() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            assertReadable(rows);

            Set<String> switchKeys = new HashSet<>();
            for (PatchFamily family : PatchFamily.values()) {
                for (BooleanSetting setting : family.switches) switchKeys.add(setting.key);
            }
            Set<String> shown = new HashSet<>();
            Preference stays = null;
            for (Preference row : rows) {
                String text = row.getTitle() + " " + row.getSummary();
                assertFalse("\"" + text + "\" says a paused Facebook is unpatched", UNPATCHED.matcher(text).find());
                if (row instanceof SwitchPreference && switchKeys.contains(row.getKey())) shown.add(row.getKey());
                if (HushfacebookPreferenceFragment.STAYS_WHILE_PAUSED.contentEquals(row.getTitle())) stays = row;
            }
            assertEquals("a switch Pause turns off is missing from the screen", switchKeys, shown);
            assertNotNull("nothing on the screen says what Pause can't reach", stays);
            assertEquals(PatchFamily.staysWhilePausedSummary(EnumSet.allOf(PatchFamily.class)),
                    String.valueOf(stays.getSummary()));

            // The Pause row turns off "every switch above" and says Debug logging keeps working.
            int pause = indexOfKey(rows, BaseSettings.PAUSED.key);
            assertTrue("the Pause row is missing", pause >= 0);
            for (String key : switchKeys) {
                assertTrue(key + " is drawn below the Pause row", indexOfKey(rows, key) < pause);
            }
            assertTrue("Debug logging is drawn above the Pause row", indexOfKey(rows, BaseSettings.DEBUG.key) > pause);
            assertTrue(String.valueOf(rows.get(pause).getSummary()),
                    String.valueOf(rows.get(pause).getSummary()).contains("Debug logging keeps working"));
        }
    }

    private static int indexOfKey(List<Preference> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (key.equals(rows.get(i).getKey())) return i;
        }
        return -1;
    }

    @Test
    public void thePausedCardSaysWhatStaysInForEveryReason() {
        for (HushfacebookPause.Reason why : HushfacebookPause.Reason.values()) {
            String summary = HushfacebookPreferenceFragment.pausedSummary(why, "com.facebook.katana");
            assertFalse(why + ": " + summary, UNPATCHED.matcher(summary).find());
            assertTrue(why + ": " + summary, summary.contains("what was set when you patched stays in"));
            // Debug logging is kept as saved while paused, so the card can't say every switch is off.
            assertTrue(why + ": " + summary, summary.contains("Every switch but Debug logging"));
        }

        // The marker counts only in the app's own files folder, and the card names that folder,
        // not the one above it a person finds first. The folder is isolated, so a right-to-left
        // sentence keeps the path in the order it was written.
        String pkg = RuntimeEnvironment.getApplication().getPackageName();
        assertTrue(HushfacebookPreferenceFragment.pausedSummary(HushfacebookPause.Reason.MARKER_FILE, pkg)
                .contains("in " + L10n.isolate("Android/data/" + pkg + "/files") + " paused Hushfacebook"));

        PauseForTests.pause(HushfacebookPause.Reason.CRASH_LOOP);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Preference card = rowsOf(controller).get(0);
            assertEquals("Hushfacebook is paused", String.valueOf(card.getTitle()));
            assertEquals(HushfacebookPreferenceFragment.pausedSummary(HushfacebookPause.Reason.CRASH_LOOP, RuntimeEnvironment.getApplication().getPackageName())
                    + " Tap to turn it back on.", String.valueOf(card.getSummary()));
        }
    }

    /**
     * A version is a value set into a sentence, so both rows that show one isolate it: in a
     * right-to-left sentence "580.0.0.51.74" then keeps the order it was written in.
     */
    @Test
    public void theVersionRowsIsolateTheVersions() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName()).versionName = "580.0.0.51.74";
        String facebook = app.morphe.extension.shared.Utils.getAppVersionName();
        assertTrue("no Facebook version to look for", facebook != null && !facebook.isEmpty());

        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            List<Preference> rows = rowsOf(controller);
            Preference card = rows.get(0);
            assertEquals("Hushfacebook is on", String.valueOf(card.getTitle()));
            assertTrue(String.valueOf(card.getSummary()), String.valueOf(card.getSummary()).contains(L10n.isolate(facebook)));
            Preference version = null;
            for (Preference row : rows) {
                if ("Version".contentEquals(row.getTitle())) version = row;
            }
            assertNotNull("no Version row", version);
            assertTrue(String.valueOf(version.getSummary()), String.valueOf(version.getSummary()).contains(L10n.isolate(facebook)));
        }
    }

    /**
     * The save folder's row keeps the one clean folder name a save would use, whatever is typed
     * into it, and says where videos and photos go. It's there with any download in the build.
     */
    @Test
    public void theFolderRowKeepsOneCleanNameAndSaysWhereSavesGo() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.STORY_DOWNLOAD);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment.FolderRow folder = null;
            for (Preference row : rowsOf(controller)) {
                if (row instanceof HushfacebookPreferenceFragment.FolderRow) folder = (HushfacebookPreferenceFragment.FolderRow) row;
            }
            assertNotNull("no folder row with a download in the build", folder);
            assertEquals(Settings.SAVE_FOLDER.key, folder.getKey());
            assertEquals("Videos go to " + L10n.isolate("Movies/Facebook") + " and photos to "
                    + L10n.isolate("Pictures/Facebook") + ".", String.valueOf(folder.getSummary()));

            // What's typed reaches the row's check the way the dialog's OK sends it.
            Preference.OnPreferenceChangeListener ok = folder.getOnPreferenceChangeListener();
            assertFalse("a path was kept as typed", ok.onPreferenceChange(folder, "../My/Clips"));
            ShadowLooper.idleMainLooper();
            assertEquals("My_Clips", folder.getText());
            assertEquals("My_Clips", Settings.SAVE_FOLDER.savedValue());
            assertEquals(HushfacebookPreferenceFragment.folderSummary("My_Clips"), String.valueOf(folder.getSummary()));

            assertTrue("a clean name was changed", ok.onPreferenceChange(folder, "Clips"));
            folder.setText("Clips");
            ShadowLooper.idleMainLooper();
            assertEquals("Clips", Settings.SAVE_FOLDER.savedValue());

            assertFalse("an empty name was kept", ok.onPreferenceChange(folder, "  "));
            ShadowLooper.idleMainLooper();
            assertEquals("Facebook", folder.getText());
            assertEquals("Facebook", Settings.SAVE_FOLDER.savedValue());
        }

        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.SPONSORED_POSTS);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (Preference row : rowsOf(controller)) {
                assertFalse("a folder row with no download in the build",
                        row instanceof HushfacebookPreferenceFragment.FolderRow);
            }
        }
    }

    private static List<Preference> rowsOf(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment fragment = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, fragment)
                .commitNow();
        List<Preference> rows = new ArrayList<>();
        collect(fragment.getPreferenceScreen(), rows);
        assertFalse("the screen has no rows", rows.isEmpty());
        return rows;
    }

    private static void assertReadable(List<Preference> rows) {
        for (Preference row : rows) {
            CharSequence title = row.getTitle();
            assertTrue("a row has no title: " + row.getClass().getSimpleName() + " " + row.getKey(),
                    title != null && title.toString().trim().length() > 0);

            TypedArray styled = row.getContext().obtainStyledAttributes(
                    new int[]{android.R.attr.textColorPrimary});
            try {
                ColorStateList primary = styled.getColorStateList(0);
                assertTrue("no primary text color for " + title, primary != null);
                ScreenColors page = ScreenColors.shown;
                if (page == null) {
                    assertTrue("\"" + title + "\" is drawn dark on the black page",
                            Color.luminance(primary.getDefaultColor()) > 0.5f);
                } else {
                    // The Material You theme's page is the palette's, dark or light as the phone
                    // is. The theme's own text, before a row paints it, still has to read on it.
                    int blended = blend(primary.getDefaultColor(), page.background);
                    assertTrue("\"" + title + "\" is " + Integer.toHexString(blended) + " on the page's "
                                    + Integer.toHexString(page.background),
                            ScreenColorsTest.contrast(blended, page.background) >= ScreenColorsTest.TEXT);
                }
            } finally {
                styled.recycle();
            }
        }
    }

    private static void collect(PreferenceGroup group, List<Preference> rows) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                collect((PreferenceGroup) preference, rows);
            } else {
                rows.add(preference);
            }
        }
    }

    /** A translucent text colour as it lands on an opaque background. */
    private static int blend(int color, int background) {
        int alpha = color >>> 24;
        int[] out = new int[3];
        for (int shift = 16, i = 0; i < 3; shift -= 8, i++) {
            int top = (color >> shift) & 0xFF;
            int bottom = (background >> shift) & 0xFF;
            out[i] = (top * alpha + bottom * (255 - alpha) + 127) / 255;
        }
        return 0xFF000000 | (out[0] << 16) | (out[1] << 8) | out[2];
    }
}
