/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/ReelDownload.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import android.content.Context;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import kotlin.jvm.functions.Function1;

/**
 * The handler behind a download button added to the reel sidebar.
 *
 * <p>The sidebar builds each of its buttons through one factory of the app, and that factory takes
 * its handlers as {@code Function1}. The reference bundle had no extension of its own, so it had to
 * borrow one of the app's multiplexed lambdas and pick unused case numbers inside it. The module
 * here already carries the Kotlin standard library, so this implements {@code Function1} directly
 * and is passed straight in. Nothing of the app is modified to hold it.
 *
 * <p>The object carries the player of the item that the button belongs to, so the file saved is
 * always the reel on the screen. The app prepares the reels that come next, so a handler that
 * reads a shared place instead saves the wrong one.
 *
 * <h2>Slots</h2>
 *
 * <p>The factory takes several handlers and only one of them is the tap. Which one is not written
 * anywhere, and the render of the button cannot settle it either, so a run answered it. Every slot
 * got one of these with its own number, and the events they received named them. The touch
 * slot fires twice per press with a {@code MotionEvent}. A visibility slot fires on its own. One
 * slot fires once per press with an event that carries nothing but the {@code View}. That last one
 * is the tap, and it is the only slot that saves. The others are kept rather than left null. The
 * factory is not documented to accept null, and a silent handler costs nothing.
 */
public final class ReelDownload implements Function1<Object, Object> {

    /** The source every tap of the button carries in the diagnostic report. */
    private static final String SOURCE = "ReelDownload";

    /** The player params of this item. Its source holds the addresses. */
    private final Object playerParams;

    private final Context context;

    /** Which handler of the factory this one was passed as. Diagnostic. */
    private final int slot;

    /** Whether this slot saves, or only reports that it ran. */
    private final boolean saves;

    /**
     * The real names of the two address fields of the source.
     *
     * <p>The patch reads them out of the app while patching and hands them over. The app renames
     * its fields on every release. The names that it reports for them do not change.
     */
    private final String hdField;
    private final String sdField;

    /** The real name of the field that holds the DASH manifest. The patch reads it the same way. */
    private final String manifestField;

    public ReelDownload(
        Object playerParams,
        Context context,
        String hdField,
        String sdField,
        String manifestField,
        int slot,
        boolean saves
    ) {
        this.playerParams = playerParams;
        this.context = context;
        this.hdField = hdField;
        this.sdField = sdField;
        this.manifestField = manifestField;
        this.slot = slot;
        this.saves = saves;
    }

    /**
     * Whether the sidebar Facebook is building for a reel gets the Download button. The patch asks
     * before it builds the button, so off, paused, or before the settings are ready, the reel has
     * only Facebook's own buttons and none of this patch's code runs in the sidebar. Never throws:
     * false is Facebook's own path.
     */
    public static boolean showsButton() {
        try {
            return Utils.settingsReady() && Settings.DOWNLOAD_REELS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "button switch", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "could not read the reel Download switch", t);
            return false;
        }
    }

    /**
     * The button's name, in the phone's language. Facebook's factory takes it as a plain string,
     * and it's what a screen reader says for the button, since nothing is written under the icon.
     * It was the English word written into the patch, so every phone heard "Download". Never throws.
     */
    public static String label() {
        try {
            return L10n.t("Download");
        } catch (Throwable t) {
            return "Download";
        }
    }

    /**
     * Answers null rather than {@code kotlin.Unit}. Facebook renames its own copy of Unit, so the
     * name does not resolve inside the app, and the sidebar's callers discard what a handler
     * answers without looking at it. null also passes any cast one of them might make.
     */
    @Override
    public Object invoke(Object argument) {
        try {
            // Only the tap slot does anything. The others get the same object, so that no
            // parameter of the factory is null, and they return without a word. They fire on every
            // touch and every visibility change, which buries the log that this feature needs.
            if (!saves) return null;

            HookStatus.invoked(FamilyNames.REEL_DOWNLOAD);
            final String event = argument == null ? "" : ", event " + argument.getClass().getName();
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "reel download tapped" + event);

            save();
        } catch (Throwable t) {
            // Nothing can leave this method. It runs on the thread that draws, inside the app's
            // own click dispatch, so a throw here ends the app rather than the download.
            HookStatus.threw(FamilyNames.REEL_DOWNLOAD, "reel handler", t);
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the reel handler failed", t);
        }

        return null;
    }

    private void save() {
        Object source = sourceOf(playerParams);

        if (source == null) {
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                () -> "the player of this reel holds no source", null);
            return;
        }

        MediaDownload.saveVideo(context, source, hdField, sdField, manifestField);
    }

    private static final String VIDEO_DATA_SOURCE = "com.facebook.video.engine.api.VideoDataSource";
    private static final String VIDEO_PLAYER_PARAMS = "com.facebook.video.engine.api.VideoPlayerParams";

    /**
     * The source of the player, found by type rather than by name.
     *
     * <p>The params object holds exactly one field of the source type, and that type is a name that
     * Redex keeps, so no letter has to be written down here. Two fields of it at one level would
     * mean that rule no longer picks the reel on the screen, so the walk takes neither and says
     * so in Hook status rather than save a reel that could be the next one.
     */
    static Object sourceOf(Object params) {
        if (params == null) return null;

        // The sidebar holds a rich params object, which holds the plain one, which holds the
        // source. Walking by type keeps all three of those names out of this file.
        Object current = params;

        for (int depth = 0; depth < 3 && current != null; depth++) {
            Object next = null;
            java.lang.reflect.Field sourceField = null;
            int sourceFields = 0;

            for (java.lang.reflect.Field field : current.getClass().getDeclaredFields()) {
                try {
                    String type = field.getType().getName();

                    if (type.equals(VIDEO_DATA_SOURCE)) {
                        if (sourceFields++ == 0) sourceField = field;
                    } else if (type.equals(VIDEO_PLAYER_PARAMS)) {
                        field.setAccessible(true);
                        next = field.get(current);
                    }
                } catch (Throwable ignored) {
                    // One unreadable field must not end the walk.
                }
            }

            if (sourceFields > 1) {
                HookStatus.ambiguous(FamilyNames.REEL_DOWNLOAD, "field", current.getClass().getName(),
                    VIDEO_DATA_SOURCE, sourceFields);
                return null;
            }
            if (sourceField != null) {
                HookStatus.bound(FamilyNames.REEL_DOWNLOAD, VIDEO_DATA_SOURCE);
                try {
                    sourceField.setAccessible(true);
                    return sourceField.get(current);
                } catch (Throwable unreadable) {
                    return null;
                }
            }

            current = next;
        }

        HookStatus.missingMember(FamilyNames.REEL_DOWNLOAD, "field", params.getClass().getName(), VIDEO_DATA_SOURCE);
        return null;
    }

}
