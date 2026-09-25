/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/PlayerSources.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The source of each player that the app builds, by video id.
 *
 * <p>A story card holds one video address, and it is 360p. The player of the same story holds a
 * DASH manifest with tracks up to 1080p. The save action holds the card and cannot get to the
 * player. But the card holds the video id of the player. So the patch records each source here by
 * its id, and the save finds it.
 *
 * <p>The id is what makes this safe. The app builds the players of the next items early, so "the
 * last source" is often a different video. A search by id finds the video on the screen, or
 * nothing.
 *
 * <p>The video patch reads the same record. A post's menu holds the post and not its player, and
 * the post's media carries the same id, so its Download to phone finds the player's manifest here
 * too. Each patch records through a call of its own, behind its own switch, so either one on its
 * own does no work while its feature is off.
 */
public final class PlayerSources {

    private PlayerSources() {}

    /** The fields of one source that a save can use. */
    static final class Source {
        final String videoId;
        final String hdUrl;
        final String manifest;

        Source(String videoId, String hdUrl, String manifest) {
            this.videoId = videoId;
            this.hdUrl = hdUrl;
            this.manifest = manifest;
        }
    }

    private static final String VIDEO_DATA_SOURCE = "com.facebook.video.engine.api.VideoDataSource";

    /**
     * The recorder's own line in Hook status. It runs for every player Facebook builds, so
     * counting it under the story save would bury the Save taps that line is there to count.
     */
    static final String FAMILY = FamilyNames.STORY_DOWNLOAD + " (player sources)";

    /** The same for the recorder the video patch adds, under the video patch's name. */
    static final String VIDEO_FAMILY = FamilyNames.VIDEO_DOWNLOAD + " (player sources)";

    /**
     * How many sources to keep. A manifest is about 20 KB of text, so 48 sources use about 1 MB.
     * The app prepares far fewer players than this before the user gets to them.
     */
    private static final int MAX_SOURCES = 48;

    /** A video id: a long run of digits. */
    private static final Pattern ID = Pattern.compile("\\d{8,20}");

    /** The limits of the walk over a card. They are the same as for the walk for addresses. */
    private static final int MAX_NODES = 512;
    private static final int MAX_DEPTH = 3;

