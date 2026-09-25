/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.parent;

import app.morphe.extension.shared.Logger;

/**
 * Settings shared across multiple apps.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class BaseSettings {
    public static final BooleanSetting DEBUG = new BooleanSetting("morphe_debug", FALSE);
    public static final StringSetting DEBUG_LOG_FILTERS = new StringSetting("morphe_debug_log_filters", "all", false, false);
    public static final BooleanSetting CAPTURE_JAVA_CRASHES =
            new BooleanSetting("morphe_capture_java_crashes", FALSE, true);
    public static final BooleanSetting DEBUG_STACKTRACE = new BooleanSetting("morphe_debug_stacktrace", FALSE, parent(DEBUG));
    /**
     * Whether a failure inside the bundle is also put on screen while {@link #DEBUG} is on.
     * No confirmation dialog: the message it carried named a string resource this bundle does
     * not ship, and the row that now exposes the switch would have raised an empty dialog.
     */
    public static final BooleanSetting DEBUG_TOAST_ON_ERROR = new BooleanSetting("morphe_debug_toast_on_error", TRUE);
    public static final BooleanSetting SANITIZE_SHARING_LINKS = new BooleanSetting("morphe_sanitize_sharing_links", TRUE);

    public static final IntegerSetting CHECK_ENVIRONMENT_WARNINGS_ISSUED = new IntegerSetting("morphe_check_environment_warnings_issued", 0, true, false);

    public static final EnumSetting<AppLanguage> MORPHE_LANGUAGE = new EnumSetting<>("morphe_language", AppLanguage.DEFAULT, true, "morphe_language_user_dialog_message");

    /**
     * Use the icons declared in the preferences created during patching. If no icons or styles are declared then this setting does nothing.
     */
    public static final BooleanSetting SHOW_MENU_ICONS = new BooleanSetting("morphe_show_menu_icons", TRUE, true);

    /**
     * The first time the app was launched with no previous app data (either a clean install, or after wiping app data).
     */
    public static final LongSetting FIRST_TIME_APP_LAUNCHED = new LongSetting("morphe_last_time_app_was_launched", -1L, false, false);

    public static final StringSetting EXPERIMENTAL_APP_CONFIRMED = new StringSetting("morphe_experimental_app_target_confirmed", "", false, false);

    /**
     * Pause Hushfacebook: from the next start every hook a setting controls takes Facebook's own
     * path, and every saved value stays as it is. Edits made at patch time have no setting to ask
     * and stay in. Left out of backups, so restoring one never pauses a phone by surprise.
     */
    public static final BooleanSetting PAUSED = new BooleanSetting("hushfacebook_paused", FALSE, true, false, null, null);

    /** Set by {@link HushfacebookPause} after three starts in a row crashed within a minute. */
    public static final BooleanSetting SAFE_MODE = new BooleanSetting("hushfacebook_safe_mode", FALSE, true, false, null, null);

    static {
        // Hushfacebook's own state and its diagnostics, which keep working while it is paused.
        Setting.keepWhenPaused(DEBUG, DEBUG_LOG_FILTERS, CAPTURE_JAVA_CRASHES, DEBUG_STACKTRACE,
                DEBUG_TOAST_ON_ERROR, CHECK_ENVIRONMENT_WARNINGS_ISSUED, MORPHE_LANGUAGE, SHOW_MENU_ICONS,
                FIRST_TIME_APP_LAUNCHED, EXPERIMENTAL_APP_CONFIRMED, PAUSED, SAFE_MODE);

        final long now = System.currentTimeMillis();

        if (FIRST_TIME_APP_LAUNCHED.savedValue() < 0) {
            Logger.printInfo(() -> "First launch of installation with no prior app data");
            FIRST_TIME_APP_LAUNCHED.save(now);
        }
    }
}
