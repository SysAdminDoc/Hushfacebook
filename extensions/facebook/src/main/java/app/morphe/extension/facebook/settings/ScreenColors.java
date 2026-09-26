/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.theme.TonePalette;

/**
 * The colours of the Hushfacebook screen, its rows and its dialogs when the Material You theme is
 * in this build. The screen then follows the phone's dark or light setting, in the tones of the
 * wallpaper palette (Android 12 and newer) or of the fixed palette Android 11 gets.
 *
 * <p>A tone is a lightness, so the contrast of each pair below is the same for any wallpaper. The
 * pairs are Material 3's: the page is the neutral at tone 10 in dark and 99 in light, text is the
 * neutral at 90 or 10, secondary text the neutral variant at 80 or 30, and section titles, switches
 * and dialog buttons the accent at 80 or 40. Every pair meets WCAG 2.2 AA with room to spare,
 * which ScreenColorsTest works out.
 *
 * <p>Without the theme in the build, {@link #forScreen} answers null and the screen keeps the black
 * page and dark Material rows it always had.
 */
final class ScreenColors {
    final boolean light;
    /** The page, behind the title bar and every row. */
    final int background;
    /** A dialog's surface. */
    final int dialog;
    /** Row and dialog titles, the title bar and its back arrow. */
    final int title;
    /** Row summaries and a dialog's message. */
    final int summary;
    /** Section titles. */
    final int heading;
    /** A switch that's on, and the buttons of a dialog. */
    final int accent;
    /** A switch that's off. */
    final int switchOff;
    /** Grouped preference rows and their edge. */
    final int card;
    final int outline;
    /** Text on the filled primary dialog action. */
    final int onAccent;

    /** The colours the screen on show was built with, or null for the black page. */
    @Nullable
    static volatile ScreenColors shown;
    private static final ScreenColors DEFAULT = new ScreenColors();

    private ScreenColors() {
        light = false;
        background = Color.BLACK;
        dialog = 0xFF171A20;
        title = Color.WHITE;
        summary = 0xFFBFC4D0;
        heading = 0xFF69A4FF;
        accent = 0xFF1769E0;
        switchOff = 0xFF858D9C;
        card = 0xFF141820;
        outline = 0xFF333A46;
        onAccent = Color.WHITE;
    }

    private ScreenColors(TonePalette palette, boolean light) {
        this.light = light;
        if (light) {
            background = palette.tone(TonePalette.NEUTRAL, 99);
            dialog = palette.tone(TonePalette.NEUTRAL, 95);
            title = palette.tone(TonePalette.NEUTRAL, 10);
            summary = palette.tone(TonePalette.NEUTRAL_VARIANT, 30);
            heading = palette.tone(TonePalette.ACCENT, 40);
            accent = palette.tone(TonePalette.ACCENT, 40);
            switchOff = palette.tone(TonePalette.NEUTRAL_VARIANT, 50);
            card = palette.tone(TonePalette.NEUTRAL, 95);
            outline = palette.tone(TonePalette.NEUTRAL_VARIANT, 80);
            onAccent = Color.WHITE;
        } else {
            background = palette.tone(TonePalette.NEUTRAL, 10);
            dialog = palette.tone(TonePalette.NEUTRAL, 20);
            title = palette.tone(TonePalette.NEUTRAL, 90);
            summary = palette.tone(TonePalette.NEUTRAL_VARIANT, 80);
            heading = palette.tone(TonePalette.ACCENT, 80);
            accent = palette.tone(TonePalette.ACCENT, 80);
            switchOff = palette.tone(TonePalette.NEUTRAL_VARIANT, 60);
            card = palette.tone(TonePalette.NEUTRAL, 20);
            outline = palette.tone(TonePalette.NEUTRAL_VARIANT, 30);
            onAccent = background;
        }
    }

    static ScreenColors of(TonePalette palette, boolean light) {
        return new ScreenColors(palette, light);
    }

    /**
     * The colours for a screen shown in this context: the phone's palette, dark or light as the
     * context's configuration says. Null when the Material You theme isn't in this build.
     */
    @Nullable
    static ScreenColors forScreen(Context context) {
        if (!PatchFamily.MATERIAL_YOU_THEME.inBuild()) return null;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new ScreenColors(TonePalette.of(context), night != Configuration.UI_MODE_NIGHT_YES);
    }

    /** The framework theme rows and dialogs are built with, so what this class doesn't paint still reads. */
    int theme() {
        return light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar;
    }

    /** {@link #theme()} of {@link #forScreen}, or the dark Material theme the black page has always had. */
    static int themeFor(Context context) {
        ScreenColors colors = forScreen(context);
        return colors == null ? android.R.style.Theme_Material_NoActionBar : colors.theme();
    }

