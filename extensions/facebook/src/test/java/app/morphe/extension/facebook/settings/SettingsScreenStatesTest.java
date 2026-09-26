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
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;
import android.widget.ListView;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.BaseSettings;
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
import org.robolectric.shadows.ShadowToast;

import java.util.EnumSet;

/** The settings screen's own states: the status card, the error page and a link no app opens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsScreenStatesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        HushfacebookPreferenceFragment.failNextInitialization = null;
        PauseForTests.resume();
        BaseSettings.PAUSED.resetToDefault();
        BaseSettings.SAFE_MODE.resetToDefault();
    }

    private static HushfacebookPreferenceFragment open(Activity activity) {
        HushfacebookPreferenceFragment page = new HushfacebookPreferenceFragment();
        activity.getFragmentManager().beginTransaction().add(android.R.id.content, page).commitNow();
        return page;
    }

    private static Preference titled(PreferenceGroup group, CharSequence title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (title.toString().contentEquals(String.valueOf(row.getTitle()))) return row;
            if (row instanceof PreferenceGroup) {
                Preference found = titled((PreferenceGroup) row, title);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String summary(Preference row) {
        return String.valueOf(row.getSummary());
    }

    /** A phone whose only browser is off: Android throws where the page asks for one. */
    public static final class NoBrowserAround extends Activity {
        @Override
        public void startActivityFromFragment(Fragment fragment, Intent intent, int requestCode, Bundle options) {
            throw new ActivityNotFoundException("No Activity found to handle Intent { act=android.intent.action.VIEW }");
        }
    }

    /** Uncaught, the exception from a tap on the source row closed Facebook. */
    @Test
    public void aSourceLinkNoAppOpensLeavesATipAndFacebookRunning() {
        try (ActivityController<NoBrowserAround> controller = Robolectric.buildActivity(NoBrowserAround.class).setup()) {
            Preference source = titled(open(controller.get()).getPreferenceScreen(), L10n.t("Source code and issues"));
            assertNotNull(source);

            source.getOnPreferenceClickListener().onPreferenceClick(source);
            ShadowLooper.idleMainLooper();

            String tip = String.valueOf(ShadowToast.getTextOfLatestToast());
            assertTrue(tip, tip.contains("github.com/SysAdminDoc/Hushfacebook"));
        }
    }

    /** Pause switched on changes the next start, and the card said "Hushfacebook is on" and no more. */
    @Test
    public void theCardSaysPauseTakesHoldAtTheNextStart() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            Preference card = titled(page.getPreferenceScreen(), L10n.t("Hushfacebook is on"));
            SwitchPreference pause = (SwitchPreference) page.findPreference(BaseSettings.PAUSED.key);
            assertNotNull(card);
            assertNotNull(pause);

            pause.setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(summary(card), summary(card).endsWith(L10n.t("Hushfacebook pauses when Facebook restarts.")));

            pause.setChecked(false);
            ShadowLooper.idleMainLooper();
            assertFalse(summary(card), summary(card).contains(L10n.t("Hushfacebook pauses when Facebook restarts.")));
        }
    }

    /** A tap on the paused card, then Pause back on: the card kept saying it turns back on. */
    @Test
    public void pauseBackOnAfterATapOnTheCardSaysItStaysPaused() {
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        BaseSettings.PAUSED.save(true);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            Preference card = titled(page.getPreferenceScreen(), L10n.t("Hushfacebook is paused"));
            assertNotNull(card);
            assertTrue(summary(card), summary(card).endsWith(L10n.t("Tap to turn it back on.")));

            card.getOnPreferenceClickListener().onPreferenceClick(card);
            ShadowLooper.idleMainLooper();
            assertEquals(L10n.t("Hushfacebook turns back on when Facebook restarts."), summary(card));

            ((SwitchPreference) page.findPreference(BaseSettings.PAUSED.key)).setChecked(true);
            ShadowLooper.idleMainLooper();
            assertTrue(summary(card), summary(card).endsWith(L10n.t("Tap to turn it back on.")));
        }
    }

    /**
     * Unset, Android's own Cancel came in the activity's language, here English, next to Save in
     * Facebook's, here German.
     */
    @Test
    @Config(qualifiers = "de")
    public void theSaveFolderDialogCancelsInFacebooksLanguage() {
        assertEquals("Abbrechen", L10n.t("Cancel"));
        try (ActivityController<SettingsL10nTest.ActivityInEnglish> controller =
                     Robolectric.buildActivity(SettingsL10nTest.ActivityInEnglish.class).setup()) {
            assertEquals(L10n.t("Cancel"), String.valueOf(
                    HushfacebookPreferenceFragment.folderRow(controller.get()).getNegativeButtonText()));
        }
    }

    /** The video file name's dialog, next to the folder's, had the same English Cancel beside Speichern. */
    @Test
    @Config(qualifiers = "de")
    public void theFileNameDialogCancelsInFacebooksLanguage() {
        assertEquals("Abbrechen", L10n.t("Cancel"));
        try (ActivityController<SettingsL10nTest.ActivityInEnglish> controller =
                     Robolectric.buildActivity(SettingsL10nTest.ActivityInEnglish.class).setup()) {
            HushfacebookPreferenceFragment.FileNameRow row = HushfacebookPreferenceFragment.fileNameRow(controller.get());
            assertEquals(L10n.t("Save"), String.valueOf(row.getPositiveButtonText()));
            assertEquals(L10n.t("Cancel"), String.valueOf(row.getNegativeButtonText()));
        }
    }

    /** The quality list's only button is DialogPreference's Cancel, and it came in the activity's language too. */
    @Test
    @Config(qualifiers = "de")
    public void theQualityListCancelsInFacebooksLanguage() {
        assertEquals("Abbrechen", L10n.t("Cancel"));
        try (ActivityController<SettingsL10nTest.ActivityInEnglish> controller =
                     Robolectric.buildActivity(SettingsL10nTest.ActivityInEnglish.class).setup()) {
            assertEquals(L10n.t("Cancel"), String.valueOf(
                    HushfacebookPreferenceFragment.qualityRow(controller.get()).getNegativeButtonText()));
        }
    }

    /** A row that ignores taps, such as Import while an export runs, looked like one that takes them. */
    @Test
    public void aDisabledRowIsDimmedAndAnEnabledOneIsNot() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment.Row row = new HushfacebookPreferenceFragment.Row(controller.get());
            row.setTitle("Import settings");
            row.setSummary("Waiting for the export.");
            ListView list = new ListView(controller.get());

            row.setEnabled(false);
            android.view.View off = row.getView(null, list);
            assertTrue(alphaOf(off, android.R.id.title) < 0xFF);
            assertTrue(alphaOf(off, android.R.id.summary) < 0xFF);

            row.setEnabled(true);
            android.view.View on = row.getView(null, list);
            assertEquals(0xFF, alphaOf(on, android.R.id.title));
            assertEquals(0xFF, alphaOf(on, android.R.id.summary));
        }
    }

    private static int alphaOf(android.view.View row, int id) {
        android.widget.TextView text = row.findViewById(id);
        assertNotNull(text);
        return android.graphics.Color.alpha(text.getCurrentTextColor());
    }

    /**
     * The page colours its list from what initialize() stored, and a page that failed before that
     * kept an earlier screen's colours, or none: black under a light Material You error page.
     */
    @Test
    public void aFailedLightMaterialYouPageIsDrawnOnItsOwnBackground() {
        PatchFamily.inBuildForTests = EnumSet.of(PatchFamily.MATERIAL_YOU_THEME);
        RuntimeEnvironment.setQualifiers("+notnight");
        HushfacebookPreferenceFragment.failNextInitialization = new IllegalStateException("settings failed to load");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment page = open(controller.get());
            ListView list = page.getView().findViewById(android.R.id.list);
            ScreenColors light = ScreenColors.forScreen(controller.get());
            assertNotNull(light);
            assertTrue(light.light);

            assertEquals(Integer.toHexString(light.background),
                    Integer.toHexString(((ColorDrawable) list.getBackground()).getColor()));
        }
    }
}
