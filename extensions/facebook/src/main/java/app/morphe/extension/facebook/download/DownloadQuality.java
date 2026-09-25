/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

/**
 * The quality a video save asks for: the best there is, a ceiling, or the smallest file.
 *
 * <p>A ceiling is a wish, not a condition. A save picks the best rendition at or under it, and
 * when a video has nothing that low it takes the lowest one above it. Nothing here can make a save
 * fail: a quality nobody states comes last, but it's still a file.
 *
 * <p>Quality is counted the way Facebook's own quality menu counts it, in the {@code 720p} unit:
 * a DASH track's own label when it has one, else its short side, and for a single file the marker
 * in its address ({@link RenditionPicker#qualityOf}). Zero means the rendition states none.
 *
 * <p>Photos aren't touched. A story's photo always saves at full size.
 *
 * <p>Like {@link RenditionPicker} and {@link DashManifest}, which rank by it, this holds no Android
 * type and reads no setting: {@link MediaDownload} reads the setting and hands the value over.
 */
public enum DownloadQuality {
    BEST(Integer.MAX_VALUE, "best"),
    P1080(1080, "1080p"),
    P720(720, "720p"),
    P480(480, "480p"),
    P360(360, "360p"),
    SMALLEST(0, "smallest");

    /** The highest quality this setting wants: MAX_VALUE for best, 0 for the smallest there is. */
    final int ceiling;

    /** What a settings file holds for this value. It never changes once written. */
    public final String fileValue;

    DownloadQuality(int ceiling, String fileValue) {
        this.ceiling = ceiling;
        this.fileValue = fileValue;
    }

    /** The value a settings file names, or null when it names none this build knows. */
    public static DownloadQuality fromFile(Object value) {
        if (!(value instanceof String)) return null;
        for (DownloadQuality quality : values()) {
            if (quality.fileValue.equals(value)) return quality;
        }
        return null;
    }

    /** The ceiling as a label, such as {@code 720p}, or null for best and smallest. */
    public String ceilingLabel() {
        return this == BEST || this == SMALLEST ? null : fileValue;
    }

    /**
     * Which of two qualities suits this setting better. Negative when [a] does, positive when [b]
     * does, zero when they're the same to it. A quality of zero is one nobody stated, and it always
     * comes last.
     *
     * <ul>
     *   <li>Best: the higher one.</li>
     *   <li>A ceiling: one at or under it beats one above it. Under it, the higher one wins, above
     *       it the lower one, since that's the nearest the video has.</li>
     *   <li>Smallest: the lower one.</li>
     * </ul>
     */
    int compare(int a, int b) {
        boolean knownA = a > 0;
        boolean knownB = b > 0;
        if (knownA != knownB) return knownA ? -1 : 1;
        if (!knownA || a == b) return 0;

        if (this == SMALLEST) return Integer.compare(a, b);

        boolean fitsA = a <= ceiling;
        boolean fitsB = b <= ceiling;
        if (fitsA != fitsB) return fitsA ? -1 : 1;
        return fitsA ? Integer.compare(b, a) : Integer.compare(a, b);
    }
}
