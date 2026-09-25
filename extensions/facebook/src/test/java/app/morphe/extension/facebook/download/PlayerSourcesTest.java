/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;

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
