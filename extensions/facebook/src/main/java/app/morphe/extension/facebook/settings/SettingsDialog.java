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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;

/**
 * The Hushfacebook screen: a full screen, black dialog with a title bar and the preference list.
 * It is a framework dialog fragment, so it needs no activity of its own and Back closes it.
 */
@SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
public final class SettingsDialog extends DialogFragment {
    private int containerId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // With three-button navigation Android lays a grey scrim under the buttons; the
            // screen is black edge to edge, so the scrim only shows as a grey band.
            window.setNavigationBarContrastEnforced(false);
        }
        return dialog;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(16);
        bar.setPadding(dp(4), dp(8), pad, dp(8));

        TextView back = new TextView(getContext());
        back.setText("←");
        back.setTextColor(Color.WHITE);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(dp(48));
        back.setMinHeight(dp(48));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> {
            SettingsEntry.onClosedByUser();
            dismissAllowingStateLoss();
        });
        bar.addView(back);

        TextView title = new TextView(getContext());
        title.setText("Hushfacebook");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPadding(dp(8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        FrameLayout container = new FrameLayout(getContext());
        containerId = View.generateViewId();
        container.setId(containerId);
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
            if (getChildFragmentManager().findFragmentById(containerId) == null) {
                getChildFragmentManager().beginTransaction()
                        .replace(containerId, new HushfacebookPreferenceFragment())
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
}
