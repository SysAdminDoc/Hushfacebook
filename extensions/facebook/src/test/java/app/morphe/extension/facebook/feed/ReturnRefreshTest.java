/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowPausedSystemClock;

import java.util.Collections;

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

    /**
     * A failure inside the check lets Facebook refresh, as it would unpatched, and the report
     * names the hook that threw. The clock is what the check asks first once it has counted the
     * call, so a clock that fails once stands in for anything that can throw there.
     */
    @Test @Config(shadows = FailingClock.class)
    public void aFailureLetsFacebookRefreshAndTheReportSaysSo() {
        HookStatus.clear();
        try {
            // Hidden just now with the switch on: the same return without the failure keeps its place.
            ReturnRefresh.uiHidden();
            assertTrue(ReturnRefresh.skip());

            ReturnRefresh.uiHidden();
            FailingClock.failNext = true;
            assertFalse(ReturnRefresh.skip());
            assertFalse("the check never asked the clock", FailingClock.failNext);
            assertEquals(Collections.singletonList("a working 'feed resume' hook (it threw "
                            + IllegalStateException.class.getName() + ")"),
                    HookStatus.missing(FamilyNames.RETURN_REFRESH));
        } finally {
            FailingClock.failNext = false;
            HookStatus.clear();
        }
    }

    /** Robolectric's clock, except that the next elapsedRealtime() can be made to throw, once. */
    @Implements(SystemClock.class)
    public static class FailingClock extends ShadowPausedSystemClock {
        static volatile boolean failNext;

        @Implementation
        protected static long elapsedRealtime() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("the clock failed");
            }
            return ShadowPausedSystemClock.elapsedRealtime();
        }
    }
}
