/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.L10nTablesForTests;
import app.morphe.extension.shared.SettingsContextRule;

/**
 * What a save says, drawn under en-XA: every word the catalog draws is bracketed there, so plain
 * English in a toast or the notification is text that never went through it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, qualifiers = "en-rXA")
public class SaveTextL10nTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void clear() {
        notifications().cancelAll();
    }

    @Test
    public void everyResultAndTheProgressLineComeFromTheCatalog() {
        List<String> said = new ArrayList<>();
        for (Downloader.Status status : Downloader.Status.values()) {
            said.add(MediaDownload.message(context, status, null));
            said.add(MediaDownload.message(context, status, "Movies/Facebook"));
        }
        said.add(SaveControl.progressText(5_000_000, 50_000_000));
        said.add(SaveControl.progressText(5_000_000, -1));
        for (String text : said) {
            assertTrue("not from the catalog: " + text, text.startsWith("[") && text.endsWith("]"));
        }
        // The folder is a value, and a value is never drawn accented.
        assertTrue(MediaDownload.message(context, Downloader.Status.OK, "Movies/Facebook").contains("Movies/Facebook"));
    }

    @Test
    public void theNotificationAndItsChannelComeFromTheCatalog() {
        SaveControl.Save save = SaveControl.begin(context, true);
        try {
            Notification shown = null;
            for (Notification notification : Shadows.shadowOf(notifications()).getAllNotifications()) {
                if (SaveControl.CHANNEL.equals(notification.getChannelId())) shown = notification;
            }
            assertNotNull("no save notification", shown);
            String title = String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_TITLE));
            assertTrue(title, title.startsWith("[") && title.endsWith("]"));
            // Cancel is Android's own word, which the phone has in every language.
            assertEquals(context.getString(android.R.string.cancel), String.valueOf(shown.actions[0].title));

            NotificationChannel channel = notifications().getNotificationChannel(SaveControl.CHANNEL);
            assertTrue(String.valueOf(channel.getName()), String.valueOf(channel.getName()).startsWith("["));
            assertTrue(channel.getDescription(), channel.getDescription().startsWith("["));
        } finally {
            save.end();
        }
    }

    /** A channel named in one language is renamed in the next, keeping what the person set. */
    @Test
    @Config(qualifiers = "de")
    public void aChannelNamedInAnotherLanguageIsRenamed() {
        NotificationChannel old = new NotificationChannel(SaveControl.CHANNEL, "Hushfacebook saves",
                NotificationManager.IMPORTANCE_LOW);
        notifications().createNotificationChannel(old);

        SaveControl.begin(context, false).end();
        NotificationChannel renamed = notifications().getNotificationChannel(SaveControl.CHANNEL);
        assertEquals(L10nTablesForTests.of("de").get("Hushfacebook saves"), String.valueOf(renamed.getName()));
        assertEquals(NotificationManager.IMPORTANCE_LOW, renamed.getImportance());
    }

    private NotificationManager notifications() {
        return context.getSystemService(NotificationManager.class);
    }
}
