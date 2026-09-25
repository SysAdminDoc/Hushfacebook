/*
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.SettingsContextRule;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class LogBufferManagerExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Test public void noExitOnRecordMeansNoLastExitSection() {
        // Android has kept no exit for this process yet, so the section has to be absent rather
        // than empty or guessed at.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");

        String report = LogBufferManager.buildExportText();
        assertNotEquals("nothing was reported at all", "", report);
        assertTrue("the report claimed to know why the process went away: " + report,
                report.indexOf("[LAST EXIT]") < 0);
    }

    @Test
    public void theReportSaysWhyTheProcessWentAwayLastTime() throws Exception {
        // A Java crash handler sees none of the ways the system ends an app. Android 17 kills one
        // that goes over a RAM-proportional limit and leaves only this behind.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();

        android.app.ApplicationExitInfo exit =
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(13)
                .setTimestamp(1757260800000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                .setDescription("MemoryLimiter:AnonSwap")
                .build();

        // Facebook runs several processes, and the most recent record is routinely a background
        // helper the system reaped. Reporting that as why the app went away is worse than saying
        // nothing, so the newer of the two here must be passed over.
        android.app.ApplicationExitInfo helper = org.robolectric.shadows.ShadowActivityManager
                .ApplicationExitInfoBuilder.newBuilder()
                .setReason(10)
                .setTimestamp(1757260900000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName() + ":push")
                .setDescription("a background helper nobody asked about")
                .build();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(exit);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(helper);

        // The line rides along with a report that was already worth making. On its own it must
        // not make one, because every process has a last exit and most of them are ordinary.
        assertEquals("a last exit alone made a report", "", LogBufferManager.buildExportText());

        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
        String report = LogBufferManager.buildExportText();
        assertTrue("the report does not say why the process went away: " + report,
                report.contains("[LAST EXIT]"));
        assertTrue("the reason was not named: " + report, report.contains("reason: OTHER"));
        assertTrue("the description the kill carried was dropped: " + report,
                report.contains("MemoryLimiter:AnonSwap"));
        assertTrue("a background helper's exit was reported as the app's: " + report,
                report.indexOf("a background helper nobody asked about") < 0);
        java.text.SimpleDateFormat utc =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String when = utc.format(new java.util.Date(1757260800000L));
        assertTrue("the time it happened was left out or in another format: " + report,
                report.contains(when));
    }

    /**
     * Facebook runs on Android 11 and newer, where the report goes through MediaStore into
     * Download/Morphe. The path handed back is the name MediaStore gave the file, which differs
     * from the one asked for when a file of that name is already there.
     */
    @Test public void repeatedExportsEachGetTheirOwnDownloadsEntry() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        ByteArrayOutputStream firstBody = new ByteArrayOutputStream();
        ByteArrayOutputStream secondBody = new ByteArrayOutputStream();
        ShadowContentResolver resolver = Shadows.shadowOf(context.getContentResolver());
        resolver.registerOutputStream(downloads.uriFor(1), firstBody);
        resolver.registerOutputStream(downloads.uriFor(2), secondBody);

        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\n";
        String first = LogBufferManager.writeToFile(context, report);
        String second = LogBufferManager.writeToFile(context, report);

        String folder = Environment.DIRECTORY_DOWNLOADS + "/Morphe/";
        assertTrue(first, first.startsWith(folder + "morphe-diagnostics-") && first.endsWith(".txt"));
        assertTrue(second, second.startsWith(folder + "morphe-diagnostics-") && second.endsWith(".txt"));
        assertNotEquals(first, second);
        assertEquals(folder + downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME), first);
        assertEquals(folder + downloads.row(2).getAsString(MediaStore.MediaColumns.DISPLAY_NAME), second);
        for (int id = 1; id <= 2; id++) {
            assertEquals("entry " + id + " was left pending", Integer.valueOf(0),
                    downloads.row(id).getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertEquals(Environment.DIRECTORY_DOWNLOADS + "/Morphe",
                    downloads.row(id).getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        }
        assertEquals(report, firstBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals(report, secondBody.toString(StandardCharsets.UTF_8.name()));
    }

    /**
     * The file's place is a value set into a sentence, so the toast isolates it: a right-to-left
     * sentence then keeps "Download/Morphe/..." in the order it was written.
     */
    @Test public void theSavedPathIsIsolatedInItsToast() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Downloads downloads = Robolectric.setupContentProvider(Downloads.class, MediaStore.AUTHORITY);
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(downloads.uriFor(1), new ByteArrayOutputStream());
        app.morphe.extension.shared.Utils.setContext(context);
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
        org.robolectric.shadows.ShadowToast.reset();

        LogBufferManager.exportToFile();
        String toast = null;
        long until = System.currentTimeMillis() + 20_000;
        while (toast == null && System.currentTimeMillis() < until) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
            if (toast == null) Thread.sleep(50);
        }

        assertNotNull("the export said nothing", toast);
        String saved = Environment.DIRECTORY_DOWNLOADS + "/Morphe/" + downloads.row(1).getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        assertEquals("Full report saved to " + app.morphe.extension.shared.L10n.isolate(saved), toast);
    }

    /** MediaStore's Downloads table, as much of it as an export touches. */
    public static final class Downloads extends ContentProvider {
        private final Map<Long, ContentValues> rows = new HashMap<>();
        private long nextId = 1;

        Uri uriFor(long id) {
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
        }

        ContentValues row(long id) {
            ContentValues row = rows.get(id);
            assertTrue("no Downloads entry " + id, row != null);
            return row;
        }

        @Override public boolean onCreate() {
            return true;
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            ContentValues row = new ContentValues(values);
            // MediaStore keeps names in a folder unique by numbering the newcomer.
            String name = row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            for (ContentValues other : rows.values()) {
                if (name.equals(other.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
                    row.put(MediaStore.MediaColumns.DISPLAY_NAME, name.replace(".txt", " (1).txt"));
                }
            }
            long id = nextId++;
            rows.put(id, row);
            return uriFor(id);
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection);
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row != null) {
                Object[] values = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) values[i] = row.get(projection[i]);
                cursor.addRow(values);
            }
            return cursor;
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            return rows.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }

        @Override public String getType(Uri uri) {
            return "text/plain";
        }
    }
}