    private static final LinkedHashMap<String, Source> SOURCES =
        new LinkedHashMap<String, Source>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Source> eldest) {
                return size() > MAX_SOURCES;
            }
        };

    /**
     * Record the source of one player.
     *
     * <p>The patch calls this at the end of each constructor of the player params. So it runs
     * often and on any thread, and it must never throw. The three names are the real names of the
     * fields. The patch reads them from the app.
     *
     * <p>This is the story patch's hook, and only a story save or a video save reads what is kept
     * here. So with Save any story off or Hushfacebook paused, this call leaves the player as
     * Facebook built it. The price: a player built while the switch was off
     * is never recorded, so a story already open when someone turns Save any story on saves at
     * the card's own 360p until Facebook builds its player again. Recording regardless would do
     * this work in every player while the feature is off or paused, which Pause promises not to.
     */
    public static void remember(Object params, String idField, String hdField, String manifestField) {
        record(false, params, idField, hdField, manifestField);
    }

    /**
     * The video patch's copy of {@link #remember}, run behind Download any video's switch and
     * counted on that patch's own line. With both patches in, a player is recorded twice with the
     * same source, which costs a map write.
     */
    public static void rememberVideo(Object params, String idField, String hdField, String manifestField) {
        record(true, params, idField, hdField, manifestField);
    }

    /**
     * The one recorder behind both hooks. The switch is picked by a flag and read only after the
     * settings are ready: naming a Setting any earlier loads the settings with no context to read
     * them from, and that start crashes (ColdStartHooksTest).
     */
    private static void record(boolean video, Object params, String idField, String hdField, String manifestField) {
        final String family = video ? VIDEO_FAMILY : FAMILY;
        try {
            HookStatus.invoked(family);
            // Facebook can build a player before the settings are ready, and no switch can be
            // read then.
            if (!Utils.settingsReady()) return;
            if (!(video ? Settings.DOWNLOAD_VIDEOS.get() : Settings.DOWNLOAD_STORIES.get())) return;

            String videoId = RenditionPicker.fieldValue(params, idField);
            if (videoId == null || videoId.isEmpty()) return;

            Object source = fieldOfType(params, VIDEO_DATA_SOURCE);
            if (source == null) {
                // A source not set yet is ordinary. A params class with no field of the type is a
                // build that moved it, and every story save then falls back to 360p.
                if (!hasFieldOfType(params, VIDEO_DATA_SOURCE)) {
                    HookStatus.missingMember(family, "field", params.getClass().getName(),
                        VIDEO_DATA_SOURCE);
                }
                return;
            }

            String hd = RenditionPicker.fieldValue(source, hdField);
            String manifest = RenditionPicker.fieldValue(source, manifestField);
            if (hd == null && manifest == null) return;

            synchronized (SOURCES) {
                SOURCES.put(videoId, new Source(videoId, hd, manifest));
            }
            HookStatus.bound(family, "player source");
        } catch (Throwable failure) {
            // This runs inside a constructor of the app. No error can go out of it.
            try {
                HookStatus.threw(family, "player source", failure);
            } catch (Throwable ignored) {
                // Not even the report of one.
            }
        }
    }

    /** Whether [host]'s class or one of its parents declares a field of [typeName]. */
    private static boolean hasFieldOfType(Object host, String typeName) {
        for (Class<?> type = host.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) return true;
            }
        }
        return false;
    }

    /**
     * The source of the video that [card] shows, or {@code null}.
     *
     * <p>The walk collects each string of the card that has the shape of an id. The first id of a
     * recorded player is the answer. Only players are recorded, so that id is the id of a video,
     * and a card shows one video.
     */
    static Source find(Object card) {
        if (card == null) return null;

        List<String> ids = idsIn(card);

        synchronized (SOURCES) {
            for (String id : ids) {
                Source source = SOURCES.get(id);
                if (source != null) return source;
            }
        }

        return null;
    }

    /** The recorded source of the video with this id, or {@code null}. */
    static Source byId(String videoId) {
        if (videoId == null || videoId.isEmpty()) return null;
        synchronized (SOURCES) {
            return SOURCES.get(videoId);
        }
    }

    // ---------------------------------------------------------------- internals

    private static List<String> idsIn(Object host) {
        List<String> found = new ArrayList<>();

        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] { host, 0 });
        seen.put(host, Boolean.TRUE);

        int nodes = 0;
        while (!queue.isEmpty() && nodes < MAX_NODES) {
            Object[] entry = queue.poll();
            Object node = entry[0];
            int depth = (Integer) entry[1];
            nodes++;

            for (Class<?> type = node.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                Field[] fields;
                try {
                    fields = type.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }

                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) continue;

                    Object value;
                    try {
                        field.setAccessible(true);
                        value = field.get(node);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (value == null) continue;

                    if (value instanceof CharSequence) {
                        addIds(found, value.toString());
                        continue;
                    }

                    if (depth >= MAX_DEPTH) continue;

                    String name = value.getClass().getName();
                    if (!name.startsWith("com.facebook.") && !name.startsWith("X.")) continue;
                    if (seen.put(value, Boolean.TRUE) != null) continue;

                    queue.add(new Object[] { value, depth + 1 });
                }
            }
        }

        return found;
    }

    /**
     * The ids in a short string. The walk ignores an address. The path and the query of an address
     * contain long runs of digits that are not ids.
     */
    private static void addIds(List<String> ids, String text) {
        if (text.length() > 40) return;

        Matcher matcher = ID.matcher(text);
        while (matcher.find()) {
            String id = matcher.group();
            if (!ids.contains(id)) ids.add(id);
        }
    }

    private static Object fieldOfType(Object host, String typeName) {
        for (Class<?> type = host.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().equals(typeName)) continue;
                try {
                    field.setAccessible(true);
                    return field.get(host);
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }
}
