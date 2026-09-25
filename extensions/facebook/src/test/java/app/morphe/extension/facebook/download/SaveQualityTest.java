/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.InetAddress;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * A save held to the download quality, the way a reel, a story and a feed video start one: the
 * manifest's track and the single files are each picked for the setting, and the one that suits
 * it better is saved. Every save here starts for real and is refused before any socket opens, and
 * the report says which rendition it chose.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveQualityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    private static final String HD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";
    private static final String SD = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_360p.mp4?oh=1&oe=2";

    /** Three H.264 tracks and a sound track. The 480p shares the 720p's frame size, as Meta's do. */
    private static final String MANIFEST = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
            + representation(1080, 1920, 3_000_000, "1080p", "t1080")
            + representation(720, 1280, 1_500_000, "720p", "t720")
            + representation(720, 1280, 700_000, "480p", "t480")
            + "</AdaptationSet><AdaptationSet mimeType=\"audio/mp4\">"
            + "<Representation codecs=\"mp4a.40.5\" bandwidth=\"64000\">"
            + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/sound.mp4?oh=1&amp;oe=2</BaseURL>"
            + "</Representation></AdaptationSet></Period></MPD>";

    private static String representation(int width, int height, long bandwidth, String label, String name) {
        return "<Representation codecs=\"avc1.64001f\" width=\"" + width + "\" height=\"" + height
                + "\" bandwidth=\"" + bandwidth + "\" FBQualityLabel=\"" + label + "\">"
                + "<BaseURL>https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/" + name + ".mp4?oh=1&amp;oe=2</BaseURL>"
                + "</Representation>";
    }

    /** A reel's player source, its fields named the way the patch passes them. */
    static final class ReelSource {
        final String hd;
        final String sd;
        final String manifest;

        ReelSource(String hd, String sd, String manifest) {
            this.hd = hd;
            this.sd = sd;
            this.manifest = manifest;
        }
    }

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        // Every Meta name answers a private address, so each save is refused before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        MediaDownload.policyForTests = null;
        Settings.DOWNLOAD_QUALITY.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** The report after one reel save at [quality], from [source]. */
    private String reelSaveAt(DownloadQuality quality, ReelSource source) throws InterruptedException {
        Settings.DOWNLOAD_QUALITY.save(quality);
        LogBufferManager.clearLogBuffer();
        assertTrue(MediaDownload.saveVideo(context, source, "hd", "sd", "manifest"));
        waitForSaves();
        return LogBufferManager.buildExportText();
    }

    private static final String DASH_1080 = "from its DASH manifest: video/mp4 avc1.64001f 1080x1920 3000kbps 1080p";
    private static final String DASH_480 = "from its DASH manifest: video/mp4 avc1.64001f 720x1280 700kbps 480p";

    @Test
    public void theBestSavesWhatItAlwaysDid() throws Exception {
        assertSame("the setting doesn't start at the best", DownloadQuality.BEST, MediaDownload.quality());
        String report = reelSaveAt(DownloadQuality.BEST, new ReelSource(HD, SD, MANIFEST));
        assertTrue(report, report.contains(DASH_1080));
        assertFalse(report, report.contains("quality setting"));
    }

    @Test
    public void aCeilingTakesTheTrackOrTheFileThatSuitsItBetter() throws Exception {
        ReelSource reel = new ReelSource(HD, SD, MANIFEST);

        String report = reelSaveAt(DownloadQuality.P1080, reel);
        assertTrue(report, report.contains(DASH_1080) && report.contains("quality setting 1080p"));

        // The manifest's 480p beats the single files' 360p under a 480 ceiling.
        report = reelSaveAt(DownloadQuality.P480, reel);
        assertTrue(report, report.contains(DASH_480 + " + audio/mp4 mp4a.40.5"));
        assertTrue(report, report.contains("instead of mp4 (360p), quality setting 480p"));

        // A tie goes to the single file: one fetch, no join.
        report = reelSaveAt(DownloadQuality.P720, reel);
        assertFalse(report, report.contains("DASH manifest"));
        assertTrue(report, report.contains("saving video mp4 (720p) from 2 candidate(s)"));
        assertTrue(report, report.contains("quality setting 720p"));

        // Nothing in the manifest fits 360, and the single 360p file does.
        report = reelSaveAt(DownloadQuality.P360, reel);
        assertFalse(report, report.contains("DASH manifest"));
        assertTrue(report, report.contains("saving video mp4 (360p) from 2 candidate(s)"));

        report = reelSaveAt(DownloadQuality.SMALLEST, reel);
        assertFalse(report, report.contains("DASH manifest"));
        assertTrue(report, report.contains("saving video mp4 (360p) from 2 candidate(s)"));
        assertTrue(report, report.contains("quality setting smallest"));
    }

    /** With nothing at or under the ceiling, the save takes the nearest above it and still starts. */
    @Test
    public void aCeilingWithNoMatchStillSaves() throws Exception {
        String report = reelSaveAt(DownloadQuality.P360, new ReelSource(null, null, MANIFEST));
        assertTrue(report, report.contains(DASH_480 + " + audio/mp4 mp4a.40.5 0x0 64kbps, instead of nothing"));

        report = reelSaveAt(DownloadQuality.P360, new ReelSource(HD, null, null));
        assertTrue(report, report.contains("saving video mp4 (720p) from 1 candidate(s)"));

        report = reelSaveAt(DownloadQuality.SMALLEST, new ReelSource(null, null, MANIFEST));
        assertTrue(report, report.contains(DASH_480));
    }

    /**
     * A story and a feed video take the same route. The feed video's player recorded the manifest
     * under its id, and the post names the two single files.
     */
    @Test
    public void aFeedVideoIsHeldToTheSettingToo() throws Exception {
        Settings.DOWNLOAD_VIDEOS.save(true);
        String id = "7777000011112222";
        PlayerSources.rememberVideo(new PlayerSourcesForTests.Params(id,
                new PlayerSourcesForTests.HdSource(null, MANIFEST)), "videoId", "hd", "manifest");

        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P480);
        assertTrue(MediaDownload.saveFeedVideo(context, id, HD, SD));
        waitForSaves();
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("saving the video " + DASH_480));

        LogBufferManager.clearLogBuffer();
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.P360);
        assertTrue(MediaDownload.saveFeedVideo(context, id, HD, SD));
        waitForSaves();
        report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("saving video mp4 (360p) from 2 candidate(s)"));
    }

    /** Before the settings can be read, a save asks for the best, as every save did before. */
    @Test
    public void beforeTheSettingsAreReadyASaveAsksForTheBest() {
        Settings.DOWNLOAD_QUALITY.save(DownloadQuality.SMALLEST);
        assertSame(DownloadQuality.SMALLEST, MediaDownload.quality());
        SettingsContextRule.withoutContext(() ->
                assertSame(DownloadQuality.BEST, MediaDownload.quality()));
        // And a paused Facebook answers the default, the way every setting does.
        app.morphe.extension.shared.settings.PauseForTests.pause(
                app.morphe.extension.shared.settings.HushfacebookPause.Reason.SWITCH);
        try {
            assertSame(DownloadQuality.BEST, MediaDownload.quality());
        } finally {
            app.morphe.extension.shared.settings.PauseForTests.resume();
        }
        assertEquals(DownloadQuality.SMALLEST, Settings.DOWNLOAD_QUALITY.savedValue());
    }
}
