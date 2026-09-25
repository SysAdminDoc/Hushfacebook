/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/Feedback.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;

/**
 * Tells the user what happened.
 *
 * <p>A download takes seconds and happens away from the screen, so without a word from it a tap
 * looks like it did nothing and the user taps again.
 *
 * <p>The text is Hushfacebook's own, read from {@link app.morphe.extension.shared.L10n} in the
 * phone's language, rather than taken from the app: Facebook keeps the words for its own menus in
 * a pack that it downloads, not in the resources of the APK, so there is nothing to borrow.
 */
final class Feedback {

    private Feedback() {}

    /** The source a message that couldn't be shown carries in the diagnostic report. */
    private static final String SOURCE = "Feedback";

    static void show(Context applicationContext, String text, boolean longToast) {
        if (applicationContext == null || text == null) return;

        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                // This is the one place where this patch runs on the thread that draws the app,
                // so it carries its own guard. An exception from here reaches the
                // looper and stops the app.
                try {
                    Toast.makeText(
                        applicationContext,
                        text,
                        longToast ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT
                    ).show();
                } catch (Throwable t) {
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not show a message", t);
                }
            });
        } catch (Throwable t) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "could not reach the main thread", t);
        }
    }
}
