/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.os.Looper;
import android.provider.MediaStore;

import com.facebook.video.engine.api.VideoDataSource;
import com.facebook.video.engine.api.VideoPlayerParams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The name a saved video gets. The template ships as Facebook's own naming, so nothing changes
 * for anyone who leaves it; its tokens fill in per save; and whatever it holds, the name is one
 * clean file name of bounded length that no path can come out of, that the gallery doesn't hide,
 * reserve or read as a photo, and that doesn't come out the same for every save.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FileNameTemplateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    /** 2026-09-25 14:30:05 on the phone's clock, whatever zone the test runs in. */
    private static Date when() {
        return at(5);
    }

    /** The same minute, [second] seconds in. */
    private static Date at(int second) {
        Calendar calendar = new java.util.GregorianCalendar();
        calendar.clear();
        calendar.set(2026, Calendar.SEPTEMBER, 25, 14, 30, second);
        return calendar.getTime();
    }

    private static final String STAMP = "20260925_143005";
    private static final String ID = "1234567890123456";

    /** The extensions MediaStoreWriter gives a file, which no template may end with. */
    private static final String[] EXTENSIONS = {".mp4", ".m4v", ".mov", ".webm", ".3gp", ".jpg", ".jpeg", ".png",
            ".webp", ".heic", ".heif", ".avif", ".gif"};

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() {
        Settings.FILENAME_TEMPLATE.resetToDefault();
        MediaDownload.policyForTests = null;
        MediaDownload.videoIdsForTests = null;
        LogBufferManager.clearLogBuffer();
    }

    // ---- The name -------------------------------------------------------------------------------

    /** The template as it ships makes the name every save always had. */
    @Test
    public void theDefaultIsFacebooksOwnName() {
        assertEquals("FB_VID_{date}", FileNameTemplate.DEFAULT);
        assertEquals("FB_VID_" + STAMP, FileNameTemplate.videoName(FileNameTemplate.DEFAULT, when(), ID));
        assertEquals("FB_VID_" + STAMP, FileNameTemplate.videoName(FileNameTemplate.DEFAULT, when(), null));
        assertEquals(FileNameTemplate.DEFAULT, FileNameTemplate.current());
        assertTrue(FileNameTemplate.isClean(FileNameTemplate.DEFAULT));
    }

    @Test
    public void theTokensFillInPerSave() {
        assertEquals(STAMP + "_" + ID, FileNameTemplate.videoName("{date}_{video_id}", when(), ID));
        assertEquals("Reel " + ID, FileNameTemplate.videoName("Reel {video_id}", when(), ID));
        assertEquals(ID, FileNameTemplate.videoName("{video_id}", when(), ID));
        assertEquals(ID + " " + ID, FileNameTemplate.videoName("{video_id} {video_id}", when(), ID));
        // Other braces stay as they are.
        assertEquals("{creator}_" + STAMP, FileNameTemplate.videoName("{creator}_{date}", when(), ID));
        // With no id, or one that isn't a number, the token is left out.
        assertEquals(STAMP + "_", FileNameTemplate.videoName("{date}_{video_id}", when(), null));
        assertEquals(STAMP, FileNameTemplate.videoName("{date}{video_id}", when(), "12a4"));
        // A name that fills in to nothing is Facebook's own.
        assertEquals("FB_VID_" + STAMP, FileNameTemplate.videoName("{video_id}", when(), null));
        assertEquals("FB_VID_" + STAMP, FileNameTemplate.videoName("", when(), ID));
        assertEquals("FB_VID_" + STAMP, FileNameTemplate.videoName(null, when(), ID));
        // The date is written in Western digits and the Gregorian calendar whatever the locale.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
            assertEquals("FB_VID_" + STAMP, FileNameTemplate.videoName(FileNameTemplate.DEFAULT, when(), null));
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * MediaStore numbers a name that's taken, (1) to (31), and then refuses the save. So no two
     * saves a second apart may get one name: a template without either token gets the date, and
     * one that counts on an id the save doesn't have gets the date and time on the end.
     */
    @Test
    public void noTemplateNamesEverySaveTheSame() {
        assertEquals("Clip_" + STAMP, FileNameTemplate.videoName("Clip", when(), ID));
        assertEquals("Reel_" + STAMP, FileNameTemplate.videoName("Reel {video_id}", when(), null));
        for (String notAnId : new String[]{"12a4", "../../1", "12345678901234567890123456", "", " 1"}) {
            assertEquals(notAnId, "Reel_" + STAMP, FileNameTemplate.videoName("Reel {video_id}", when(), notAnId));
        }
        assertEquals("Reel_" + STAMP, FileNameTemplate.videoName("Reel_{video_id}", when(), null));

        String[] templates = {"Clip", "Reel {video_id}", "{video_id}", "Clip_", "x-", FileNameTemplate.DEFAULT,
                "{date}", "{creator}"};
        for (String template : templates) {
            for (String id : new String[]{null, ID}) {
                String first = FileNameTemplate.videoName(template, at(5), id);
                String second = FileNameTemplate.videoName(template, at(6), id);
                if (id != null && FileNameTemplate.sanitize(template).contains(FileNameTemplate.VIDEO_ID)
                        && !FileNameTemplate.sanitize(template).contains(FileNameTemplate.DATE)) {
                    // One video, named by its own number: the same name for the same video.
                    assertEquals(template, first, second);
                } else {
                    assertNotEquals(template + " with id " + id, first, second);
                }
            }
        }
    }

    /** Each typed or imported template, and the one template a save uses for it. */
    @Test
    public void everyTemplateBecomesOneCleanName() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("FB_VID_{date}", "FB_VID_{date}");
        cases.put("{date} {video_id}", "{date} {video_id}");
        cases.put("My/{date}", "My_{date}");
        cases.put("../../{video_id}", "{video_id}");
        cases.put("a\\b:c*d?e\"f<g>h|i{date}", "a_b_c_d_e_f_g_h_i{date}");
        cases.put(".{date}", "{date}");
        cases.put("{date}.", "{date}");
        cases.put("..", FileNameTemplate.DEFAULT);
        cases.put("   ", FileNameTemplate.DEFAULT);
        cases.put("", FileNameTemplate.DEFAULT);
        cases.put(null, FileNameTemplate.DEFAULT);
        cases.put("a" + u(0x200B) + "b{date}", "ab{date}");
        cases.put(u(0x202E) + "Clip{date}", "Clip{date}");
        cases.put("a\nb{date}", "a b{date}");
        cases.put(u(0xFF0F) + "{date}", "{date}");
        // A template with neither token gets the date.
        cases.put("Clip", "Clip_{date}");
        cases.put("Clip_", "Clip_{date}");
        cases.put("Clip-", "Clip-{date}");
        cases.put("a\\b", "a_b_{date}");
        cases.put("{creator}", "{creator}_{date}");
        // The extension is the writer's to give.
        cases.put("{date}.mp4", "{date}");
        cases.put("{video_id}.jpg.WEBM", "{video_id}");
        cases.put("clip.MP4", "clip_{date}");
        cases.put("{date} .mov", "{date}");
        cases.put("{date}.mp4v", "{date}.mp4v");
        // Facebook's photo names stay photos'.
        cases.put("FB_IMG_{date}", "FB_VID_{date}");
        cases.put("fb_img_{video_id}", "FB_VID_{video_id}");
        cases.put("FB_IMG_", FileNameTemplate.DEFAULT);
        cases.put("FB_IMG_Clip", "FB_VID_Clip_{date}");
        // MediaStore's hidden and reserved names start with a dot, and none can.
        cases.put(".pending-1234-{date}", "pending-1234-{date}");
        cases.put(".trashed-1234-clip", "trashed-1234-clip_{date}");
        cases.put(".nomedia", "nomedia_{date}");
        StringBuilder wrong = new StringBuilder();
        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String got = FileNameTemplate.sanitize(entry.getKey());
            if (!entry.getValue().equals(got)) {
                wrong.append('\n').append(entry.getKey()).append(" -> ").append(got).append(", expected ").append(entry.getValue());
            }
            assertTrue(got, FileNameTemplate.isClean(got));
            assertEquals(got, FileNameTemplate.sanitize(got));
        }
        assertEquals("", wrong.toString());
        for (String unclean : new String[]{"a/b{date}", "", null, " {date}", "Clip", "{date}.mp4", "FB_IMG_{date}",
                ".nomedia{date}"}) {
            assertFalse(String.valueOf(unclean), FileNameTemplate.isClean(unclean));
        }
    }

    /**
     * Whatever the template and the id, the name is one file name: no separator, no dot or space
     * at either end, nothing invisible, no photo's name, and no more bytes than leave room for an
     * extension. And the template a save keeps always has a token, and no extension of its own.
     */
    @Test
    public void noTemplateMakesAPathOrAnOverlongName() {
        assertEquals(FileNameTemplate.MAX_TEMPLATE_CODE_POINTS, FileNameTemplate.sanitize(repeat("a", 300)).length());
        assertEquals(repeat("a", 43) + "_{date}", FileNameTemplate.sanitize(repeat("a", 300)));
        String emoji = new String(Character.toChars(0x1F3AC));
        String fifty = FileNameTemplate.sanitize(repeat(emoji, 80));
        assertEquals(50, fifty.codePointCount(0, fifty.length()));
        assertTrue(FileNameTemplate.videoName(fifty, when(), ID).getBytes(StandardCharsets.UTF_8).length
                <= FileNameTemplate.MAX_NAME_BYTES);
        String ids = FileNameTemplate.sanitize(repeat(emoji, 40) + "{video_id}");
        assertTrue(FileNameTemplate.videoName(ids, when(), null).getBytes(StandardCharsets.UTF_8).length
                <= FileNameTemplate.MAX_NAME_BYTES);

        Random random = new Random(20260925L);
        String[] pool = {"a", "Z", "0", " ", ".", "/", "\\", ":", "_", "-", "{date}", "{video_id}", "{", "}", "\u0000",
                "\n", u(0x200B), u(0x202E), u(0xFF0F), u(0x00E9), u(0x0301), emoji, u(0xAC00), u(0x3164),
                "FB_IMG_", "fb_img_", ".mp4", ".JPG", "mp", "4", ".pending-"};
        Pattern bad = Pattern.compile("[/\\\\:*?\"<>|\\p{Cntrl}\\p{Cf}]");
        for (int round = 0; round < 5_000; round++) {
            StringBuilder template = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) template.append(pool[random.nextInt(pool.length)]);
            String id = random.nextBoolean() ? ID : random.nextBoolean() ? null : "x/1";
            String name = FileNameTemplate.videoName(template.toString(), when(), id);
            assertFalse(template + " -> " + name, name.isEmpty() || bad.matcher(name).find()
                    || name.startsWith(".") || name.endsWith(".") || name.startsWith(" ") || name.endsWith(" "));
            assertFalse(template + " -> " + name, name.regionMatches(true, 0, "FB_IMG_", 0, 7));
            assertTrue(template + " -> " + name,
                    name.getBytes(StandardCharsets.UTF_8).length <= FileNameTemplate.MAX_NAME_BYTES);
            String once = FileNameTemplate.sanitize(template.toString());
            assertEquals(template.toString(), once, FileNameTemplate.sanitize(once));
            assertTrue(template + " -> " + once, once.contains("{date}") || once.contains("{video_id}"));
            assertFalse(template + " -> " + once, once.regionMatches(true, 0, "FB_IMG_", 0, 7));
            for (String extension : EXTENSIONS) {
                assertFalse(template + " -> " + once, once.toLowerCase(Locale.ROOT).endsWith(extension));
            }
            assertTrue(template + " -> " + once, once.codePointCount(0, once.length())
                    <= FileNameTemplate.MAX_TEMPLATE_CODE_POINTS);
        }
    }

    /** A template from a phone on a newer Android can hold a character this one doesn't know yet. */
    @Test
    public void aTemplateFromANewerAndroidComesInAsTheNameSavesUseHere() {
        String unknown = new String(Character.toChars(0x50000));
        assertEquals(Character.UNASSIGNED, Character.getType(0x50000));
        assertTrue(FileNameTemplate.isImportable("Clip " + unknown + " {date}"));
        assertTrue(FileNameTemplate.isImportable(FileNameTemplate.DEFAULT));
        for (String refused : new String[]{"../" + unknown, "a/{date}", " {date}", "{date}.", "", null,
                "Clip " + unknown, "{date}.mp4", "FB_IMG_{date}"}) {
            assertFalse(String.valueOf(refused), FileNameTemplate.isImportable(refused));
        }
        assertEquals("Clip {date}", FileNameTemplate.sanitize("Clip " + unknown + " {date}"));
    }

    @Test
    public void theTemplateFollowsTheSetting() {
        Settings.FILENAME_TEMPLATE.save("../My/{video_id}");
        assertEquals("a value written past the settings row still makes one clean template",
                "My_{video_id}", FileNameTemplate.current());
        Settings.FILENAME_TEMPLATE.save("Clip.mp4");
        assertEquals("Clip_{date}", FileNameTemplate.current());
        SettingsContextRule.withoutContext(() -> assertEquals(FileNameTemplate.DEFAULT, FileNameTemplate.current()));
    }

    // ---- The gallery ----------------------------------------------------------------------------

    private SaveProgressTest.Gallery gallery() {
        return Robolectric.setupContentProvider(SaveProgressTest.Gallery.class, MediaStore.AUTHORITY);
    }

    private static String nameOf(ContentValues row) {
        return row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
    }

    /** With the template as it ships, a video and a photo get exactly the names they always did. */
    @Test
    public void withTheDefaultEveryFileKeepsItsName() throws Exception {
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
        writable(gallery, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2);
        MediaStoreWriter video = new MediaStoreWriter(context, true, ID);
        video.open("video/mp4").close();
        MediaStoreWriter photo = new MediaStoreWriter(context, false, ID);
        photo.open("image/webp").close();
        assertTrue(nameOf(gallery.rows.get(1L)), nameOf(gallery.rows.get(1L)).matches("FB_VID_\\d{8}_\\d{6}\\.mp4"));
        assertTrue(nameOf(gallery.rows.get(2L)), nameOf(gallery.rows.get(2L)).matches("FB_IMG_\\d{8}_\\d{6}\\.webp"));
        video.abandon();
        photo.abandon();
    }

    /** A template names videos and leaves photos alone; the id fills in when the save has one. */
    @Test
    public void aTemplateNamesVideosAndNotPhotos() throws Exception {
        Settings.FILENAME_TEMPLATE.save("Reel {video_id}");
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1);
        writable(gallery, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2);
        writable(gallery, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 3);
        new MediaStoreWriter(context, true, ID).open("video/webm").close();
        new MediaStoreWriter(context, false, ID).open("image/jpeg").close();
        new MediaStoreWriter(context, true, null).open("video/mp4").close();
        assertEquals("Reel " + ID + ".webm", nameOf(gallery.rows.get(1L)));
        assertTrue(nameOf(gallery.rows.get(2L)), nameOf(gallery.rows.get(2L)).matches("FB_IMG_\\d{8}_\\d{6}\\.jpg"));
        // No id: the date and time keep this save's name apart from the next one's.
        assertTrue(nameOf(gallery.rows.get(3L)), nameOf(gallery.rows.get(3L)).matches("Reel_\\d{8}_\\d{6}\\.mp4"));

        // The report says the id was missing, and never names one.
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the file name asks for the video id and this save has none, "
                + "so the date and time go on the end"));
        assertFalse(report, report.contains(ID));
    }

    private void writable(SaveProgressTest.Gallery gallery, android.net.Uri table, long id) {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                android.content.ContentUris.withAppendedId(table, id), new ByteArrayOutputStream());
    }

    /**
     * A whole save through the job every single-file route runs, with the id the route hands to
     * the save: the gallery row is named from the template when it's published.
     */
    @Test
    public void aSaveIsNamedFromTheTemplateWithItsVideosId() throws Exception {
        Settings.FILENAME_TEMPLATE.save("{video_id}_{date}");
        SaveProgressTest.Gallery gallery = gallery();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), new ByteArrayOutputStream());
        try (LocalServer server = new LocalServer()) {
            int port = server.port();
            MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
                @Override
                Refusal refusal(URL url) {
                    if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                    return super.refusal(url);
                }
            };
            byte[] body = new byte[64_000];
            byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
            System.arraycopy(head, 0, body, 0, head.length);
            server.serve("/clip.mp4", 200, "video/mp4", body, body.length);

            Thread worker = MediaDownload.start(context, true, ID,
                    MediaDownload.fileJob(context, server.origin() + "/clip.mp4", Downloader.Kind.VIDEO));
            worker.join(30_000);
            assertFalse(worker.isAlive());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            String name = nameOf(gallery.rows.get(1L));
            assertTrue(name, name.matches(ID + "_\\d{8}_\\d{6}\\.mp4"));
            assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        }
    }

    // ---- Where the id comes from ----------------------------------------------------------------

    /** The reel's params hold Facebook's player params, which hold the source and say their id. */
    static final class RichParams {
        final VideoPlayerParams plain;

        RichParams(VideoPlayerParams plain) {
            this.plain = plain;
        }
    }

    /** Params that hold two player params: which one is the reel on the screen isn't known. */
    static final class TwoParams {
        final VideoPlayerParams one;
        final VideoPlayerParams two;

        TwoParams(VideoPlayerParams one, VideoPlayerParams two) {
            this.one = one;
            this.two = two;
        }
    }

    /**
     * Facebook's player params say {@code "VideoId: "} and the id in their toString, on 577 and 580
     * alike (checked in both fixtures' dex: a const-string and the id field, joined). The stand-in
     * says what each case gives it.
     */
    @Test
    public void aReelsIdIsWhatItsPlayerParamsSay() {
        VideoPlayerParams params = new VideoPlayerParams("VideoId: " + ID, new VideoDataSource());
        assertEquals(ID, ReelDownload.videoIdOf(params));
        assertEquals(ID, ReelDownload.videoIdOf(new RichParams(params)));
        // A build that says something else there gives no id, and the name leaves it out.
        for (String said : new String[]{"VideoId: ", "VideoId: null", "VideoId: 12a4", "videoId: " + ID, ID,
                "VideoId: " + ID + " (live)", "VideoId: ../1"}) {
            assertNull(said, ReelDownload.videoIdOf(new RichParams(new VideoPlayerParams(said, new VideoDataSource()))));
        }
        assertNull(ReelDownload.videoIdOf(new RichParams(null)));
        assertNull(ReelDownload.videoIdOf(null));
        assertNull(ReelDownload.videoIdOf("not params"));
        // Two player params at one level: neither is known to be the reel on the screen.
        assertNull(ReelDownload.videoIdOf(new TwoParams(params, new VideoPlayerParams("VideoId: 99", null))));
        // A toString that throws gives no id either.
        assertNull(ReelDownload.videoIdOf(new RichParams(new VideoPlayerParams(null, null) {
            @Override
            public String toString() {
                throw new IllegalStateException("renamed");
            }
        })));
    }

    /**
     * Each route hands the save the id it has: a reel its player's, a story the id its recorded
     * player was kept under, and a feed video the id its post carries.
     */
    @Test
    public void everyRouteHandsTheSaveItsVideosId() throws Exception {
        List<String> ids = new ArrayList<>();
        MediaDownload.videoIdsForTests = id -> {
            synchronized (ids) {
                ids.add(id);
            }
        };
        // Every Meta name answers a private address, so each save stops before it connects.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
        String clip = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/clip_720p.mp4?oh=1&oe=2";

        // A reel: its Download button's handler, tapped.
        ReelSourceParams reel = new ReelSourceParams(new VideoPlayerParams("VideoId: 2233445566778899",
                new ReelSource(clip)));
        new ReelDownload(reel, context, "hd", "sd", "manifest", 1, true).invoke(null);
        waitForSaves();

        // A story: its card holds the id its player was recorded under.
        PlayerSources.remember(new PlayerSourcesForTests.Params("3344556677889900",
                new PlayerSourcesForTests.HdSource(clip, null)), "videoId", "hd", "manifest");
        assertTrue(MediaDownload.saveStory(context, new PlayerSourcesForTests.Card("3344556677889900")));
        waitForSaves();

        // A feed video: the post's own id.
        assertTrue(MediaDownload.saveFeedVideo(context, "4455667788990011", clip, null));
        waitForSaves();

        assertEquals(java.util.Arrays.asList("2233445566778899", "3344556677889900", "4455667788990011"), ids);
    }

    /** A reel's source, its address field named the way the patch passes it. */
    static final class ReelSource extends VideoDataSource {
        final String hd;

        ReelSource(String hd) {
            this.hd = hd;
        }
    }

    /** The sidebar's rich params: they hold Facebook's plain player params. */
    static final class ReelSourceParams {
        final VideoPlayerParams plain;

        ReelSourceParams(VideoPlayerParams plain) {
            this.plain = plain;
        }
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /** Text made of these code points, so no character here hides in the source. */
    private static String u(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }
}
