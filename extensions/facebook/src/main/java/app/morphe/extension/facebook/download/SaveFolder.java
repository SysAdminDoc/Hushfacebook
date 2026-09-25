/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.text.Normalizer;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;

/**
 * The folder a save lands in: one name, under Movies for a video and under Pictures for a photo,
 * so the two kinds sit side by side the way they always have.
 *
 * <p>The name is typed by a person or read from a settings file, and MediaStore makes every folder
 * a relative path names. A slash in it would build folders nobody chose, a dot segment would climb
 * out of Movies (MediaStore refuses that, and the save fails), a leading dot hides the folder from
 * the gallery, and a character nobody can see makes two folders that look the same. So the value
 * goes through {@link #sanitize} where it's used, whatever wrote it, and an empty result means
 * {@link #DEFAULT}.
 */
public final class SaveFolder {

    private SaveFolder() {}

    /** Where every save went before the setting existed. */
    public static final String DEFAULT = "Facebook";

    /**
     * The longest name kept, in code points. Fifty of them are at most 200 bytes of UTF-8, well
     * inside the 255 a name can take on the phone's storage and on a FAT card it's copied to.
     */
    static final int MAX_CODE_POINTS = 50;

    /** What a run of characters a folder name can't hold becomes. */
    private static final char REPLACEMENT = '_';

    /** The folder the next save uses. Never throws, and never answers anything but a clean name. */
    public static String leaf() {
        try {
            if (!Utils.settingsReady()) return DEFAULT;
            return sanitize(Settings.SAVE_FOLDER.get());
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    /** Whether [name] is already a folder name this would use exactly as written. */
    public static boolean isClean(String name) {
        return name != null && !name.isEmpty() && name.equals(sanitize(name));
    }

    /**
     * Whether a settings file's [name] may be taken as the folder. A newer Android knows characters
     * an older one doesn't, and {@link #sanitize} drops what the phone can't place, so a name one
     * phone saved can read as unclean on another. Each character this phone doesn't know counts as
     * a plain symbol here, and the rest has to be exactly as sanitize leaves it.
     */
    public static boolean isImportable(String name) {
        if (name == null || name.isEmpty()) return false;
        StringBuilder known = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);
            known.appendCodePoint(Character.getType(codePoint) == Character.UNASSIGNED ? STAND_IN : codePoint);
        }
        return isClean(known.toString());
    }

    /** A symbol every phone knows and sanitize keeps as it is (black star). */
    private static final int STAND_IN = 0x2605;

    /**
     * The folder name to use for [raw].
     *
     * <ul>
     *   <li>Compatibility forms fold first (NFKC), so a full-width slash is a slash.</li>
     *   <li>Control, format, private-use and unassigned characters and the invisible fillers go.
     *       A line break or a tab is a space.</li>
     *   <li>Path separators, their look-alikes and the characters a FAT name can't hold become
     *       one underscore per run. At either end they go, so "/Clips/" is "Clips".</li>
     *   <li>Runs of spaces are one space. Spaces and dots go from both ends, which is what makes
     *       "." and ".." empty and stops a name from hiding the folder.</li>
     *   <li>At most {@link #MAX_CODE_POINTS} code points are kept, never half of a pair.</li>
     * </ul>
     *
     * The result is folded once more, since taking a character out can leave a mark that now
     * composes. Nothing left means {@link #DEFAULT}. Running it on its own answer changes nothing.
     */
    public static String sanitize(String raw) {
        if (raw == null) return DEFAULT;

        String text = Normalizer.normalize(raw, Normalizer.Form.NFKC);

        // Separators become a marker first, so the ones at either end can go with the spaces.
        final char separator = '\u0000';
        StringBuilder mapped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                mapped.append(' ');
            } else if (isSeparator(codePoint)) {
                mapped.append(separator);
            } else if (!isInvisible(codePoint)) {
                mapped.appendCodePoint(codePoint);
            }
        }

        StringBuilder out = new StringBuilder(mapped.length());
        int start = 0;
        int end = mapped.length();
        while (start < end && trimmable(mapped.charAt(start), separator)) start++;
        while (end > start && trimmable(mapped.charAt(end - 1), separator)) end--;

        for (int i = start; i < end; i++) {
            char c = mapped.charAt(i);
            char last = out.length() == 0 ? 0 : out.charAt(out.length() - 1);
            if (c == separator) {
                if (last != REPLACEMENT || mapped.charAt(i - 1) != separator) out.append(REPLACEMENT);
            } else if (c == ' ') {
                if (last != ' ') out.append(' ');
            } else {
                out.append(c);
            }
        }

        // Taking a character out can leave a mark next to a letter it now composes with, so the
        // result is folded again. That's what makes a second run change nothing.
        String name = Normalizer.normalize(out, Normalizer.Form.NFKC);
        name = trim(truncate(name));
        return name.isEmpty() ? DEFAULT : name;
    }

    private static boolean trimmable(char c, char separator) {
        return c == ' ' || c == '.' || c == separator;
    }

    /** Spaces and dots off both ends, as often as it takes. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == ' ' || text.charAt(start) == '.')) start++;
        while (end > start && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '.')) end--;
        return text.substring(start, end);
    }

    private static String truncate(String text) {
        if (text.codePointCount(0, text.length()) <= MAX_CODE_POINTS) return text;
        return text.substring(0, text.offsetByCodePoints(0, MAX_CODE_POINTS));
    }

    /** A character that splits a path, looks like it does, or can't stand in a FAT name. */
    private static boolean isSeparator(int codePoint) {
        switch (codePoint) {
            case '/':
            case '\\':
            case ':':
            case '*':
            case '?':
            case '"':
            case '<':
            case '>':
            case '|':
            case 0x2044: // fraction slash
            case 0x2215: // division slash
            case 0x2216: // set minus
            case 0x29F5: // reverse solidus operator
            case 0x29F8: // big solidus
            case 0x29F9: // big reverse solidus
                return true;
            default:
                return false;
        }
    }

    /** A character that shows nothing, or isn't a character at all. */
    private static boolean isInvisible(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
                return true;
            default:
                break;
        }
        return codePoint == 0x034F // combining grapheme joiner
            || codePoint == 0x115F || codePoint == 0x1160 // Hangul choseong and jungseong fillers
            || codePoint == 0x17B4 || codePoint == 0x17B5 // Khmer inherent vowels
            || (codePoint >= 0x180B && codePoint <= 0x180F) // Mongolian free variation selectors
            || codePoint == 0x2800 // braille blank
            || codePoint == 0x3164 // Hangul filler
            || (codePoint >= 0xFE00 && codePoint <= 0xFE0F) // variation selectors
            || codePoint == 0xFFA0 // half-width Hangul filler
            || (codePoint >= 0xE0100 && codePoint <= 0xE01EF); // variation selectors supplement
    }
}
