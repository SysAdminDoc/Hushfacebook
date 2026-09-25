/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.extension.facebook.settings;

import static java.lang.Boolean.TRUE;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * The switches behind the hooks that ask before they act.
 *
 * <p>Every one of them is on by default. Picking a patch in Morphe Manager is the choice to use
 * it, and the switch is the way to turn it off again without patching a second time. While
 * Hushfacebook is paused, or in safe mode after three crashed starts, each switch answers off
 * and the hook behind it takes Facebook's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    /** Feed edges Facebook files under the SPONSORED story category. */
    public static final BooleanSetting HIDE_SPONSORED_POSTS =
            new BooleanSetting("hushfacebook_hide_sponsored_posts", TRUE);

    /** Feed edges filed under PROMOTION, which FroggoMorphePatches also drops. */
    public static final BooleanSetting HIDE_PROMOTED_POSTS =
            new BooleanSetting("hushfacebook_hide_promoted_posts", TRUE);

    /** "Pages you may like", Facebook's own upsell units and the in-feed surveys. */
    public static final BooleanSetting HIDE_SUGGESTED_POSTS =
            new BooleanSetting("hushfacebook_hide_suggested_posts", TRUE);

    /** The four story bucket sources that splice ad cards into the story viewer. */
    public static final BooleanSetting HIDE_SPONSORED_STORIES =
            new BooleanSetting("hushfacebook_hide_sponsored_stories", TRUE);

    /** The two page filters that take server-inlined ads out of Reels and Watch. */
    public static final BooleanSetting HIDE_SPONSORED_REELS =
            new BooleanSetting("hushfacebook_hide_sponsored_reels", TRUE);

    /** Web links leave Facebook's in-app browser for the default browser. */
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY =
            new BooleanSetting("hushfacebook_open_links_externally", TRUE);

    /**
     * The story viewer's menu offers Save on anyone's story, and Save runs Hushfacebook's own
     * download. Off, only your own stories offer it, and it's Facebook's own save.
     */
    public static final BooleanSetting DOWNLOAD_STORIES =
            new BooleanSetting("hushfacebook_download_stories", TRUE);

    /** The Download button the reel patch adds to every reel's sidebar. */
    public static final BooleanSetting DOWNLOAD_REELS =
            new BooleanSetting("hushfacebook_download_reels", TRUE);
}
