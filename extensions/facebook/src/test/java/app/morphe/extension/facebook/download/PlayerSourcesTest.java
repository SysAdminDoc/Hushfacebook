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
 * story save reads what it keeps.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PlayerSourcesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        Settings.DOWNLOAD_STORIES.resetToDefault();
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
