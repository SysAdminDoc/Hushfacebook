/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * What a save that never finished leaves behind, and its removal.
 *
 * <p>A save removes its own files when it fails or is cancelled. When Android ends Facebook's
 * process in the middle of one, nothing runs: the work files stay in the cache, a save that was
 * already copying into the gallery leaves a row marked pending, which the platform only clears
 * after about a week, and its notification stays up. So the first save of each process removes all
 * three before it makes anything of its own. Only the main process saves, so nothing there can
 * belong to a save still running, and a notification of one this process is running is kept.
 *
 * <p>The pending rows are known by a list kept here, not found by a query. Facebook's own save
 * writes into the same folders with the same kind of names, and a query can't tell its rows from
 * these.
 */
final class SaveLeftovers {

    private SaveLeftovers() {}

    /** Facebook's own preferences folder holds this file; the key is the only one in it. */
    private static final String LEDGER = "hushfacebook_saves";
    private static final String PENDING = "pending_rows";

    private static final Object LOCK = new Object();
    private static boolean swept;

    /** Once per process, before the first save makes a file or a row. Every save calls it first. */
    static void sweepOnce(Context application) {
        synchronized (LOCK) {
            if (swept) return;
            swept = true;
            int files = removeWorkFiles(application);
            int rows = removePendingRows(application);
            int notices = SaveControl.removeStale(application);
            if (files > 0 || rows > 0 || notices > 0) {
                MediaDownload.info(() -> "removed what a stopped save left: " + files + " work file(s), "
                    + rows + " pending gallery row(s), " + notices + " notification(s)");
            }
        }
    }

    /** The next save sweeps again, as the first save of a new process would. For tests. */
    static void forgetSweepForTests() {
        synchronized (LOCK) {
            swept = false;
        }
    }

    /** A row was just inserted pending. */
    static void pending(Context application, Uri row) {
        record(application, row, true);
    }

    /** The row was published or removed. */
    static void settled(Context application, Uri row) {
        record(application, row, false);
    }

    private static void record(Context application, Uri row, boolean add) {
        if (row == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
                Set<String> rows = new HashSet<>(ledger.getStringSet(PENDING, new HashSet<>()));
                boolean changed = add ? rows.add(row.toString()) : rows.remove(row.toString());
                // commit(), not apply(): a process ended right after an apply() can lose the
                // entry, and the entry exists for exactly that case. This runs on the save's thread.
                if (changed) ledger.edit().putStringSet(PENDING, rows).commit();
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not update the list of pending gallery rows", t);
            }
        }
    }

    private static int removeWorkFiles(Context application) {
        File folder = DashSave.workFolder(application);
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) return 0;

        int removed = 0;
        for (File file : files) {
            if (file.delete()) removed++;
        }
        return removed;
    }

    private static int removePendingRows(Context application) {
        SharedPreferences ledger = application.getSharedPreferences(LEDGER, Context.MODE_PRIVATE);
        Set<String> rows = ledger.getStringSet(PENDING, null);
        if (rows == null || rows.isEmpty()) return 0;

        ContentResolver resolver = application.getContentResolver();
        int removed = 0;
        for (String row : new HashSet<>(rows)) {
            try {
                // Only while it's still pending: a row the stopped save had already published is a
                // finished file that was crossed off too late. MediaStore matches a pending row
                // when it's named by its own address.
                removed += resolver.delete(Uri.parse(row), MediaStore.MediaColumns.IS_PENDING + "=1", null);
            } catch (Throwable t) {
                MediaDownload.failure(() -> "could not remove a pending gallery row a stopped save left", t);
            }
        }
        ledger.edit().remove(PENDING).commit();
        return removed;
    }
}
