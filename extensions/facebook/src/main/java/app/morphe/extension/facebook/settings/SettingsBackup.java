/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/bcc57ee555f4346c4eb1cbdae9f6ca8a3fa6fef4/extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/SettingsBackup.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: an exact list of the switches in place of every
 * setting, no Feature Gate Lab, reset, undo or journal, an import read into a preview before
 * anything is written, and a refusal of its own for a name given twice.
 */
package app.morphe.extension.facebook.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SettingsJson;

/**
 * Hushfacebook's switches as a file, and back.
 *
 * <p>Morphe Manager can export the patches that were picked and the signing key. It can't see the
 * switches, which live in Facebook's own data, so a reinstall or a new phone started them all
 * over. This writes them to a JSON file the person chooses and reads one back.
 *
 * <p>Only the switches in {@link #ALLOWLIST} go out or come in. Pause, safe mode, the debug
 * settings, the app language and the counters Hushfacebook keeps for itself stay out, and so do
 * the log, the diagnostic data and anything about the person or the phone: a file is a format
 * name, a version number and one true or false per switch. An import applies what it read in one
 * preference commit. A file that is too large, isn't JSON, names something twice, holds a value
 * of the wrong type or comes from a newer version changes nothing.
 *
 * <p>Call the file and preference work on a worker thread.
 */
public final class SettingsBackup {
    /** Far more than a settings file needs: one is a few hundred bytes. */
    public static final int MAX_BYTES = 64 * 1024;
    public static final String FORMAT = "hushfacebook-settings";
    /** The file shape this build writes and the newest it reads. A file declaring more is refused. */
    static final int SCHEMA = 1;

    static final String FORMAT_NAME = "format";
    static final String SCHEMA_NAME = "schema";
    static final String SETTINGS_NAME = "settings";

    /**
     * The switches a file carries, and the only settings an import can write: every feature
     * switch, and nothing of Hushfacebook's own. A switch added to {@link Settings} stays out
     * until someone decides it belongs here, and SettingsBackupTest fails until they do.
     */
    static final List<BooleanSetting> ALLOWLIST = Collections.unmodifiableList(Arrays.asList(
            Settings.HIDE_SPONSORED_POSTS,
            Settings.HIDE_PROMOTED_POSTS,
            Settings.HIDE_SUGGESTED_POSTS,
            Settings.HIDE_SPONSORED_STORIES,
            Settings.HIDE_SPONSORED_REELS,
            Settings.OPEN_LINKS_EXTERNALLY,
            Settings.DOWNLOAD_STORIES,
            Settings.DOWNLOAD_REELS));

    /**
     * Bounds for the parser, well past anything this class writes, so a file built to be
     * expensive to read is refused before it is.
     */
    private static final SettingsJson.Limits LIMITS = new SettingsJson.Limits(8, 1024, 1024, 256, MAX_BYTES);

