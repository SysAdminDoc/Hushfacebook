/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import android.app.Dialog;
import android.app.DialogFragment;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;

/**
 * The Hushfacebook screen: a full screen, black dialog with a title bar and the preference list.
 * It is a framework dialog fragment, so it needs no activity of its own and Back closes it. With the
 * Material You theme in the build, the page follows the phone's dark or light setting in its
 * wallpaper colours instead ({@link ScreenColors}).
 */
@SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
public final class SettingsDialog extends DialogFragment {
    /**
     * The preference list's container. Fixed, because the child manager saves the page with this
     * id and puts it back into a view with the same id after rotation or process recreation. A
     * fresh View.generateViewId() on each onCreateView left the restored page no container.
     * Outside both the generated-id range and aapt's 0x7f ids, and nothing else in this dialog
     * carries an id.
     */
    static final int CONTAINER_ID = 0x48464301;

    /** The back arrows, left and right, built from their code points. */
    static final String BACK_ARROW = String.valueOf((char) 0x2190);
    static final String BACK_ARROW_RIGHT_TO_LEFT = String.valueOf((char) 0x2192);

    /** The page's colours, or null for the black page. Read when the dialog is created. */
    @Nullable
    private ScreenColors colors;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        colors = ScreenColors.forScreen(getContext());
        setStyle(STYLE_NO_TITLE, colors == null ? android.R.style.Theme_Material_NoActionBar : colors.theme());
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background()));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // With three-button navigation Android lays a grey scrim under the buttons; the
            // screen is black edge to edge, so the scrim only shows as a grey band.
            window.setNavigationBarContrastEnforced(false);
            // Below Android 15 this window draws its own bars in the framework theme's colours,
            // grey and black, which a light page's dark icons can't be read on. The page's colour
            // goes behind them instead. Android 15 and newer draw the page there already.
            if (colors != null) {
                window.setStatusBarColor(colors.background);
                window.setNavigationBarColor(colors.background);
            }
        }
        return dialog;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(16);
        // Start and end, not left and right: in a right-to-left language the bar is mirrored.
        bar.setPaddingRelative(dp(4), dp(12), pad, dp(12));

        TextView back = new TextView(getContext());
        back.setText(rightToLeft() ? BACK_ARROW_RIGHT_TO_LEFT : BACK_ARROW);
        back.setTextColor(foreground());
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(dp(48));
        back.setMinHeight(dp(48));
        back.setContentDescription(L10n.t(getContext(), "Back"));
        // A screen reader calls it a button, as it would the back arrow of any other screen.
        back.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        back.setOnClickListener(v -> {
            SettingsEntry.onClosedByUser();
            dismissAllowingStateLoss();
        });
        bar.addView(back);

        TextView title = new TextView(getContext());
        title.setText("Hushfacebook");
        title.setTextColor(foreground());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setPaddingRelative(dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title);
        root.addView(bar);

        FrameLayout container = new FrameLayout(getContext());
        container.setId(CONTAINER_ID);
        root.addView(container, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Facebook targets a recent API, so its windows are edge to edge: keep the bars off the
        // content.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        return root;
    }

    /**
     * On a light page, dark status and navigation bar icons, which the window only takes once it
     * has its decor.
     */
    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        if (colors == null || !colors.light || window == null) return;
        WindowInsetsController bars = window.getInsetsController();
        if (bars == null) return;
        int dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        bars.setSystemBarsAppearance(dark, dark);
    }

    private int background() {
        return colors == null ? Color.BLACK : colors.background;
    }

    private int foreground() {
        return colors == null ? Color.WHITE : colors.title;
    }

    @Override
    public void onCancel(android.content.DialogInterface dialog) {
        // Back key or a tap outside: the person closed it.
        SettingsEntry.onClosedByUser();
        super.onCancel(dialog);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            if (getChildFragmentManager().findFragmentById(CONTAINER_ID) == null) {
                getChildFragmentManager().beginTransaction()
                        .replace(CONTAINER_ID, new HushfacebookPreferenceFragment())
                        .commitNow();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the preference list", ex);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** Whether the phone's language reads right to left, so the way back points right. */
    private boolean rightToLeft() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }
}
