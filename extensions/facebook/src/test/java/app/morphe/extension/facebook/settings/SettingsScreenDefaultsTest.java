/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.SwitchPreference;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.Setting;

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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Opening the settings screen stores nothing. Each row stored the value it was shown when its key
 * was missing, so one look stored every default, and a later release that changed a default (the
 * AI switch is meant to go on once it's been checked on a real feed) never reached anyone who had
 * opened the screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsScreenDefaultsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
        Settings.HIDE_AI_DETECTED_POSTS.resetToDefault();
    }

    private static SharedPreferences store() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(Setting.preferences.name, Context.MODE_PRIVATE);
    }

    private static HushfacebookPreferenceFragment open(ActivityController<Activity> controller) {
        HushfacebookPreferenceFragment fragment = new HushfacebookPreferenceFragment();
        controller.get().getFragmentManager().beginTransaction()
                .add(android.R.id.content, fragment)
                .commitNow();
        return fragment;
    }

    @Test
    public void openingTheScreenStoresNoDefault() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Set<String> before = new HashSet<>(store().getAll().keySet());
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            open(controller);
            ShadowLooper.idleMainLooper();

            Set<String> added = new HashSet<>(store().getAll().keySet());
            added.removeAll(before);
            assertEquals("opening the screen stored " + added, Collections.emptySet(), added);
        }
    }

    /** The mutation control: a switch the person turns is still stored, and the setting follows it. */
    @Test
    public void aSwitchThePersonTurnsIsStored() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        String key = Settings.HIDE_AI_DETECTED_POSTS.key;
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SwitchPreference row = find(open(controller).getPreferenceScreen(), key);
            assertNotNull("no row for " + key, row);

            row.setChecked(!row.isChecked());
            ShadowLooper.idleMainLooper();

            assertTrue("the turned switch wasn't stored", store().contains(key));
            assertEquals(row.isChecked(), store().getBoolean(key, !row.isChecked()));
            assertEquals(row.isChecked(), Settings.HIDE_AI_DETECTED_POSTS.get());
        }
    }

    private static SwitchPreference find(PreferenceGroup group, String key) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof SwitchPreference && key.equals(preference.getKey())) {
                return (SwitchPreference) preference;
            }
            if (preference instanceof PreferenceGroup) {
                SwitchPreference found = find((PreferenceGroup) preference, key);
                if (found != null) return found;
            }
        }
        return null;
    }
}
