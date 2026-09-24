/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import app.morphe.extension.shared.SettingsContextRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    public void everyRowHasATitleAndLightText() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            HushfacebookPreferenceFragment fragment = new HushfacebookPreferenceFragment();
            controller.get().getFragmentManager().beginTransaction()
                    .add(android.R.id.content, fragment)
                    .commitNow();

            List<Preference> rows = new ArrayList<>();
            collect(fragment.getPreferenceScreen(), rows);
            assertFalse("the screen has no rows", rows.isEmpty());

            for (Preference row : rows) {
                CharSequence title = row.getTitle();
                assertTrue("a row has no title: " + row.getClass().getSimpleName() + " " + row.getKey(),
                        title != null && title.toString().trim().length() > 0);

                TypedArray styled = row.getContext().obtainStyledAttributes(
                        new int[]{android.R.attr.textColorPrimary});
                try {
                    ColorStateList primary = styled.getColorStateList(0);
                    assertTrue("no primary text color for " + title, primary != null);
                    assertTrue("\"" + title + "\" is drawn dark on the black page",
                            Color.luminance(primary.getDefaultColor()) > 0.5f);
                } finally {
                    styled.recycle();
                }
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
}
