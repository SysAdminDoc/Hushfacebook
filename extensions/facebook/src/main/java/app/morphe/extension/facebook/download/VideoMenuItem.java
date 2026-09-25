/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.lang.reflect.Method;
import java.util.List;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The Download to phone item that Download any video adds to the menu of a post holding a video,
 * in the feed and in Watch.
 *
 * <p>Facebook fills every post's menu through one call, and the patch calls {@link #add} right
 * after that call returns. The item goes below Facebook's own and none of them changes, its own
 * Download video included on the posts where it offers one. With the switch off, paused, or
 * before the settings are ready, the menu is Facebook's own.
 *
 * <p>Nothing here names a class or member that Facebook renames. The post and its attachment are
 * kept GraphQL model classes, and the patch finds their getters by the GraphQL field each one
 * reads and hands their names over. The media's own fields are read the way Facebook's code reads
 * them, by the hash of the field's GraphQL name.
 */
public final class VideoMenuItem {

    private VideoMenuItem() {}

    /** The source every event of the item carries in the diagnostic report. */
    static final String SOURCE = "VideoMenuItem";

    private static final String GRAPHQL_STORY = "com.facebook.graphql.model.GraphQLStory";
    private static final String GRAPHQL_STORY_ATTACHMENT = "com.facebook.graphql.model.GraphQLStoryAttachment";

    /**
     * The media's fields, by the hash Facebook's models use for a field name: the Java hash of
     * the GraphQL name, which doesn't move when Redex renames the code around it.
     */
    static final int ID = "id".hashCode();
    static final int PLAYABLE_URL = "playable_url".hashCode();
    static final int HD_PLAYABLE_URL = "hd_playable_url".hashCode();

    /** What GraphQL calls a video. A photo post's media is a Photo. */
    static final String VIDEO_TYPE = "Video";

    /** How many shared posts deep the video is looked for, as Facebook's own menu does. */
    private static final int MAX_SHARE_DEPTH = 4;

    /**
     * Adds the item to [menu] when the post Facebook filled it for holds a video.
     *
     * <p>[item] is what the menu was built for: the post, or one of its attachments.
     * [attachmentsGetter], [mediaGetter] and [attachedStoryGetter] are the real names of the
     * post's attachment list, an attachment's media and the post a share wraps. [icon] is the
     * drawable Facebook's own Download video row uses in this build. Never throws: this runs
     * inside Facebook's menu code, and false is always Facebook's own menu.
     */
    public static void add(Menu menu, View anchor, Object item, int icon,
                           String attachmentsGetter, String mediaGetter, String attachedStoryGetter) {
        try {
            if (!Utils.settingsReady() || !Settings.DOWNLOAD_VIDEOS.get()) return;
            if (menu == null) return;
            HookStatus.invoked(FamilyNames.VIDEO_DOWNLOAD);

            Object media = mediaOf(item, attachmentsGetter, mediaGetter, attachedStoryGetter);
            if (media == null) return;

            Video video = Video.of(media);
            if (!video.offered()) return;

            MenuItem entry = menu.add(L10n.t("Download to phone"));
            if (entry == null) return;
            if (icon != 0) entry.setIcon(icon);
            Context context = anchor == null ? null : anchor.getContext();
            entry.setOnMenuItemClickListener(tapped -> {
                tap(context, video);
                return true;
            });
            HookStatus.bound(FamilyNames.VIDEO_DOWNLOAD, "video menu item");
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "video menu", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not add Download to phone to a post's menu", t);
        }
    }

