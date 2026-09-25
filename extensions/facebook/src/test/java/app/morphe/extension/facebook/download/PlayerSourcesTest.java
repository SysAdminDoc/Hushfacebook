/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The recorder runs in every player Facebook builds, feed, Reels and Watch included, and only a
 * story save or a feed or Watch video's Download to phone reads what it keeps. Each patch records
 * through its own call, behind its own switch, on its own Hook status line.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PlayerSourcesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.DOWNLOAD_STORIES.resetToDefault();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        HookStatus.clear();
    }

    /**
     * Every player built is a run of the recorder, on its own Hook status line, so the story
     * save's line still counts Save taps. A miss the recorder finds is on the line that counted it.
     */
    @Test
    public void everyPlayerBuiltIsARunOnTheRecordersOwnLine() {
        HookStatus.clear();
        Settings.DOWNLOAD_STORIES.save(false);
        PlayerSourcesForTests.recordsAPlayer();
        Settings.DOWNLOAD_STORIES.save(true);
        PlayerSourcesForTests.recordsAPlayer();

        assertEquals(Collections.singletonList("Download any story (player sources): invoked 2, 1 found, 0 missing"),
                HookStatus.report());
    }

    /**
     * A build that moved the source out of the player's params. The miss belongs to the recorder,
     * on its own line, and not to a story save that never ran (9199cd6 moved it there).
     */
    @Test
    public void aMissTheRecorderFindsIsOnItsOwnLine() {
        HookStatus.clear();
        Settings.DOWNLOAD_STORIES.save(true);
        PlayerSources.remember(new ParamsWithoutSource("123456789012"), "videoId", "hd", "manifest");

        assertEquals(Collections.singletonList("Download any story (player sources): invoked 1, 0 found, 1 missing. "
                + "First missing: field " + ParamsWithoutSource.class.getName()
                + "#com.facebook.video.engine.api.VideoDataSource"), HookStatus.report());
    }

    /** Player params with the video's id and no field for its source. */
    static final class ParamsWithoutSource {
        final String videoId;

        ParamsWithoutSource(String videoId) {
            this.videoId = videoId;
        }
    }

    /**
     * The video patch's recorder asks Download any video's switch and nothing else, so a build
     * with only one of the two patches does no work for the one it lacks, and the story switch
     * can't turn the video recorder on or off.
     */
    @Test
    public void aVideoPlayerIsRecordedOnlyWhileVideoSavesAreOn() {
        Settings.DOWNLOAD_STORIES.save(false);
        Settings.DOWNLOAD_VIDEOS.save(true);
        assertTrue("with the video switch on, its recorder keeps the player's source",
                PlayerSourcesForTests.recordsAVideoPlayer());
        assertFalse("the story recorder still asks the story switch", PlayerSourcesForTests.recordsAPlayer());

        Settings.DOWNLOAD_STORIES.save(true);
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertFalse("with the video switch off, its recorder leaves the player alone",
                PlayerSourcesForTests.recordsAVideoPlayer());
        assertTrue(PlayerSourcesForTests.recordsAPlayer());
    }

    @Test
    public void theVideoRecorderCountsOnItsOwnLine() {
        HookStatus.clear();
        Settings.DOWNLOAD_VIDEOS.save(false);
        PlayerSourcesForTests.recordsAVideoPlayer();
        Settings.DOWNLOAD_VIDEOS.save(true);
        PlayerSourcesForTests.recordsAVideoPlayer();

        assertEquals(Collections.singletonList("Download any video (player sources): invoked 2, 1 found, 0 missing"),
                HookStatus.report());
    }

    @Test
    public void aPlayerIsFoundByItsIdAndNoOtherWay() {
        Settings.DOWNLOAD_VIDEOS.save(true);
        assertTrue(PlayerSourcesForTests.recordsAVideoPlayer());
        assertEquals(null, PlayerSources.byId(null));
        assertEquals(null, PlayerSources.byId(""));
        assertEquals("an id no player was built for", null, PlayerSources.byId("99999999999999999"));
    }

    @Test
    public void aPlayerIsRecordedOnlyWhileStorySavesAreOn() {
        Settings.DOWNLOAD_STORIES.save(true);
        assertTrue("with the switch on, the recorder keeps the player's source",
                PlayerSourcesForTests.recordsAPlayer());

        Settings.DOWNLOAD_STORIES.save(false);
        assertFalse("with the switch off, the player is left as Facebook built it",
                PlayerSourcesForTests.recordsAPlayer());
    }
}