    /** Written by some editors at the start of a UTF-8 file. It isn't part of the JSON. */
    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);

    private SettingsBackup() {
    }

    /**
     * Why a file was refused. Each one reaches the person as its own sentence: a download that
     * stopped halfway, a file from a newer Hushfacebook and a photo picked by mistake call for
     * different things.
     */
    public enum Reason {
        /** Larger than any settings file. */
        SIZE,
        /** Not UTF-8 text at all. */
        ENCODING,
        /** Text that isn't one JSON object, which is what a part-finished download is. */
        DAMAGED,
        /** A name given twice in one object, so which of its values was meant can't be told. */
        DUPLICATE,
        /** JSON, but not a Hushfacebook settings file, or one whose version isn't a version. */
        FORMAT,
        /** A settings file from a newer Hushfacebook, in a shape this build doesn't know. */
        SCHEMA,
        /** A switch whose value isn't true or false. */
        VALUE,
        /** The file couldn't be opened or read to the end. */
        UNREADABLE
    }

    /** A file refused before anything was written. */
    public static final class Rejected extends Exception {
        public final Reason reason;

        Rejected(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** A write that failed. Whether the switches are all back as they were is known and said. */
    public static final class ApplyFailed extends Exception {
        public final boolean rolledBack;

        ApplyFailed(boolean rolledBack, Throwable cause) {
            super(cause.getClass().getSimpleName(), cause);
            this.rolledBack = rolledBack;
        }
    }

    /** What a file says: a value for each switch it names, and how many other names it holds. */
    public static final class Snapshot {
        private static final String SWITCHES = "switches";
        private static final String UNKNOWN = "unknown";

        /** In {@link #ALLOWLIST} order, and only the switches the file named. */
        final Map<BooleanSetting, Boolean> values;
        /** Names the file holds that aren't switches this build knows. They're left out. */
        final int unknown;

        Snapshot(Map<BooleanSetting, Boolean> values, int unknown) {
            this.values = Collections.unmodifiableMap(values);
            this.unknown = unknown;
        }

        /**
         * The switches whose saved value this file changes. Saved, not what a paused Facebook is
         * answered: a file holds what the person chose.
         */
        Map<Setting<?>, Object> changes() {
            Map<Setting<?>, Object> changes = new LinkedHashMap<>();
            for (Map.Entry<BooleanSetting, Boolean> entry : values.entrySet()) {
                if (!entry.getValue().equals(entry.getKey().savedValue())) {
                    changes.put(entry.getKey(), entry.getValue());
                }
            }
            return changes;
        }

        /** For the settings page's saved state, so a preview outlives the page being rebuilt. */
        Bundle toBundle() {
            Bundle switches = new Bundle();
            for (Map.Entry<BooleanSetting, Boolean> entry : values.entrySet()) {
                switches.putBoolean(entry.getKey().key, entry.getValue());
            }
            Bundle state = new Bundle();
            state.putBundle(SWITCHES, switches);
            state.putInt(UNKNOWN, unknown);
            return state;
        }

        /**
         * The snapshot a page saved, or null when there's none or it isn't one. Only switches on
         * the list come back, whatever the bundle holds.
         */
        @Nullable
        @SuppressWarnings("deprecation") // Bundle.get is how a value's type is checked below API 33.
        static Snapshot fromBundle(@Nullable Bundle state) {
            if (state == null) return null;
            Bundle switches = state.getBundle(SWITCHES);
            int unknown = state.getInt(UNKNOWN, -1);
            if (switches == null || unknown < 0) return null;
            Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
            for (BooleanSetting setting : ALLOWLIST) {
                Object value = switches.get(setting.key);
                if (value instanceof Boolean) values.put(setting, (Boolean) value);
            }
            return new Snapshot(values, unknown);
        }
    }

    /**
     * The file for the switches as they're saved now. Saved rather than what a paused Facebook is
     * answered, so exporting while paused keeps what the person chose.
     */
    public static String create() throws JSONException {
        JSONObject switches = new JSONObject();
        for (BooleanSetting setting : ALLOWLIST) {
            switches.put(setting.key, setting.savedValue().booleanValue());
        }
        return new JSONObject()
                .put(FORMAT_NAME, FORMAT)
                .put(SCHEMA_NAME, SCHEMA)
                .put(SETTINGS_NAME, switches)
                .toString(2);
    }

    /**
     * A file as text, read no further than {@link #MAX_BYTES}, so a huge file or a stream that
     * never ends is refused without being held in memory.
     */
    static String read(@Nullable InputStream input) throws Rejected {
        if (input == null) throw new Rejected(Reason.UNREADABLE, "No stream to read");
        byte[] bytes;
        try (InputStream stream = input; ByteArrayOutputStream buffered = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (buffered.size() + count > MAX_BYTES) {
                    throw new Rejected(Reason.SIZE, "Larger than " + MAX_BYTES + " bytes");
                }
                buffered.write(buffer, 0, count);
            }
            bytes = buffered.toByteArray();
        } catch (IOException error) {
            // The class only: a provider's message can carry the document's name or address.
            throw new Rejected(Reason.UNREADABLE, error.getClass().getSimpleName());
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new Rejected(Reason.ENCODING, "Not UTF-8");
        }
    }

    /** What a file's text says, or why it's refused. Nothing is written here. */
    static Snapshot parse(@Nullable String text) throws Rejected {
        if (text == null) throw new Rejected(Reason.UNREADABLE, "No text");
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new Rejected(Reason.SIZE, "Larger than " + MAX_BYTES + " bytes");
        }
        if (text.startsWith(BYTE_ORDER_MARK)) text = text.substring(1);
        JSONObject root;
        try {
            root = SettingsJson.parseObject(text, LIMITS);
        } catch (SettingsJson.DuplicateNameException duplicate) {
            throw new Rejected(Reason.DUPLICATE, "A name appears twice");
        } catch (IOException | JSONException error) {
            // The parser reports a document that simply stops as an IOException, not a
            // JSONException, and a download cut off halfway is exactly that.
            throw new Rejected(Reason.DAMAGED, "Not one JSON object");
        }
        if (!FORMAT.equals(root.opt(FORMAT_NAME))) {
            throw new Rejected(Reason.FORMAT, "Not a Hushfacebook settings file");
        }
        // A version is a whole number this build wrote or a later one. A later one is refused
        // rather than read field by field, which could land half of a file this build doesn't
        // understand. Anything else in its place was never written by Hushfacebook.
        Object schema = root.opt(SCHEMA_NAME);
        if (!(schema instanceof Integer) || (Integer) schema < 1) {
            throw new Rejected(Reason.FORMAT, "No version number");
        }
        if ((Integer) schema > SCHEMA) {
            throw new Rejected(Reason.SCHEMA, "Version " + schema + " is newer than " + SCHEMA);
        }
        Object settings = root.opt(SETTINGS_NAME);
        if (!(settings instanceof JSONObject)) {
            throw new Rejected(Reason.FORMAT, "No settings");
        }

        int unknown = 0;
        for (Iterator<String> names = root.keys(); names.hasNext(); ) {
            String name = names.next();
            if (!FORMAT_NAME.equals(name) && !SCHEMA_NAME.equals(name) && !SETTINGS_NAME.equals(name)) {
                unknown++;
            }
        }
        Map<String, BooleanSetting> known = new HashMap<>();
        for (BooleanSetting setting : ALLOWLIST) known.put(setting.key, setting);
        Map<BooleanSetting, Boolean> found = new HashMap<>();
        JSONObject values = (JSONObject) settings;
        for (Iterator<String> names = values.keys(); names.hasNext(); ) {
            String name = names.next();
            BooleanSetting setting = known.get(name);
            if (setting == null) {
                // A name this build doesn't know, Pause and the debug settings included: left
                // out and counted, so the preview can say so.
                unknown++;
                continue;
            }
            Object value = values.opt(name);
            if (!(value instanceof Boolean)) {
                throw new Rejected(Reason.VALUE, "Not true or false: " + name);
            }
            found.put(setting, (Boolean) value);
        }
        Map<BooleanSetting, Boolean> ordered = new LinkedHashMap<>();
        for (BooleanSetting setting : ALLOWLIST) {
            Boolean value = found.get(setting);
            if (value != null) ordered.put(setting, value);
        }
        return new Snapshot(ordered, unknown);
    }

    /**
     * Writes the switches a file changes, all of them in one preference commit. A switch the
     * file doesn't name is left as it is.
     *
     * @return how many switches changed.
     * @throws ApplyFailed when the commit failed. {@link Setting#saveAll} puts the switches
     *                     back; {@link ApplyFailed#rolledBack} says whether that worked.
     */
    static int apply(Snapshot snapshot) throws ApplyFailed {
        Map<Setting<?>, Object> changes = snapshot.changes();
        if (changes.isEmpty()) return 0;
        Map<String, ?> before = new HashMap<>(Setting.preferences.preferences.getAll());
        try {
            Setting.saveAll(changes);
            return changes.size();
        } catch (IOException | RuntimeException error) {
            throw new ApplyFailed(storeMatches(before), error);
        }
    }

    private static boolean storeMatches(Map<String, ?> expected) {
        try {
            return expected.equals(Setting.preferences.preferences.getAll());
        } catch (RuntimeException error) {
            return false;
        }
    }
}