    /**
     * A tap on the item: the save, or a message saying it didn't start. The report says why in
     * either case, and never with an address or a file name.
     */
    static void tap(Context context, Video video) {
        try {
            // A menu can stay open while the switch goes off. The tap then does nothing, quietly.
            if (!Utils.settingsReady() || !Settings.DOWNLOAD_VIDEOS.get()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Download to phone tapped after its switch went off");
                return;
            }
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "Download to phone tapped");
            Context application = context == null ? Utils.getContext() : context.getApplicationContext();
            if (MediaDownload.saveFeedVideo(application, video.id, video.hd, video.sd)) return;

            Feedback.show(application,
                L10n.t(application, "Couldn't save this video. Play it for a moment, then try again."), true);
        } catch (Throwable t) {
            // It runs inside Facebook's click dispatch, on the thread that draws the app.
            HookStatus.threw(FamilyNames.VIDEO_DOWNLOAD, "video menu tap", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the Download to phone tap failed", t);
        }
    }

    /**
     * The media of [item], or {@code null} when it has none. A post that shares another post
     * carries the video in the shared one, so that's followed first, the way Facebook's own menu
     * finds a post's media. Then the first attachment's media, again as Facebook does it.
     */
    static Object mediaOf(Object item, String attachmentsGetter, String mediaGetter, String attachedStoryGetter) {
        if (item == null) return null;

        if (isA(item, GRAPHQL_STORY_ATTACHMENT)) return call(item, mediaGetter);
        if (!isA(item, GRAPHQL_STORY)) return null;

        Object story = item;
        for (int depth = 0; depth < MAX_SHARE_DEPTH; depth++) {
            Object shared = call(story, attachedStoryGetter);
            if (shared == null) break;
            story = shared;
        }

        Object attachments = call(story, attachmentsGetter);
        if (!(attachments instanceof List) || ((List<?>) attachments).isEmpty()) return null;
        Object first = ((List<?>) attachments).get(0);
        return first == null ? null : call(first, mediaGetter);
    }

    /** What the item needs of one video: its id, the two single files, and whether it says it's one. */
    static final class Video {
        final String id;
        final String hd;
        final String sd;
        final boolean typed;

        Video(String id, String hd, String sd, boolean typed) {
            this.id = id;
            this.hd = hd;
            this.sd = sd;
            this.typed = typed;
        }

        static Video of(Object media) {
            return new Video(cachedString(media, ID), cachedString(media, HD_PLAYABLE_URL),
                cachedString(media, PLAYABLE_URL), VIDEO_TYPE.equals(typeName(media)));
        }

        /**
         * Whether the post gets the item: its media says it's a video, or there's something to
         * save. A photo post answers neither and keeps Facebook's own menu.
         */
        boolean offered() {
            return typed || RenditionPicker.isHttpUrl(hd) || RenditionPicker.isHttpUrl(sd)
                || PlayerSources.byId(id) != null;
        }
    }

    // ---------------------------------------------------------------- reflection

    private static boolean isA(Object value, String className) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(className)) return true;
        }
        return false;
    }

    /**
     * The answer of the public getter [name] of [host]. A getter that isn't there is a build that
     * moved it, which Hook status reports; any other failure is only a post without that part.
     */
    private static Object call(Object host, String name) {
        if (host == null || name == null) return null;
        Method method;
        try {
            method = host.getClass().getMethod(name);
        } catch (NoSuchMethodException moved) {
            HookStatus.missingMember(FamilyNames.VIDEO_DOWNLOAD, "method", host.getClass().getName(), name);
            return null;
        }
        try {
            return method.invoke(host);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A string field of a GraphQL model, by the hash of its name, the way Facebook's code reads
     * one. The model checks its own native tree before reading it.
     */
    private static String cachedString(Object model, int field) {
        if (model == null) return null;
        try {
            Object value = model.getClass().getMethod("getCachedString", int.class).invoke(model, field);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The model's GraphQL type name, asked only of a model whose native tree is still there,
     * since the name is read from it.
     */
    private static String typeName(Object model) {
        if (model == null) return null;
        try {
            Object valid = model.getClass().getMethod("isValidGraphServicesJNIModel").invoke(model);
            if (!Boolean.TRUE.equals(valid)) return null;
            Object name = model.getClass().getMethod("getTypeName").invoke(model);
            return name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
