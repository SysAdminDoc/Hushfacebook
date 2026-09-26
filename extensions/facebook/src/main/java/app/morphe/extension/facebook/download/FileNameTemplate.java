/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;

/**
 * The name a saved video gets, from a template the person can change.
 *
 * <p>The default is Facebook's own naming, {@code FB_VID_} and the date and time, so with the
 * template as it ships every video is named the way it always was. Two tokens fill in per save:
 * {@link #DATE} is the moment the file goes into the gallery, as {@code yyyyMMdd_HHmmss}, and
 * {@link #VIDEO_ID} is the video's number on Facebook when the save knows it, and nothing when it
 * doesn't. Anything else in braces stays as written.
 *
 * <p>A template is typed by a person or read from a settings file, and MediaStore takes the name
 * as given. So {@link #sanitize} cleans it the way {@link SaveFolder} cleans a folder name: no
 * separator can make a path, nothing invisible stays, no dot or space starts or ends it, and it's
 * cut at {@link #MAX_TEMPLATE_CODE_POINTS}. Then it holds the template to the gallery's own naming:
 *
 * <ul>
 *   <li>No leading dot, so MediaStore's hidden and reserved names ({@code .pending-},
 *       {@code .trashed-}, {@code .nomedia}) are out of reach.</li>
 *   <li>The extension is the writer's to give, from the bytes it checked, so a media extension typed
 *       at the end ({@code .mp4}, {@code .jpg}) goes.</li>
 *   <li>{@link #PHOTO_PREFIX} is how Facebook names a saved photo, so a video template that starts
 *       with it starts with {@code FB_VID_} instead.</li>
 *   <li>MediaStore numbers a name that's taken, {@code (1)} to {@code (31)}, and then refuses the
 *       save. A template with neither token names every video the same, so it gets {@code _{date}}
 *       on the end.</li>
 * </ul>
 *
 * <p>The filled-in name is cleaned again and cut at {@link #MAX_NAME_BYTES} of UTF-8, which leaves
 * room for the extension and the number MediaStore adds, inside the 255 bytes a name can have. When
 * the template has no {@link #DATE} and the save doesn't know the video's id, the date and time go
 * on the end, so two such saves still get two names.
 *
 * <p>Photos keep Facebook's own {@code FB_IMG_} names. A photo has no video id, and one template
 * for both would name every photo a video.
 */
public final class FileNameTemplate {

    private FileNameTemplate() {}

    /** Fills in as the date and time of the save. */
    public static final String DATE = "{date}";

    /** Fills in as the video's number on Facebook, or nothing when the save doesn't know it. */
    public static final String VIDEO_ID = "{video_id}";

    /** What Facebook starts the name of a saved photo with. Photos keep it, whatever the template. */
    public static final String PHOTO_PREFIX = "FB_IMG_";

    /** What Facebook starts the name of a saved video with. */
    static final String VIDEO_PREFIX = "FB_VID_";

    /** Facebook's own name for a saved video, the one every save used before the setting existed. */
    public static final String DEFAULT = VIDEO_PREFIX + DATE;

    /** The longest template kept, in code points: the folder name's bound. */
    static final int MAX_TEMPLATE_CODE_POINTS = SaveFolder.MAX_CODE_POINTS;

    /** The longest filled-in name, in bytes of UTF-8, before the extension. */
    static final int MAX_NAME_BYTES = 200;

    /** A video id, as long as one gets. Longer is not an id. */
    private static final int MAX_ID_DIGITS = 25;

    /**
     * The extensions {@link MediaStoreWriter} gives a file, and the one other spelling of JPEG. A
     * template can't end with one: the writer adds the one the bytes call for.
     */
    private static final String[] MEDIA_EXTENSIONS = {
        ".mp4", ".m4v", ".mov", ".webm", ".3gp", ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".avif", ".gif",
    };

