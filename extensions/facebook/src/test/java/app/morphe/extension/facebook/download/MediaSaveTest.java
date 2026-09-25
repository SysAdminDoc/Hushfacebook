/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.MediaStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A save as the feature runs one: into the cache, then through MediaStoreWriter into a stand-in
 * for MediaStore. A refused or broken fetch must never insert a row, pending or not, and must
 * leave the work folder empty. A good one publishes one finished row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class MediaSaveTest {
    private LocalServer server;
    private String origin;
    private MediaUrlPolicy policy;
    private Context context;
    private Gallery gallery;
    private final ByteArrayOutputStream published = new ByteArrayOutputStream();

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        origin = server.origin();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        gallery = Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        resolver.registerOutputStream(gallery.videoUri(1), published);
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        // The join cases tell Robolectric's extractor about their work files, and it keeps that
        // in a static map. Cleared here rather than left to Robolectric's own reset.
        ShadowMediaExtractor.reset();
    }

    private void serve(String path, String type, byte[] body, long announced) {
        server.serve(path, 200, type, body, announced);
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) (i * 13);
        return body;
    }

    private Downloader.Result save(String path, long max) {
        File folder = DashSave.workFolder(context);
        assertNotNull(folder);
        return Downloader.save(origin + path, Downloader.Kind.VIDEO, folder,
                new MediaStoreWriter(context, true), policy, max);
    }

    private void assertNothingWasCreated(String what, Downloader.Result result) {
        assertTrue(what + " was saved: " + result, !result.ok());
        assertEquals(what + " created a gallery row", 0, gallery.inserts.size());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(what + " left work files behind", 0, left == null ? 0 : left.length);
    }

    @Test
    public void refusedAndBrokenFetchesNeverCreateARow() {
        byte[] page = "<html><body>Log in</body></html>".getBytes(StandardCharsets.UTF_8);
        serve("/page.mp4", "video/mp4", page, page.length);
        assertNothingWasCreated("a page sent as video", save("/page.mp4", Downloader.MAX_BYTES));

        serve("/big.mp4", "video/mp4", mp4(4096), -1);
        assertNothingWasCreated("an oversized stream", save("/big.mp4", 1024));

        serve("/short.mp4", "video/mp4", mp4(1000), 5000);
        assertNothingWasCreated("a truncated body", save("/short.mp4", Downloader.MAX_BYTES));

        server.redirect("/away", "https://scontent.xx.fbcdn.net/v.mp4");
        // The lookup answers 10.9.8.7 for every Meta name here.
        assertNothingWasCreated("a redirect to a Meta name on a private address", save("/away", Downloader.MAX_BYTES));
    }

    @Test
    public void aGoodVideoIsPublishedAsOneFinishedRow() {
        byte[] body = mp4(64_000);
        serve("/v.mp4", "video/mp4", body, body.length);

        Downloader.Result result = save("/v.mp4", Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(1, gallery.inserts.size());
        ContentValues row = gallery.rows.get(1L);
        assertEquals("the row was left pending", Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals("video/mp4", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertTrue(row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).endsWith(".mp4"));
        assertArrayEquals(body, published.toByteArray());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(0, left == null ? 0 : left.length);
    }

    @Test
    public void aDashTrackOffMetasServersIsRefusedBeforeAnythingIsFetched() {
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                "https://example.com/v.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000,
                "http://scontent.xx.fbcdn.net/a.mp4");

        Downloader.Result result = DashSave.save(context, video, audio, new MediaStoreWriter(context, true));

        assertEquals(result.toString(), Downloader.Status.REFUSED, result.status);
        assertNothingWasCreated("a foreign DASH track", result);
    }

    private DashManifest.Track goodPicture() {
        byte[] picture = mp4(64_000);
        serve("/v.mp4", "video/mp4", picture, picture.length);
        return new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000, origin + "/v.mp4");
    }

    /**
     * The case above stops at the picture, so nothing held the sound track to the same checks.
     * The sound here is a good file on a second server this test's policy doesn't let through, so
     * a sound fetch that skipped the policy would get it and fail later, at the join.
     */
    @Test
    public void aSoundTrackOffMetasServersIsRefusedAfterAGoodPicture() throws IOException {
        DashManifest.Track video = goodPicture();
        try (LocalServer foreign = new LocalServer()) {
            byte[] sound = mp4(20_000);
            foreign.serve("/a.mp4", 200, "audio/mp4", sound, sound.length);
            server.redirect("/away.mp4", foreign.origin() + "/a.mp4");

            for (String url : new String[] { foreign.origin() + "/a.mp4", origin + "/away.mp4" }) {
                DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, url);
                Downloader.Result result = DashSave.save(context, video, audio, new MediaStoreWriter(context, true), policy);
                assertEquals(url + ": " + result, Downloader.Status.REFUSED, result.status);
                assertNothingWasCreated("a DASH save whose sound is at " + url, result);
            }
        }
    }

    /**
     * Each track used to get the whole cap, so a pair could join into a file over it. The sound
     * gets what the picture left now, and 64,000 plus 50,000 doesn't fit in 100,000.
     */
    @Test
    public void aDashPairOverTheCapIsRefusedBeforeItIsJoined() {
        DashManifest.Track video = goodPicture();
        byte[] sound = mp4(50_000);
        serve("/a.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a.mp4");

        Downloader.Result result = DashSave.save(context, video, audio, new MediaStoreWriter(context, true), policy, 100_000);

        assertEquals(result.toString(), Downloader.Status.TOO_LARGE, result.status);
        assertNothingWasCreated("a DASH pair over the cap", result);
    }

    /**
     * The case above holds the share from above only: a sound given nothing, or a picture given
     * half the cap, refused it just as well. So a pair that fits has to get through both fetches
     * (nothing describes them to Robolectric's extractor, so the join fails and the save ends
     * there), and a picture that takes the whole cap leaves the sound none.
     */
    @Test
    public void aDashPairThatFitsTheCapIsFetchedWhole() {
        byte[] picture = mp4(60_000);
        serve("/v60.mp4", "video/mp4", picture, picture.length);
        byte[] sound = mp4(40_000);
        serve("/a40.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v60.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a40.mp4");

        Downloader.Result result = DashSave.save(context, video, audio, new MediaStoreWriter(context, true), policy, 100_000);

        assertEquals("WRITE_ERROR (the tracks could not be joined)", result.toString());
        assertEquals("the sound track wasn't fetched", 1, server.hits("/a40.mp4"));
        assertNothingWasCreated("a pair that fits, with nothing described to join", result);

        byte[] whole = mp4(100_000);
        serve("/v100.mp4", "video/mp4", whole, whole.length);
        DashManifest.Track wholeCap = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v100.mp4");
        result = DashSave.save(context, wholeCap, audio, new MediaStoreWriter(context, true), policy, 100_000);
        assertEquals(result.toString(), Downloader.Status.TOO_LARGE, result.status);
        assertTrue(result.toString(), result.reason.endsWith("more than 0"));
        assertNothingWasCreated("a picture that took the whole cap", result);
    }

    /**
     * A DASH save is one save to the person watching it. Told of the sound track on its own, its
     * notification started over at the sound and gave that track's size as the whole save's; the
     * sound's bytes now count on from the picture's.
     */
    @Test
    public void aDashSavesProgressCountsOnThroughTheSoundTrack() {
        byte[] picture = mp4(60_000);
        serve("/v60.mp4", "video/mp4", picture, picture.length);
        byte[] sound = mp4(40_000);
        serve("/a40.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v60.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a40.mp4");
        List<long[]> told = new ArrayList<>();
        Downloader.Progress watching = new Downloader.Progress() {
            @Override
            public void transferred(long done, long total) {
                told.add(new long[]{done, total});
            }

            @Override
            public void reading(Runnable close) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };

        DashSave.save(context, video, audio, new MediaStoreWriter(context, true), policy, 100_000, watching);

        assertFalse("nothing was reported", told.isEmpty());
        for (int i = 1; i < told.size(); i++) {
            assertTrue("the count went back from " + told.get(i - 1)[0] + " to " + told.get(i)[0],
                    told.get(i)[0] >= told.get(i - 1)[0]);
        }
        long[] last = told.get(told.size() - 1);
        assertEquals("the save's bytes", 100_000, last[0]);
        assertEquals("the save's size, not the sound track's", 100_000, last[1]);
    }

    /**
     * A 1,000-byte picture and a 1,000-byte sound, saved under a 10,000-byte cap so both fetches
     * fit, and joined. Robolectric's extractor reads no file: it answers with the samples a test
     * gives it for a path, and its muxer writes exactly those samples into the joined file, so the
     * joined size is the test's to choose. The work files get random names, so they're described
     * as the sound's address is checked. Both exist by then, and the join hasn't started.
     */
    private Downloader.Result saveAndJoin(byte[] pictureSample, byte[] soundSample) {
        byte[] picture = mp4(1_000);
        serve("/v.mp4", "video/mp4", picture, picture.length);
        byte[] sound = mp4(1_000);
        serve("/a.mp4", "audio/mp4", sound, sound.length);
        DashManifest.Track video = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 2_000_000,
                origin + "/v.mp4");
        DashManifest.Track audio = new DashManifest.Track("audio/mp4", "mp4a.40.2", 0, 0, 128_000, origin + "/a.mp4");
        int port = server.port();
        MediaUrlPolicy describing = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getPath().equals("/a.mp4")) describeWorkFiles(pictureSample, soundSample);
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        return DashSave.save(context, video, audio, new MediaStoreWriter(context, true), describing, 10_000);
    }

    /** Each work file, found by the name DashSave starts it with, gets its one sample. */
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

    /** [size] bytes of [fill], so the joined file shows which track each byte came from. */
    private static byte[] sample(int size, char fill) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    /**
     * Every DASH case above ends before the gallery, so nothing checked what a finished join
     * publishes. This pair fits and is joined, and the gallery gets the joined file: the picture's
     * sample, then the sound's. Robolectric puts every sample at time zero, and the join takes the
     * picture on a tie.
     */
    @Test
    public void aDashPairThatFitsTheCapIsJoinedAndPublished() {
        byte[] picture = sample(1_000, 'v');
        byte[] sound = sample(1_000, 'a');

        Downloader.Result result = saveAndJoin(picture, sound);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(1, gallery.inserts.size());
        ContentValues row = gallery.rows.get(1L);
        assertEquals("the row was left pending", Integer.valueOf(0), row.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals("video/mp4", row.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(picture, 0, picture.length);
        joined.write(sound, 0, sound.length);
        assertArrayEquals("the gallery didn't get the joined file", joined.toByteArray(), published.toByteArray());
        String[] left = DashSave.workFolder(context).list();
        assertEquals(0, left == null ? 0 : left.length);
    }

    /**
     * Joining writes boxes of its own, so a pair whose fetches fit the cap can join into a file
     * over it. These are the fetches above, and their samples join into 12,000 bytes, which must
     * not reach the gallery.
     */
    @Test
    public void aDashPairWhoseJoinedFileRunsPastTheCapIsRefused() {
        Downloader.Result result = saveAndJoin(sample(6_000, 'v'), sample(6_000, 'a'));

        assertEquals("TOO_LARGE (the joined file is 12000 bytes, more than 10000)", result.toString());
        assertNothingWasCreated("a pair whose joined file ran past the cap", result);
    }

    /**
     * The cap is a limit and not a margin, on both sides of it: a joined file of exactly 10,000
     * bytes reaches the gallery, and one byte more doesn't. The case above, 2,000 bytes over,
     * couldn't tell the cap from a bound off by a byte or two.
     */
    @Test
    public void aJoinedFileExactlyAtTheCapIsPublished() {
        Downloader.Result result = saveAndJoin(sample(5_000, 'v'), sample(5_000, 'a'));

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(10_000, published.size());
    }

    @Test
    public void aJoinedFileOneByteOverTheCapIsRefused() {
        Downloader.Result result = saveAndJoin(sample(5_001, 'v'), sample(5_000, 'a'));

        assertEquals("TOO_LARGE (the joined file is 10001 bytes, more than 10000)", result.toString());
        assertNothingWasCreated("a joined file one byte over the cap", result);
    }

    /**
     * Two saves that start together both find no work folder yet, and the one whose mkdirs()
     * comes second is told false because the other just made it. That save used to end "no cache
     * folder". Each round removes the folder and lets several saves ask for it at once.
     */
    @Test
    public void savesStartingTogetherAllGetTheWorkFolder() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 300; round++) {
                File folder = DashSave.workFolder(context);
                assertNotNull(folder);
                assertTrue("round " + round + " couldn't clear the folder", folder.delete());

                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<File>> asked = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    asked.add(pool.submit(() -> {
                        go.await();
                        return DashSave.workFolder(context);
                    }));
                }
                go.countDown();
                for (java.util.concurrent.Future<File> answer : asked) {
                    assertNotNull("a save in round " + round + " got no work folder", answer.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** MediaStore's video and image tables, as much of them as a save touches. */
    public static final class Gallery extends ContentProvider {
        final Map<Long, ContentValues> rows = new HashMap<>();
        final List<Uri> inserts = new ArrayList<>();
        private long nextId = 1;

        Uri videoUri(long id) {
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = nextId++;
            rows.put(id, new ContentValues(values));
            Uri item = ContentUris.withAppendedId(uri, id);
            inserts.add(item);
            return item;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            return new MatrixCursor(projection == null ? new String[0] : projection);
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "video/mp4";
        }
    }
}
