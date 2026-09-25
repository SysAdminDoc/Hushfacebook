/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/DashManifest.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.extension.facebook.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tracks of a DASH manifest, and the best video track and audio track of them.
 *
 * <p>This class holds no Android type, like {@link RenditionPicker}. So {@code javac} alone can
 * test it against a manifest from a device.
 *
 * <p>Facebook writes each track as one {@code Representation} with one {@code BaseURL}. That is the
 * address of a whole MP4 file that holds only this track. So one plain fetch gets a track, and the
 * video and the audio are two files. A track with no {@code BaseURL} is not used, because one fetch
 * cannot get it.
 *
 * <p>This reads only that one shape. It is not a general XML parser.
 */
final class DashManifest {

    private DashManifest() {}

    /** One track of the manifest. */
    static final class Track {
        final String mime;
        final String codecs;
        final int width;
        final int height;
        final long bandwidth;
        final String url;

        /**
         * The quality Facebook's own menu names this track by, such as 480 for {@code 480p}, or 0
         * when the track carries no label. Facebook encodes several of a video's tracks at one
         * frame size and tells them apart by this: a 720 by 1280 track can be its 480p.
         */
        final int label;

        Track(String mime, String codecs, int width, int height, long bandwidth, String url) {
            this(mime, codecs, width, height, bandwidth, url, 0);
        }

        Track(String mime, String codecs, int width, int height, long bandwidth, String url, int label) {
            this.mime = mime;
            this.codecs = codecs;
            this.width = width;
            this.height = height;
            this.bandwidth = bandwidth;
            this.url = url;
            this.label = label;
        }

        boolean isVideo() {
            return mime.startsWith("video/");
        }

        boolean isAudio() {
            return mime.startsWith("audio/");
        }

        /** The quality in the same unit as {@code 720p}: the short side, in pixels. */
        int shortSide() {
            return Math.min(width, height);
        }

        /** The quality a setting below the best is held to: the label, else the short side. */
        int quality() {
            return label > 0 ? label : shortSide();
        }

        @Override
        public String toString() {
            return mime + " " + codecs + " " + width + "x" + height + " " + (bandwidth / 1000) + "kbps"
                + (label > 0 ? " " + label + "p" : "");
        }
    }

    private static final Pattern ADAPTATION_SET =
        Pattern.compile("<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>", Pattern.DOTALL);