    /** The template the next save uses. Never throws, and never answers anything but a clean one. */
    public static String current() {
        try {
            if (!Utils.settingsReady()) return DEFAULT;
            return sanitize(Settings.FILENAME_TEMPLATE.get());
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    /**
     * The template to use for [raw]: cleaned like a folder name and held to the gallery's naming
     * (see the class), and {@link #DEFAULT} when nothing's left. Running it on its own answer
     * changes nothing.
     */
    public static String sanitize(String raw) {
        String clean = asVideo(withoutMediaExtension(SaveFolder.clean(raw, MAX_TEMPLATE_CODE_POINTS)));
        if (clean.isEmpty()) return DEFAULT;

        if (!usesDate(clean) && !usesVideoId(clean)) {
            // Every video would get this one name. The date keeps them apart, and it has to fit.
            clean = SaveFolder.trim(firstCodePoints(clean, MAX_TEMPLATE_CODE_POINTS - 1 - DATE.length()));
            clean = joined(clean, DATE);
        }

        return clean;
    }

    /** Whether [template] is already a template this would use exactly as written. */
    public static boolean isClean(String template) {
        return template != null && !template.isEmpty() && template.equals(sanitize(template));
    }

    /**
     * Whether a settings file's [template] may be taken, the way {@link SaveFolder#isImportable}
     * decides for a folder: a character this phone doesn't know yet counts as a plain symbol, and
     * the rest has to be exactly as {@link #sanitize} leaves it.
     */
    public static boolean isImportable(String template) {
        if (template == null || template.isEmpty()) return false;
        return isClean(SaveFolder.withUnknownAsKnown(template));
    }

    /** Whether [template] asks for the video id. */
    public static boolean usesVideoId(String template) {
        return template != null && template.contains(VIDEO_ID);
    }

    /** Whether [template] fills in the date and time. */
    public static boolean usesDate(String template) {
        return template != null && template.contains(DATE);
    }

    /**
     * The name, without its extension, of a video saved at [when] under [template]. [videoId] is
     * used only when it's digits, so nothing from the app can reach the name but a number. A name
     * that fills in to nothing is Facebook's own, and one that would come out the same for every
     * save, because the id it counts on is missing, gets the date and time on the end.
     */
    public static String videoName(String template, Date when, String videoId) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(when);
        String clean = sanitize(template);
        boolean hasId = isVideoId(videoId);
        String filled = clean.replace(DATE, stamp).replace(VIDEO_ID, hasId ? videoId : "");
        String name = asVideo(SaveFolder.clean(filled, Integer.MAX_VALUE));
        if (name.isEmpty()) return VIDEO_PREFIX + stamp;

        if (usesDate(clean) || (hasId && usesVideoId(clean))) return cut(name, MAX_NAME_BYTES);
        // The id this template counts on is missing, so the date and time keep the name apart.
        return joined(cut(name, MAX_NAME_BYTES - 1 - stamp.length()), stamp);
    }

    /** [name] with {@link #VIDEO_PREFIX} where it starts with {@link #PHOTO_PREFIX}, in any case. */
    private static String asVideo(String name) {
        if (!name.regionMatches(true, 0, PHOTO_PREFIX, 0, PHOTO_PREFIX.length())) return name;
        return VIDEO_PREFIX + name.substring(PHOTO_PREFIX.length());
    }

    /** [head] and [tail] with an underscore between them, unless [head] already ends in one or a hyphen. */
    private static String joined(String head, String tail) {
        if (head.isEmpty() || head.endsWith("_") || head.endsWith("-")) return head + tail;
        return head + "_" + tail;
    }

    /** Whether [text] is a video id: digits, and not too many of them. */
    static boolean isVideoId(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_ID_DIGITS) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        }
        return true;
    }

    /** [name] without a media extension at its end, however many there are, and trimmed after each. */
    private static String withoutMediaExtension(String name) {
        boolean again = true;
        while (again) {
            again = false;
            for (String extension : MEDIA_EXTENSIONS) {
                int at = name.length() - extension.length();
                if (at >= 0 && name.regionMatches(true, at, extension, 0, extension.length())) {
                    name = SaveFolder.trim(name.substring(0, at));
                    again = true;
                    break;
                }
            }
        }
        return name;
    }

    /** The first [count] code points of [text], never half of a pair. */
    private static String firstCodePoints(String text, int count) {
        if (text.codePointCount(0, text.length()) <= count) return text;
        return text.substring(0, text.offsetByCodePoints(0, count));
    }

    /** [name] cut to [maxBytes] of UTF-8, never inside a character, with no space or dot left at its end. */
    private static String cut(String name, int maxBytes) {
        int bytes = 0;
        int end = 0;
        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) break;
            bytes += size;
            i += Character.charCount(codePoint);
            end = i;
        }
        return end == name.length() ? name : SaveFolder.trim(name.substring(0, end));
    }
}
