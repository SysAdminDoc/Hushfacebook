/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
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
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * A save the person can watch and stop: from the moment it starts, a notification with how far it
 * has got and a Cancel button; gone when the save ends; and after a cancel, or after Android ended
 * the process half way, nothing left in the cache or the gallery.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SaveProgressTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long MIB = 1024L * 1024L;

    /** An ftyp box the sniff reads as an MP4 video; the made-up bytes that follow don't matter. */
    private static final byte[] MP4_HEAD = {
        0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
        0, 0, 0, 0, 'm', 'p', '4', '2', 'i', 's', 'o', 'm',
    };

    private final CountDownLatch release = new CountDownLatch(1);
    private LocalServer server;
    private MediaUrlPolicy policy;
    private Context context;
    private Gallery gallery;
    private final CountingStream published = new CountingStream();

    @Before
    public void setUp() throws IOException {
        server = new LocalServer();
        int port = server.port();
        policy = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
        context = RuntimeEnvironment.getApplication();
        gallery = Robolectric.setupContentProvider(Gallery.class, MediaStore.AUTHORITY);
        LogBufferManager.clearLogBuffer();
        // Every save here is the first of its process, as a save after a restart would be.
        SaveLeftovers.forgetSweepForTests();
    }

    @After
    public void tearDown() throws IOException {
        release.countDown();
        server.close();
        MediaDownload.policyForTests = null;
        LogBufferManager.clearLogBuffer();
    }

    /** A save on the feature's own worker, as a tap starts one, of [path] into the gallery. */
    private Thread save(String path) {
        File folder = DashSave.workFolder(context);
        return MediaDownload.start(context, true, (writer, progress) -> Downloader.save(server.origin() + path,
                Downloader.Kind.VIDEO, folder, writer, policy, Downloader.MAX_BYTES, progress));
    }

    private void finish(Thread worker) throws InterruptedException {
        worker.join(30_000);
        assertFalse("the save never finished", worker.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static NotificationManager notifications() {
        return RuntimeEnvironment.getApplication().getSystemService(NotificationManager.class);
    }

    /** The save's notification, or null when none is showing. */
    private static Notification saveNotification() {
        for (Notification shown : Shadows.shadowOf(notifications()).getAllNotifications()) {
            if (SaveControl.CHANNEL.equals(shown.getChannelId())) return shown;
        }
        return null;
    }

    private static Notification waitForNotification(Predicate<Notification> wanted, String what)
            throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < until) {
            Notification shown = saveNotification();
            if (shown != null && wanted.test(shown)) return shown;
            Thread.sleep(50);
        }
        throw new AssertionError("the notification never showed " + what + ": " + describe(saveNotification()));
    }

    private static String describe(Notification shown) {
        if (shown == null) return "none";
        return shown.extras.getCharSequence(Notification.EXTRA_TITLE) + " | "
                + shown.extras.getCharSequence(Notification.EXTRA_TEXT) + " | "
                + shown.extras.getInt(Notification.EXTRA_PROGRESS) + "/" + shown.extras.getInt(Notification.EXTRA_PROGRESS_MAX)
                + (shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ? " indeterminate" : "");
    }

    private static boolean moving(Notification shown) {
        return !shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
                && shown.extras.getInt(Notification.EXTRA_PROGRESS) > 0;
    }

    private int workFiles() {
        String[] left = DashSave.workFolder(context).list();
        return left == null ? 0 : left.length;
    }

    private static int saveId(Notification shown) {
        Intent sent = Shadows.shadowOf(shown.actions[0].actionIntent).getSavedIntent();
        return sent.getIntExtra(SaveControl.EXTRA_ID, -1);
    }

    @Test
    public void aSaveShowsItsProgressAtOnceAndCancelStopsIt() throws Exception {
        cancelFromTheNotification();
    }

    /** From Android 13 the receiver isn't exported: the button still reaches it. */
    @Test
    @Config(sdk = 34)
    public void cancelReachesTheSaveOnAndroid14Too() throws Exception {
        cancelFromTheNotification();
    }

    private void cancelFromTheNotification() throws Exception {
        server.serveGenerated("/big.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        Thread worker = save("/big.mp4");

        // Straight away, before a byte arrives: nothing queues a save.
        Notification started = saveNotification();
        assertNotNull("no notification when the save started", started);
        assertEquals("Saving a video", String.valueOf(started.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals(1, started.actions.length);
        assertEquals("Cancel", String.valueOf(started.actions[0].title));

        // Then how far it has got, against the 100 MiB the server announced.
        Notification moving = waitForNotification(SaveProgressTest::moving, "progress");
        String text = String.valueOf(moving.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(text, text.endsWith(" of 100 MB"));
        assertEquals(100, moving.extras.getInt(Notification.EXTRA_PROGRESS_MAX));

        // A Cancel without this process's token is somebody else's broadcast and changes nothing.
        int id = saveId(moving);
        context.sendBroadcast(new Intent(SaveControl.ACTION_CANCEL).setPackage(context.getPackageName())
                .putExtra(SaveControl.EXTRA_ID, id));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Thread.sleep(400);
        assertTrue("a Cancel with no token stopped the save", worker.isAlive());

        // The button.
        moving.actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        finish(worker);

        assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save: " + describe(saveNotification()), saveNotification());
        assertEquals("a cancelled save made a gallery row", 0, gallery.inserts.size());
        assertEquals("a cancelled save left work files", 0, workFiles());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("save " + id + " cancelled from its notification"));
        assertTrue(report, report.contains("save finished: CANCELLED"));
        assertFalse("a cancel was logged as an error:\n" + report, report.contains("ERROR | save finished: CANCELLED"));
    }

    /**
     * Each save's button stops that save. Given one request code for every button, Android updates
     * the one intent to the newest save's number, and the first notification's Cancel stops the
     * second save while the first goes on.
     */
    @Test
    public void cancelOnOneOfTwoSavesStopsThatOne() throws Exception {
        server.serveGenerated("/one.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        server.serveGenerated("/two.mp4", "video/mp4", MP4_HEAD, 100 * MIB, 4 * MIB, release);
        Thread first = save("/one.mp4");
        Thread second = save("/two.mp4");
        List<Integer> ids = new ArrayList<>();
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (SaveControl.TAG.equals(up.getTag())) ids.add(up.getId());
        }
        java.util.Collections.sort(ids);
        assertEquals("two saves, two notifications: " + ids, 2, ids.size());
        Notification firstShown = Shadows.shadowOf(notifications()).getNotification(SaveControl.TAG, ids.get(0));
        assertEquals("the first save's button names another save", (int) ids.get(0), saveId(firstShown));
        assertEquals((int) ids.get(1),
                saveId(Shadows.shadowOf(notifications()).getNotification(SaveControl.TAG, ids.get(1))));

        firstShown.actions[0].actionIntent.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        first.join(15_000);
        assertFalse("Cancel on the first save's notification didn't stop it", first.isAlive());
        assertTrue("Cancel on the first save's notification stopped the second", second.isAlive());
        assertFalse(shown(SaveControl.TAG, ids.get(0)));
        assertTrue("the second save's notification went with the first", shown(SaveControl.TAG, ids.get(1)));

        assertTrue(SaveControl.cancel(ids.get(1)));
        finish(second);
    }

    /**
     * From Android 13 the Cancel receiver is registered as not exported. Facebook targets a newer
     * Android than 14, where a receiver registered with no flag throws, and every Cancel button
     * then did nothing.
     *
     * <p>Android 13 itself is checked by setting SDK_INT here rather than with an sdk 33 config:
     * that sandbox is ColdStartHooksTest's alone, and a class that shares it sets the context
     * before that test runs.
     */
    @Test
    @Config(sdk = 34)
    public void fromAndroid13TheCancelReceiverIsNotExported() {
        assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
        int sdk = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 33);
            assertEquals(java.util.Collections.singletonList(Context.RECEIVER_NOT_EXPORTED), receiverRegistrations());
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    /** Below Android 13 there's no flag to give, and the token keeps other apps' broadcasts out. */
    @Test
    public void belowAndroid13TheCancelReceiverIsRegisteredWithNoFlag() {
        assertEquals(java.util.Collections.singletonList(-1), receiverRegistrations());
    }

    /** The flags each receiver a save registers is given, -1 for a registration with none. */
    private List<Integer> receiverRegistrations() {
        List<Integer> flags = new ArrayList<>();
        Context recording = new android.content.ContextWrapper(context) {
            @Override
            public Intent registerReceiver(android.content.BroadcastReceiver receiver, android.content.IntentFilter filter) {
                flags.add(-1);
                return super.registerReceiver(receiver, filter);
            }

            @Override
            public Intent registerReceiver(android.content.BroadcastReceiver receiver, android.content.IntentFilter filter,
                    int given) {
                flags.add(given);
                return super.registerReceiver(receiver, filter, given);
            }
        };
        SaveControl.begin(recording, true).end();
        return flags;
    }

    @Test
    public void aFinishedSaveTakesItsNotificationAwayAndLeavesNoRowPending() throws Exception {
        long size = 8 * MIB;
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, size, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);

        finish(save("/whole.mp4"));

        assertEquals("Saved to " + L10n.isolate("Movies/Facebook"), ShadowToast.getTextOfLatestToast());
        assertNull("the notification outlived the save", saveNotification());
        assertEquals(size, published.count.get());
        assertEquals(Integer.valueOf(0), gallery.rows.get(1L).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertTrue("a published row is still on the pending list", pendingList().isEmpty());
        assertEquals(0, workFiles());
    }

    @Test
    public void withNotificationsOffASaveRunsWithItsToasts() throws Exception {
        Shadows.shadowOf(notifications()).setNotificationsEnabled(false);
        server.serveGenerated("/whole.mp4", "video/mp4", MP4_HEAD, 256 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);

        Thread worker = save("/whole.mp4");
        assertNull("a notification was posted with notifications off", saveNotification());
        finish(worker);

        assertEquals("Saved to " + L10n.isolate("Movies/Facebook"), ShadowToast.getTextOfLatestToast());
        assertEquals(0, Shadows.shadowOf(notifications()).getAllNotifications().size());
    }

    /**
     * What a process Android ended mid-save leaves: work files, a row still pending, and a row it
     * had published but not yet crossed off. The next process's first save removes the first two
     * and keeps the finished file; a later save in the same process doesn't sweep again.
     */
    @Test
    public void whatAStoppedSaveLeftIsRemovedByTheNextOne() throws Exception {
        File folder = DashSave.workFolder(context);
        assertTrue(new File(folder, "video1.part").createNewFile());
        assertTrue(new File(folder, "joined2.mp4").createNewFile());
        Uri pending = row(1);
        Uri finished = row(0);
        SaveLeftovers.pending(context, pending);
        SaveLeftovers.pending(context, finished);
        // The stopped save's notification, which Android leaves up when it ends the process, and
        // one of Facebook's own under another tag, which isn't this sweep's to touch.
        notifications().notify(SaveControl.TAG, 42, new Notification.Builder(context, SaveControl.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Saving a video")
                .setOngoing(true).build());
        notifications().notify("facebook", 42, new Notification.Builder(context, "facebook")
                .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("A message").build());

        server.serveGenerated("/next.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(3), published);
        finish(save("/next.mp4"));

        assertFalse("the pending row a stopped save left is still there", gallery.rows.containsKey(ContentUris.parseId(pending)));
        assertTrue("a finished file was taken for a leftover", gallery.rows.containsKey(ContentUris.parseId(finished)));
        assertEquals(0, workFiles());
        assertTrue(pendingList().isEmpty());
        assertFalse("the stopped save's notification is still up", shown(SaveControl.TAG, 42));
        assertTrue("Facebook's own notification was taken down", shown("facebook", 42));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("removed what a stopped save left: 2 work file(s), 1 pending gallery "
                + "row(s), 1 notification(s)"));

        // Once per process: a file there now belongs to this process's own saves.
        assertTrue(new File(folder, "video9.part").createNewFile());
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(4), published);
        finish(save("/next.mp4"));
        assertTrue("a second save in the same process swept again", new File(folder, "video9.part").exists());
    }

    /** Whether a notification with [tag] and [id] is up. */
    private static boolean shown(String tag, int id) {
        for (android.service.notification.StatusBarNotification up : notifications().getActiveNotifications()) {
            if (tag.equals(up.getTag()) && up.getId() == id) return true;
        }
        return false;
    }
    /** A row as a stopped save's writer would have left it. */
    private Uri row(int pendingFlag) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "FB_VID_20260925_010203.mp4");
        values.put(MediaStore.MediaColumns.IS_PENDING, pendingFlag);
        return context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }

    private java.util.Set<String> pendingList() {
        return context.getSharedPreferences("hushfacebook_saves", Context.MODE_PRIVATE)
                .getStringSet("pending_rows", new java.util.HashSet<>());
    }

    /** A cancel while the finished file is copied into the gallery removes the row it opened. */
    @Test
    public void aCancelDuringTheCopyIntoTheGalleryLeavesNoRow() throws IOException {
        File file = File.createTempFile("whole", ".mp4", DashSave.workFolder(context));
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(new byte[512 * 1024]);
        }
        AtomicInteger asked = new AtomicInteger();
        Downloader.Progress cancelsOnceCopying = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
            }

            @Override public void reading(Runnable close) {
            }

            // The first ask is before the gallery opens; the second is after the first block.
            @Override public boolean cancelled() {
                return asked.incrementAndGet() > 1;
            }
        };
        RecordingSink sink = new RecordingSink();

        Downloader.Result result = Downloader.publish(file, "video/mp4", sink, cancelsOnceCopying);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertTrue("the gallery was never opened, so nothing was tested", sink.opened);
        assertTrue("the opened row was left behind", sink.abandoned);
        assertFalse(sink.committed);
        assertTrue(file.delete());
    }

    /**
     * A DASH save that's cancelled stays cancelled. It used to be a failed DASH save like any other,
     * and the single file behind it started the save over.
     */
    @Test
    public void aCancelledDashSaveDoesNotFallBackToTheSingleFile() throws Exception {
        MediaDownload.policyForTests = policy;
        server.serveGenerated("/track.mp4", "video/mp4", MP4_HEAD, 50 * MIB, 2 * MIB, release);
        server.serveGenerated("/single.mp4", "video/mp4", MP4_HEAD, 64 * 1024, Long.MAX_VALUE, null);
        DashManifest.Track track = new DashManifest.Track("video/mp4", "avc1.64001f", 1920, 1080, 4_000_000,
                server.origin() + "/track.mp4");

        Thread worker = MediaDownload.start(context, true,
                MediaDownload.dashJob(context, track, null, server.origin() + "/single.mp4"));
        Notification moving = waitForNotification(SaveProgressTest::moving, "progress");
        assertTrue(SaveControl.cancel(saveId(moving)));
        finish(worker);

        assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
        assertEquals("the single file was fetched after the cancel", 0, server.hits("/single.mp4"));
        // The fallback would reuse the cancelled progress and stop before a request, so the log
        // is what shows whether it was tried at all.
        String report = LogBufferManager.buildExportText();
        assertFalse("a cancelled DASH save went on to the single file:\n" + report,
                report.contains("the DASH save ended with"));
        assertEquals(0, gallery.inserts.size());
        assertEquals(0, workFiles());
    }

    /**
     * The flag alone stops a fetch between two reads, with no connection closed under it: the
     * close is what ends a read that's waiting, and each is tested apart from the other.
     */
    @Test
    public void aCancelSeenBetweenReadsStopsTheFetchAndDeletesItsFile() throws IOException {
        server.serveGenerated("/two.mp4", "video/mp4", MP4_HEAD, 2 * MIB, Long.MAX_VALUE, null);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closers = new AtomicInteger();
        Downloader.Progress cancelsAfterThreeReads = new Downloader.Progress() {
            @Override public void transferred(long done, long total) {
                reads.incrementAndGet();
            }

            @Override public void reading(Runnable close) {
                if (close != null) closers.incrementAndGet();
            }

            @Override public boolean cancelled() {
                return reads.get() >= 3;
            }
        };
        File into = File.createTempFile("video", ".part", DashSave.workFolder(context));

        Downloader.Result result = Downloader.fetch(server.origin() + "/two.mp4", Downloader.Kind.VIDEO, into, policy,
                Downloader.MAX_BYTES, cancelsAfterThreeReads);

        assertEquals(result.toString(), Downloader.Status.CANCELLED, result.status);
        assertFalse("a cancelled fetch left its file", into.exists());
        assertTrue("the fetch read on long after the cancel: " + reads.get(), reads.get() < 10);
        // What a cancel needs to end a read waiting on the network, on Android.
        assertEquals("the fetch never handed over a way to close its connection", 1, closers.get());
    }

    /**
     * A read waiting on a server that went quiet sees no flag. On Android the cancel closes the
     * connection under it and it ends at once; the JDK these tests run on waits out the read
     * timeout instead, so it's short here. Either way the notification goes the moment Cancel is
     * pressed, and the save ends cancelled, not as a network failure.
     */
    @Test
    public void aCancelDuringAStalledReadTakesTheNotificationAwayAtOnce() throws Exception {
        Downloader.readTimeoutMs = 2_000;
        try {
            server.serveHeld("/quiet.mp4", "video/mp4", MP4_HEAD, 10 * MIB, MIB, release);
            Thread worker = save("/quiet.mp4");
            Notification started = saveNotification();
            assertNotNull(started);
            Thread.sleep(700);
            assertTrue("the save ended before the server went quiet", worker.isAlive());

            assertTrue(SaveControl.cancel(saveId(started)));
            assertNull("the notification stayed after Cancel", saveNotification());
            finish(worker);

            assertEquals("Save cancelled", ShadowToast.getTextOfLatestToast());
            assertEquals(0, workFiles());
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("save finished: CANCELLED"));
        } finally {
            Downloader.readTimeoutMs = 20_000;
        }
    }

    /** Cancel closes the connection being read, and one opened after the cancel as soon as it's handed over. */
    @Test
    public void cancelClosesTheConnectionTheSaveIsReading() throws Exception {
        SaveControl.Save save = SaveControl.begin(context, true);
        Probe before = new Probe();
        save.reading(before);

        assertTrue(SaveControl.cancel(save.id));
        assertTrue("cancel left the connection open", before.closed.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Probe after = new Probe();
        save.reading(after);
        assertTrue("a connection handed over after the cancel stayed open",
                after.closed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        save.end();
    }

    /** A connection's closer that only records being run. */
    private static final class Probe implements Runnable {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override public void run() {
            closed.countDown();
        }
    }

    /** The writer lists its row as soon as it's inserted, and crosses it off once it's settled. */
    @Test
    public void theWriterListsItsRowWhileItIsPending() throws IOException {
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(1), published);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(gallery.videoUri(2), published);

        MediaStoreWriter kept = new MediaStoreWriter(context, true);
        kept.open("video/mp4");
        assertEquals(java.util.Collections.singleton(gallery.videoUri(1).toString()), pendingList());
        kept.commit();
        assertTrue(pendingList().isEmpty());

        MediaStoreWriter dropped = new MediaStoreWriter(context, true);
        dropped.open("video/mp4");
        assertEquals(java.util.Collections.singleton(gallery.videoUri(2).toString()), pendingList());
        dropped.abandon();
        assertTrue(pendingList().isEmpty());
        assertFalse(gallery.rows.containsKey(2L));
    }

    @Test
    public void progressTextSaysHowMuchOfHowMuch() {
        assertNull(SaveControl.progressText(0, 100 * MIB));
        assertEquals("4.2 MB of 100 MB", SaveControl.progressText((long) (4.2 * MIB), 100 * MIB));
        assertEquals("12 MB so far", SaveControl.progressText(12 * MIB, -1));
    }

    /** Counts what the gallery was given, so a large save needs no array of its size. */
    static final class CountingStream extends OutputStream {
        final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();

        @Override public void write(int b) {
            count.incrementAndGet();
        }

        @Override public void write(byte[] bytes, int offset, int length) {
            count.addAndGet(length);
        }
    }

    private static final class RecordingSink implements Downloader.Sink {
        boolean opened;
        boolean committed;
        boolean abandoned;

        @Override public OutputStream open(String mime) {
            opened = true;
            return new CountingStream();
        }

        @Override public void commit() {
            committed = true;
        }

        @Override public void abandon() {
            abandoned = true;
        }
    }

    /** MediaStore's video table, as a save and the leftover sweep use it. */
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

        /** Honours the one selection the sweep uses, the way MediaStore does for a row named by id. */
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            long id = ContentUris.parseId(uri);
            ContentValues row = rows.get(id);
            if (row == null) return 0;
            if ((MediaStore.MediaColumns.IS_PENDING + "=1").equals(selection)
                    && !Integer.valueOf(1).equals(row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))) {
                return 0;
            }
            rows.remove(id);
            return 1;
        }

        @Override public String getType(Uri uri) {
            return "video/mp4";
        }
    }
}
