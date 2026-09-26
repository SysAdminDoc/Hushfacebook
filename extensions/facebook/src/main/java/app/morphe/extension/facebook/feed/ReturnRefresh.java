/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;

/** A one-use marker for a return after all Facebook UI was hidden. */
public final class ReturnRefresh {
    private static final long HOLD_MS = 10 * 60 * 1000L;
    private static long hiddenAt = -1;
    private static boolean registered;

    private ReturnRefresh() { }

    public static synchronized void register(Context context) {
        if (registered || !(context instanceof Application)) return;
        ((Application) context).registerComponentCallbacks(new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                if (level == TRIM_MEMORY_UI_HIDDEN) ReturnRefresh.uiHidden();
            }
            @Override public void onConfigurationChanged(Configuration configuration) { }
            @Override public void onLowMemory() { }
        });
        registered = true;
    }

    public static void uiHidden() {
        uiHidden(SystemClock.elapsedRealtime());
    }

    static synchronized void uiHidden(long now) {
        hiddenAt = now;
    }

    /** Called only from the feed's resume callback, never from swipe refresh or cold start. */
    public static boolean skip() {
        return skipAt(SystemClock.elapsedRealtime());
    }

    static synchronized boolean skipAt(long now) {
        long at = hiddenAt;
        hiddenAt = -1;
        return at >= 0 && now >= at && now - at <= HOLD_MS
                && Utils.settingsReady() && Settings.BLOCK_RETURN_REFRESH.get();
    }
}
