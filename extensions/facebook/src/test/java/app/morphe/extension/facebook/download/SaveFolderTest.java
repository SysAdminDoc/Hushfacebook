/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Looper;
import android.provider.MediaStore;

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
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * The folder every save lands in: one clean folder name under Movies and Pictures, whatever the
 * setting holds, and a save through the jobs every route runs publishing there whole.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveFolderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        LogBufferManager.clearLogBuffer();
        ShadowToast.reset();
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() {
        Settings.SAVE_FOLDER.resetToDefault();
        MediaDownload.policyForTests = null;
        ShadowMediaExtractor.reset();
        LogBufferManager.clearLogBuffer();
    }

    // ---- The name -------------------------------------------------------------------------------

    /** Each typed or imported value, and the one folder name a save uses for it. */
    private static Map<String, String> cases() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("Facebook", "Facebook");
        cases.put("My clips", "My clips");
        cases.put("Vid" + u(0xE9) + "os 2026", "Vid" + u(0xE9) + "os 2026");
        cases.put(u(0x65E5, 0x672C, 0x8A9E, 0x306E, 0x52D5, 0x753B), u(0x65E5, 0x672C, 0x8A9E, 0x306E, 0x52D5, 0x753B));
        cases.put("a_b-c (1)", "a_b-c (1)");
        // Path separators, their look-alikes and what a FAT name can't hold.
        cases.put("My/Clips", "My_Clips");
        cases.put("My" + u(0x5C) + "Clips", "My_Clips");
        cases.put("a//b" + u(0x5C, 0x5C) + "c", "a_b_c");
        cases.put("/Clips/", "Clips");
        cases.put("Movies/Facebook", "Movies_Facebook");
        cases.put("a:b*c?d\"e<f>g|h", "a_b_c_d_e_f_g_h");
        cases.put("a" + u(0xFF0F) + "b", "a_b");
        cases.put("a" + u(0xFF3C) + "b", "a_b");
        cases.put("a" + u(0x2215) + "b", "a_b");
        cases.put("a" + u(0x2044) + "b", "a_b");
        cases.put("a" + u(0x29F8) + "b", "a_b");
        // Dot segments, and a dot that would hide the folder.
        cases.put(".", "Facebook");
        cases.put("..", "Facebook");
        cases.put("...", "Facebook");
        cases.put("../Clips", "Clips");
        cases.put("../../..", "Facebook");
        cases.put("Clips/..", "Clips");
        cases.put("./.", "Facebook");
        cases.put(".hidden", "hidden");
        cases.put("Clips.", "Clips");
        cases.put("a.b", "a.b");
        cases.put("a..b", "a..b");
        // Control and invisible characters.
        cases.put("a" + u(0x0) + "b", "ab");
        cases.put("a" + u(0x7) + "b" + u(0x7F) + "c", "abc");
        cases.put("a\nb", "a b");
        cases.put("a\tb\r\nc", "a b c");
        cases.put("a" + u(0x200B) + "b", "ab");
        cases.put(u(0x200E, 0x202E) + "Clips" + u(0x202C), "Clips");
        cases.put(u(0xFEFF) + "Clips", "Clips");
        cases.put("Cl" + u(0xAD) + "ips", "Clips");
        cases.put("a" + u(0x2028) + "b", "a b");
        cases.put("a" + u(0x3164) + "b", "ab");
        cases.put("a" + u(0x115F) + "b", "ab");
        cases.put("a" + u(0xFE0F) + "b", "ab");
        cases.put("a" + u(0xE000) + "b", "ab");
        cases.put("a" + u(0xE0041) + "b", "ab");
        cases.put(u(0x2060, 0x2061), "Facebook");
        // Spaces.
        cases.put("   ", "Facebook");
        cases.put("  Clips  ", "Clips");
        cases.put("a    b", "a b");
        cases.put("a" + u(0xA0, 0x3000) + "b", "a b");
        // Empty, and nothing at all.
        cases.put("", "Facebook");
        cases.put(null, "Facebook");
        // Compatibility forms fold.
        cases.put(u(0xFF23, 0xFF4C, 0xFF49, 0xFF50, 0xFF53), "Clips");
        cases.put(u(0xFB01) + "lms", "films");
        return cases;
    }

    /** Text made of these code points, so no character here hides in the source. */
    private static String u(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    public void everyValueBecomesOneCleanFolderName() {
        StringBuilder wrong = new StringBuilder();
        for (Map.Entry<String, String> entry : cases().entrySet()) {
            String got = SaveFolder.sanitize(entry.getKey());
            if (!entry.getValue().equals(got)) {
                wrong.append('\n').append(printable(entry.getKey())).append(" -> ").append(printable(got))
                        .append(", expected ").append(printable(entry.getValue()));
            }
        }
        assertEquals("", wrong.toString());
    }

    @Test
    public void anOverlongNameIsCutAtFiftyCodePointsNeverInsideAPair() {
        String ascii = SaveFolder.sanitize(repeat("a", 300));
        assertEquals(SaveFolder.MAX_CODE_POINTS, ascii.length());

        // Emoji take two chars each: fifty of them are a hundred chars and no half of one.
        String emoji = SaveFolder.sanitize(repeat(u(0x1F3AC), 80));
        assertEquals(SaveFolder.MAX_CODE_POINTS, emoji.codePointCount(0, emoji.length()));
        assertFalse("a cut left half a pair", Character.isHighSurrogate(emoji.charAt(emoji.length() - 1)));
        assertTrue("fifty code points ran past the 255 bytes a name can take",
                emoji.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255);

        // A cut that lands on a space or a dot doesn't leave one at the end.
        String cut = SaveFolder.sanitize(repeat("a", 49) + " " + repeat("b", 20));
        assertEquals(repeat("a", 49), cut);
        String dotted = SaveFolder.sanitize(repeat("a", 49) + "." + repeat("b", 20));
        assertEquals(repeat("a", 49), dotted);
    }

    /** What a save uses is what a second pass keeps, so the row, the file and the next save agree. */
    @Test
    public void aCleanNameStaysAsItIs() {
        for (String value : cases().keySet()) {
            String once = SaveFolder.sanitize(value);
            assertEquals(printable(value), once, SaveFolder.sanitize(once));
            assertTrue(printable(once), SaveFolder.isClean(once));
        }
        Random random = new Random(20260925L);
        int[] pool = {'a', 'Z', '0', ' ', '.', '/', '\\', ':', '_', '-', 0x00, 0x0A, 0x7F, 0x200B, 0x202E,
                0x00AD, 0x3164, 0xFE0F, 0xFF0F, 0x2215, 0x00E9, 0x0301, 0x1F3AC, 0x1100, 0x1161, 0xFB01, 0xAC00};
        for (int round = 0; round < 5_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(70);
            for (int i = 0; i < length; i++) text.appendCodePoint(pool[random.nextInt(pool.length)]);
            String once = SaveFolder.sanitize(text.toString());
            assertEquals(printable(text.toString()), once, SaveFolder.sanitize(once));
            assertFalse(printable(once), once.isEmpty() || once.contains("/") || once.contains("\\")
                    || once.startsWith(".") || once.endsWith(".") || once.startsWith(" ") || once.endsWith(" "));
        }
        assertFalse(SaveFolder.isClean(""));
        assertFalse(SaveFolder.isClean(null));
        assertFalse(SaveFolder.isClean("a/b"));
        assertFalse(SaveFolder.isClean(".."));
        assertFalse(SaveFolder.isClean(" Clips"));
    }

    /**
     * A name from a phone on a newer Android can hold a character this one doesn't know yet. It
     * counts as an ordinary character, so the name can come in, but only a name that would be clean
     * with it: a path or a hidden folder stays out whatever else it holds.
     */
    @Test
    public void aCharacterThisPhoneDoesNotKnowYetCountsAsAnOrdinaryOne() {
        int newer = 0x50000;
        assertEquals("the stand-in for a newer character has to be unknown here",
                Character.UNASSIGNED, Character.getType(newer));
        String unknown = new String(Character.toChars(newer));
        assertFalse(SaveFolder.isClean("Clips" + unknown));
        for (String importable : new String[]{"Clips" + unknown, "Clips " + unknown, unknown, "My " + unknown + " clips", "Clips"}) {
            assertTrue(printable(importable), SaveFolder.isImportable(importable));
        }
        for (String refused : new String[]{"../" + unknown, "My/" + unknown, unknown + " ", "." + unknown, unknown + "​",
                "a/b", " Clips", "", null}) {
            assertFalse(refused == null ? "null" : printable(refused), SaveFolder.isImportable(refused));
        }
        assertEquals("the saves here drop what the phone can't place", "Clips", SaveFolder.sanitize("Clips " + unknown));
    }

    @Test
    public void theFolderFollowsTheSettingAndStartsAsFacebook() {
        assertEquals("Facebook", SaveFolder.leaf());
        Settings.SAVE_FOLDER.save("Clips");
        assertEquals("Clips", SaveFolder.leaf());
        Settings.SAVE_FOLDER.save("../../Android/data");
        assertEquals("a value written past the settings row still makes one folder name",
                "Android_data", SaveFolder.leaf());
    }

    // ---- The gallery ----------------------------------------------------------------------------

    private SaveProgressTest.Gallery gallery() {
        return Robolectric.setupContentProvider(SaveProgressTest.Gallery.class, MediaStore.AUTHORITY);
    }

    private void writable(Uri row) {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(row, new ByteArrayOutputStream());
    }

    private static String pathOf(ContentValues row) {
        return row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
    }

    @Test
    public void aVideoGoesUnderMoviesAndAPhotoUnderPicturesInTheSameFolder() throws IOException {
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery.videoUri(1));
        writable(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 2));
        Settings.SAVE_FOLDER.save("My/Clips");

        MediaStoreWriter video = new MediaStoreWriter(context, true);
        video.open("video/mp4").close();
        MediaStoreWriter photo = new MediaStoreWriter(context, false);
        photo.open("image/jpeg").close();

        assertEquals("Movies/My_Clips", pathOf(gallery.rows.get(1L)));
        assertEquals("Movies/My_Clips", video.savedLocation());
        assertEquals("Pictures/My_Clips", pathOf(gallery.rows.get(2L)));
        assertEquals("Pictures/My_Clips", photo.savedLocation());
        video.abandon();
        photo.abandon();
    }

    @Test
    public void withTheDefaultEverySaveGoesWhereItAlwaysDid() throws IOException {
        SaveProgressTest.Gallery gallery = gallery();
        writable(gallery.videoUri(1));
        MediaStoreWriter video = new MediaStoreWriter(context, true);
        video.open("video/mp4").close();
        assertEquals("Movies/Facebook", pathOf(gallery.rows.get(1L)));
        video.abandon();
    }

    // ---- A save through the jobs every route runs -----------------------------------------------

    private static final byte[] MP4_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
        0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm',
    };

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        System.arraycopy(MP4_HEAD, 0, body, 0, MP4_HEAD.length);
        for (int i = MP4_HEAD.length; i < size; i++) body[i] = (byte) (i * 13);
        return body;
    }

    private void finish(Thread worker) throws InterruptedException {
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * A single file, the progressive route a feed video takes without a manifest: fetched, checked
     * and published as one finished row in the folder the person chose, and the toast says where.
     */
    @Test
    public void aProgressiveVideoIsPublishedInTheChosenFolder() throws Exception {
        Settings.SAVE_FOLDER.save("Clips");
        SaveProgressTest.Gallery gallery = gallery();
        ByteArrayOutputStream published = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        try (LocalServer server = new LocalServer()) {
            MediaDownload.policyForTests = localPolicy(server, null);
            byte[] body = mp4(64_000);
            server.serve("/clip.mp4", 200, "video/mp4", body, body.length);

            finish(MediaDownload.start(context, true,
                    MediaDownload.fileJob(context, server.origin() + "/clip.mp4", Downloader.Kind.VIDEO)));

            ContentValues row = gallery.rows.get(1L);
            assertEquals("Movies/Clips", pathOf(row));
            assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertArrayEquals(body, published.toByteArray());
            assertEquals("Saved to " + L10n.isolate("Movies/Clips"), ShadowToast.getTextOfLatestToast());
        }
    }

    /**
     * The DASH route: the video and sound tracks fetched, joined and published whole in the chosen
     * folder, through the same job a story, a reel and a feed video's manifest run.
     */
    @Test
    public void aDashVideoIsJoinedAndPublishedInTheChosenFolder() throws Exception {
        Settings.SAVE_FOLDER.save("Clips");
        SaveProgressTest.Gallery gallery = gallery();
        ByteArrayOutputStream published = new ByteArrayOutputStream();
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        byte[] picture = sample(1_000, 'v');
        byte[] sound = sample(1_000, 'a');
        try (LocalServer server = new LocalServer()) {
            MediaDownload.policyForTests = localPolicy(server, () -> describeWorkFiles(picture, sound));
            server.serve("/v.mp4", 200, "video/mp4", mp4(1_000), 1_000);
            server.serve("/a.mp4", 200, "audio/mp4", mp4(1_000), 1_000);
            DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                    server.origin() + "/v.mp4");
            DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000,
                    server.origin() + "/a.mp4");

            finish(MediaDownload.start(context, true, MediaDownload.dashJob(context, video, audio, null)));

            ContentValues row = gallery.rows.get(1L);
            assertEquals("Movies/Clips", pathOf(row));
            assertEquals(Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            joined.write(picture);
            joined.write(sound);
            assertArrayEquals("the gallery didn't get the joined file", joined.toByteArray(), published.toByteArray());
            String[] left = DashSave.workFolder(context).list();
            assertEquals("the join left work files behind", 0, left == null ? 0 : left.length);
        }
    }

    /**
     * Lets the local server through, and runs [onSound] when the sound track is asked for, which
     * is after the picture track is on disk: that's when a test can describe both work files to
     * Robolectric's extractor.
     */
    private static MediaUrlPolicy localPolicy(LocalServer server, Runnable onSound) {
        int port = server.port();
        return new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (onSound != null && url.getPath().equals("/a.mp4")) onSound.run();
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
    }

    private void describeWorkFiles(byte[] pictureSample, byte[] soundSample) {
        for (File file : DashSave.workFolder(context).listFiles()) {
            DataSource path = DataSource.toDataSource(file.getPath());
            if (file.getName().startsWith("video")) {
                ShadowMediaExtractor.addTrack(path, MediaFormat.createVideoFormat("video/avc", 1280, 720), pictureSample);
            } else if (file.getName().startsWith("audio")) {
                ShadowMediaExtractor.addTrack(path, MediaFormat.createAudioFormat("audio/mp4a-latm", 44_100, 2),
                        soundSample);
            }
        }
    }

    private static byte[] sample(int size, char fill) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }

    /** A value with its invisible characters spelled out, for a failure message. */
    private static String printable(String text) {
        if (text == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 0x20 || codePoint > 0x7E) out.append(String.format("<U+%04X>", codePoint));
            else out.appendCodePoint(codePoint);
        }
        return out.append('"').toString();
    }
}
