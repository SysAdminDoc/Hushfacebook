/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The fetch against a local server. The policy lets exactly that server through and hands every
 * other address, redirects included, to the real rules with a fixed lookup, so no test ever
 * leaves the machine. Each failure has to leave no file and never reach the sink.
 */
public class DownloaderTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private LocalServer server;
    private String origin;

    /** A Meta name that the fixed lookup answers with a private address. */
    private static final String REBOUND = "https://scontent-rebound.xx.fbcdn.net/v.mp4";

    private MediaUrlPolicy policy;

    @Before
    public void startServer() throws IOException {
        server = new LocalServer();
        int port = server.port();
        origin = server.origin();
        policy = new MediaUrlPolicy(host -> {
            if (host.equals("scontent-rebound.xx.fbcdn.net")) return new InetAddress[] { InetAddress.getByName("10.0.0.7") };
            return new InetAddress[] { InetAddress.getByName("157.240.22.19") };
        }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    // ------------------------------------------------------------------ fixtures

    private static byte[] mp4(int size) {
        byte[] body = new byte[size];
        byte[] head = { 0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm' };
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < size; i++) body[i] = (byte) i;
        return body;
    }

    private static byte[] jpeg(int size) {
        byte[] body = new byte[size];
        body[0] = (byte) 0xFF;
        body[1] = (byte) 0xD8;
        body[2] = (byte) 0xFF;
        for (int i = 3; i < size; i++) body[i] = (byte) (i * 7);
        return body;
    }

    private static final byte[] HTML =
            "<!DOCTYPE html><html><body>Log in to continue</body></html>".getBytes(StandardCharsets.UTF_8);

    private void serve(String path, int code, String type, byte[] body, long announced) {
        server.serve(path, code, type, body, announced);
    }

    private void serve(String path, String type, byte[] body) {
        server.serve(path, type, body);
    }

    private void redirect(String path, String location) {
        server.redirect(path, location);
    }

    private int hitsOf(String path) {
        return server.hits(path);
    }

    private Downloader.Result fetch(String path, Downloader.Kind kind, File into, long max) {
        return Downloader.fetch(origin + path, kind, into, policy, max);
    }

    /** A fetch that must fail with [status] and leave no file behind. */
    private void assertRefused(Downloader.Status status, String path, Downloader.Kind kind, long max) throws IOException {
        File into = temp.newFile();
        Downloader.Result result = fetch(path, kind, into, max);
        assertEquals(path + ": " + result, status, result.status);
        assertFalse(path + " left its file behind", into.exists());
    }

    // ------------------------------------------------------------------ what still saves

    @Test
    public void aProgressiveVideoArrivesWhole() throws IOException {
        byte[] body = mp4(200_000);
        serve("/v.mp4", "video/mp4", body);
        File into = temp.newFile();

        Downloader.Result result = fetch("/v.mp4", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("video/mp4", result.mime);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
    }

    @Test
    public void aPictureArrivesWhole() throws IOException {
        byte[] body = jpeg(40_000);
        serve("/p.jpg", "image/jpeg; charset=binary", body);
        File into = temp.newFile();

        Downloader.Result result = fetch("/p.jpg", Downloader.Kind.IMAGE, into, Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("image/jpeg", result.mime);
        assertArrayEquals(body, Files.readAllBytes(into.toPath()));
    }

    @Test
    public void aDashSoundTrackArrivesAsAudio() throws IOException {
        serve("/a.mp4", "audio/mp4", mp4(5000));
        File into = temp.newFile();
        Downloader.Result result = fetch("/a.mp4", Downloader.Kind.AUDIO, into, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
    }

    @Test
    public void aGenericTypeIsNamedByWhatTheBytesAre() throws IOException {
        serve("/bin", "application/octet-stream", mp4(3000));
        File into = temp.newFile();
        Downloader.Result result = fetch("/bin", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals("video/mp4", result.mime);
    }

    @Test
    public void aRedirectWithinTheAllowedServerIsFollowed() throws IOException {
        serve("/final.mp4", "video/mp4", mp4(4000));
        redirect("/hop", origin + "/final.mp4");
        File into = temp.newFile();
        Downloader.Result result = fetch("/hop", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.OK, result.status);
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    public void aPageIsRefused() throws IOException {
        serve("/login", "text/html", HTML);
        assertRefused(Downloader.Status.REFUSED, "/login", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    @Test
    public void aPageSentAsVideoIsRefused() throws IOException {
        serve("/fake.mp4", "video/mp4", HTML);
        assertRefused(Downloader.Status.REFUSED, "/fake.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /**
     * The sniff reads a box name in the fifth to eighth bytes, and text can spell one there. The
     * size in front of it is what gives text away: four printable characters read as more than
     * 512 MB.
     */
    @Test
    public void textThatSpellsABoxNameIsNotAContainer() throws IOException {
        String[] texts = {
                "The free trial has ended. Log in to watch.", "<!--free-->", "Get free music at example",
                "Now skip ahead to the good part", "Too wide a request for this page",
        };
        for (String text : texts) {
            byte[] body = text.getBytes(StandardCharsets.US_ASCII);
            assertEquals(text, null, Downloader.sniff(Downloader.Kind.VIDEO, body, body.length));
            assertEquals(text, null, Downloader.sniff(Downloader.Kind.AUDIO, body, body.length));
        }
        byte[] trial = texts[0].getBytes(StandardCharsets.US_ASCII);
        serve("/trial.mp4", "video/mp4", trial);
        assertRefused(Downloader.Status.REFUSED, "/trial.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /** 0 runs to the end of the file and 1 means a 64-bit size follows. Nothing else under 8 is a box. */
    @Test
    public void aBoxSizeHasToBeOneAContainerCanStartWith() {
        for (long size : new long[] { 0, 1, 8, 0x20, Downloader.MAX_BYTES }) {
            assertEquals("size " + size, "video/mp4", Downloader.sniff(Downloader.Kind.VIDEO, box(size), 12));
        }
        for (long size : new long[] { 2, 7, Downloader.MAX_BYTES + 1, 0xFFFFFFFFL }) {
            assertEquals("size " + size, null, Downloader.sniff(Downloader.Kind.VIDEO, box(size), 12));
        }
    }

    private static byte[] box(long size) {
        return new byte[] {
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size,
                'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
        };
    }

    @Test
    public void aPictureAskedForAsVideoIsRefused() throws IOException {
        serve("/typed.jpg", "image/jpeg", jpeg(5000));
        assertRefused(Downloader.Status.REFUSED, "/typed.jpg", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        serve("/sneaky.mp4", "video/mp4", jpeg(5000));
        assertRefused(Downloader.Status.REFUSED, "/sneaky.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /**
     * Announces a million bytes and sends a valid start only. Refused on the announcement: a
     * fetch that read first would find a short body instead and answer something else.
     */
    @Test
    public void aBodyAnnouncedTooLargeIsRefusedBeforeItIsRead() throws IOException {
        serve("/huge.mp4", 200, "video/mp4", mp4(64), 1_000_000);
        assertRefused(Downloader.Status.TOO_LARGE, "/huge.mp4", Downloader.Kind.VIDEO, 1024);
    }

    /** A DASH sound track is an MP4 file too. Saved as a video, it plays as a black screen. */
    @Test
    public void aSoundTrackAskedForAsVideoIsRefused() throws IOException {
        serve("/sound.mp4", "audio/mp4", mp4(5000));
        assertRefused(Downloader.Status.REFUSED, "/sound.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    @Test
    public void aBodyThatRunsPastTheCapWithoutALengthIsRefused() throws IOException {
        serve("/stream.mp4", 200, "video/mp4", mp4(8192), -1);
        assertRefused(Downloader.Status.TOO_LARGE, "/stream.mp4", Downloader.Kind.VIDEO, 1024);
    }

    @Test
    public void aTruncatedBodyIsNotKept() throws IOException {
        // Announces 8000 bytes and sends 3000.
        serve("/short.mp4", 200, "video/mp4", mp4(3000), 8000);
        File into = temp.newFile();
        Downloader.Result result = fetch("/short.mp4", Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
        assertNotEquals(result.toString(), Downloader.Status.OK, result.status);
        assertFalse("the short file was kept", into.exists());
    }

    @Test
    public void anExpiredSignatureSaysSo() throws IOException {
        serve("/old.mp4", 403, "text/html", HTML, HTML.length);
        assertRefused(Downloader.Status.EXPIRED, "/old.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        serve("/broken.mp4", 500, "text/html", HTML, HTML.length);
        assertRefused(Downloader.Status.HTTP_ERROR, "/broken.mp4", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
    }

    /** A loop is the server's fault, not a file that isn't Facebook's, so it reads as a failed download. */
    @Test
    public void aRedirectLoopIsRefused() throws IOException {
        redirect("/a", origin + "/b");
        redirect("/b", origin + "/a");
        assertRefused(Downloader.Status.HTTP_ERROR, "/a", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        assertEquals("the loop was fetched again", 1, hitsOf("/a"));
    }

    @Test
    public void tooManyRedirectsAreRefused() throws IOException {
        for (int i = 0; i < 7; i++) redirect("/r" + i, origin + "/r" + (i + 1));
        assertRefused(Downloader.Status.HTTP_ERROR, "/r0", Downloader.Kind.VIDEO, Downloader.MAX_BYTES);
        assertEquals("a hop past the limit was fetched", 0, hitsOf("/r6"));
    }

    /** Offline, or a lookup that timed out: a network failure, told as one, and nothing connects. */
    @Test
    public void aFailedLookupIsANetworkErrorNotARefusal() throws IOException {
        MediaUrlPolicy offline = new MediaUrlPolicy(host -> { throw new java.net.UnknownHostException(host); });
        File into = temp.newFile();
        Downloader.Result result = Downloader.fetch("https://scontent.xx.fbcdn.net/v.mp4", Downloader.Kind.VIDEO,
                into, offline, Downloader.MAX_BYTES);
        assertEquals(result.toString(), Downloader.Status.NETWORK_ERROR, result.status);
        assertEquals("the address: its host does not resolve", result.reason);
        assertFalse(into.exists());
    }

    /**
     * Each redirect is held to the real rules before anything connects to it. None of these
     * targets exists, so a connection attempt would fail with a network error, not a refusal.
     */
    @Test
    public void everyRedirectIsCheckedBeforeItIsFollowed() throws IOException {
        String[] unsafe = {
                "http://scontent.xx.fbcdn.net/v.mp4",
                "https://example.com/v.mp4",
                "https://scontent.xx.fbcdn.net@example.com/v.mp4",
                "https://scontent.xx.fbcdn.net:8443/v.mp4",
                "https://127.0.0.1/v.mp4",
                "https://169.254.169.254/latest/meta-data/",
                REBOUND,
                "file:///etc/passwd",
        };
        for (int i = 0; i < unsafe.length; i++) {
            redirect("/to" + i, unsafe[i]);
            File into = temp.newFile();
            Downloader.Result result = fetch("/to" + i, Downloader.Kind.VIDEO, into, Downloader.MAX_BYTES);
            // A Meta name answering with a private address is the network's doing; the rest aren't Meta's.
            Downloader.Status expected = unsafe[i].equals(REBOUND) ? Downloader.Status.NETWORK_ERROR : Downloader.Status.REFUSED;
            assertEquals(unsafe[i] + ": " + result, expected, result.status);
            assertTrue(unsafe[i] + ": " + result, result.reason.startsWith("redirect 1: "));
            assertFalse(into.exists());
        }
    }

    @Test
    public void theRealPolicyRefusesPlainHttpWithoutConnecting() throws IOException {
        File into = temp.newFile();
        Downloader.Result result = Downloader.fetch(origin + "/v.mp4", Downloader.Kind.VIDEO, into);
        assertEquals(result.toString(), Downloader.Status.REFUSED, result.status);
        assertEquals(0, hitsOf("/v.mp4"));
        assertFalse(into.exists());
    }

    // ------------------------------------------------------------------ the sink

    @Test
    public void aRefusedFetchNeverOpensTheSinkAndLeavesTheFolderEmpty() throws IOException {
        serve("/login", "text/html", HTML);
        File folder = temp.newFolder();
        RecordingSink sink = new RecordingSink();

        Downloader.Result result = Downloader.save(origin + "/login", Downloader.Kind.VIDEO, folder, sink, policy, Downloader.MAX_BYTES);

        assertEquals(Downloader.Status.REFUSED, result.status);
        assertEquals("the sink was opened for a refused fetch", 0, sink.opened);
        assertEquals(0, folder.list().length);
    }

    @Test
    public void aGoodFetchIsPublishedWholeAndTheFolderIsLeftEmpty() throws IOException {
        byte[] body = mp4(50_000);
        serve("/v.mp4", "video/mp4", body);
        File folder = temp.newFolder();
        RecordingSink sink = new RecordingSink();

        Downloader.Result result = Downloader.save(origin + "/v.mp4", Downloader.Kind.VIDEO, folder, sink, policy, Downloader.MAX_BYTES);

        assertEquals(result.toString(), Downloader.Status.OK, result.status);
        assertEquals(1, sink.opened);
        assertEquals("video/mp4", sink.mime);
        assertTrue(sink.committed);
        assertArrayEquals(body, sink.bytes.toByteArray());
        assertEquals(0, folder.list().length);
    }

    @Test
    public void aSinkThatFailsIsAbandoned() throws IOException {
        serve("/v.mp4", "video/mp4", mp4(5000));
        File folder = temp.newFolder();
        RecordingSink sink = new RecordingSink();
        sink.failCommit = true;

        Downloader.Result result = Downloader.save(origin + "/v.mp4", Downloader.Kind.VIDEO, folder, sink, policy, Downloader.MAX_BYTES);

        assertEquals(Downloader.Status.WRITE_ERROR, result.status);
        assertTrue("a failed publish left its entry", sink.abandoned);
        assertEquals(0, folder.list().length);
    }

    /**
     * MediaStoreWriter inserts its pending row before it asks for a stream, so an open() that
     * throws has already left one. The publish has to abandon it all the same.
     */
    @Test
    public void anEntryWhoseOpenFailedIsAbandoned() throws IOException {
        serve("/v.mp4", "video/mp4", mp4(5000));
        File folder = temp.newFolder();
        RecordingSink sink = new RecordingSink();
        sink.failOpen = true;

        Downloader.Result result = Downloader.save(origin + "/v.mp4", Downloader.Kind.VIDEO, folder, sink, policy, Downloader.MAX_BYTES);

        assertEquals(Downloader.Status.WRITE_ERROR, result.status);
        assertTrue("an open that failed after its insert left the entry", sink.abandoned);
        assertEquals(0, folder.list().length);
    }

    private static final class RecordingSink implements Downloader.Sink {
        int opened;
        String mime;
        boolean committed;
        boolean abandoned;
        boolean failCommit;
        boolean failOpen;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public OutputStream open(String mime) throws IOException {
            opened++;
            this.mime = mime;
            if (failOpen) throw new IOException("the gallery gave no way to write");
            return bytes;
        }

        @Override
        public void commit() throws IOException {
            if (failCommit) throw new IOException("the gallery went away");
            committed = true;
        }

        @Override
        public void abandon() {
            abandoned = true;
        }
    }
}
