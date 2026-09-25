/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which rendition each download quality picks, among DASH tracks and among single files. A
 * ceiling takes the best at or under it and the nearest above it when there's nothing that low,
 * so a video always has an answer. These are plain JUnit: the ranking holds no Android type.
 */
public class DownloadQualityTest {

    // ---- The order a setting puts qualities in --------------------------------------------------

    @Test
    public void eachSettingOrdersQualitiesItsOwnWay() {
        // Best: higher first.
        assertTrue(DownloadQuality.BEST.compare(1080, 720) < 0);
        assertTrue(DownloadQuality.BEST.compare(360, 720) > 0);
        // A ceiling: under it the higher, and anything under beats anything above.
        assertTrue(DownloadQuality.P720.compare(720, 480) < 0);
        assertTrue(DownloadQuality.P720.compare(480, 1080) < 0);
        assertTrue(DownloadQuality.P720.compare(1080, 480) > 0);
        // Above it, the nearest.
        assertTrue(DownloadQuality.P360.compare(480, 720) < 0);
        assertTrue(DownloadQuality.P360.compare(1080, 480) > 0);
        // Exactly the ceiling fits.
        assertTrue(DownloadQuality.P480.compare(480, 540) < 0);
        // Smallest: lower first.
        assertTrue(DownloadQuality.SMALLEST.compare(240, 360) < 0);
        assertTrue(DownloadQuality.SMALLEST.compare(1080, 360) > 0);
        // A quality nobody stated comes last in every setting, and two of them are equal.
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertTrue(quality + " put an unstated quality first", quality.compare(0, 144) > 0);
            assertTrue(quality + " put an unstated quality first", quality.compare(4320, 0) < 0);
            assertEquals(0, quality.compare(0, 0));
            assertEquals(0, quality.compare(720, 720));
        }
    }

    @Test
    public void aFileValueNamesExactlyOneQuality() {
        Map<DownloadQuality, String> expected = new EnumMap<>(DownloadQuality.class);
        expected.put(DownloadQuality.BEST, "best");
        expected.put(DownloadQuality.P1080, "1080p");
        expected.put(DownloadQuality.P720, "720p");
        expected.put(DownloadQuality.P480, "480p");
        expected.put(DownloadQuality.P360, "360p");
        expected.put(DownloadQuality.SMALLEST, "smallest");
        assertEquals("a quality was added or renamed without its file value being decided",
                expected.keySet(), new java.util.HashSet<>(Arrays.asList(DownloadQuality.values())));
        for (Map.Entry<DownloadQuality, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), entry.getKey().fileValue);
            assertSame(entry.getKey(), DownloadQuality.fromFile(entry.getValue()));
        }
        for (Object refused : new Object[]{null, "", "BEST", "Best", "720", "720P", " 720p", "1440p", 720, true}) {
            assertNull(String.valueOf(refused), DownloadQuality.fromFile(refused));
        }
        assertNull(DownloadQuality.BEST.ceilingLabel());
        assertNull(DownloadQuality.SMALLEST.ceilingLabel());
        assertEquals("480p", DownloadQuality.P480.ceilingLabel());
    }

    // ---- DASH tracks ----------------------------------------------------------------------------

    private static DashManifest.Track track(String codecs, int width, int height, long bandwidth, int label) {
        return new DashManifest.Track("video/mp4", codecs, width, height, bandwidth,
                "https://video.xx.fbcdn.net/v/" + codecs + "-" + label + "-" + bandwidth + ".mp4", label);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = DownloadQualityTest.class.getResourceAsStream(name)) {
            assertNotNull("no " + name + " beside the test", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Meta's own manifest, captured from a public video: eight AV1 tracks, seven of them 720 by
     * 1280, told apart only by their labels from 240p to 1080p. Each setting lands on the label it
     * names, and one past the ends lands on the nearest.
     */
    @Test
    public void onTheCapturedManifestEachSettingTakesItsLabel() throws IOException {
        List<DashManifest.Track> tracks = DashManifest.parse(resource("meta-public-dash.mpd"));
        Map<DownloadQuality, Integer> label = new EnumMap<>(DownloadQuality.class);
        label.put(DownloadQuality.BEST, 1080);
        label.put(DownloadQuality.P1080, 1080);
        label.put(DownloadQuality.P720, 720);
        label.put(DownloadQuality.P480, 480);
        label.put(DownloadQuality.P360, 360);
        label.put(DownloadQuality.SMALLEST, 240);
        for (DownloadQuality quality : DownloadQuality.values()) {
            DashManifest.Track picked = DashManifest.pickVideo(tracks, true, quality);
            assertNotNull(quality.toString(), picked);
            assertEquals(quality + " picked " + picked, (int) label.get(quality), picked.label);
            // Before Android 14 nothing in it can be written, whatever the setting.
            assertNull(quality.toString(), DashManifest.pickVideo(tracks, false, quality));
        }
        // The best keeps the pick it always made.
        assertSame(DashManifest.bestVideo(tracks, true), DashManifest.pickVideo(tracks, true, DownloadQuality.BEST));
        assertSame(DashManifest.bestVideo(tracks, true), DashManifest.pickVideo(tracks, true, null));
    }

    @Test
    public void aTrackWithNoLabelIsHeldToItsShortSide() {
        DashManifest.Track tall = track("avc1.64001f", 1080, 1920, 3_000_000, 0);
        DashManifest.Track mid = track("avc1.64001f", 720, 1280, 1_500_000, 0);
        DashManifest.Track low = track("avc1.64001f", 360, 640, 400_000, 0);
        List<DashManifest.Track> tracks = Arrays.asList(tall, mid, low);
        assertSame(mid, DashManifest.pickVideo(tracks, false, DownloadQuality.P720));
        assertSame(low, DashManifest.pickVideo(tracks, false, DownloadQuality.P480));
        assertSame(low, DashManifest.pickVideo(tracks, false, DownloadQuality.SMALLEST));
        assertSame(tall, DashManifest.pickVideo(tracks, false, DownloadQuality.P1080));
    }

    /** A ceiling under every track still saves: the nearest above it, never nothing. */
    @Test
    public void aCeilingUnderEveryTrackTakesTheNearestAboveIt() {
        DashManifest.Track hd = track("avc1.64001f", 1080, 1920, 3_000_000, 1080);
        DashManifest.Track sd = track("avc1.64001f", 720, 1280, 1_500_000, 720);
        List<DashManifest.Track> tracks = Arrays.asList(hd, sd);
        assertSame(sd, DashManifest.pickVideo(tracks, false, DownloadQuality.P480));
        assertSame(sd, DashManifest.pickVideo(tracks, false, DownloadQuality.P360));
        assertSame(hd, DashManifest.pickVideo(Collections.singletonList(hd), false, DownloadQuality.P360));
        // A ceiling over every track takes the best there is.
        assertSame(sd, DashManifest.pickVideo(Collections.singletonList(sd), false, DownloadQuality.P1080));
    }

    /**
     * At one label, H.264 still beats H.265, and a track the muxer can't write is never picked.
     * Then the higher bitrate, except for the smallest file, which takes the lower.
     */
    @Test
    public void atOneLabelTheCodecThenTheBitrateDecide() {
        DashManifest.Track hevc = track("hvc1.1.6.L93.90", 720, 1280, 900_000, 480);
        DashManifest.Track avcHigh = track("avc1.64001f", 720, 1280, 800_000, 480);
        DashManifest.Track avcLow = track("avc1.64001f", 720, 1280, 500_000, 480);
        DashManifest.Track vp9 = track("vp09.00.40.08", 720, 1280, 100_000, 240);
        DashManifest.Track av1 = track("av01.0.05m.08", 720, 1280, 90_000, 240);
        List<DashManifest.Track> tracks = Arrays.asList(hevc, avcHigh, vp9, avcLow, av1);
        assertSame(avcHigh, DashManifest.pickVideo(tracks, false, DownloadQuality.P480));
        assertSame(avcLow, DashManifest.pickVideo(tracks, false, DownloadQuality.SMALLEST));
        assertSame("an AV1 track the muxer can write was passed over", av1,
                DashManifest.pickVideo(tracks, true, DownloadQuality.SMALLEST));
        assertNull(DashManifest.pickVideo(Collections.singletonList(vp9), true, DownloadQuality.SMALLEST));
    }

    /** A label reads only as three or four digits and a p; anything else leaves the short side. */
    @Test
    public void onlyAWholeLabelIsRead() {
        String[][] labels = {{"720p", "720"}, {"1080p", "1080"}, {" 480P ", "480"}, {"4320p", "4320"},
                {"720", "0"}, {"p720", "0"}, {"72p", "0"}, {"10800p", "0"}, {"7a0p", "0"}, {"", "0"}, {"hd", "0"}};
        for (String[] label : labels) {
            String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                    + "<Representation codecs=\"avc1.64001f\" width=\"720\" height=\"1280\" bandwidth=\"900000\""
                    + " FBQualityLabel=\"" + label[0] + "\"><BaseURL>https://video.xx.fbcdn.net/v/a.mp4</BaseURL>"
                    + "</Representation></AdaptationSet></Period></MPD>";
            DashManifest.Track parsed = DashManifest.parse(manifest).get(0);
            assertEquals(label[0], Integer.parseInt(label[1]), parsed.label);
            assertEquals(label[0], label[1].equals("0") ? 720 : Integer.parseInt(label[1]), parsed.quality());
        }
    }

    // ---- Single files ---------------------------------------------------------------------------

    private static final String HD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";
    private static final String SD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_360p.mp4?oh=1&oe=2";
    /** A file that states no quality. */
    private static final String PLAIN = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip.mp4?oh=1&oe=2";

    @Test
    public void amongSingleFilesEachSettingTakesItsOwn() {
        List<String> both = Arrays.asList(HD, SD);
        assertEquals(720, RenditionPicker.qualityOf(HD));
        assertEquals(360, RenditionPicker.qualityOf(SD));
        assertEquals(HD, RenditionPicker.bestVideo(both, DownloadQuality.BEST));
        assertEquals(HD, RenditionPicker.bestVideo(both, DownloadQuality.P1080));
        assertEquals(HD, RenditionPicker.bestVideo(both, DownloadQuality.P720));
        assertEquals(SD, RenditionPicker.bestVideo(both, DownloadQuality.P480));
        assertEquals(SD, RenditionPicker.bestVideo(both, DownloadQuality.P360));
        assertEquals(SD, RenditionPicker.bestVideo(both, DownloadQuality.SMALLEST));
        // The order they arrive in changes nothing.
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertEquals(quality.toString(), RenditionPicker.bestVideo(both, quality),
                    RenditionPicker.bestVideo(Arrays.asList(SD, HD), quality));
        }
        // The best is the ranking it always was.
        assertEquals(RenditionPicker.bestOf(both, true), RenditionPicker.bestVideo(both, DownloadQuality.BEST));
    }

    /** One file, or files that state nothing: a ceiling never leaves the save without one. */
    @Test
    public void aCeilingNeverLeavesASaveWithNothing() {
        for (DownloadQuality quality : DownloadQuality.values()) {
            assertEquals(quality.toString(), HD, RenditionPicker.bestVideo(Collections.singletonList(HD), quality));
            assertEquals(quality.toString(), PLAIN, RenditionPicker.bestVideo(Collections.singletonList(PLAIN), quality));
            assertNull(RenditionPicker.bestVideo(Collections.<String>emptyList(), quality));
            assertNull(RenditionPicker.bestVideo(null, quality));
            assertNull("a picture passed as a video",
                    RenditionPicker.bestVideo(Collections.singletonList("https://scontent.xx.fbcdn.net/v/p.jpg"), quality));
        }
        // A file that states its quality beats one that doesn't, below the best too.
        assertEquals(SD, RenditionPicker.bestVideo(Arrays.asList(PLAIN, SD), DownloadQuality.P1080));
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(PLAIN, HD), DownloadQuality.P360));
    }

    /** A single file still beats an address that only might be one, whatever quality it states. */
    @Test
    public void aSingleFileBeatsAPlausibleAddressFirst() {
        String plausible = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/stream_360p?oh=1";
        assertEquals(RenditionPicker.TIER_PLAUSIBLE, RenditionPicker.videoTier(plausible));
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(plausible, HD), DownloadQuality.P360));
        assertEquals(HD, RenditionPicker.bestVideo(Arrays.asList(plausible, HD), DownloadQuality.SMALLEST));
    }
}
