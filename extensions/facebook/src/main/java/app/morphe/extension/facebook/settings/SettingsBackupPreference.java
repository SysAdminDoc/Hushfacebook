/*
 * Forked from:
 * https://github.com/SysAdminDoc/hushfeed/blob/bcc57ee555f4346c4eb1cbdae9f6ca8a3fa6fef4/extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/SettingsBackupPreference.java
 * Copyright 2026 Hushfeed contributors (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: two rows, Export settings and Import settings, on the
 * framework preference page inside Hushfacebook's settings dialog; an import is read and shown as a
 * preview of how many switches it changes before anything is written; no Reset or Undo.
 */
package app.morphe.extension.facebook.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.L10n;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Export settings and Import settings.
 *
 * <p>Both open Android's file picker from the settings page. The picker is an activity of its
 * own, so Android may rebuild Facebook's activity behind it, and the settings dialog and its page
 * with it. The result still reaches the page: Android hands it to the fragment the request came
 * from by the name the framework gave it, and a rebuilt page is given the same one. So nothing
 * about a request is kept on the page. Its code says which it was, and the file's address comes
 * with the result.
 *
 * <p>An import reads the file first and shows how many switches it changes. Nothing is written
 * until the person says so, and then everything is written in one commit. The preview is kept in
 * the page's saved state, so a rotation or a trip away from Facebook brings it back.
 */
@SuppressWarnings("deprecation") // Framework preferences are what the shared settings page builds on.
public class SettingsBackupPreference extends Preference {
    static final int EXPORT = 7311;
    static final int IMPORT = 7312;

    /** What a settings file is saved as. */
    static final String MIME_TYPE = "application/json";

    /**
     * What an import offers to open. A provider that doesn't know .json files calls them text or
     * plain bytes, and a picker filtered to JSON alone greyed out a file saved there.
     */
    static final String[] OPENABLE_TYPES = {MIME_TYPE, "text/plain", "application/octet-stream"};

    /** A read, a write or an import is running. */
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    /** The rows on the page right now, so a run can take them all out of reach. */
    private static final List<WeakReference<SettingsBackupPreference>> ROWS = new CopyOnWriteArrayList<>();

    /** The action running and the line its row shows, so a row built during a run shows it too. */
    private static volatile int runningAction;
    @Nullable
    private static volatile String runningLine;

    /**
     * The newest page, which a file read finishes on. A page rebuilt while the file was being read
     * is a new one, and the old one can no longer show anything.
     */
    private static WeakReference<HushfacebookPreferenceFragment> latestPage = new WeakReference<>(null);

    private final int rowAction;
    private final CharSequence restingSummary;
    /** The line this row shows while it's the one running, else null. */
    @Nullable
    private String busyLine;

    SettingsBackupPreference(HushfacebookPreferenceFragment page, Context context, int action,
                             CharSequence title, CharSequence summary) {
        super(context);
        rowAction = action;
        restingSummary = summary;
        setKey(action == EXPORT ? "action_export_settings" : "action_import_settings");
        setPersistent(false);
        setTitle(title);
        setSummary(summary);
        setOnPreferenceClickListener(preference -> {
            // Rows are out of reach while a run is going, so this is only the race between a tap
            // and that.
            if (!BUSY.get()) pickFile(page, action);
            return true;
        });
        latestPage = new WeakReference<>(page);
        ROWS.add(new WeakReference<>(this));
        int running = runningAction;
        if (running != 0) showBusy(running, runningLine);
    }

    /** A readable, sortable name for the file, stamped in UTC like the diagnostic report. */
    static String suggestedExportName() {
        return "hushfacebook-settings-" + LogBufferManager.fileTimestamp() + ".json";
    }