    /** A row's title and summary, and its switch if it has one. */
    void paintRow(View row, Preference preference) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(this.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) summary.setTextColor(this.summary);
        View widget = row.findViewById(android.R.id.switch_widget);
        if (widget instanceof Switch) {
            int[][] states = {{android.R.attr.state_checked}, {}};
            Switch toggle = (Switch) widget;
            toggle.setThumbTintList(new ColorStateList(states, new int[]{accent, switchOff}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{half(accent), half(switchOff)}));
        }
        PreferenceGroup parent = preference.getParent();
        boolean grouped = parent instanceof PreferenceCategory;
        boolean first = !grouped || parent.getPreference(0) == preference;
        boolean last = !grouped || parent.getPreference(parent.getPreferenceCount() - 1) == preference;
        float radius = dp(row, 18);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(card);
        surface.setStroke(dp(row, 1), outline);
        surface.setCornerRadii(new float[]{
                first ? radius : 0, first ? radius : 0,
                first ? radius : 0, first ? radius : 0,
                last ? radius : 0, last ? radius : 0,
                last ? radius : 0, last ? radius : 0});
        Drawable inset = new InsetDrawable(surface, dp(row, 10), first ? dp(row, 6) : 0,
                dp(row, 10), last ? dp(row, 6) : 0);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(ColorStateList.valueOf(half(accent)), inset, null) : inset);
    }

    /** A section title. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) title.setTextColor(heading);
        row.setPaddingRelative(dp(row, 16), dp(row, 16), dp(row, 16), dp(row, 2));
    }

    /**
     * A dialog on show: its surface, title, message, buttons and text field. A list's rows keep the
     * theme's colours.
     */
    void paint(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(this.dialog);
            panel.setCornerRadius(dp(window.getDecorView(), 22));
            panel.setStroke(dp(window.getDecorView(), 1), outline);
            window.setBackgroundDrawable(panel);
        }
        @SuppressWarnings("DiscouragedApi")
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) title.setTextColor(this.title);
        // AlertDialog's own message, and the one a preference's dialog layout brings, which is the
        // one on show there: AlertDialog's own sits GONE above it, and findViewById finds that first.
        if (window != null) paintMessages(window.getDecorView());
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            boolean primary = which == AlertDialog.BUTTON_POSITIVE;
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(primary ? accent : this.dialog);
            pill.setCornerRadius(dp(button, 22));
            pill.setStroke(dp(button, 1), primary ? accent : outline);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                    pill, null));
            button.setTextColor(primary ? onAccent : accent);
            button.setAllCaps(false);
            button.setPaddingRelative(dp(button, 18), dp(button, 6), dp(button, 18), dp(button, 6));
        }
        View field = dialog.findViewById(android.R.id.edit);
        if (field instanceof EditText) paintField((EditText) field);
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in
     * Facebook's teal, whatever the wallpaper, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setHintTextColor(summary);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(dialog);
        outline.setCornerRadius(dp(field, 12));
        outline.setStroke(dp(field, 2), accent);
        field.setBackgroundTintList(null);
        field.setBackground(outline);
        field.setPaddingRelative(dp(field, 12), dp(field, 10), dp(field, 12), dp(field, 10));
        field.setTextColor(title);
        field.setHighlightColor(half(accent));
        Drawable cursor = field.getTextCursorDrawable();
        if (cursor != null) {
            cursor = cursor.mutate();
            cursor.setTint(accent);
            field.setTextCursorDrawable(cursor);
        }
        Drawable handle = field.getTextSelectHandle();
        if (handle != null) {
            handle = handle.mutate();
            handle.setTint(accent);
            field.setTextSelectHandle(handle);
        }
        Drawable left = field.getTextSelectHandleLeft();
        if (left != null) {
            left = left.mutate();
            left.setTint(accent);
            field.setTextSelectHandleLeft(left);
        }
        Drawable right = field.getTextSelectHandleRight();
        if (right != null) {
            right = right.mutate();
            right.setTint(accent);
            field.setTextSelectHandleRight(right);
        }
    }

    private void paintMessages(View view) {
        if (view instanceof TextView && view.getId() == android.R.id.message) ((TextView) view).setTextColor(summary);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintMessages(group.getChildAt(index));
        }
    }

    /** Paints a row with the colours on show, if there are any. */
    static void row(View row, Preference preference) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintRow(row, preference);
    }

    /** Paints a section title with the colours on show, if there are any. */
    static void heading(View row) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paintHeading(row);
    }

    /** Paints a dialog with the colours on show, if there are any. */
    static void dialog(@Nullable AlertDialog dialog) {
        ScreenColors colors = shown;
        (colors == null ? DEFAULT : colors).paint(dialog);
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /** A switch's track: the thumb's colour at half strength, as Material's own switch draws it. */
    static int half(int color) {
        return (color & 0x00FFFFFF) | 0x80000000;
    }
}
