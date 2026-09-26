/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReturnRefreshTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void restore() {
        PauseForTests.resume();
        Settings.BLOCK_RETURN_REFRESH.resetToDefault();
        ReturnRefresh.skipAt(Long.MAX_VALUE);
    }

    @Test public void onlyFirstReturnWithinTenMinutesKeepsPosition() {
        assertFalse(ReturnRefresh.skipAt(100));
        ReturnRefresh.uiHidden(1_000);
        assertTrue(ReturnRefresh.skipAt(1_000 + 10 * 60 * 1000));
        assertFalse(ReturnRefresh.skipAt(1_000 + 10 * 60 * 1000));
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.skipAt(1_000 + 10 * 60 * 1000 + 1));
    }

    @Test public void disabledOrPausedReturnsToFacebookRefresh() {
        Settings.BLOCK_RETURN_REFRESH.save(false);
        ReturnRefresh.uiHidden(1_000);
        assertFalse(ReturnRefresh.skipAt(1_001));
        Settings.BLOCK_RETURN_REFRESH.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        ReturnRefresh.uiHidden(2_000);
        assertFalse(ReturnRefresh.skipAt(2_001));
    }

    @Test public void clocksMovingBackCannotHoldTheFeed() {
        ReturnRefresh.uiHidden(2_000);
        assertFalse(ReturnRefresh.skipAt(1_999));
    }

    /**
     * Every other hook counts its calls in the report. This one didn't, so a report from someone
     * whose feed still refreshed couldn't say whether the callback ever ran.
     */
    @Test public void eachResumeCountsInTheReport() {
        HookStatus.clear();
        try {
            ReturnRefresh.skip();
            ReturnRefresh.skip();
            String report = String.join("\n", HookStatus.report());
            assertTrue(report, report.contains(FamilyNames.RETURN_REFRESH + ": invoked 2"));
        } finally {
            HookStatus.clear();
        }
    }
}
