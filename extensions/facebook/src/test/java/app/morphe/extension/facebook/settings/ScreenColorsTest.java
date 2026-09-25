/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.facebook.theme.PalettesForTests;
import app.morphe.extension.facebook.theme.TonePalette;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * WCAG 2.2 AA for what Hushfacebook draws itself with the Material You theme in the build: the
 * settings screen, its dialogs and its switches, in the dark and light setting, for the fixed
 * palette, the framework's own and wallpapers of three other hues. Text needs 4.5:1 against what
 * it sits on, a switch 3:1 against the page (success criteria 1.4.3 and 1.4.11). Tones fix
 * lightness, so the ratios barely move from one wallpaper to the next.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class ScreenColorsTest {
    /** WCAG 2.2 AA for text smaller than 18pt, or 14pt bold. */
    static final double TEXT = 4.5;
    /** WCAG 2.2 AA for large text and for the parts of a control that show its state. */
    static final double NON_TEXT = 3.0;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        ScreenColors.shown = null;
    }

    /** The WCAG contrast ratio of two opaque colours, 1 to 21. */
    static double contrast(int one, int two) {
        double a = TonePalette.luminance(one) + 0.05;
        double b = TonePalette.luminance(two) + 0.05;
        return Math.max(a, b) / Math.min(a, b);
    }

    /** Every pair on the screen and in its dialogs, by where it's drawn. */
    static Map<String, int[]> pairs(ScreenColors c) {
        Map<String, int[]> text = new LinkedHashMap<>();
        text.put("row title on the page", new int[]{c.title, c.background});
        text.put("row summary on the page", new int[]{c.summary, c.background});
        text.put("section title on the page", new int[]{c.heading, c.background});
        text.put("title bar and back arrow on the page", new int[]{c.title, c.background});
        text.put("dialog title on the dialog", new int[]{c.title, c.dialog});
        text.put("dialog message on the dialog", new int[]{c.summary, c.dialog});
        text.put("dialog button on the dialog", new int[]{c.accent, c.dialog});
        return text;
    }

    static Map<String, int[]> controls(ScreenColors c) {
        Map<String, int[]> parts = new LinkedHashMap<>();
        parts.put("switch on, against the page", new int[]{c.accent, c.background});
        parts.put("switch off, against the page", new int[]{c.switchOff, c.background});
        return parts;
    }

    /** Fails naming the pair when a ratio is under what it needs. */
    static void assertMeets(String palette, Map<String, int[]> pairs, double needed) {
        for (Map.Entry<String, int[]> pair : pairs.entrySet()) {
            double ratio = contrast(pair.getValue()[0], pair.getValue()[1]);
            if (ratio < needed) {
                fail(String.format(java.util.Locale.ROOT, "%s: %s is %.2f:1, under %.1f:1 (#%08X on #%08X)",
                        palette, pair.getKey(), ratio, needed, pair.getValue()[0], pair.getValue()[1]));
            }
        }
    }

    private static Map<String, TonePalette> palettes() {
        Map<String, TonePalette> palettes = new LinkedHashMap<>();
        palettes.put("fixed palette (Android 11)", TonePalette.fallback());
        palettes.put("red wallpaper", PalettesForTests.palette(PalettesForTests.RED));
        palettes.put("yellow wallpaper", PalettesForTests.palette(PalettesForTests.YELLOW));
        palettes.put("green wallpaper", PalettesForTests.palette(PalettesForTests.GREEN));
        return palettes;
    }

    @Test
    public void everyPairMeetsAaInDarkAndLight() {
        for (Map.Entry<String, TonePalette> palette : palettes().entrySet()) {
            for (boolean light : new boolean[]{false, true}) {
                ScreenColors colors = ScreenColors.of(palette.getValue(), light);
                String name = palette.getKey() + (light ? ", light" : ", dark");
                assertMeets(name, pairs(colors), TEXT);
                assertMeets(name, controls(colors), NON_TEXT);
            }
        }
    }

    /** The framework's own wallpaper palette, as a phone on Android 12 and newer reads it. */
    @Test
    @Config(sdk = 34)
    public void theFrameworksPaletteMeetsAaInDarkAndLight() {
        TonePalette phone = TonePalette.of(RuntimeEnvironment.getApplication());
        assertTrue(phone.dynamic);
        for (boolean light : new boolean[]{false, true}) {
            ScreenColors colors = ScreenColors.of(phone, light);
            assertMeets("framework palette" + (light ? ", light" : ", dark"), pairs(colors), TEXT);
            assertMeets("framework palette" + (light ? ", light" : ", dark"), controls(colors), NON_TEXT);
        }
    }

    /** What the ratios actually are, so a reader of a failure elsewhere knows the margin. */
    @Test
    public void theFixedPalettesRatios() {
        ScreenColors dark = ScreenColors.of(TonePalette.fallback(), false);
        ScreenColors light = ScreenColors.of(TonePalette.fallback(), true);
        assertEquals(13.35, contrast(dark.title, dark.background), 0.01);
        assertEquals(10.11, contrast(dark.summary, dark.background), 0.01);
        assertEquals(10.10, contrast(dark.heading, dark.background), 0.01);
        assertEquals(16.73, contrast(light.title, light.background), 0.01);
        assertEquals(9.12, contrast(light.summary, light.background), 0.01);
        assertEquals(6.32, contrast(light.heading, light.background), 0.01);
    }

    /**
     * The negative control: a low-contrast pair has to fail the same check. Grey tone 60 on tone 50
     * is legible to nobody.
     */
    @Test
    public void aLowContrastPairFails() {
        TonePalette palette = TonePalette.fallback();
        Map<String, int[]> weak = new LinkedHashMap<>();
        weak.put("tone 60 on tone 50", new int[]{palette.tone(TonePalette.NEUTRAL, 60), palette.tone(TonePalette.NEUTRAL, 50)});
        try {
            assertMeets("control", weak, TEXT);
        } catch (AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("tone 60 on tone 50"));
            // A pair that clears 3:1 and not 4.5:1 passes as a control and fails as text.
            Map<String, int[]> between = new LinkedHashMap<>();
            between.put("neutral 60 on white", new int[]{palette.tone(TonePalette.NEUTRAL, 60), Color.WHITE});
            assertMeets("control", between, NON_TEXT);
            try {
                assertMeets("control", between, TEXT);
            } catch (AssertionError alsoExpected) {
                return;
            }
            fail("neutral tone 60 on white passed as text at "
                    + contrast(palette.tone(TonePalette.NEUTRAL, 60), Color.WHITE));
        }
        fail("a pair at " + contrast(weak.values().iterator().next()[0], weak.values().iterator().next()[1]) + ":1 passed");
    }

    /** Without the theme in the build the screen stays what it was: black, with the dark Material theme. */
    @Test
    public void withoutTheThemeTheScreenStaysBlack() {
        PatchFamily.inBuildForTests = EnumSet.complementOf(EnumSet.of(PatchFamily.MATERIAL_YOU_THEME));
        Context context = RuntimeEnvironment.getApplication();
        assertNull(ScreenColors.forScreen(context));
        assertEquals(android.R.style.Theme_Material_NoActionBar, ScreenColors.themeFor(context));
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            assertNull(ScreenColors.shown);
            assertEquals(Color.BLACK, ((ColorDrawable) dialog.getView().getBackground()).getColor());
        }
    }

    @Test
    public void withTheThemeALightPhoneGetsALightScreen() {
        assertScreenPainted(true);
    }

    @Test
    @Config(qualifiers = "night")
    public void withTheThemeADarkPhoneGetsADarkScreen() {
        assertScreenPainted(false);
    }

    private void assertScreenPainted(boolean light) {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            ScreenColors colors = ScreenColors.shown;
            assertNotNull("the page has no palette colours", colors);
            assertEquals(light, colors.light);
            assertEquals(ScreenColors.of(TonePalette.fallback(), light).background, colors.background);
            assertEquals(colors.background, ((ColorDrawable) dialog.getView().getBackground()).getColor());

            int titles = 0;
            int switches = 0;
            for (View row : rows(dialog)) {
                TextView title = row.findViewById(android.R.id.title);
                if (title == null) continue;
                int drawn = title.getCurrentTextColor();
                assertTrue("\"" + title.getText() + "\" is " + Integer.toHexString(drawn),
                        drawn == colors.title || drawn == colors.heading);
                assertTrue(contrast(drawn, colors.background) >= TEXT);
                titles++;
                TextView summary = row.findViewById(android.R.id.summary);
                if (summary != null && summary.getVisibility() == View.VISIBLE) {
                    assertEquals(colors.summary, summary.getCurrentTextColor());
                }
                View widget = row.findViewById(android.R.id.switch_widget);
                if (widget instanceof Switch) {
                    assertNotNull(((Switch) widget).getThumbTintList());
                    assertEquals(colors.accent, ((Switch) widget).getThumbTintList()
                            .getColorForState(new int[]{android.R.attr.state_checked}, 0));
                    switches++;
                }
            }
            assertTrue("no row titles were painted", titles > 10);
            assertTrue("no switch was painted", switches > 3);
            assertFalse(colors.light != light);
        }
    }

    /**
     * The save folder's field. The framework theme draws its underline, cursor and selection in
     * Facebook's teal, which clashes with every wallpaper, so they take the accent the dialog's
     * buttons have, and the underline reads against the dialog at the ratio a control needs.
     */
    @Test
    public void theFolderDialogsFieldTakesTheAccent() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog dialog = show(controller.get());
            HushfacebookPreferenceFragment page = (HushfacebookPreferenceFragment)
                    dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
            HushfacebookPreferenceFragment.FolderRow row =
                    (HushfacebookPreferenceFragment.FolderRow) page.findPreference(Settings.SAVE_FOLDER.key);
            assertNotNull("no save folder row", row);
            row.showDialog(null);
            ShadowLooper.idleMainLooper();
            try {
                ScreenColors colors = ScreenColors.shown;
                assertNotNull(colors);
                EditText field = row.getEditText();
                assertNotNull("the underline keeps the framework's colour", field.getBackgroundTintList());
                assertEquals(colors.accent, field.getBackgroundTintList().getDefaultColor());
                assertEquals(ScreenColors.half(colors.accent), field.getHighlightColor());
                assertTrue(contrast(colors.accent, colors.dialog) >= NON_TEXT);
            } finally {
                row.getDialog().dismiss();
            }
        }
    }

    private static SettingsDialog show(Activity activity) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(activity.getFragmentManager(), "hushfacebook_settings");
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page", page instanceof HushfacebookPreferenceFragment);
        return dialog;
    }

    /** Every row the list draws, laid out tall enough that none is left off. */
    private static List<View> rows(SettingsDialog dialog) {
        ListView list = dialog.getView().findViewById(android.R.id.list);
        assertNotNull("no list in the dialog", list);
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40000, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 40000);
        List<View> rows = new ArrayList<>();
        for (int index = 0; index < list.getChildCount(); index++) rows.add(list.getChildAt(index));
        return rows;
    }
}
