/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.stories;

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
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoryAdvanceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PauseForTests.resume();
        Settings.BLOCK_STORY_AUTO_ADVANCE.resetToDefault();
    }

    @Test public void switchAndPauseLeaveFacebooksTimingAlone() {
        assertTrue(StoryAdvance.waitForTap());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(false);
        assertFalse(StoryAdvance.waitForTap());
        Settings.BLOCK_STORY_AUTO_ADVANCE.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(StoryAdvance.waitForTap());
    }
}