    private static void pickFile(HushfacebookPreferenceFragment page, int action) {
        Intent intent = new Intent(action == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE);
        if (action == EXPORT) {
            intent.setType(MIME_TYPE).putExtra(Intent.EXTRA_TITLE, suggestedExportName());
        } else {
            intent.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, OPENABLE_TYPES);
        }
        try {
            page.startActivityForResult(intent, action);
        } catch (ActivityNotFoundException missing) {
            Logger.printInfo(() -> "No file picker for the settings file");
            Utils.showToastLong(L10n.t("This phone has no file picker, so there's no way to choose a file here."));
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "Could not open the file picker: " + error.getClass().getSimpleName());
            Utils.showToastLong(L10n.t("Couldn't open the file picker. Try again."));
        }
    }

    /**
     * A picker's answer, from the page's onActivityResult.
     *
     * @return whether the request was one of these rows'.
     */
    static boolean onResult(HushfacebookPreferenceFragment page, int request, int result, @Nullable Intent data) {
        if (request != EXPORT && request != IMPORT) return false;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) return true; // Cancelled: nothing to do and nothing to say.
        if (request == EXPORT) export(page, uri);
        else readForPreview(page, uri);
        return true;
    }

    private static void export(HushfacebookPreferenceFragment page, Uri uri) {
        Context context = appContext(page);
        if (!start(EXPORT, L10n.t("Saving the settings file"))) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                write(context, uri, SettingsBackup.create().getBytes(StandardCharsets.UTF_8));
                Utils.showToastLong(L10n.t("Settings exported."));
            } catch (Exception error) {
                Logger.printInfo(() -> "Settings export failed: " + error.getClass().getSimpleName());
                Utils.showToastLong(L10n.t("Couldn't save the settings file. Try again."));
            } finally {
                Utils.runOnMainThread(SettingsBackupPreference::finish);
            }
        });
        if (!accepted) notStarted();
    }

    /**
     * Writes over whatever is there. "wt" truncates a file being replaced; a provider that turns
     * the mode down gets "w". Where that leaves old bytes past the new end, an import refuses the
     * file as damaged rather than reading half of it.
     */
    private static void write(Context context, Uri uri, byte[] bytes) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        OutputStream output;
        try {
            output = resolver.openOutputStream(uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException | FileNotFoundException unsupported) {
            output = resolver.openOutputStream(uri, "w");
        }
        if (output == null) throw new IOException("No stream to write to");
        try (OutputStream stream = output) {
            stream.write(bytes);
        }
    }

    private static void readForPreview(HushfacebookPreferenceFragment page, Uri uri) {
        Context context = appContext(page);
        if (!start(IMPORT, L10n.t("Reading the settings file"))) return;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            SettingsBackup.Snapshot snapshot = null;
            try {
                snapshot = SettingsBackup.parse(SettingsBackup.read(open(context, uri)));
            } catch (SettingsBackup.Rejected refused) {
                Logger.printInfo(() -> "Settings file refused: " + refused.reason);
                Utils.showToastLong(refusal(refused.reason));
            } finally {
                SettingsBackup.Snapshot read = snapshot;
                Utils.runOnMainThread(() -> {
                    finish();
                    if (read != null) offer(read);
                });
            }
        });
        if (!accepted) notStarted();
    }

    @Nullable
    private static InputStream open(Context context, Uri uri) throws SettingsBackup.Rejected {
        try {
            return context.getContentResolver().openInputStream(uri);
        } catch (IOException | RuntimeException error) {
            throw new SettingsBackup.Rejected(SettingsBackup.Reason.UNREADABLE, error.getClass().getSimpleName());
        }
    }

    /** One sentence per refusal, so a cut-off download and a newer version's file don't read the same. */
    static String refusal(SettingsBackup.Reason reason) {
        switch (reason) {
            case SIZE:
                return L10n.t("That file is too large to be a settings file. Nothing was changed.");
            case ENCODING:
                return L10n.t("That file isn't readable text, so it may have been damaged on the way. Nothing was changed.");
            case DAMAGED:
                return L10n.t("That settings file is damaged or only partly downloaded. Nothing was changed.");
            case DUPLICATE:
                return L10n.t("That file lists a setting twice, so there's no telling which value to use. Nothing was changed.");
            case FORMAT:
                return L10n.t("That isn't a Hushfacebook settings file. Nothing was changed.");
            case SCHEMA:
                return L10n.t("That settings file was written by a newer Hushfacebook than this one. Nothing was changed.");
            case VALUE:
                return L10n.t("That settings file holds a value Hushfacebook can't read. Nothing was changed.");
            default:
                return L10n.t("Couldn't open that file. Nothing was changed.");
        }
    }

    /** Hands a read file to the page on screen, which shows the preview now or when it resumes. */
    private static void offer(SettingsBackup.Snapshot snapshot) {
        HushfacebookPreferenceFragment page = latestPage.get();
        if (page == null || !page.isAdded()) {
            // The settings screen was closed while the file was read.
            Logger.printInfo(() -> "Settings file read with no settings page left to preview it on");
            return;
        }
        page.pendingImport = snapshot.toBundle();
        if (page.isResumed()) showPreview(page);
    }

    /** Called when the page resumes: a preview waiting on an answer is shown again. */
    static void onPageResumed(HushfacebookPreferenceFragment page) {
        if (page.pendingImport != null) showPreview(page);
    }

    /**
     * Takes the preview off the screen without answering it, when the page's view goes. A page
     * rebuilt from the saved state shows it again.
     */
    static void closePreview(HushfacebookPreferenceFragment page) {
        AlertDialog shown = page.importPreview;
        page.importPreview = null;
        if (shown == null) return;
        shown.setOnCancelListener(null);
        shown.dismiss();
    }

    /**
     * How many switches the waiting file changes, the folder it moves the saves to, and what's in
     * it that this build doesn't know.
     */
    static void showPreview(HushfacebookPreferenceFragment page) {
        if (page.importPreview != null) return;
        SettingsBackup.Snapshot snapshot = SettingsBackup.Snapshot.fromBundle(page.pendingImport);
        Activity activity = page.getActivity();
        if (snapshot == null || activity == null) {
            page.pendingImport = null;
            return;
        }
        int changes = snapshot.changes().size();
        int switches = snapshot.switchChanges();
        String folder = snapshot.folderChange();
        String message;
        if (changes == 0) {
            message = L10n.t("Your switches already match that file, so nothing will change.");
        } else if (switches == 0) {
            message = folderSentence(folder);
        } else {
            message = L10n.quantity(switches, "%1$d switch will change.", "%1$d switches will change.", switches);
            if (folder != null) message += "\n\n" + folderSentence(folder);
        }
        if (snapshot.unknown > 0) {
            message += "\n\n" + L10n.quantity(snapshot.unknown,
                    "%1$d item in that file isn't a setting this version of Hushfacebook knows, so it'll be left out.",
                    "%1$d items in that file aren't settings this version of Hushfacebook knows, so they'll be left out.",
                    snapshot.unknown);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(HushfacebookPreferenceFragment.themed(activity))
                .setTitle(L10n.t("Import settings"))
                .setMessage(message)
                .setOnCancelListener(dialog -> answered(page));
        if (changes == 0) {
            builder.setPositiveButton(L10n.t("OK"), (dialog, which) -> answered(page));
        } else {
            builder.setPositiveButton(L10n.t("Import"), (dialog, which) -> {
                Bundle chosen = page.pendingImport;
                answered(page);
                apply(page, chosen);
            });
            builder.setNegativeButton(L10n.t("Cancel"), (dialog, which) -> answered(page));
        }
        page.importPreview = builder.show();
        ScreenColors.dialog(page.importPreview);
    }

    /** The sentence that says where saves go after an import, for the folder name [folder]. */
    static String folderSentence(String folder) {
        return L10n.f("Saves will go to a folder named %1$s.", L10n.isolate(folder));
    }

    private static void answered(HushfacebookPreferenceFragment page) {
        page.pendingImport = null;
        page.importPreview = null;
    }

    private static void apply(HushfacebookPreferenceFragment page, @Nullable Bundle chosen) {
        SettingsBackup.Snapshot snapshot = SettingsBackup.Snapshot.fromBundle(chosen);
        if (snapshot == null) return;
        if (!start(IMPORT, L10n.t("Importing settings"))) return;
        // The page shows what the store now holds rather than reading its own switches back into it.
        AbstractPreferenceFragment.settingImportInProgress = true;
        // Counted before the write, which makes every change match the store.
        int switches = snapshot.switchChanges();
        String folder = snapshot.folderChange();
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                SettingsBackup.apply(snapshot);
                Utils.showToastLong(switches == 0 && folder != null
                        ? L10n.f("Settings imported. Saves will go to a folder named %1$s.", L10n.isolate(folder))
                        : L10n.quantity(switches, "Settings imported. %1$d switch changed.",
                                "Settings imported. %1$d switches changed.", switches)
                                + (folder == null ? "" : " " + folderSentence(folder)));
            } catch (SettingsBackup.ApplyFailed failure) {
                Logger.printInfo(() -> "Settings import failed: " + failure.getMessage()
                        + (failure.rolledBack ? ", rolled back" : ", not rolled back"));
                Utils.showToastLong(failure.rolledBack
                        ? L10n.t("Couldn't import the settings. Nothing was changed.")
                        : L10n.t("Couldn't import every setting. Check the switches on this screen."));
            } finally {
                Utils.runOnMainThread(() -> {
                    AbstractPreferenceFragment.settingImportInProgress = false;
                    finish();
                    HushfacebookPreferenceFragment current = latestPage.get();
                    if (current != null && current.isAdded()) current.refreshSwitches();
                });
            }
        });
        if (!accepted) {
            AbstractPreferenceFragment.settingImportInProgress = false;
            notStarted();
        }
    }

    @Nullable
    private static Context appContext(HushfacebookPreferenceFragment page) {
        Activity activity = page.getActivity();
        return activity != null ? activity.getApplicationContext() : Utils.getContext();
    }

    /** Claims the rows for one run, or says why not. */
    private static boolean start(int action, String line) {
        if (!BUSY.compareAndSet(false, true)) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
            return false;
        }
        runningAction = action;
        runningLine = line;
        setRowsBusy(action, line);
        return true;
    }

    private static void finish() {
        runningAction = 0;
        runningLine = null;
        BUSY.set(false);
        setRowsBusy(0, null);
    }

    /** The worker queue was full, so nothing ran: the rows come back and the person hears why. */
    private static void notStarted() {
        finish();
        Utils.showToastLong(L10n.t("Couldn't start that. Try again in a moment."));
    }

    /**
     * Takes both rows out of reach while one of them runs and puts the running line on the one
     * acting. With a zero action and no line, they come back with their own summaries.
     */
    static void setRowsBusy(int action, @Nullable String line) {
        for (WeakReference<SettingsBackupPreference> held : ROWS) {
            SettingsBackupPreference row = held.get();
            if (row == null) {
                ROWS.remove(held);
                continue;
            }
            if (line == null) row.showResting();
            else row.showBusy(action, line);
        }
    }

    private void showBusy(int action, @Nullable String line) {
        busyLine = rowAction == action ? line : null;
        setEnabled(false);
        if (busyLine != null) setSummary(busyLine);
    }

    private void showResting() {
        busyLine = null;
        setEnabled(true);
        setSummary(restingSummary);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        // A screen reader hears the row as unavailable, and this says why.
        view.setStateDescription(busyLine);
    }
}