    private static final Pattern REPRESENTATION =
        Pattern.compile("<Representation\\b([^>]*?)(?:/>|>(.*?)</Representation>)", Pattern.DOTALL);

    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:]+)\\s*=\\s*\"([^\"]*)\"");

    private static final Pattern BASE_URL = Pattern.compile("<BaseURL[^>]*>(.*?)</BaseURL>", Pattern.DOTALL);

    /** Every track with a single file address. Never throws, and never returns {@code null}. */
    static List<Track> parse(String manifest) {
        List<Track> tracks = new ArrayList<>();
        if (manifest == null) return tracks;

        try {
            Matcher sets = ADAPTATION_SET.matcher(manifest);
            while (sets.find()) {
                String setAttributes = sets.group(1);
                Matcher representations = REPRESENTATION.matcher(sets.group(2));

                while (representations.find()) {
                    Track track = track(setAttributes, representations.group(1), representations.group(2));
                    if (track != null) tracks.add(track);
                }
            }
        } catch (Throwable ignored) {
            // A manifest of an unexpected shape answers with what was read so far.
        }

        return tracks;
    }

    /**
     * The best video track that {@code MediaMuxer} can write into an MP4, or {@code null}.
     *
     * <p>H.264 and H.265 are always permitted. AV1 is permitted only when [allowAv1] is true. A
     * story often lists only AV1 tracks. But the muxer writes AV1 only from Android 14, and a
     * device with no AV1 decoder cannot play the file. VP9 is never used, because the MP4 muxer
     * refuses it, also on Android 17. At the same size, H.264 is the first choice, because all
     * players can play it.
     */
    static Track bestVideo(List<Track> tracks, boolean allowAv1) {
        Track best = null;

        for (Track track : tracks) {
            if (!track.isVideo()) continue;

            int family = videoFamily(track.codecs, allowAv1);
            if (family == 0) continue;

            if (best == null) {
                best = track;
                continue;
            }

            if (track.shortSide() != best.shortSide()) {
                if (track.shortSide() > best.shortSide()) best = track;
                continue;
            }

            int bestFamily = videoFamily(best.codecs, allowAv1);
            if (family != bestFamily) {
                if (family > bestFamily) best = track;
                continue;
            }

            if (track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    /**
     * The video track that suits [quality] best, or {@code null}. The best quality is
     * {@link #bestVideo}, as it always was. Below it, the tracks are held to their labels: the
     * best one at or under the ceiling, else the nearest above it, and for the smallest file the
     * lowest. At one quality H.264 still goes first, then the higher bitrate, or the lower one for
     * the smallest file. A track the muxer can't write is never picked, whatever it's labelled.
     */
    static Track pickVideo(List<Track> tracks, boolean allowAv1, DownloadQuality quality) {
        if (quality == null || quality == DownloadQuality.BEST) return bestVideo(tracks, allowAv1);

        Track best = null;

        for (Track track : tracks) {
            if (!track.isVideo()) continue;

            int family = videoFamily(track.codecs, allowAv1);
            if (family == 0) continue;

            if (best == null) {
                best = track;
                continue;
            }

            int order = quality.compare(track.quality(), best.quality());
            if (order != 0) {
                if (order < 0) best = track;
                continue;
            }

            int bestFamily = videoFamily(best.codecs, allowAv1);
            if (family != bestFamily) {
                if (family > bestFamily) best = track;
                continue;
            }

            boolean smaller = quality == DownloadQuality.SMALLEST;
            if (smaller ? track.bandwidth < best.bandwidth : track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    /**
     * The AAC track with the highest bitrate, or {@code null}. All AAC profiles are permitted.
     * Stories list xHE-AAC ({@code mp4a.40.42}).
     */
    static Track bestAudio(List<Track> tracks) {
        Track best = null;

        for (Track track : tracks) {
            if (!track.isAudio()) continue;
            if (!track.codecs.startsWith("mp4a")) continue;
            if (best == null || track.bandwidth > best.bandwidth) best = track;
        }

        return best;
    }

    // ---------------------------------------------------------------- internals

    /** A higher number is a better choice: 3 for H.264, 2 for H.265, 1 for AV1, 0 for not used. */
    private static int videoFamily(String codecs, boolean allowAv1) {
        if (codecs.startsWith("avc1") || codecs.startsWith("avc3")) return 3;
        if (codecs.startsWith("hvc1") || codecs.startsWith("hev1")) return 2;
        if (allowAv1 && codecs.startsWith("av01")) return 1;
        return 0;
    }

    private static Track track(String setAttributes, String attributes, String body) {
        if (body == null) return null;

        Matcher base = BASE_URL.matcher(body);
        if (!base.find()) return null;

        String url = unescape(base.group(1).trim());
        if (!url.toLowerCase(Locale.US).startsWith("https://")) return null;

        // A track gives its type on itself or on its adaptation set.
        String mime = attribute(attributes, "mimeType");
        if (mime == null) mime = attribute(setAttributes, "mimeType");
        if (mime == null) {
            String content = attribute(setAttributes, "contentType");
            if (content != null) mime = content + "/mp4";
        }
        if (mime == null) return null;

        String codecs = attribute(attributes, "codecs");
        if (codecs == null) codecs = attribute(setAttributes, "codecs");

        return new Track(
            mime.toLowerCase(Locale.US),
            codecs == null ? "" : codecs.toLowerCase(Locale.US),
            number(attribute(attributes, "width")),
            number(attribute(attributes, "height")),
            number(attribute(attributes, "bandwidth")),
            url,
            qualityLabel(attribute(attributes, "FBQualityLabel"))
        );
    }

    /** The number in a label such as {@code 720p}, or 0 for anything else. */
    private static int qualityLabel(String label) {
        if (label == null) return 0;
        String text = label.trim().toLowerCase(Locale.US);
        int digits = text.length() - 1;
        if (digits < 3 || digits > 4 || text.charAt(digits) != 'p') return 0;
        for (int i = 0; i < digits; i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return 0;
        }
        return number(text.substring(0, digits));
    }

    private static String attribute(String attributes, String name) {
        if (attributes == null) return null;

        Matcher matcher = ATTRIBUTE.matcher(attributes);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) return unescape(matcher.group(2));
        }
        return null;
    }

    private static int number(String text) {
        if (text == null) return 0;
        try {
            long value = Long.parseLong(text.trim());
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The five entities of XML. {@code &amp;} is the last. Thus {@code &amp;lt;} becomes {@code &lt;}. */
    private static String unescape(String text) {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&");
    }
}
