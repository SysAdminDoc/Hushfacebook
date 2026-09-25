/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.settings;

/**
 * The name each patch goes by in Morphe Manager, which is also the family its hooks report
 * under in Hook status.
 *
 * <p>Compile-time constants, so a hook that names its family inlines the text and loads no
 * class. That matters for the hooks that can run before Hushfacebook has a context, such as the
 * signature check Facebook makes while its content providers start: touching {@link PatchFamily}
 * there would load the settings with no context to read them from.
 */
public final class FamilyNames {
    public static final String SPONSORED_POSTS = "Hide sponsored posts";
    public static final String SUGGESTED_POSTS = "Hide suggested and promoted posts";
    public static final String SPONSORED_STORIES = "Hide sponsored stories";
    public static final String SPONSORED_REELS = "Hide sponsored reels";
    public static final String EXTERNAL_BROWSER = "Open links in external browser";
    public static final String STORY_DOWNLOAD = "Download any story";
    public static final String REEL_DOWNLOAD = "Download any reel";
    public static final String AD_PREFETCH = "Block background ad prefetch";
    public static final String AD_TELEMETRY = "Block ad telemetry";
    public static final String AUDIENCE_NETWORK = "Disable Audience Network";
    public static final String AMOLED_THEME = "AMOLED black theme";
    public static final String RESTORE_TRUST = "Restore screens on re-signed builds";

    private FamilyNames() {
    }
}
