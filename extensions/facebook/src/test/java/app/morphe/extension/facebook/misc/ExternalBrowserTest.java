/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/** Which links leave Facebook's in-app browser, and where they go. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ExternalBrowserTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restoreSwitch() {
        Settings.OPEN_LINKS_EXTERNALLY.resetToDefault();
    }

    private static Uri unwrap(String url) throws Exception {
        Method unwrap = ExternalBrowser.class.getDeclaredMethod("unwrapLinkShim", Uri.class);
        unwrap.setAccessible(true);
        return (Uri) unwrap.invoke(null, Uri.parse(url));
    }

    @Test
    public void theLinkShimGivesUpItsDestination() throws Exception {
        assertEquals("https://example.org/a?b=1",
                unwrap("https://lm.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Fa%3Fb%3D1&h=AT0x").toString());
    }

    /** The mutation control: a link that is not a shim, or a shim whose u is no web link, stays. */
    @Test
    public void anythingElseIsLeftAsItCame() throws Exception {
        assertEquals("https://example.org/l.php?u=https%3A%2F%2Fother.org",
                unwrap("https://example.org/l.php?u=https%3A%2F%2Fother.org").toString());
        assertEquals("https://m.facebook.com/l.php?u=javascript%3Aalert(1)",
                unwrap("https://m.facebook.com/l.php?u=javascript%3Aalert(1)").toString());
    }

    private static Activity browserWith(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        return Robolectric.buildActivity(Activity.class, intent).create().get();
    }

    @Test
    public void anOutsideLinkOpensInTheDefaultBrowser() {
        Activity browser = browserWith("https://lm.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2F&h=AT0x");

        assertTrue(ExternalBrowser.redirect(browser, browser.getIntent()));
        Intent started = shadowOf(browser).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://example.org/", started.getDataString());
        assertTrue("the in-app browser closes behind the link", browser.isFinishing());
    }

    @Test
    public void facebooksOwnPagesStayInTheApp() {
        Activity browser = browserWith("https://www.facebook.com/help/1234");

        assertFalse(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertNull(shadowOf(browser).getNextStartedActivity());
    }

    @Test
    public void theSwitchKeepsEveryLinkInTheApp() {
        Settings.OPEN_LINKS_EXTERNALLY.save(false);
        Activity browser = browserWith("https://example.org/");

        assertFalse(ExternalBrowser.redirect(browser, browser.getIntent()));
        assertNull(shadowOf(browser).getNextStartedActivity());
    }

    /**
     * Facebook's in-app browser on a phone where no app opens web links. The message quotes the
     * link with its scheme and, the way some Android versions print an intent, without one: the
     * redactor takes out the first, and only leaving the message out keeps the second out too.
     */
    public static final class NoBrowserAround extends Activity {
        @Override
        public void startActivity(Intent intent) {
            throw new ActivityNotFoundException("No Activity found to handle Intent { act=android.intent.action.VIEW "
                    + "dat=https://example.org/private/path host=example.org/private/path flg=0x10000000 }");
        }
    }

    /**
     * A link no browser took stays in the app and says so in the report, by the exception's class
     * alone: its message quotes the intent, and the intent carries the link.
     */
    @Test
    public void aLinkNoBrowserTookReachesTheReportWithoutTheLink() {
        LogBufferManager.clearLogBuffer();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/private/path"));
            Activity browser = Robolectric.buildActivity(NoBrowserAround.class, intent).create().get();

            assertFalse(ExternalBrowser.redirect(browser, browser.getIntent()));
            assertFalse("the in-app browser closed with no link opened elsewhere", browser.isFinishing());

            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains(
                    "| ExternalBrowser | ERROR | No external browser took the link (ActivityNotFoundException)"));
            assertTrue(report, report.contains("Open links in external browser: invoked 1, 0 found, 0 missing"));
            assertFalse(report, report.contains("example.org"));
            assertFalse(report, report.contains("private/path"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }
}
