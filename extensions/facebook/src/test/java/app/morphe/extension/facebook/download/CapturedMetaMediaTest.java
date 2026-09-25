/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * What Meta's CDN really sends, captured on 2026-09-25 while logged out: the progressive file and
 * the two DASH tracks of a public video on Facebook's own page, and one of its static images. Each
 * shape is the Content-Type and the first 64 bytes as served, and no media body is kept. Every
 * other download test serves bytes built by hand, so these are what show the checks a save runs
 * still let Meta's own files through.
 */
public class CapturedMetaMediaTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static final class Shape {
        final String name;
        final String type;
        final Downloader.Kind kind;
        final String head;
        final String saves;

        Shape(String name, String type, Downloader.Kind kind, String head, String saves) {
            this.name = name;
            this.type = type;
            this.kind = kind;
            this.head = head;
            this.saves = saves;
        }
    }

    private static final Shape[] SHAPES = {
            // ftyp isom, then the moov box: a progressive file that starts playing before it ends.
            new Shape("progressive video", "video/mp4", Downloader.Kind.VIDEO,
                    "000000206674797069736f6d0000020069736f6d69736f32617663316d7034310000729c6d6f6f76"
                            + "0000006c6d766864000000000000000000000000000003e8", "video/mp4"),
            // ftyp mp41 listing dash and av01: the best picture track of the same video.
            new Shape("DASH picture track (AV1)", "video/mp4", Downloader.Kind.VIDEO,
                    "00000028667479706d7034310000000069736f3869736f6d6d7034316461736861763031636d6663"
                            + "000003136d6f6f760000006c6d76686400000000e6c0a14c", "video/mp4"),
            // Meta labels the sound track video/mp4, not audio/mp4, and a fetch keeps a type that
            // specific. Nothing reads it: the joined file is published as video/mp4.
            new Shape("DASH sound track (HE-AAC)", "video/mp4", Downloader.Kind.AUDIO,
                    "00000024667479706d7034310000000069736f3869736f6d6d70343164617368636d6663000003146d"
                            + "6f6f760000006c6d76686400000000e6c09de8e6c09de8", "video/mp4"),
            new Shape("static image", "image/webp", Downloader.Kind.IMAGE,
                    "52494646580d000057454250565038580a00000010000000bc00004f0000414c50489f08000001b0"
                            + "80feffbc44da171191909088100991b9cf884124648ef68a", "image/webp"),
    };

    private static byte[] bodyFrom(String hexHead, int size) {
        byte[] body = new byte[size];
        for (int i = 0; i < hexHead.length() / 2; i++) {
            body[i] = (byte) Integer.parseInt(hexHead.substring(2 * i, 2 * i + 2), 16);
        }
        for (int i = hexHead.length() / 2; i < size; i++) body[i] = (byte) (i * 31);
        return body;
    }

    private static MediaUrlPolicy onlyThrough(int port) throws IOException {
        return new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") }) {
            @Override
            Refusal refusal(URL url) {
                if (url.getHost().equals("127.0.0.1") && url.getPort() == port) return null;
                return super.refusal(url);
            }
        };
    }

    @Test
    public void everyCapturedShapeIsFetchedAsWhatItIs() throws IOException {
        try (LocalServer server = new LocalServer()) {
            MediaUrlPolicy policy = onlyThrough(server.port());
            for (int i = 0; i < SHAPES.length; i++) {
                Shape shape = SHAPES[i];
                byte[] body = bodyFrom(shape.head, 4096);
                server.serve("/" + i, 200, shape.type, body, body.length);

                File into = temp.newFile();
                Downloader.Result result = Downloader.fetch(server.origin() + "/" + i, shape.kind, into, policy,
                        Downloader.MAX_BYTES);

                assertEquals(shape.name + ": " + result, Downloader.Status.OK, result.status);
                assertEquals(shape.name, shape.saves, result.mime);
                assertEquals(shape.name, body.length, into.length());
            }
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CapturedMetaMediaTest.class.getResourceAsStream(name)) {
            assertNotNull("no " + name + " beside the test", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The manifest that came with the video, with its ids and signed addresses replaced. It lists
     * AV1 pictures only, as stories often do, so before Android 14 there is no track the muxer can
     * write and the save falls back to the progressive file.
     */
    @Test
    public void theCapturedManifestParsesIntoItsTracks() throws IOException {
        List<DashManifest.Track> tracks = DashManifest.parse(resource("meta-public-dash.mpd"));
        assertEquals(tracks.toString(), 9, tracks.size());

        assertNull("an AV1 track was picked for a muxer that can't write it", DashManifest.bestVideo(tracks, false));
        DashManifest.Track picture = DashManifest.bestVideo(tracks, true);
        assertNotNull(tracks.toString(), picture);
        assertEquals("av01.0.08m.08.0.111.01.01.01.0", picture.codecs);
        assertEquals(1080, picture.shortSide());
        assertEquals(1_085_775L, picture.bandwidth);

        DashManifest.Track sound = DashManifest.bestAudio(tracks);
        assertNotNull(tracks.toString(), sound);
        assertEquals("mp4a.40.5", sound.codecs);

        for (DashManifest.Track track : tracks) {
            assertNull(track.url, MediaUrlPolicy.shapeRefusal(track.url));
        }
    }
}
