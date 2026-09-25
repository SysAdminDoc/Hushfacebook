/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.facebook.video.engine.api.VideoDataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;

import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * What a failed save leaves in the diagnostic report. These saves used to log to logcat alone,
 * so the report a bug needs said nothing about the save that failed.
 *
 * <p>Each save runs on the worker thread the feature uses, against a local server the policy lets
 * through, and the report is read afterwards the way a person exports it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DownloadDiagnosticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private LocalServer server;
    private String origin;
    private MediaUrlPolicy policy;
    private Context context;

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
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void tearDown() throws IOException {
        server.close();
        LogBufferManager.clearLogBuffer();
    }

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2' };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) (i * 13);
        return body;
    }

    /** One save on the feature's own worker, waited for. */
    private void run(MediaDownload.Job job) throws InterruptedException {
        Thread worker = MediaDownload.start(context, true, job);
        worker.join(20_000);
        assertFalse("the save never finished", worker.isAlive());
    }

    /** A gallery that refuses every new entry, the way a full or locked MediaStore does. */
    private static final class RefusingGallery implements Downloader.Sink {
        @Override
        public OutputStream open(String mime) throws IOException {
            throw new IOException("the gallery refused a new entry");
        }

        @Override
        public void commit() {
        }

        @Override
        public void abandon() {
        }
    }

    @Test
    public void progressiveDashAndGalleryFailuresReachTheReport() throws Exception {
        // Where Android put Facebook's native code: the ABI it was installed for.
        context.getApplicationInfo().nativeLibraryDir = "/data/app/~~x/com.facebook.katana-y/lib/arm64";
        File folder = DashSave.workFolder(context);

        // A progressive save whose address the server no longer has.
        run((writer, progress) -> Downloader.save(origin + "/gone.mp4", Downloader.Kind.VIDEO, folder, writer, policy,
                Downloader.MAX_BYTES));

        // A DASH save whose track downloads but can't be joined into a file.
        server.serve("/track.mp4", 200, "video/mp4", mp4(4096), 4096);
        DashManifest.Track track = new DashManifest.Track("video/mp4", "avc1.64001f", 1280, 720, 900_000,
                origin + "/track.mp4");
        run((writer, progress) -> DashSave.save(context, track, null, writer, policy));

        // A good file the gallery won't take.
        server.serve("/whole.mp4", 200, "video/mp4", mp4(4096), 4096);
        run((writer, progress) -> Downloader.save(origin + "/whole.mp4", Downloader.Kind.VIDEO, folder, new RefusingGallery(),
                policy, Downloader.MAX_BYTES));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("MediaDownload | ERROR | save finished: HTTP_ERROR (the server answered 404)"));
        assertTrue(report, report.contains("DashSave | ERROR | the DASH save failed"));
        assertTrue(report, report.contains("save finished: WRITE_ERROR (the tracks could not be joined)"));
        assertTrue(report, report.contains("save finished: WRITE_ERROR (the gallery refused the file: IOException)"));
        assertTrue(report, report.contains("downloads |"));

        // What a report carries about the phone, the host and this bundle, and the real values:
        // each part has a fallback (-1, "unknown") a shape check would pass.
        long versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        assertTrue(report, report.contains("\napp: " + context.getPackageName() + " ")
                && report.contains(" (" + versionCode + ")\n"));
        assertTrue(report, report.contains("\nabi: app arm64, process "
                + (android.os.Process.is64Bit() ? "64-bit" : "32-bit") + ", device "
                + android.text.TextUtils.join(",", android.os.Build.SUPPORTED_ABIS) + "\n"));
        assertTrue(report, report.contains("\nmorphe: "));

        // And never where the file was fetched from.
        assertFalse(report, report.contains("127.0.0.1"));
        assertFalse(report, report.contains("http://"));
        assertFalse(report, report.contains("/gone.mp4"));
    }

    /** A photo on Meta's CDN: the middle number of the file name is the photo's own id. */
    private static final String PHOTO = "https://scontent-iad3-1.xx.fbcdn.net/v/t39.30808-6/"
            + "475148478_1134540631592283_1316146539584337463_n.jpg?_nc_cat=1&oh=00_AYA&oe=66F0A1B2";

    /** A video file named the same way. */
    private static final String CLIP = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/"
            + "449237416_1234567890123456_1234567890123456789_n.mp4?efg=x&oh=1&oe=2";

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

    /**
     * Waits for every save the feature started on its own worker. The count goes up before a hook
     * returns and down only after the worker has logged how its save ended.
     */
    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    /**
     * Save lines used to print the file name, and on Meta's CDN that carries the object's own id,
     * so a report pasted into a public issue named what was saved. Both saves here start for real
     * and are refused before any socket opens: this test's policy answers every Meta name with a
     * private address.
     */
    @Test
    public void aSaveLeavesNoFileNameAndNoIdInTheReport() throws Exception {
        String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                + "<Representation codecs=\"avc1.64001f\" width=\"1280\" height=\"720\" bandwidth=\"900000\">"
                + "<BaseURL>" + CLIP.replace("&", "&amp;") + "</BaseURL></Representation>"
                + "</AdaptationSet></Period></MPD>";
        MediaDownload.policyForTests = policy;
        try {
            assertTrue(MediaDownload.saveVideo(context, new ReelSource(PHOTO, null, null), "hd", "sd", "manifest"));
            assertTrue(MediaDownload.saveVideo(context, new ReelSource(null, CLIP, manifest), "hd", "sd", "manifest"));
            waitForSaves();
        } finally {
            MediaDownload.policyForTests = null;
        }

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("saving image jpg ("));
        assertTrue(report, report.contains("from its DASH manifest"));
        assertTrue(report, report.contains("the DASH save ended with NETWORK_ERROR"));
        assertFalse(report, report.contains("_n.jpg") || report.contains("_n.mp4"));
        assertFalse(report, java.util.regex.Pattern.compile("\\d{15}").matcher(report).find());
    }

    /** One field of the player source type: the reel on the screen. */
    static final class OneSource {
        final VideoDataSource source = new VideoDataSource();
    }

    /** No field of the type: a build that moved it. */
    static final class NoSource {
        final String name = "not a source";
    }

    /** Two fields of the type, so the rule that picks the reel on the screen no longer can. */
    static final class TwoSources {
        final VideoDataSource current = new VideoDataSource();
        final VideoDataSource upcoming = new VideoDataSource();
    }

    @Test
    public void theReelSourceLookupSaysWhetherItFoundOneNoneOrTwo() {
        OneSource one = new OneSource();
        assertSame(one.source, ReelDownload.sourceOf(one));
        assertNull("a build with no source field handed something back", ReelDownload.sourceOf(new NoSource()));
        assertNull("a player with two sources had one picked for it", ReelDownload.sourceOf(new TwoSources()));

        String hooks = String.join("\n", HookStatus.report());
        assertTrue(hooks, hooks.contains("Download any reel: invoked 0, 1 found, 1 ambiguous, 1 missing. First missing: "));
        assertTrue(hooks, hooks.contains("field " + NoSource.class.getName() + "#com.facebook.video.engine.api.VideoDataSource"));
        assertTrue(hooks, HookStatus.missing("Download any reel").contains("a single field " + TwoSources.class.getName()
                + "#com.facebook.video.engine.api.VideoDataSource (found 2)"));
    }

    /** Counts and findings recorded before a clear come back with Undo, beside the ones since. */
    @Test
    public void invocationsSurviveAClearThatIsUndone() {
        HookStatus.invoked("Download any reel");
        HookStatus.invoked("Download any reel");
        LogBufferManager.clearLogBuffer();
        assertTrue(HookStatus.report().isEmpty());

        HookStatus.invoked("Download any reel");
        LogBufferManager.undoClear();
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains("Download any reel: invoked 3, 0 found, 0 missing"));
    }
}
