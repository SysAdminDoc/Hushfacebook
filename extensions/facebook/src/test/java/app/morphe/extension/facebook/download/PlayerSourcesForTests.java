/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import com.facebook.video.engine.api.VideoDataSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The player-source recorder, for a test outside this package. It runs in every player Facebook
 * builds, so recording a player is the work a pause has to stop.
 */
public final class PlayerSourcesForTests {
    private PlayerSourcesForTests() {
    }

    /** A new id each time, so a player recorded by an earlier call can't answer for this one. */
    private static final AtomicLong NEXT_ID = new AtomicLong(100_000_000_000L);

    static final class HdSource extends VideoDataSource {
        final String hd;
        final String manifest;

        HdSource(String hd, String manifest) {
            this.hd = hd;
            this.manifest = manifest;
        }
    }

    static final class Params {
        final String videoId;
        final VideoDataSource source;

        Params(String videoId, VideoDataSource source) {
            this.videoId = videoId;
            this.source = source;
        }
    }

    /** A story card: the save finds the player by the id it holds. */
    static final class Card {
        final String id;

        Card(String id) {
            this.id = id;
        }
    }

    /** Builds one player the way the patch sees it, and says whether the recorder kept its source. */
    public static boolean recordsAPlayer() {
        String id = Long.toString(NEXT_ID.getAndIncrement());
        Params params = new Params(id, new HdSource("https://video.xx.fbcdn.net/v/hd.mp4", null));
        PlayerSources.remember(params, "videoId", "hd", "manifest");
        return PlayerSources.find(new Card(id)) != null;
    }
}
